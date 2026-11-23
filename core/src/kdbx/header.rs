//! The unencrypted outer header at the start of every KDBX file.

use super::crypto::KdfCost;
use crate::error::{Error, Result, corrupt};
use crate::util::Cursor;

const SIGNATURE_1: u32 = 0x9AA2_D903;
const SIGNATURE_2_KDBX: u32 = 0xB54B_FB67;
const SIGNATURE_2_KDB: u32 = 0xB54B_FB65; // KeePass 1.x

/// KDBX format version as stored in the file, e.g. 3.1, 4.0, 4.1.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FormatVersion {
    pub major: u16,
    pub minor: u16,
}

impl std::fmt::Display for FormatVersion {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}.{}", self.major, self.minor)
    }
}

// Outer header field IDs.
pub(crate) const END: u8 = 0;
pub(crate) const CIPHER_ID: u8 = 2;
pub(crate) const COMPRESSION: u8 = 3;
pub(crate) const MASTER_SEED: u8 = 4;
pub(crate) const TRANSFORM_SEED: u8 = 5; // KDBX 3
pub(crate) const TRANSFORM_ROUNDS: u8 = 6; // KDBX 3
pub(crate) const ENCRYPTION_IV: u8 = 7;
pub(crate) const PROTECTED_STREAM_KEY: u8 = 8; // KDBX 3
pub(crate) const STREAM_START_BYTES: u8 = 9; // KDBX 3
pub(crate) const INNER_STREAM_ID: u8 = 10; // KDBX 3
pub(crate) const KDF_PARAMETERS: u8 = 11; // KDBX 4

#[derive(Clone)]
pub(crate) struct OuterHeader {
    pub version: FormatVersion,
    /// Every field exactly as read, in order, so fields we don't understand are written back
    /// unchanged.
    pub fields: Vec<(u8, Vec<u8>)>,
    /// Byte length of the header in the file it was read from, including signature and version.
    pub len: usize,
}

impl OuterHeader {
    pub fn read(data: &[u8]) -> Result<Self> {
        let mut c = Cursor::new(data);
        if data.len() < 12 {
            return Err(Error::NotKeePass);
        }
        let sig1 = c.u32()?;
        let sig2 = c.u32()?;
        if sig1 != SIGNATURE_1 {
            return Err(Error::NotKeePass);
        }
        if sig2 == SIGNATURE_2_KDB {
            return Err(Error::UnsupportedVersion("KeePass 1.x (.kdb)".into()));
        }
        if sig2 != SIGNATURE_2_KDBX {
            return Err(Error::NotKeePass);
        }
        let minor = c.u16()?;
        let major = c.u16()?;
        let version = FormatVersion { major, minor };
        if !(major == 3 || major == 4) {
            return Err(Error::UnsupportedVersion(version.to_string()));
        }

        let mut fields = Vec::new();
        loop {
            let id = c.u8()?;
            let len = if major >= 4 {
                c.u32()? as usize
            } else {
                c.u16()? as usize
            };
            let value = c.take(len)?.to_vec();
            if id == END {
                fields.push((id, value));
                break;
            }
            fields.push((id, value));
        }
        Ok(Self {
            version,
            fields,
            len: c.position(),
        })
    }

    /// Replaces a field's value, or adds the field just before the end marker.
    pub fn set(&mut self, id: u8, value: Vec<u8>) {
        if let Some(field) = self.fields.iter_mut().find(|(i, _)| *i == id) {
            field.1 = value;
        } else {
            let end = self.fields.iter().position(|(i, _)| *i == END);
            self.fields
                .insert(end.unwrap_or(self.fields.len()), (id, value));
        }
    }

    /// The header as it goes at the very start of a KDBX 4 file.
    pub fn to_bytes(&self) -> Result<Vec<u8>> {
        if self.version.major != 4 {
            return Err(Error::NeedsUpgrade);
        }
        let mut out = Vec::new();
        out.extend_from_slice(&SIGNATURE_1.to_le_bytes());
        out.extend_from_slice(&SIGNATURE_2_KDBX.to_le_bytes());
        out.extend_from_slice(&self.version.minor.to_le_bytes());
        out.extend_from_slice(&self.version.major.to_le_bytes());
        for (id, value) in &self.fields {
            let len =
                u32::try_from(value.len()).map_err(|_| corrupt("a header field is too large"))?;
            out.push(*id);
            out.extend_from_slice(&len.to_le_bytes());
            out.extend_from_slice(value);
        }
        Ok(out)
    }

