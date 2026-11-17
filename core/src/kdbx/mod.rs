//! Reading the KDBX container: header → key derivation → integrity checks → decryption →
//! decompression → XML, with protected values decrypted in place.

mod blocks;
mod crypto;
mod header;

use std::io::Read;

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;
use zeroize::Zeroizing;

pub use header::FormatVersion;

use crate::error::{Error, Result, corrupt};
use crate::key::CompositeKey;
use crate::util::{Cursor, sha256, sha512};
use crate::xml::{self, Element};
use crypto::InnerStream;
use header::{OuterHeader, VariantDictionary};

/// An attachment stored in the KDBX 4 inner header.
#[allow(dead_code)] // read by the writer (next milestone)
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
    #[allow(dead_code)] // used by the writer (next milestone)
    pub header: OuterHeader,
}

pub(crate) fn decrypt(data: &[u8], key: &CompositeKey) -> Result<Decrypted> {
    let header = OuterHeader::read(data)?;
    match header.version.major {
        3 => decrypt_v3(data, header, key),
        4 => decrypt_v4(data, header, key),
        _ => Err(Error::UnsupportedVersion(header.version.to_string())),
    }
}

fn decrypt_v3(data: &[u8], header: OuterHeader, key: &CompositeKey) -> Result<Decrypted> {
    let transformed = crypto::aes_kdf(
        &key.hash(),
        header.transform_seed()?,
        header.transform_rounds()?,
    )?;
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
    let xml = parse_and_unprotect(&payload, &mut stream)?;

    // KDBX 3 stores a hash of the header inside the encrypted XML.
    if let Some(stored) = xml.path(&["Meta", "HeaderHash"]) {
        let actual = sha256(&[&data[..header.len]]);
        if BASE64.decode(stored.text.trim()).ok().as_deref() != Some(actual.as_slice()) {
            return Err(corrupt("the header doesn't match its stored hash"));
        }
    }
    Ok(Decrypted {
        version: header.version,
        xml,
        binaries: Vec::new(),
        header,
    })
}

fn decrypt_v4(data: &[u8], header: OuterHeader, key: &CompositeKey) -> Result<Decrypted> {
    let mut c = Cursor::new(&data[header.len..]);
    let header_bytes = &data[..header.len];

    let stored_hash = c.take(32)?;
    if sha256(&[header_bytes]).as_slice() != stored_hash {
        return Err(corrupt("the header doesn't match its stored hash"));
    }
    let stored_hmac = c.take(32)?;

    let kdf = VariantDictionary::read(header.kdf_parameters()?)?;
    let transformed = crypto::kdbx4_kdf(&key.hash(), &kdf)?;
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
    loop {
        let id = c.u8()?;
        let len = c.u32()? as usize;
        let value = c.take(len)?;
        match id {
            0 => break,
            1 => {
                stream_id = Some(u32::from_le_bytes(
                    value.try_into().map_err(|_| corrupt("bad inner header"))?,
                ))
            }
            2 => stream_key = Some(Zeroizing::new(value.to_vec())),
            3 => {
                let (&flags, data) = value
                    .split_first()
                    .ok_or_else(|| corrupt("empty attachment record"))?;
                binaries.push(InnerBinary {
                    flags,
                    data: Zeroizing::new(data.to_vec()),
                });
            }
            _ => {} // Unknown inner-header fields carry no data we need.
        }
    }
    let stream_id = stream_id.ok_or_else(|| corrupt("inner header has no stream type"))?;
    let stream_key = stream_key.ok_or_else(|| corrupt("inner header has no stream key"))?;
    let mut stream = InnerStream::new(stream_id, &stream_key)?;
    let xml = parse_and_unprotect(c.remaining(), &mut stream)?;

    Ok(Decrypted {
        version: header.version,
        xml,
        binaries,
        header,
    })
}

fn decompress(data: Zeroizing<Vec<u8>>, compressed: bool) -> Result<Zeroizing<Vec<u8>>> {
    if !compressed {
        return Ok(data);
    }
    let mut out = Zeroizing::new(Vec::with_capacity(data.len() * 4));
    flate2::read::GzDecoder::new(data.as_slice())
        .read_to_end(&mut out)
        .map_err(|_| corrupt("the compressed data is damaged"))?;
    Ok(out)
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
        let protected = el
            .attr("Protected")
            .is_some_and(|v| v.eq_ignore_ascii_case("true"));
        if !protected {
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
