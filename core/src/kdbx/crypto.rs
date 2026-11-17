//! KeePass's use of the crypto primitives: key derivation, payload ciphers, and the stream that
//! protects individual values. The primitives themselves are RustCrypto's.

use aes::Aes256;
use aes::cipher::block_padding::Pkcs7;
use aes::cipher::{
    BlockCipherEncrypt, BlockModeDecrypt, BlockModeEncrypt, KeyInit, KeyIvInit, StreamCipher,
};
use chacha20::ChaCha20;
use salsa20::Salsa20;
use twofish::Twofish;
use zeroize::{Zeroize, Zeroizing};

use crate::error::{Error, Result, corrupt};
use crate::kdbx::header::VariantDictionary;
use crate::util::{random_bytes, sha256, sha512};

/// Parses a UUID like "31C1F2E6-BF71-4350-BE58-05216AFC5AFF" into its 16 stored bytes.
const fn uuid(s: &str) -> [u8; 16] {
    const fn nibble(c: u8) -> u8 {
        match c {
            b'0'..=b'9' => c - b'0',
            b'A'..=b'F' => c - b'A' + 10,
            b'a'..=b'f' => c - b'a' + 10,
            _ => panic!("bad hex"),
        }
    }
    let s = s.as_bytes();
    let mut out = [0u8; 16];
    let (mut i, mut o) = (0, 0);
    while i < s.len() {
        if s[i] == b'-' {
            i += 1;
            continue;
        }
        out[o] = nibble(s[i]) << 4 | nibble(s[i + 1]);
        o += 1;
        i += 2;
    }
    out
}

pub(crate) const CIPHER_AES256: [u8; 16] = uuid("31C1F2E6-BF71-4350-BE58-05216AFC5AFF");
pub(crate) const CIPHER_CHACHA20: [u8; 16] = uuid("D6038A2B-8B6F-4CB5-A524-339A31DBB59A");
pub(crate) const CIPHER_TWOFISH: [u8; 16] = uuid("AD68F29F-576F-4BB9-A36A-D47AF965346C");

pub(crate) const KDF_AES: [u8; 16] = uuid("C9D9F39A-628A-4460-BF74-0D08C18A4FEA");
const KDF_AES_KEEPASSXC: [u8; 16] = uuid("7C02BB82-79A7-4AC0-927D-114A00648238");
const KDF_ARGON2D: [u8; 16] = uuid("EF636DDF-8C29-444B-91F7-A9A403E30A0C");
const KDF_ARGON2ID: [u8; 16] = uuid("9E298B19-56DB-4773-B23D-FC3EC6F0A1E6");

/// AES-KDF: encrypt the key with AES-256 (keyed by the seed) `rounds` times, then SHA-256 it.
pub(crate) fn aes_kdf(
    composite: &[u8; 32],
    seed: &[u8],
    rounds: u64,
) -> Result<Zeroizing<[u8; 32]>> {
    let cipher =
        Aes256::new_from_slice(seed).map_err(|_| corrupt("AES-KDF seed has the wrong length"))?;
    let mut blocks = [aes::Block::default(), aes::Block::default()];
    blocks[0].copy_from_slice(&composite[..16]);
    blocks[1].copy_from_slice(&composite[16..]);
    for _ in 0..rounds {
        cipher.encrypt_blocks(&mut blocks);
    }
    let out = sha256(&[&blocks[0], &blocks[1]]);
    for b in &mut blocks {
        b.as_mut_slice().zeroize();
    }
    Ok(out)
}

