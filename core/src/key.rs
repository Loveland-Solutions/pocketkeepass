//! The user's credentials: password, key file, or both.

use zeroize::Zeroizing;

use crate::error::{Error, Result};
use crate::util::{hex_decode, sha256};
use crate::xml;

/// The combined key a database is locked with.
///
/// KeePass hashes each component separately (password → SHA-256, key file → 32-byte key), then
/// hashes the concatenation. Only the hashes are kept here, never the plaintext password, and
/// they're wiped from memory on drop.
pub struct CompositeKey {
    parts: Vec<Zeroizing<[u8; 32]>>,
}

impl CompositeKey {
    /// `password` is UTF-8 bytes. The caller should wipe its own copy afterwards.
    pub fn new(password: Option<&[u8]>, key_file: Option<&[u8]>) -> Result<Self> {
        let mut parts = Vec::new();
        if let Some(pw) = password {
            parts.push(sha256(&[pw]));
        }
        if let Some(kf) = key_file {
            parts.push(key_file_key(kf)?);
        }
        if parts.is_empty() {
            return Err(Error::InvalidKeyFile);
        }
        Ok(Self { parts })
    }

    pub(crate) fn hash(&self) -> Zeroizing<[u8; 32]> {
        let refs: Vec<&[u8]> = self.parts.iter().map(|p| p.as_slice()).collect();
        sha256(&refs)
    }
}

/// Turns key file contents into a 32-byte key, following KeePass 2.x's rules in order:
/// an XML key file (v1.0 base64 or v2.0 hex), 32 raw bytes, 64 hex characters, or else the
/// SHA-256 of the whole file (any file can be a key file).
fn key_file_key(data: &[u8]) -> Result<Zeroizing<[u8; 32]>> {
    if let Some(key) = xml_key_file(data)? {
        return Ok(key);
    }
    if data.len() == 32 {
        return Ok(Zeroizing::new(data.try_into().unwrap()));
    }
    if data.len() == 64
        && let Some(bytes) = std::str::from_utf8(data).ok().and_then(hex_decode)
    {
        return Ok(Zeroizing::new(bytes.try_into().unwrap()));
    }
    Ok(sha256(&[data]))
}

fn xml_key_file(data: &[u8]) -> Result<Option<Zeroizing<[u8; 32]>>> {
    let Ok(text) = std::str::from_utf8(data) else {
        return Ok(None);
    };
    let trimmed = text.trim_start_matches('\u{feff}').trim_start();
    if !trimmed.starts_with('<') {
        return Ok(None);
    }
    let Ok(doc) = xml::parse(trimmed) else {
        return Ok(None);
    };
    if doc.name != "KeyFile" {
        return Ok(None);
    }
    let version = doc
        .path(&["Meta", "Version"])
        .map(|e| e.text.trim().to_owned());
    let data_el = doc.path(&["Key", "Data"]).ok_or(Error::InvalidKeyFile)?;

    let key: Vec<u8> = match version.as_deref() {
        Some("2.0") => {
            let bytes = hex_decode(&data_el.text).ok_or(Error::InvalidKeyFile)?;
            if let Some(expected) = data_el.attr("Hash") {
                let actual = sha256(&[&bytes]);
                let expected = hex_decode(expected).ok_or(Error::InvalidKeyFile)?;
                if actual[..4] != expected[..] {
                    return Err(Error::InvalidKeyFile);
                }
            }
            bytes
        }
        Some("1.0") | Some("1.00") => {
            use base64::Engine;
            base64::engine::general_purpose::STANDARD
                .decode(data_el.text.trim())
                .map_err(|_| Error::InvalidKeyFile)?
        }
        _ => return Err(Error::InvalidKeyFile),
    };
    let key = Zeroizing::new(key);
    let arr: [u8; 32] = key
        .as_slice()
        .try_into()
        .map_err(|_| Error::InvalidKeyFile)?;
    Ok(Some(Zeroizing::new(arr)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn raw_32_byte_key_file_is_used_directly() {
        let raw = [7u8; 32];
        assert_eq!(*key_file_key(&raw).unwrap(), raw);
    }

    #[test]
    fn hex_key_file_is_decoded() {
        let hex = "ab".repeat(32);
        assert_eq!(*key_file_key(hex.as_bytes()).unwrap(), [0xab; 32]);
    }

    #[test]
    fn any_other_file_is_hashed() {
        let data = b"just some photo bytes";
        assert_eq!(key_file_key(data).unwrap(), sha256(&[data]));
    }

    #[test]
    fn xml_v2_key_file_with_bad_hash_is_rejected() {
        let hex = "00".repeat(32);
        let xml = format!(
            "<?xml version=\"1.0\"?><KeyFile><Meta><Version>2.0</Version></Meta>\
             <Key><Data Hash=\"DEADBEEF\">{hex}</Data></Key></KeyFile>"
        );
        assert!(matches!(
            key_file_key(xml.as_bytes()),
            Err(Error::InvalidKeyFile)
        ));
    }
}
