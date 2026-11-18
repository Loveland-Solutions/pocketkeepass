//! Saving: every database we can save must come back from a save-and-reopen with nothing added,
//! removed, reordered, or changed.

mod common;

use common::{KDBX3, KDBX4, PASSWORD, assert_matches_keepassxc_export, first_difference, read};
use pocketkeepass_core::{CompositeKey, Database, Error};

#[test]
fn kdbx4_databases_come_back_identical_after_saving() {
    for fixture in KDBX4 {
        let original = fixture.open();
        let saved = original
            .save()
            .unwrap_or_else(|e| panic!("{}: {e}", fixture.file));
        assert!(
            saved != read(fixture.file),
            "{}: nothing was re-encrypted",
            fixture.file
        );

        let reopened = Database::open(&saved, &fixture.key())
            .unwrap_or_else(|e| panic!("{}: reopening failed: {e}", fixture.file));
        assert_eq!(
            first_difference(original.xml(), reopened.xml()),
            None,
            "{}",
            fixture.file
        );
        assert_eq!(reopened.format_version(), original.format_version());
        assert_eq!(reopened.attachment_count(), original.attachment_count());
        assert_matches_keepassxc_export(&reopened, fixture.export);

        // And again from the file we wrote, so our own output is as good a starting point.
        let second = Database::open(&reopened.save().unwrap(), &fixture.key()).unwrap();
        assert_eq!(first_difference(original.xml(), second.xml()), None);
    }
}

#[test]
fn every_save_is_encrypted_differently() {
    let db = KDBX4[0].open();
    let (a, b) = (db.save().unwrap(), db.save().unwrap());
    assert!(a != b);
    // Different salts and seeds make the two files differ from the header onwards, not just
    // in the payload. The first 12 bytes are the signature and version.
    assert_eq!(a[..12], b[..12]);
    let shared = a.iter().zip(&b).take_while(|(x, y)| x == y).count();
    assert!(shared < 200, "the files only differ after byte {shared}");
}

#[test]
fn a_saved_file_only_opens_with_the_same_key() {
    let saved = KDBX4[0].open().save().unwrap();
    let wrong = CompositeKey::new(Some(b"nope"), None).unwrap();
    assert!(matches!(
        Database::open(&saved, &wrong),
        Err(Error::WrongKey)
    ));

    // Password plus key file: each half alone must fail.
    let saved = KDBX4[1].open().save().unwrap();
    let password_only = CompositeKey::new(Some(PASSWORD), None).unwrap();
    let key_file_only = CompositeKey::new(None, Some(&read("kdbx4-hex.key"))).unwrap();
    assert!(matches!(
        Database::open(&saved, &password_only),
        Err(Error::WrongKey)
    ));
    assert!(matches!(
        Database::open(&saved, &key_file_only),
        Err(Error::WrongKey)
    ));
    assert!(Database::open(&saved, &KDBX4[1].key()).is_ok());
}

#[test]
fn damage_to_a_saved_file_is_detected_everywhere() {
    let saved = KDBX4[0].open().save().unwrap();
    let key = KDBX4[0].key();
    // One flipped bit in the header, in the middle of the payload, and near the end.
    for at in [20, saved.len() / 2, saved.len() - 50] {
        let mut damaged = saved.clone();
        damaged[at] ^= 0x01;
        assert!(
            matches!(Database::open(&damaged, &key), Err(Error::Corrupt(_))),
            "a flipped bit at byte {at} went unnoticed"
        );
    }
    for cut in [100, saved.len() / 2, saved.len() - 1] {
        assert!(Database::open(&saved[..cut], &key).is_err());
    }
}

#[test]
fn kdbx3_databases_are_not_saved_without_an_upgrade() {
    for fixture in KDBX3 {
        let db = fixture.open();
        assert!(db.needs_upgrade());
        assert!(
            matches!(db.save(), Err(Error::NeedsUpgrade)),
            "{}",
            fixture.file
        );
    }
    assert!(!KDBX4[0].open().needs_upgrade());
}

#[test]
fn upgraded_kdbx3_databases_save_as_kdbx4_with_the_same_content() {
    for fixture in KDBX3 {
        let mut db = fixture.open();
        db.upgrade_to_kdbx4()
            .unwrap_or_else(|e| panic!("{}: {e}", fixture.file));
        assert!(!db.needs_upgrade());
        assert_eq!(db.format_version().to_string(), "4.0");
        // Every field KeePassXC found in the KDBX 3 file is still there, unchanged.
        assert_matches_keepassxc_export(&db, fixture.export);

        let saved = db
            .save()
            .unwrap_or_else(|e| panic!("{}: {e}", fixture.file));
        let reopened = Database::open(&saved, &fixture.key())
            .unwrap_or_else(|e| panic!("{}: reopening failed: {e}", fixture.file));
        assert_eq!(reopened.format_version().to_string(), "4.0");
        assert_eq!(
            first_difference(db.xml(), reopened.xml()),
            None,
            "{}",
            fixture.file
        );
        assert_eq!(reopened.attachment_count(), db.attachment_count());
        assert_matches_keepassxc_export(&reopened, fixture.export);
    }
}

#[test]
fn upgrading_moves_attachments_out_of_the_xml() {
    let mut db = KDBX3[3].open();
    assert_eq!(db.attachment_count(), 0); // KDBX 3 keeps them inside the XML
    assert!(db.xml().path(&["Meta", "Binaries"]).is_some());
    db.upgrade_to_kdbx4().unwrap();
    assert_eq!(db.attachment_count(), 2);
    assert!(db.xml().path(&["Meta", "Binaries"]).is_none());
}

#[test]
fn upgrading_a_kdbx4_database_changes_nothing() {
    let original = KDBX4[3].open();
    let mut db = KDBX4[3].open();
    db.upgrade_to_kdbx4().unwrap();
    assert_eq!(db.format_version().to_string(), "4.1");
    assert_eq!(first_difference(original.xml(), db.xml()), None);
}
