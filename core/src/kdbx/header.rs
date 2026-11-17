//! The unencrypted outer header at the start of every KDBX file.

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
const END: u8 = 0;
const CIPHER_ID: u8 = 2;
const COMPRESSION: u8 = 3;
const MASTER_SEED: u8 = 4;
const TRANSFORM_SEED: u8 = 5; // KDBX 3
const TRANSFORM_ROUNDS: u8 = 6; // KDBX 3
const ENCRYPTION_IV: u8 = 7;
const PROTECTED_STREAM_KEY: u8 = 8; // KDBX 3
const STREAM_START_BYTES: u8 = 9; // KDBX 3
const INNER_STREAM_ID: u8 = 10; // KDBX 3
const KDF_PARAMETERS: u8 = 11; // KDBX 4

pub(crate) struct OuterHeader {
    pub version: FormatVersion,
    /// Every field exactly as read, in order, so the header can be written back unchanged.
    pub fields: Vec<(u8, Vec<u8>)>,
    /// Byte length of the header, including signature and version.
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

    pub fn stream_start_bytes(&self) -> Result<&[u8]> {
        self.required(STREAM_START_BYTES, "the stream start bytes")
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
#[allow(dead_code)] // `version` is written back by the writer (next milestone)
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
