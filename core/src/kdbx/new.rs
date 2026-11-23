//! The container for a new database: a KDBX 4.0 header with AES-256, gzip, and Argon2id, its key
//! derivation timed on this device. Saving (`write.rs`) fills in every random value.
//!
//! KDBX 4.0 rather than 4.1, because more apps read it (KeePass 2.35 and later, every current
//! KeePassXC, KeePassDX, and Keepass2Android), and nothing a new database holds needs 4.1.

use std::time::{Duration, Instant};

use super::Decrypted;
use super::crypto::{self, CIPHER_AES256, KDF_ARGON2ID};
use super::header::{self, FormatVersion, OuterHeader, VariantDictionary};
use crate::error::Result;
use crate::util::random_bytes;
use crate::xml::Element;

/// How long unlocking should take on the device the database is made on. KeePassXC aims for
/// one second too.
pub(crate) const UNLOCK_TIME: Duration = Duration::from_secs(1);

/// Argon2's memory, in bytes. KeePassXC's default is 64 MiB, but the iOS AutoFill extension
/// unlocks the database itself and gets far less memory than an app, so this stays at 32 MiB
/// and the time comes from more passes instead.
pub(crate) const ARGON2_MEMORY: u64 = 32 << 20;
/// Argon2's lanes. The engine computes them one after another, so this doesn't change how long
/// unlocking takes here; 2 is what KeePassXC uses on most phones' worth of cores.
const ARGON2_PARALLELISM: u32 = 2;
/// The fewest passes, however fast the device: Argon2's own recommended minimum for Argon2id.
const MIN_ITERATIONS: u64 = 2;
/// The most passes, so a slow measurement can't make the database take minutes to open.
const MAX_ITERATIONS: u64 = 100;

/// Argon2id settings with this many passes, and a placeholder salt (each save makes a new one).
pub(crate) fn argon2id(iterations: u64) -> Result<VariantDictionary> {
    let mut kdf = VariantDictionary::new();
    kdf.set("$UUID", VariantDictionary::BYTES, KDF_ARGON2ID.to_vec());
    kdf.set("S", VariantDictionary::BYTES, random_bytes(32)?.to_vec());
    kdf.set(
        "P",
        VariantDictionary::U32,
        ARGON2_PARALLELISM.to_le_bytes().to_vec(),
    );
    kdf.set(
        "M",
        VariantDictionary::U64,
        ARGON2_MEMORY.to_le_bytes().to_vec(),
    );
    kdf.set(
        "I",
        VariantDictionary::U64,
        iterations.to_le_bytes().to_vec(),
    );
    kdf.set("V", VariantDictionary::U32, 0x13u32.to_le_bytes().to_vec());
    Ok(kdf)
}

/// Argon2id settings that take about `target` to run on this device: one pass is timed, and
/// the number of passes scaled from it.
pub(crate) fn timed_argon2id(target: Duration) -> Result<VariantDictionary> {
    let probe = argon2id(1)?;
    let start = Instant::now();
    crypto::kdbx4_kdf(&[0u8; 32], &probe)?;
    let one_pass = start.elapsed().as_secs_f64().max(1e-4);
    let passes = (target.as_secs_f64() / one_pass).round() as u64;
    argon2id(passes.clamp(MIN_ITERATIONS, MAX_ITERATIONS))
}

/// A KDBX 4.0 container around `xml`, with `kdf` for key derivation.
pub(crate) fn container(xml: Element, kdf: &VariantDictionary) -> Result<Decrypted> {
    let mut header = OuterHeader {
        version: FormatVersion { major: 4, minor: 0 },
        fields: Vec::new(),
        len: 0, // not from a file
    };
    header.set(header::CIPHER_ID, CIPHER_AES256.to_vec());
    header.set(header::COMPRESSION, 1u32.to_le_bytes().to_vec()); // gzip
    // Placeholders, of the right size: saving replaces both.
    header.set(header::MASTER_SEED, random_bytes(32)?.to_vec());
    header.set(header::ENCRYPTION_IV, random_bytes(16)?.to_vec());
    header.set(header::KDF_PARAMETERS, kdf.to_bytes());
    header.set(header::END, b"\r\n\r\n".to_vec());
    Ok(Decrypted {
        version: header.version,
        xml,
        binaries: Vec::new(),
        inner_extra: Vec::new(),
        header,
    })
}