    pub fn field(&self, id: u8) -> Option<&[u8]> {
        self.fields
            .iter()
            .find(|(i, _)| *i == id)
            .map(|(_, v)| v.as_slice())
    }

    fn required(&self, id: u8, name: &str) -> Result<&[u8]> {
        self.field(id)
            .ok_or_else(|| corrupt(format!("header is missing {name}")))
    }

    pub fn cipher_id(&self) -> Result<&[u8]> {
        self.required(CIPHER_ID, "the cipher")
    }

    pub fn compressed(&self) -> Result<bool> {
        let v = self.required(COMPRESSION, "the compression flag")?;
        match u32::from_le_bytes(v.try_into().map_err(|_| corrupt("bad compression flag"))?) {
            0 => Ok(false),
            1 => Ok(true),
            n => Err(Error::Unsupported(format!("compression type {n}"))),
        }
    }

    pub fn master_seed(&self) -> Result<&[u8]> {
        let seed = self.required(MASTER_SEED, "the master seed")?;
        if seed.len() != 32 {
            return Err(corrupt("master seed has the wrong length"));
        }
        Ok(seed)
    }

    pub fn encryption_iv(&self) -> Result<&[u8]> {
        self.required(ENCRYPTION_IV, "the encryption IV")
    }

    // KDBX 3 only:
    pub fn transform_seed(&self) -> Result<&[u8]> {
        self.required(TRANSFORM_SEED, "the transform seed")
    }

    pub fn transform_rounds(&self) -> Result<u64> {
        let v = self.required(TRANSFORM_ROUNDS, "the transform rounds")?;
        Ok(u64::from_le_bytes(
            v.try_into().map_err(|_| corrupt("bad transform rounds"))?,
        ))
    }

    pub fn protected_stream_key(&self) -> Result<&[u8]> {
        self.required(PROTECTED_STREAM_KEY, "the protected stream key")
    }

    /// The 32 bytes the decrypted payload has to start with. They're how a KDBX 3 file tells a
    /// right key from a wrong one, so a shorter value (which would check less, or nothing) is
    /// refused.
    pub fn stream_start_bytes(&self) -> Result<&[u8]> {
        let bytes = self.required(STREAM_START_BYTES, "the stream start bytes")?;
        if bytes.len() != 32 {
            return Err(corrupt("the stream start bytes have the wrong length"));
        }
        Ok(bytes)
    }

    /// What this file's key derivation costs to run, for either format version.
    pub fn kdf_cost(&self) -> Result<KdfCost> {
        if self.version.major < 4 {
            return Ok(KdfCost::Aes {
                rounds: self.transform_rounds()?,
            });
        }
        KdfCost::of(&VariantDictionary::read(self.kdf_parameters()?)?)
    }

    pub fn inner_stream_id(&self) -> Result<u32> {
        let v = self.required(INNER_STREAM_ID, "the inner stream ID")?;
        Ok(u32::from_le_bytes(
            v.try_into().map_err(|_| corrupt("bad inner stream ID"))?,
        ))
    }

    // KDBX 4 only:
    pub fn kdf_parameters(&self) -> Result<&[u8]> {
        self.required(KDF_PARAMETERS, "the key derivation settings")
    }
}

/// KDBX 4's typed key/value dictionary (used for KDF parameters and public custom data).
/// Values are kept as raw bytes plus their type tag so they can be written back unchanged.
pub(crate) struct VariantDictionary {
    pub version: u16,
    pub items: Vec<(String, u8, Vec<u8>)>,
}

