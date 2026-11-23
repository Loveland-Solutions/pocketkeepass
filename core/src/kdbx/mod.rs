//! Reading the KDBX container: header → key derivation → integrity checks → decryption →
//! decompression → XML, with protected values decrypted in place. Writing (`write.rs`) is the
//! same pipeline run backwards.

mod blocks;
mod crypto;
mod header;
mod new;
mod upgrade;
mod write;

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;
use zeroize::Zeroizing;

pub(crate) use crypto::KdfCost;
pub use header::FormatVersion;
#[cfg(test)]
pub(crate) use new::{ARGON2_MEMORY, argon2id};
pub(crate) use new::{UNLOCK_TIME, container, timed_argon2id};
pub(crate) use upgrade::{seconds_since_year_1, upgrade_to_kdbx4};
pub(crate) use write::encrypt;

use crate::error::{Error, Result, corrupt};
use crate::util::{Cursor, SecretBuf, sha256, sha512};
use crate::xml::{self, Element};
use crypto::InnerStream;
use header::OuterHeader;
pub(crate) use header::VariantDictionary;

// Inner header field IDs (KDBX 4).
const INNER_END: u8 = 0;
const INNER_STREAM_ID: u8 = 1;
const INNER_STREAM_KEY: u8 = 2;
const INNER_BINARY: u8 = 3;

/// An attachment stored in the KDBX 4 inner header.
pub(crate) struct InnerBinary {
    /// Bit 0 = "protect in memory".
    pub flags: u8,
    pub data: Zeroizing<Vec<u8>>,
}

/// Everything recovered from a file. The header and inner-header details are kept so the
/// database can be written back faithfully.
pub(crate) struct Decrypted {
    pub version: FormatVersion,
    pub xml: Element,
    pub binaries: Vec<InnerBinary>,
    /// Inner header fields we don't understand, kept so they're written back unchanged.
    pub inner_extra: Vec<(u8, Zeroizing<Vec<u8>>)>,
    pub header: OuterHeader,
}

/// `key` is the composite key hash ([`crate::CompositeKey::hash`]).
pub(crate) fn decrypt(data: &[u8], key: &[u8; 32]) -> Result<Decrypted> {
    let header = OuterHeader::read(data)?;
    match header.version.major {
        3 => decrypt_v3(data, header, key),
        4 => decrypt_v4(data, header, key),
        _ => Err(Error::UnsupportedVersion(header.version.to_string())),
    }
}

fn decrypt_v3(data: &[u8], header: OuterHeader, key: &[u8; 32]) -> Result<Decrypted> {
    let transformed = crypto::aes_kdf(key, header.transform_seed()?, header.transform_rounds()?)?;
    let master_key = sha256(&[header.master_seed()?, transformed.as_slice()]);

    let plain = crypto::decrypt_payload(
        header.cipher_id()?,
        &master_key,
        header.encryption_iv()?,
        &data[header.len..],
    )?;

    // KDBX 3 has no header MAC. The first 32 decrypted bytes must match a value from the header;
    // otherwise the key is wrong.
    let start = header.stream_start_bytes()?;
    if plain.len() < start.len() || plain[..start.len()] != *start {
        return Err(Error::WrongKey);
    }
    let payload = blocks::read_hashed_blocks(&plain[start.len()..])?;
    let payload = decompress(payload, header.compressed()?)?;

    let mut stream = InnerStream::new(header.inner_stream_id()?, header.protected_stream_key()?)?;
    let mut xml = parse_and_unprotect(&payload, &mut stream)?;

    // KDBX 3 stores a hash of the header inside the encrypted XML.
    if let Some(stored) = xml.path(&["Meta", "HeaderHash"]) {
        let actual = sha256(&[&data[..header.len]]);
        if BASE64.decode(stored.text.trim()).ok().as_deref() != Some(actual.as_slice()) {
            return Err(corrupt("the header doesn't match its stored hash"));
        }
    }
    drop_header_hash(&mut xml);
    Ok(Decrypted {
        version: header.version,
        xml,
        binaries: Vec::new(),
        inner_extra: Vec::new(),
        header,
    })
}

/// Removes `Meta/HeaderHash` from the tree. It describes the file the tree was read from, not
/// the database's content, and it would be wrong in any file we write. (KeePass 2.x rejects a
/// file whose stored header hash doesn't match, even a KDBX 4 one.)
fn drop_header_hash(xml: &mut Element) {
    if let Some(meta) = xml.child_mut("Meta") {
        meta.children.retain(|c| c.name != "HeaderHash");
    }
}

fn decrypt_v4(data: &[u8], header: OuterHeader, key: &[u8; 32]) -> Result<Decrypted> {
    // The header's checksum needs no key, so it's checked before the key derivation, which is
    // slow and takes its settings from that same header: a header damaged by accident is then
    // reported at once, not after however long its damaged settings ask for. (It's checked
    // again with the key, by the header HMAC, in `read_v4`.)
    let stored_hash = data.get(header.len..header.len + 32);
    if stored_hash != Some(sha256(&[&data[..header.len]]).as_slice()) {
        return Err(corrupt("the header doesn't match its stored hash"));
    }
    let kdf = VariantDictionary::read(header.kdf_parameters()?)?;
    let transformed = crypto::kdbx4_kdf(key, &kdf)?;
    read_v4(data, header, &transformed)
}

