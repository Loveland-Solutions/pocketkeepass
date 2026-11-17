//! The bridge to the Android app. UniFFI turns everything exported here into Kotlin.
//!
//! Rules for this file:
//! - It holds no logic of its own. It only wraps the engine in types that can cross the bridge.
//! - Secrets cross as bytes, never as strings. Kotlin can wipe a `ByteArray` after use, but a
//!   JVM `String` stays in memory until the garbage collector gets to it.
//! - Opening and saving run the key derivation, which is slow on purpose. Kotlin must call them
//!   off the main thread.

use std::sync::{Arc, PoisonError, RwLock, RwLockReadGuard};

use zeroize::Zeroizing;

use crate::error::{Error, Result};
use crate::{CompositeKey, Database, Group};

/// A database that has been opened with the right key and is held decrypted in memory.
///
/// Call [`lock`](Self::lock) as soon as the user is done (auto-lock, screen off, app closed).
/// That wipes the decrypted content straight away instead of whenever Kotlin's garbage
/// collector lets go of this object. Every other method fails with `Locked` afterwards.
#[derive(uniffi::Object)]
pub struct UnlockedDatabase {
    db: RwLock<Option<Database>>,
}

/// What the app shows about a database once it's open. Nothing secret.
#[derive(uniffi::Record)]
pub struct DatabaseSummary {
    /// The name stored in the database. Often empty.
    pub name: String,
    /// The KDBX version, e.g. "4.1".
    pub format_version: String,
    /// True for KDBX 3 databases: they must be upgraded (with the user's consent) to be saved.
    pub needs_upgrade: bool,
    /// Groups below the root group, at any depth.
    pub group_count: u32,
    /// Entries at any depth, not counting older versions kept in entry history.
    pub entry_count: u32,
}

#[uniffi::export]
impl UnlockedDatabase {
    /// Decrypts a whole `.kdbx` file. `password` is the master password as UTF-8 bytes;
    /// `key_file` is the content of the key file. At least one of them is needed.
    ///
    /// The copies of `password` and `key_file` on this side of the bridge are wiped before this
    /// returns. The caller should wipe its own.
    #[uniffi::constructor]
    pub fn open(
        file: Vec<u8>,
        password: Option<Vec<u8>>,
        key_file: Option<Vec<u8>>,
    ) -> Result<Arc<Self>> {
        let password = password.map(Zeroizing::new);
        let key_file = key_file.map(Zeroizing::new);
        let key = CompositeKey::new(
            password.as_ref().map(|p| p.as_slice()),
            key_file.as_ref().map(|k| k.as_slice()),
        )?;
        let db = Database::open(&file, &key)?;
        Ok(Arc::new(Self {
            db: RwLock::new(Some(db)),
        }))
    }

    pub fn summary(&self) -> Result<DatabaseSummary> {
        fn count(group: Group<'_>, groups: &mut u32, entries: &mut u32) {
            *entries += group.entries().count() as u32;
            for child in group.groups() {
                *groups += 1;
                count(child, groups, entries);
            }
        }
        self.with(|db| {
            let (mut group_count, mut entry_count) = (0, 0);
            count(db.root(), &mut group_count, &mut entry_count);
            Ok(DatabaseSummary {
                name: db.name().to_owned(),
                format_version: db.format_version().to_string(),
                needs_upgrade: db.needs_upgrade(),
                group_count,
                entry_count,
            })
        })
    }

    /// Converts a KDBX 3 database to KDBX 4 in memory so that it can be saved. Ask the user
    /// first: older apps that only read KDBX 3 won't open the file once it's saved.
    pub fn upgrade_to_kdbx4(&self) -> Result<()> {
        let mut db = self.db.write().unwrap_or_else(PoisonError::into_inner);
        db.as_mut().ok_or(Error::Locked)?.upgrade_to_kdbx4()
    }

