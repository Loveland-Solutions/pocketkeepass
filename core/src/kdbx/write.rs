//! Writing the KDBX 4 container: XML (protected values encrypted in place) → inner header →
//! compression → encryption → HMAC blocks, behind a fresh outer header.

use std::io::Write;

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;
use zeroize::Zeroizing;

use super::crypto::{self, INNER_STREAM_CHACHA20, InnerStream};
use super::header::{self, OuterHeader, VariantDictionary};
use super::{Decrypted, INNER_BINARY, INNER_END, INNER_STREAM_ID, INNER_STREAM_KEY, blocks};
use crate::error::{Error, Result, corrupt};
use crate::util::{SecretBuf, random_bytes, sha256, sha512};
use crate::xml;

/// Builds a complete `.kdbx` file. `key` is the composite key hash.
///
/// Every secret that protects the file is replaced on every save: the key derivation salt, the
/// master seed, the encryption IV, and the key of the stream that protects individual values.
/// Reusing any of them across saves would weaken the encryption. The cipher, compression, key
/// derivation settings, and every header field we don't understand are kept as they were.
///
/// The finished file is then read back and compared with the database in memory. If anything
/// at all differs, this returns [`Error::SaveCheckFailed`] instead of the file.
pub(crate) fn encrypt(db: &Decrypted, key: &[u8; 32]) -> Result<Vec<u8>> {
    if db.header.version.major != 4 {
        return Err(Error::NeedsUpgrade);
    }

    let mut kdf = VariantDictionary::read(db.header.kdf_parameters()?)?;
    crypto::reseed_kdf(&mut kdf)?;
    let kdf_bytes = kdf.to_bytes();
    let iv_len = crypto::iv_len(db.header.cipher_id()?)?;

    let mut header = db.header.clone();
    header.set(header::MASTER_SEED, random_bytes(32)?.to_vec());
    header.set(header::ENCRYPTION_IV, random_bytes(iv_len)?.to_vec());
    header.set(header::KDF_PARAMETERS, kdf_bytes.clone());
    let header_bytes = header.to_bytes()?;

    let transformed = crypto::kdbx4_kdf(key, &kdf)?;
    let seed = header.master_seed()?;
    let hmac_base_key = sha512(&[seed, transformed.as_slice(), &[1u8]]);
    let master_key = sha256(&[seed, transformed.as_slice()]);

    let payload = build_payload(db)?;
    let payload = if header.compressed()? {
        compress(&payload)?
    } else {
        payload
    };
    let ciphertext = crypto::encrypt_payload(
        header.cipher_id()?,
        &master_key,
        header.encryption_iv()?,
        payload.as_slice(),
    )?;
    drop(payload);

    let mut file = Vec::with_capacity(header_bytes.len() + ciphertext.len() + 4096);
    file.extend_from_slice(&header_bytes);
    file.extend_from_slice(sha256(&[&header_bytes]).as_slice());
    file.extend_from_slice(&blocks::header_hmac(&header_bytes, &hmac_base_key));
    blocks::write_hmac_blocks(&ciphertext, &hmac_base_key, &mut file);

    match read_back_matches(&file, db, &kdf_bytes, &transformed) {
        Ok(true) => Ok(file),
        _ => Err(Error::SaveCheckFailed),
    }
}