/// Everything in reading a KDBX 4 file that comes after key derivation. `transformed` is the
/// key derivation's output. Saving reuses this to check a file it has just written.
fn read_v4(data: &[u8], header: OuterHeader, transformed: &[u8; 32]) -> Result<Decrypted> {
    let mut c = Cursor::new(data.get(header.len..).unwrap_or_default());
    let header_bytes = &data[..header.len];

    let stored_hash = c.take(32)?;
    if sha256(&[header_bytes]).as_slice() != stored_hash {
        return Err(corrupt("the header doesn't match its stored hash"));
    }
    let stored_hmac = c.take(32)?;

    let seed = header.master_seed()?;
    let hmac_base_key = sha512(&[seed, transformed.as_slice(), &[1u8]]);
    blocks::verify_header_hmac(header_bytes, stored_hmac, &hmac_base_key)?;

    let ciphertext = Zeroizing::new(blocks::read_hmac_blocks(c.remaining(), &hmac_base_key)?);
    let master_key = sha256(&[seed, transformed.as_slice()]);
    let plain = crypto::decrypt_payload(
        header.cipher_id()?,
        &master_key,
        header.encryption_iv()?,
        &ciphertext,
    )
    // The key is already proven correct by the header HMAC, so bad padding means damage.
    .map_err(|e| {
        if matches!(e, Error::WrongKey) {
            corrupt("decryption failed")
        } else {
            e
        }
    })?;
    let payload = decompress(plain, header.compressed()?)?;

    // Inner header: protected-value stream settings and attachments, then the XML.
    let mut c = Cursor::new(&payload);
    let mut stream_id = None;
    let mut stream_key = None;
    let mut binaries = Vec::new();
    let mut inner_extra = Vec::new();
    loop {
        let id = c.u8()?;
        let len = c.u32()? as usize;
        let value = c.take(len)?;
        match id {
            INNER_END => break,
            INNER_STREAM_ID => {
                stream_id = Some(u32::from_le_bytes(
                    value.try_into().map_err(|_| corrupt("bad inner header"))?,
                ))
            }
            INNER_STREAM_KEY => stream_key = Some(Zeroizing::new(value.to_vec())),
            INNER_BINARY => {
                let (&flags, data) = value
                    .split_first()
                    .ok_or_else(|| corrupt("empty attachment record"))?;
                binaries.push(InnerBinary {
                    flags,
                    data: Zeroizing::new(data.to_vec()),
                });
            }
            _ => inner_extra.push((id, Zeroizing::new(value.to_vec()))),
        }
    }
    let stream_id = stream_id.ok_or_else(|| corrupt("inner header has no stream type"))?;
    let stream_key = stream_key.ok_or_else(|| corrupt("inner header has no stream key"))?;
    let mut stream = InnerStream::new(stream_id, &stream_key)?;
    let mut xml = parse_and_unprotect(c.remaining(), &mut stream)?;
    drop_header_hash(&mut xml);

    Ok(Decrypted {
        version: header.version,
        xml,
        binaries,
        inner_extra,
        header,
    })
}

fn decompress(data: Zeroizing<Vec<u8>>, compressed: bool) -> Result<Zeroizing<Vec<u8>>> {
    if !compressed {
        return Ok(data);
    }
    gunzip(&data).map_err(|_| corrupt("the compressed data is damaged"))
}

/// The most a database may decompress to: 1 GiB, far more than a phone could hold open. A few
/// kilobytes of compressed data can expand to gigabytes, and running out of memory closes the
/// app without a word.
const MAX_DECOMPRESSED: u64 = 1 << 30;

/// Decompresses gzip data into a buffer that never leaves plaintext behind as it grows.
fn gunzip(data: &[u8]) -> std::io::Result<Zeroizing<Vec<u8>>> {
    use std::io::Read;
    let mut out = SecretBuf::with_capacity(data.len().saturating_mul(2).min(1 << 24));
    let mut decoder = flate2::read::GzDecoder::new(data).take(MAX_DECOMPRESSED + 1);
    let size = std::io::copy(&mut decoder, &mut out)?;
    if size > MAX_DECOMPRESSED {
        return Err(std::io::Error::other("too large"));
    }
    Ok(out.into_inner())
}

/// What a file's key derivation costs to run, read from its header without running it.
pub(crate) fn kdf_cost(data: &[u8]) -> Result<KdfCost> {
    OuterHeader::read(data)?.kdf_cost()
}

/// Parses the XML, then decrypts every `<Value Protected="True">` in document order. The
/// `Protected` attribute is kept so the value is re-protected when saved.
fn parse_and_unprotect(payload: &[u8], stream: &mut InnerStream) -> Result<Element> {
    let text = std::str::from_utf8(payload).map_err(|_| corrupt("the XML isn't valid UTF-8"))?;
    let mut root = xml::parse(text)?;
    if root.name != "KeePassFile" {
        return Err(corrupt("unexpected XML root element"));
    }
    root.walk_mut(&mut |el| {
        if !el.is_protected() {
            return Ok(());
        }
        let mut bytes = Zeroizing::new(
            BASE64
                .decode(el.text.trim())
                .map_err(|_| corrupt("a protected value isn't valid base64"))?,
        );
        stream.apply(&mut bytes);
        let plain = std::str::from_utf8(&bytes)
            .map_err(|_| corrupt("a protected value isn't valid text"))?;
        el.text = Zeroizing::new(plain.to_owned());
        Ok(())
    })?;
    Ok(root)
}
