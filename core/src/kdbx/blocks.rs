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

/// KDBX 4: verifies the header's HMAC. A mismatch here is how a wrong password is detected.
pub(crate) fn verify_header_hmac(
    header: &[u8],
    expected: &[u8],
    hmac_base_key: &[u8; 64],
) -> Result<()> {
    let key = block_hmac_key(hmac_base_key, u64::MAX);
    let mut mac =
        Hmac::<Sha256>::new_from_slice(key.as_slice()).expect("HMAC takes any key length");
    mac.update(header);
    mac.verify_slice(expected).map_err(|_| Error::WrongKey)
}