/// KDBX 4 key derivation, configured by the header's KDF dictionary.
pub(crate) fn kdbx4_kdf(
    composite: &[u8; 32],
    params: &VariantDictionary,
) -> Result<Zeroizing<[u8; 32]>> {
    let id = params
        .bytes("$UUID")
        .ok_or_else(|| corrupt("key derivation has no algorithm"))?;
    let id: [u8; 16] = id
        .try_into()
        .map_err(|_| corrupt("bad key derivation algorithm ID"))?;

    if id == KDF_AES || id == KDF_AES_KEEPASSXC {
        let seed = params
            .bytes("S")
            .ok_or_else(|| corrupt("AES-KDF has no seed"))?;
        let rounds = params
            .u64("R")
            .ok_or_else(|| corrupt("AES-KDF has no round count"))?;
        return aes_kdf(composite, seed, rounds);
    }

    let algorithm = match id {
        KDF_ARGON2D => argon2::Algorithm::Argon2d,
        KDF_ARGON2ID => argon2::Algorithm::Argon2id,
        _ => return Err(Error::Unsupported("this key derivation algorithm".into())),
    };
    if params.bytes("K").is_some_and(|k| !k.is_empty())
        || params.bytes("A").is_some_and(|a| !a.is_empty())
    {
        return Err(Error::Unsupported(
            "Argon2 secret key or associated data".into(),
        ));
    }
    let salt = params
        .bytes("S")
        .ok_or_else(|| corrupt("Argon2 has no salt"))?;
    let parallelism = params
        .u32("P")
        .ok_or_else(|| corrupt("Argon2 has no parallelism"))?;
    let memory = params
        .u64("M")
        .ok_or_else(|| corrupt("Argon2 has no memory size"))?;
    let iterations = params
        .u64("I")
        .ok_or_else(|| corrupt("Argon2 has no iteration count"))?;
    let version = match params
        .u32("V")
        .ok_or_else(|| corrupt("Argon2 has no version"))?
    {
        0x10 => argon2::Version::V0x10,
        0x13 => argon2::Version::V0x13,
        v => return Err(Error::Unsupported(format!("Argon2 version {v:#x}"))),
    };

    let bad = |_| corrupt("Argon2 settings are out of range");
    let params = argon2::Params::new(
        u32::try_from(memory / 1024).map_err(bad)?,
        u32::try_from(iterations).map_err(bad)?,
        parallelism,
        Some(32),
    )
    .map_err(|_| corrupt("Argon2 settings are out of range"))?;

    let mut out = Zeroizing::new([0u8; 32]);
    argon2::Argon2::new(algorithm, version, params)
        .hash_password_into(composite, salt, out.as_mut_slice())
        .map_err(|_| corrupt("Argon2 settings are out of range"))?;
    Ok(out)
}

/// Gives the key derivation a fresh random salt, so that every save derives a different key
/// from the same password. KeePass and KeePassXC do the same.
pub(crate) fn reseed_kdf(params: &mut VariantDictionary) -> Result<()> {
    // The AES-KDF seed is an AES-256 key, so it's always 32 bytes. An Argon2 salt can be longer.
    let len = params.bytes("S").map_or(32, |s| s.len().max(32));
    let is_aes = params
        .bytes("$UUID")
        .is_some_and(|id| id == KDF_AES || id == KDF_AES_KEEPASSXC);
    let len = if is_aes { 32 } else { len };
    params.set("S", VariantDictionary::BYTES, random_bytes(len)?.to_vec());
    Ok(())
}

/// The IV (or nonce) length each payload cipher needs.
pub(crate) fn iv_len(cipher_id: &[u8]) -> Result<usize> {
    match <[u8; 16]>::try_from(cipher_id).ok() {
        Some(CIPHER_AES256 | CIPHER_TWOFISH) => Ok(16),
        Some(CIPHER_CHACHA20) => Ok(12),
        _ => Err(Error::Unsupported("this encryption cipher".into())),
    }
}

/// Encrypts the main payload. The exact inverse of [`decrypt_payload`].
pub(crate) fn encrypt_payload(
    cipher_id: &[u8],
    key: &[u8; 32],
    iv: &[u8],
    data: &[u8],
) -> Result<Vec<u8>> {
    let bad_iv = |_| corrupt("encryption IV has the wrong length");
    Ok(match <[u8; 16]>::try_from(cipher_id).ok() {
        Some(CIPHER_AES256) => cbc::Encryptor::<Aes256>::new_from_slices(key, iv)
            .map_err(bad_iv)?
            .encrypt_padded_vec::<Pkcs7>(data),
        Some(CIPHER_TWOFISH) => cbc::Encryptor::<Twofish>::new_from_slices(key, iv)
            .map_err(bad_iv)?
            .encrypt_padded_vec::<Pkcs7>(data),
        Some(CIPHER_CHACHA20) => {
            // The copy is plaintext only until the next line encrypts it in place.
            let mut buf = data.to_vec();
            ChaCha20::new_from_slices(key, iv)
                .map_err(bad_iv)?
                .apply_keystream(&mut buf);
            buf
        }
        _ => return Err(Error::Unsupported("this encryption cipher".into())),
    })
}