    /// Encrypts the database into the bytes of a complete `.kdbx` file. The engine has already
    /// read the result back and checked it. Replacing the old file safely is the caller's job.
    pub fn save(&self) -> Result<Vec<u8>> {
        self.with(Database::save)
    }

    /// Wipes the decrypted database from memory. Safe to call more than once.
    pub fn lock(&self) {
        *self.db.write().unwrap_or_else(PoisonError::into_inner) = None;
    }

    pub fn is_locked(&self) -> bool {
        self.read().is_none()
    }
}

impl UnlockedDatabase {
    fn read(&self) -> RwLockReadGuard<'_, Option<Database>> {
        // A panic while the lock was held doesn't make the data unusable, so ignore poisoning.
        self.db.read().unwrap_or_else(PoisonError::into_inner)
    }

    fn with<T>(&self, f: impl FnOnce(&Database) -> Result<T>) -> Result<T> {
        f(self.read().as_ref().ok_or(Error::Locked)?)
    }
}

/// The engine's version, for the app's About screen.
#[uniffi::export]
pub fn engine_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(name: &str) -> Vec<u8> {
        std::fs::read(format!(
            "{}/tests/fixtures/{name}",
            env!("CARGO_MANIFEST_DIR")
        ))
        .unwrap()
    }

    fn open(name: &str) -> Arc<UnlockedDatabase> {
        UnlockedDatabase::open(fixture(name), Some(b"test-password".to_vec()), None).unwrap()
    }

    #[test]
    fn summary_describes_the_database() {
        let summary = open("kdbx41-keepassxc.kdbx").summary().unwrap();
        assert_eq!(summary.name, "Passwords");
        assert_eq!(summary.format_version, "4.1");
        assert!(!summary.needs_upgrade);
        assert_eq!((summary.group_count, summary.entry_count), (1, 3));

        // History items aren't entries: "Email" was edited once, and still counts once.
        let summary = open("pw-only.kdbx").summary().unwrap();
        assert_eq!(summary.format_version, "3.1");
        assert!(summary.needs_upgrade);
        assert_eq!((summary.group_count, summary.entry_count), (1, 2));
    }

    #[test]
    fn open_reports_a_wrong_or_missing_key() {
        let file = fixture("pw-only.kdbx");
        let wrong = UnlockedDatabase::open(file.clone(), Some(b"nope".to_vec()), None);
        assert!(matches!(wrong, Err(Error::WrongKey)));
        let none = UnlockedDatabase::open(file, None, None);
        assert!(matches!(none, Err(Error::InvalidKeyFile)));
    }

    #[test]
    fn a_key_file_unlocks_through_the_bridge() {
        let db = UnlockedDatabase::open(
            fixture("keyfile-only.kdbx"),
            None,
            Some(fixture("keyfile.keyx")),
        )
        .unwrap();
        assert_eq!(db.summary().unwrap().entry_count, 1);
    }

    #[test]
    fn upgrade_then_save_gives_a_file_that_opens_again() {
        let db = open("pw-only.kdbx");
        assert!(matches!(db.save(), Err(Error::NeedsUpgrade)));
        db.upgrade_to_kdbx4().unwrap();
        let saved = db.save().unwrap();

        let reopened =
            UnlockedDatabase::open(saved, Some(b"test-password".to_vec()), None).unwrap();
        let summary = reopened.summary().unwrap();
        assert_eq!(summary.format_version, "4.0");
        assert!(!summary.needs_upgrade);
        assert_eq!(summary.entry_count, 2);
    }

    #[test]
    fn locking_wipes_the_database_and_refuses_everything_after() {
        let db = open("kdbx4-argon2id-chacha20.kdbx");
        assert!(!db.is_locked());
        db.lock();
        db.lock();
        assert!(db.is_locked());
        assert!(matches!(db.summary(), Err(Error::Locked)));
        assert!(matches!(db.save(), Err(Error::Locked)));
        assert!(matches!(db.upgrade_to_kdbx4(), Err(Error::Locked)));
    }
}
