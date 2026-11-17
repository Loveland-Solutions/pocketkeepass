use sha2::{Digest, Sha256, Sha512};
use zeroize::Zeroizing;

use crate::error::{Error, Result, corrupt};

pub(crate) fn sha256(parts: &[&[u8]]) -> Zeroizing<[u8; 32]> {
    let mut h = Sha256::new();
    for p in parts {
        h.update(p);
    }
    Zeroizing::new(h.finalize().into())
}

pub(crate) fn sha512(parts: &[&[u8]]) -> Zeroizing<[u8; 64]> {
    let mut h = Sha512::new();
    for p in parts {
        h.update(p);
    }
    Zeroizing::new(h.finalize().into())
}

/// Decodes hex, ignoring ASCII whitespace (KeePass key files split hex into groups).
pub(crate) fn hex_decode(s: &str) -> Option<Vec<u8>> {
    let digits: Vec<u8> = s.bytes().filter(|b| !b.is_ascii_whitespace()).collect();
    if !digits.len().is_multiple_of(2) {
        return None;
    }
    digits
        .chunks(2)
        .map(|pair| {
            let hi = (pair[0] as char).to_digit(16)?;
            let lo = (pair[1] as char).to_digit(16)?;
            Some((hi * 16 + lo) as u8)
        })
        .collect()
}

/// A little-endian byte reader that reports truncation as corruption instead of panicking.
pub(crate) struct Cursor<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    pub fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    pub fn position(&self) -> usize {
        self.pos
    }

    pub fn remaining(&self) -> &'a [u8] {
        &self.data[self.pos..]
    }

    pub fn is_empty(&self) -> bool {
        self.pos >= self.data.len()
    }

    pub fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self.pos.checked_add(n).filter(|&e| e <= self.data.len());
        let end = end.ok_or_else(|| corrupt("the file ends too early"))?;
        let out = &self.data[self.pos..end];
        self.pos = end;
        Ok(out)
    }

    pub fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    pub fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_le_bytes(self.take(2)?.try_into().unwrap()))
    }

    pub fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }

    pub fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }

    #[allow(dead_code)] // kept for symmetry with the other readers
    pub fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_le_bytes(self.take(8)?.try_into().unwrap()))
    }
}

/// Fills a buffer from the operating system's secure random number generator.
pub(crate) fn random_bytes(n: usize) -> Result<Zeroizing<Vec<u8>>> {
    let mut out = Zeroizing::new(vec![0u8; n]);
    getrandom::fill(&mut out).map_err(|_| Error::Random)?;
    Ok(out)
}

/// A growable byte buffer for plaintext. A plain `Vec` leaves a copy of its old contents behind
/// in freed memory every time it grows. This one wipes the old allocation first, and wipes itself
/// on drop.
pub(crate) struct SecretBuf(Zeroizing<Vec<u8>>);

impl SecretBuf {
    pub fn with_capacity(n: usize) -> Self {
        Self(Zeroizing::new(Vec::with_capacity(n)))
    }

    pub fn extend(&mut self, bytes: &[u8]) {
        let needed = self.0.len() + bytes.len();
        if needed > self.0.capacity() {
            let mut bigger = Zeroizing::new(Vec::with_capacity(needed.max(self.0.capacity() * 2)));
            bigger.extend_from_slice(&self.0);
            self.0 = bigger; // the old allocation is wiped as it's dropped
        }
        self.0.extend_from_slice(bytes);
    }

    pub fn push_str(&mut self, s: &str) {
        self.extend(s.as_bytes());
    }

    pub fn as_slice(&self) -> &[u8] {
        &self.0
    }

    pub fn into_inner(self) -> Zeroizing<Vec<u8>> {
        self.0
    }
}

impl std::io::Write for SecretBuf {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.extend(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn secret_buf_keeps_its_contents_as_it_grows() {
        let mut buf = SecretBuf::with_capacity(2);
        let mut expected = Vec::new();
        for i in 0..1000u32 {
            buf.extend(&i.to_le_bytes());
            expected.extend_from_slice(&i.to_le_bytes());
        }
        buf.push_str("end");
        expected.extend_from_slice(b"end");
        assert_eq!(buf.as_slice(), expected);
    }

    #[test]
    fn random_bytes_are_not_all_the_same() {
        let a = random_bytes(32).unwrap();
        let b = random_bytes(32).unwrap();
        assert_eq!(a.len(), 32);
        assert_ne!(a, b);
    }
}