/// The plaintext that gets compressed and encrypted: the inner header, then the XML.
fn build_payload(db: &Decrypted) -> Result<SecretBuf> {
    fn field(out: &mut SecretBuf, id: u8, parts: &[&[u8]]) -> Result<()> {
        let len: usize = parts.iter().map(|p| p.len()).sum();
        let len = u32::try_from(len).map_err(|_| corrupt("an attachment is too large to save"))?;
        out.extend(&[id]);
        out.extend(&len.to_le_bytes());
        for part in parts {
            out.extend(part);
        }
        Ok(())
    }

    let attachments: usize = db.binaries.iter().map(|b| b.data.len()).sum();
    let mut out = SecretBuf::with_capacity(attachments + 64 * 1024);

    // KDBX 4 protects values with ChaCha20 and a 64-byte key, which is hashed into key and nonce.
    let stream_key = random_bytes(64)?;
    field(
        &mut out,
        INNER_STREAM_ID,
        &[&INNER_STREAM_CHACHA20.to_le_bytes()],
    )?;
    field(&mut out, INNER_STREAM_KEY, &[&stream_key])?;
    for binary in &db.binaries {
        field(&mut out, INNER_BINARY, &[&[binary.flags], &binary.data])?;
    }
    for (id, value) in &db.inner_extra {
        field(&mut out, *id, &[value])?;
    }
    field(&mut out, INNER_END, &[])?;

    let mut stream = InnerStream::new(INNER_STREAM_CHACHA20, &stream_key)?;
    xml::write(&db.xml, &mut out, &mut |text| {
        let mut bytes = Zeroizing::new(text.as_bytes().to_vec());
        stream.apply(&mut bytes);
        BASE64.encode(&bytes)
    });
    Ok(out)
}

fn compress(data: &SecretBuf) -> Result<SecretBuf> {
    let out = SecretBuf::with_capacity(data.as_slice().len() / 2 + 1024);
    let mut encoder = flate2::write::GzEncoder::new(out, flate2::Compression::default());
    // Writing into memory can't fail, but there's no reason to panic if it somehow does.
    encoder
        .write_all(data.as_slice())
        .map_err(|_| Error::SaveCheckFailed)?;
    encoder.finish().map_err(|_| Error::SaveCheckFailed)
}

