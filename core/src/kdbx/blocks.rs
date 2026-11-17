//! The integrity-checked block streams that wrap the payload.

use hmac::{Hmac, KeyInit, Mac};
use sha2::Sha256;
use zeroize::Zeroizing;

use crate::error::{Error, Result, corrupt};
use crate::util::{Cursor, sha256, sha512};

/// KDBX 3: after decryption, the payload is a series of SHA-256-checked blocks.
pub(crate) fn read_hashed_blocks(data: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
    let mut c = Cursor::new(data);
    let mut out = Zeroizing::new(Vec::with_capacity(data.len()));
    loop {
        let _index = c.u32()?;
        let hash = c.take(32)?;
        let size = usize::try_from(c.i32()?).map_err(|_| corrupt("bad block size"))?;
        if size == 0 {
            if hash.iter().any(|&b| b != 0) {
                return Err(corrupt("bad final block"));
            }
            return Ok(out);
        }
        let block = c.take(size)?;
        if sha256(&[block]).as_slice() != hash {
            return Err(corrupt("a data block failed its integrity check"));
        }
        out.extend_from_slice(block);
    }
}

/// KDBX 4: the HMAC key for block `index` (u64::MAX is used for the header).
pub(crate) fn block_hmac_key(hmac_base_key: &[u8; 64], index: u64) -> Zeroizing<[u8; 64]> {
    sha512(&[&index.to_le_bytes(), hmac_base_key])
}

/// KDBX 4: the encrypted payload is a series of HMAC-SHA256-authenticated blocks. Returns the
/// still-encrypted ciphertext once every block checks out.
pub(crate) fn read_hmac_blocks(data: &[u8], hmac_base_key: &[u8; 64]) -> Result<Vec<u8>> {
    let mut c = Cursor::new(data);
    let mut out = Vec::with_capacity(data.len());
    for index in 0u64.. {
        let expected = c.take(32)?;
        let size_bytes = c.take(4)?;
        let size = i32::from_le_bytes(size_bytes.try_into().unwrap());
        let size = usize::try_from(size).map_err(|_| corrupt("bad block size"))?;
        let block = c.take(size)?;

        let key = block_hmac_key(hmac_base_key, index);
        let mut mac =
            Hmac::<Sha256>::new_from_slice(key.as_slice()).expect("HMAC takes any key length");
        mac.update(&index.to_le_bytes());
        mac.update(size_bytes);
        mac.update(block);
        mac.verify_slice(expected)
            .map_err(|_| corrupt("a data block failed its integrity check"))?;

        if size == 0 {
            break;
        }
        out.extend_from_slice(block);
    }
    if !c.is_empty() {
        return Err(corrupt("unexpected data after the last block"));
    }
    Ok(out)
}

/// KDBX 4: splits the encrypted payload into HMAC-SHA256-authenticated blocks and appends them
/// to `out`, ending with the empty block that marks the end.
pub(crate) fn write_hmac_blocks(data: &[u8], hmac_base_key: &[u8; 64], out: &mut Vec<u8>) {
    const BLOCK_SIZE: usize = 1024 * 1024; // what KeePass and KeePassXC write
    let blocks = data.chunks(BLOCK_SIZE).chain(std::iter::once(&[][..]));
    for (index, block) in (0u64..).zip(blocks) {
        let size_bytes = (block.len() as i32).to_le_bytes(); // at most BLOCK_SIZE
        let key = block_hmac_key(hmac_base_key, index);
        let mut mac =
            Hmac::<Sha256>::new_from_slice(key.as_slice()).expect("HMAC takes any key length");
        mac.update(&index.to_le_bytes());
        mac.update(&size_bytes);
        mac.update(block);
        out.extend_from_slice(&mac.finalize().into_bytes());
        out.extend_from_slice(&size_bytes);
        out.extend_from_slice(block);
    }
}

fn header_mac(header: &[u8], hmac_base_key: &[u8; 64]) -> Hmac<Sha256> {
    let key = block_hmac_key(hmac_base_key, u64::MAX);
    let mut mac =
        Hmac::<Sha256>::new_from_slice(key.as_slice()).expect("HMAC takes any key length");
    mac.update(header);
    mac
}

/// KDBX 4: the HMAC that authenticates the header and proves the key.
pub(crate) fn header_hmac(header: &[u8], hmac_base_key: &[u8; 64]) -> [u8; 32] {
    header_mac(header, hmac_base_key)
        .finalize()
        .into_bytes()
        .into()
}

/// KDBX 4: verifies the header's HMAC. A mismatch here is how a wrong password is detected.
pub(crate) fn verify_header_hmac(
    header: &[u8],
    expected: &[u8],
    hmac_base_key: &[u8; 64],
) -> Result<()> {
    header_mac(header, hmac_base_key)
        .verify_slice(expected)
        .map_err(|_| Error::WrongKey)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hmac_blocks_round_trip_at_every_awkward_size() {
        let key = [9u8; 64];
        let mib = 1024 * 1024;
        for len in [0, 1, mib - 1, mib, mib + 1, 2 * mib + 12345] {
            let data: Vec<u8> = (0..len).map(|i| (i % 251) as u8).collect();
            let mut written = Vec::new();
            write_hmac_blocks(&data, &key, &mut written);
            assert_eq!(
                read_hmac_blocks(&written, &key).unwrap(),
                data,
                "{len} bytes"
            );

            // Full blocks, then the last partial one if any, then the empty end marker.
            let blocks = len / mib + usize::from(len % mib != 0) + 1;
            assert_eq!(written.len(), len + blocks * 36, "{len} bytes");
        }
    }

    #[test]
    fn hmac_blocks_reject_another_key_and_a_dropped_end_marker() {
        let mut written = Vec::new();
        write_hmac_blocks(b"some ciphertext", &[1u8; 64], &mut written);
        assert!(read_hmac_blocks(&written, &[2u8; 64]).is_err());
        assert!(read_hmac_blocks(&written[..written.len() - 36], &[1u8; 64]).is_err());
    }

    #[test]
    fn header_hmac_is_what_the_reader_verifies() {
        let key = [3u8; 64];
        let mac = header_hmac(b"header bytes", &key);
        assert!(verify_header_hmac(b"header bytes", &mac, &key).is_ok());
        assert!(matches!(
            verify_header_hmac(b"header bytez", &mac, &key),
            Err(Error::WrongKey)
        ));
    }
}