impl VariantDictionary {
    pub fn read(data: &[u8]) -> Result<Self> {
        let mut c = Cursor::new(data);
        let version = c.u16()?;
        if version & 0xFF00 != 0x0100 {
            return Err(Error::Unsupported(format!(
                "settings dictionary version {version:#06x}"
            )));
        }
        let mut items = Vec::new();
        loop {
            let kind = c.u8()?;
            if kind == 0 {
                break;
            }
            let key_len = usize::try_from(c.i32()?).map_err(|_| corrupt("bad dictionary key"))?;
            let key = String::from_utf8(c.take(key_len)?.to_vec())
                .map_err(|_| corrupt("bad dictionary key"))?;
            let value_len =
                usize::try_from(c.i32()?).map_err(|_| corrupt("bad dictionary value"))?;
            items.push((key, kind, c.take(value_len)?.to_vec()));
        }
        Ok(Self { version, items })
    }

    // Value type tags.
    pub const U32: u8 = 0x04;
    pub const U64: u8 = 0x05;
    pub const BYTES: u8 = 0x42;

    pub fn new() -> Self {
        Self {
            version: 0x0100,
            items: Vec::new(),
        }
    }

    /// Replaces a value, or adds it at the end.
    pub fn set(&mut self, key: &str, kind: u8, value: Vec<u8>) {
        if let Some(item) = self.items.iter_mut().find(|(k, _, _)| k == key) {
            (item.1, item.2) = (kind, value);
        } else {
            self.items.push((key.to_owned(), kind, value));
        }
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        let mut out = self.version.to_le_bytes().to_vec();
        for (key, kind, value) in &self.items {
            // Lengths are signed 32-bit in the format. Nothing we hold comes close to that.
            out.push(*kind);
            out.extend_from_slice(&(key.len() as i32).to_le_bytes());
            out.extend_from_slice(key.as_bytes());
            out.extend_from_slice(&(value.len() as i32).to_le_bytes());
            out.extend_from_slice(value);
        }
        out.push(0);
        out
    }

    pub fn bytes(&self, key: &str) -> Option<&[u8]> {
        self.items
            .iter()
            .find(|(k, _, _)| k == key)
            .map(|(_, _, v)| v.as_slice())
    }

    pub fn u32(&self, key: &str) -> Option<u32> {
        self.bytes(key)?.try_into().ok().map(u32::from_le_bytes)
    }

    pub fn u64(&self, key: &str) -> Option<u64> {
        self.bytes(key)?.try_into().ok().map(u64::from_le_bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_and_dictionary_are_written_exactly_as_they_were_read() {
        let mut dict = VariantDictionary::new();
        dict.set("$UUID", VariantDictionary::BYTES, vec![7; 16]);
        dict.set(
            "R",
            VariantDictionary::U64,
            60_000u64.to_le_bytes().to_vec(),
        );
        dict.set("X", 0x18, b"text".to_vec()); // a type we never interpret
        let dict_bytes = dict.to_bytes();
        let reread = VariantDictionary::read(&dict_bytes).unwrap();
        assert_eq!(reread.u64("R"), Some(60_000));
        assert_eq!(reread.to_bytes(), dict_bytes);

        let mut header = OuterHeader {
            version: FormatVersion { major: 4, minor: 1 },
            fields: vec![
                (CIPHER_ID, vec![1; 16]),
                (12, b"public custom data".to_vec()), // not interpreted, must survive
                (END, b"\r\n\r\n".to_vec()),
            ],
            len: 0,
        };
        header.set(KDF_PARAMETERS, dict_bytes.clone());
        header.set(CIPHER_ID, vec![2; 16]);
        let bytes = header.to_bytes().unwrap();
        let reread = OuterHeader::read(&bytes).unwrap();
        assert_eq!(reread.version, header.version);
        assert_eq!(reread.len, bytes.len());
        assert_eq!(reread.fields, header.fields);
        assert_eq!(
            reread.fields.iter().map(|(id, _)| *id).collect::<Vec<_>>(),
            [CIPHER_ID, 12, KDF_PARAMETERS, END]
        );
        assert_eq!(reread.to_bytes().unwrap(), bytes);
    }

    #[test]
    fn a_kdbx3_header_is_never_written() {
        let header = OuterHeader {
            version: FormatVersion { major: 3, minor: 1 },
            fields: vec![(END, Vec::new())],
            len: 0,
        };
        assert!(matches!(header.to_bytes(), Err(Error::NeedsUpgrade)));
    }
}