/// Reads the finished file back the way opening it would, and checks that it holds exactly
/// what's in memory.
///
/// Key derivation is the one step not repeated: it's slow by design, and running the same
/// function on the same input a second time proves nothing. Instead this confirms that the file
/// really carries the derivation settings the key was just derived with, and then uses that key
/// for the header HMAC, block HMACs, decryption, decompression, and parsing.
fn read_back_matches(
    file: &[u8],
    db: &Decrypted,
    kdf_bytes: &[u8],
    transformed: &[u8; 32],
) -> Result<bool> {
    let header = OuterHeader::read(file)?;
    if header.kdf_parameters()? != kdf_bytes {
        return Ok(false);
    }
    let saved = super::read_v4(file, header, transformed)?;
    Ok(db.xml.round_trips_to(&saved.xml)
        && db.binaries.len() == saved.binaries.len()
        && db
            .binaries
            .iter()
            .zip(&saved.binaries)
            .all(|(a, b)| a.flags == b.flags && a.data == b.data)
        && db.inner_extra == saved.inner_extra)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::CompositeKey;
    use crate::kdbx::{InnerBinary, decrypt};

    fn key() -> Zeroizing<[u8; 32]> {
        CompositeKey::new(Some(b"test-password"), None)
            .unwrap()
            .hash()
    }

    fn fixture(name: &str) -> Decrypted {
        let path = format!("{}/tests/fixtures/{name}", env!("CARGO_MANIFEST_DIR"));
        decrypt(&std::fs::read(path).unwrap(), &key()).unwrap()
    }

    #[test]
    fn every_save_replaces_the_secrets_and_keeps_the_settings() {
        let db = fixture("kdbx4-argon2id-chacha20.kdbx");
        let saved: Vec<Decrypted> = (0..2)
            .map(|_| decrypt(&encrypt(&db, &key()).unwrap(), &key()).unwrap())
            .collect();

        let salt = |d: &Decrypted| {
            let kdf = VariantDictionary::read(d.header.kdf_parameters().unwrap()).unwrap();
            kdf.bytes("S").unwrap().to_vec()
        };
        let without_salt = |d: &Decrypted| {
            let mut kdf = VariantDictionary::read(d.header.kdf_parameters().unwrap()).unwrap();
            kdf.set("S", VariantDictionary::BYTES, Vec::new());
            kdf.to_bytes()
        };
        for (a, b) in [(&db, &saved[0]), (&db, &saved[1]), (&saved[0], &saved[1])] {
            assert_ne!(
                a.header.master_seed().unwrap(),
                b.header.master_seed().unwrap()
            );
            assert_ne!(
                a.header.encryption_iv().unwrap(),
                b.header.encryption_iv().unwrap()
            );
            assert_ne!(salt(a), salt(b));

            assert_eq!(a.header.version, b.header.version);
            assert_eq!(a.header.cipher_id().unwrap(), b.header.cipher_id().unwrap());
            assert_eq!(
                a.header.compressed().unwrap(),
                b.header.compressed().unwrap()
            );
            assert_eq!(without_salt(a), without_salt(b));
            let ids = |d: &Decrypted| d.header.fields.iter().map(|f| f.0).collect::<Vec<_>>();
            assert_eq!(ids(a), ids(b));
        }
        assert_eq!(saved[0].header.encryption_iv().unwrap().len(), 12);
        assert_eq!(salt(&saved[0]).len(), 32);
    }

    #[test]
    fn unknown_fields_big_attachments_and_uncompressed_payloads_survive() {
        let mut db = fixture("kdbx4-aeskdf-twofish.kdbx");
        // Things no fixture has: an outer header field and an inner header field we don't
        // understand, no compression, and an attachment that spans several 1 MiB blocks and
        // can't be compressed.
        db.header.set(12, b"public custom data".to_vec());
        db.header
            .set(header::COMPRESSION, 0u32.to_le_bytes().to_vec());
        db.inner_extra
            .push((0x7F, Zeroizing::new(b"from the future".to_vec())));
        db.binaries.push(InnerBinary {
            flags: 1,
            data: random_bytes(2 * 1024 * 1024 + 4321).unwrap(),
        });

        let file = encrypt(&db, &key()).unwrap();
        assert!(file.len() > 2 * 1024 * 1024);
        let saved = decrypt(&file, &key()).unwrap();

        assert_eq!(saved.header.field(12), Some(&b"public custom data"[..]));
        assert!(!saved.header.compressed().unwrap());
        assert!(saved.inner_extra == db.inner_extra);
        assert_eq!(saved.binaries.len(), 2);
        for (a, b) in db.binaries.iter().zip(&saved.binaries) {
            assert!(a.flags == b.flags && a.data == b.data);
        }
        assert!(db.xml.round_trips_to(&saved.xml));
        assert!(saved.xml.round_trips_to(&db.xml));
    }

    #[test]
    fn the_read_back_check_notices_a_file_that_holds_something_else() {
        let db = fixture("kdbx4-argon2id-chacha20.kdbx");
        let other = fixture("kdbx41-keepassxc.kdbx");

        // A real, valid file, checked against a database with different content, different
        // attachments, or different key derivation settings: all three must be refused.
        let mut kdf = VariantDictionary::read(db.header.kdf_parameters().unwrap()).unwrap();
        let transformed = crypto::kdbx4_kdf(&key(), &kdf).unwrap();
        let file = std::fs::read(format!(
            "{}/tests/fixtures/kdbx4-argon2id-chacha20.kdbx",
            env!("CARGO_MANIFEST_DIR")
        ))
        .unwrap();
        let kdf_bytes = kdf.to_bytes();
        assert!(read_back_matches(&file, &db, &kdf_bytes, &transformed).unwrap());

        let mut changed = fixture("kdbx4-argon2id-chacha20.kdbx");
        changed.xml = other.xml;
        assert!(!read_back_matches(&file, &changed, &kdf_bytes, &transformed).unwrap());

        let mut changed = fixture("kdbx4-argon2id-chacha20.kdbx");
        changed.binaries[0].data[0] ^= 1;
        assert!(!read_back_matches(&file, &changed, &kdf_bytes, &transformed).unwrap());

        kdf.set("S", VariantDictionary::BYTES, vec![0; 32]);
        assert!(!read_back_matches(&file, &db, &kdf.to_bytes(), &transformed).unwrap());

        // And a wrong key is an error, which `encrypt` treats the same as a mismatch.
        assert!(read_back_matches(&file, &db, &kdf_bytes, &[0; 32]).is_err());
    }
}