/// Decrypts the main payload. A padding failure means the key was wrong (KDBX 3) or the data is
/// damaged (KDBX 4, where the key has already been verified); the caller decides which.
pub(crate) fn decrypt_payload(
    cipher_id: &[u8],
    key: &[u8; 32],
    iv: &[u8],
    data: &[u8],
) -> Result<Zeroizing<Vec<u8>>> {
    let bad_iv = |_| corrupt("encryption IV has the wrong length");
    let out = match <[u8; 16]>::try_from(cipher_id).ok() {
        Some(CIPHER_AES256) => cbc::Decryptor::<Aes256>::new_from_slices(key, iv)
            .map_err(bad_iv)?
            .decrypt_padded_vec::<Pkcs7>(data)
            .map_err(|_| Error::WrongKey)?,
        Some(CIPHER_TWOFISH) => cbc::Decryptor::<Twofish>::new_from_slices(key, iv)
            .map_err(bad_iv)?
            .decrypt_padded_vec::<Pkcs7>(data)
            .map_err(|_| Error::WrongKey)?,
        Some(CIPHER_CHACHA20) => {
            let mut buf = data.to_vec();
            ChaCha20::new_from_slices(key, iv)
                .map_err(bad_iv)?
                .apply_keystream(&mut buf);
            buf
        }
        _ => return Err(Error::Unsupported("this encryption cipher".into())),
    };
    Ok(Zeroizing::new(out))
}

/// The stream cipher that hides "protected" values (passwords etc.) inside the XML.
pub(crate) const INNER_STREAM_CHACHA20: u32 = 3;

pub(crate) enum InnerStream {
    Salsa20(Box<Salsa20>),
    ChaCha20(Box<ChaCha20>),
}

impl InnerStream {
    const SALSA20_NONCE: [u8; 8] = [0xE8, 0x30, 0x09, 0x4B, 0x97, 0x20, 0x5D, 0x2A];

    pub fn new(id: u32, key: &[u8]) -> Result<Self> {
        match id {
            2 => {
                let k = sha256(&[key]);
                let c = Salsa20::new_from_slices(k.as_slice(), &Self::SALSA20_NONCE)
                    .map_err(|_| corrupt("bad inner stream key"))?;
                Ok(Self::Salsa20(Box::new(c)))
            }
            3 => {
                let h = sha512(&[key]);
                let c = ChaCha20::new_from_slices(&h[..32], &h[32..44])
                    .map_err(|_| corrupt("bad inner stream key"))?;
                Ok(Self::ChaCha20(Box::new(c)))
            }
            1 => Err(Error::Unsupported("ArcFour value protection".into())),
            n => Err(Error::Unsupported(format!("value protection type {n}"))),
        }
    }

    pub fn apply(&mut self, buf: &mut [u8]) {
        match self {
            Self::Salsa20(c) => c.apply_keystream(buf),
            Self::ChaCha20(c) => c.apply_keystream(buf),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn payload_encryption_round_trips_for_every_cipher() {
        let key = [5u8; 32];
        for cipher in [CIPHER_AES256, CIPHER_TWOFISH, CIPHER_CHACHA20] {
            let iv = vec![6u8; iv_len(&cipher).unwrap()];
            // Lengths around the 16-byte block size exercise the padding.
            for len in [0, 1, 15, 16, 17, 1000] {
                let plain: Vec<u8> = (0..len).map(|i| i as u8).collect();
                let encrypted = encrypt_payload(&cipher, &key, &iv, &plain).unwrap();
                assert!(len == 0 || encrypted != plain);
                let decrypted = decrypt_payload(&cipher, &key, &iv, &encrypted).unwrap();
                assert_eq!(*decrypted, plain);
            }
        }
        assert!(iv_len(&[0u8; 16]).is_err());
    }

    #[test]
    fn reseeding_replaces_only_the_salt() {
        let mut params = VariantDictionary::new();
        params.set("$UUID", VariantDictionary::BYTES, KDF_ARGON2ID.to_vec());
        params.set("S", VariantDictionary::BYTES, vec![0; 48]);
        params.set("M", VariantDictionary::U64, 1024u64.to_le_bytes().to_vec());
        reseed_kdf(&mut params).unwrap();
        let salt = params.bytes("S").unwrap();
        assert_eq!(salt.len(), 48);
        assert_ne!(salt, [0; 48]);
        assert_eq!(params.u64("M"), Some(1024));
        assert_eq!(params.items.len(), 3);

        // AES-KDF needs exactly 32 bytes, whatever was there before.
        let mut params = VariantDictionary::new();
        params.set("$UUID", VariantDictionary::BYTES, KDF_AES.to_vec());
        params.set("S", VariantDictionary::BYTES, vec![0; 48]);
        reseed_kdf(&mut params).unwrap();
        assert_eq!(params.bytes("S").unwrap().len(), 32);
    }
}
