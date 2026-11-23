//! Making a new, empty database.

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;

use crate::edit::{
    GENERATOR, GROUP_ICON, UNIX_EPOCH_IN_KEEPASS_SECONDS, check_text, format_date, leaf, new_group,
};
use crate::error::{Error, Result};
use crate::kdbx::{self, VariantDictionary};
use crate::util::random_bytes;
use crate::xml::Element;
use crate::{CompositeKey, Database};

/// What KeePass keeps of an entry's old versions by default.
const HISTORY_MAX_ITEMS: &str = "10";
const HISTORY_MAX_SIZE: &str = "6291456"; // 6 MiB

impl Database {
    /// A new, empty database called `name`, locked with `key`: KDBX 4.0 with AES-256, gzip,
    /// and Argon2id timed to take about a second to unlock on this device (see `kdbx/new.rs`).
    /// It has only its top group, named `name` too, as KeePass names it; the recycle bin is
    /// made at the first deletion. Nothing is written anywhere: [`save`](Self::save) makes
    /// the file.
    ///
    /// Slow on purpose: timing the key derivation runs it once.
    pub fn create(name: &str, key: &CompositeKey) -> Result<Self> {
        Self::create_with(name, key, &kdbx::timed_argon2id(kdbx::UNLOCK_TIME)?)
    }

    /// [`create`](Self::create) with given key derivation settings (tests use cheap ones).
    pub(crate) fn create_with(
        name: &str,
        key: &CompositeKey,
        kdf: &VariantDictionary,
    ) -> Result<Self> {
        let name = name.trim();
        if name.is_empty() {
            return Err(Error::Invalid("The database needs a name.".into()));
        }
        check_text("The name", name)?;
        let unix = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_secs() as i64);
        let now = format_date(unix + UNIX_EPOCH_IN_KEEPASS_SECONDS, 4);
        let root_uuid: [u8; 16] = random_bytes(16)?
            .as_slice()
            .try_into()
            .expect("asked for 16 bytes");
        let xml = new_file(name, &root_uuid, &now);
        Ok(Self::from_parts(kdbx::container(xml, kdf)?, key.hash()))
    }
}

/// The XML of an empty database, as KeePassXC writes a new one (`KeePassFile`, `Meta`, `Root`).
fn new_file(name: &str, root_uuid: &[u8; 16], now: &str) -> Element {
    let none = BASE64.encode([0u8; 16]);
    let mut meta = Element::new("Meta");
    for (tag, text) in [
        ("Generator", GENERATOR),
        ("DatabaseName", name),
        ("DatabaseNameChanged", now),
        ("DatabaseDescription", ""),
        ("DatabaseDescriptionChanged", now),
        ("DefaultUserName", ""),
        ("DefaultUserNameChanged", now),
        ("MaintenanceHistoryDays", "365"),
        ("Color", ""),
        ("MasterKeyChanged", now),
        ("MasterKeyChangeRec", "-1"),
        ("MasterKeyChangeForce", "-1"),
    ] {
        meta.children.push(leaf(tag, text));
    }
    let mut protection = Element::new("MemoryProtection");
    for (tag, on) in [
        ("ProtectTitle", false),
        ("ProtectUserName", false),
        ("ProtectPassword", true),
        ("ProtectURL", false),
        ("ProtectNotes", false),
    ] {
        protection
            .children
            .push(leaf(tag, if on { "True" } else { "False" }));
    }
    meta.children.push(protection);
    meta.children.push(Element::new("CustomIcons"));
    for (tag, text) in [
        ("RecycleBinEnabled", "True"),
        ("RecycleBinUUID", none.as_str()),
        ("RecycleBinChanged", now),
        ("EntryTemplatesGroup", none.as_str()),
        ("EntryTemplatesGroupChanged", now),
        ("HistoryMaxItems", HISTORY_MAX_ITEMS),
        ("HistoryMaxSize", HISTORY_MAX_SIZE),
        ("LastSelectedGroup", none.as_str()),
        ("LastTopVisibleGroup", none.as_str()),
    ] {
        meta.children.push(leaf(tag, text));
    }
    meta.children.push(Element::new("CustomData"));

    let mut root = Element::new("Root");
    root.children
        .push(new_group(root_uuid, name, "", GROUP_ICON, now, true));
    root.children.push(Element::new("DeletedObjects"));

    let mut file = Element::new("KeePassFile");
    file.children.push(meta);
    file.children.push(root);
    file
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> CompositeKey {
        CompositeKey::new(Some(b"correct horse"), None).unwrap()
    }

    /// Cheap settings, so the tests don't spend a second each in key derivation.
    fn quick() -> VariantDictionary {
        kdbx::argon2id(2).unwrap()
    }

    #[test]
    fn a_new_database_saves_and_opens_again() {
        let db = Database::create_with("Personal", &key(), &quick()).unwrap();
        let file = db.save().unwrap();
        let opened = Database::open(&file, &key()).unwrap();
        assert_eq!(opened.format_version().to_string(), "4.0");
        assert!(opened.recycle_bin_enabled());
        assert_eq!(opened.root().name(), "Personal");
        let listing = opened.listing(None).unwrap();
        assert!(listing.entries.is_empty() && listing.groups.is_empty());
        assert!(matches!(
            Database::open(&file, &CompositeKey::new(Some(b"wrong"), None).unwrap()),
            Err(Error::WrongKey)
        ));
    }

    #[test]
    fn entries_and_deletions_work_in_a_new_database() {
        let mut db = Database::create_with("Work", &key(), &quick()).unwrap();
        let root = db.root().uuid().unwrap();
        let group = db.create_group(&root, "Servers", "").unwrap();
        db.delete_group(&group).unwrap(); // makes the recycle bin
        assert!(db.recycle_bin().is_some());
        let file = db.save().unwrap();
        Database::open(&file, &key()).unwrap();
    }

    #[test]
    fn a_name_is_needed_and_must_be_text_keepass_can_hold() {
        assert!(matches!(
            Database::create_with("  ", &key(), &quick()),
            Err(Error::Invalid(_))
        ));
        assert!(Database::create_with("bad\u{1}name", &key(), &quick()).is_err());
    }

    #[test]
    fn key_derivation_is_argon2id_with_the_memory_autofill_can_afford() {
        let db = Database::create_with("Personal", &key(), &quick()).unwrap();
        match db.kdf_cost().unwrap() {
            kdbx::KdfCost::Argon2 {
                memory, iterations, ..
            } => {
                assert_eq!(memory, kdbx::ARGON2_MEMORY);
                assert_eq!(iterations, 2);
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn timing_gives_a_usable_number_of_passes() {
        let kdf = kdbx::timed_argon2id(std::time::Duration::from_millis(200)).unwrap();
        let passes = kdf.u64("I").unwrap();
        assert!((2..=100).contains(&passes), "{passes}");
    }
}
