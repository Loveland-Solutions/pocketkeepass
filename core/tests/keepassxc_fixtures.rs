//! Opens real databases written by other KeePass apps and checks every decrypted field against
//! KeePassXC's own export of the same file.

mod common;

use common::{PASSWORD, assert_matches_keepassxc_export, open, read};
use passthekey_core::{CompositeKey, Database, Error};

#[test]
fn password_only_database() {
    let db = open("pw-only.kdbx", Some(PASSWORD), None).unwrap();
    assert_eq!(db.format_version().to_string(), "3.1");

    let root = db.root();
    let email = root.entry("Email").expect("Email entry");
    assert_eq!(email.username(), "me@example.com");
    assert_eq!(email.url(), "https://mail.example.com");
    assert_eq!(email.notes(), "line one\nline two");
    assert_eq!(email.password(), "hunter3");
    assert!(email.is_protected("Password"));
    assert_eq!(
        email.history().map(|h| h.password()).collect::<Vec<_>>(),
        ["hunter2"]
    );

    let cu = root
        .group("Banking")
        .and_then(|g| g.entry("Credit Union"))
        .expect("Banking/Credit Union");
    assert_eq!(cu.password(), "üñíçødé-pässwörd ✓");

    assert_matches_keepassxc_export(&db, "pw-only.export.xml");
}

#[test]
fn key_file_only_database() {
    let db = open("keyfile-only.kdbx", None, Some("keyfile.keyx")).unwrap();
    assert_eq!(
        db.root().entry("Keyfile Entry").unwrap().password(),
        "s3cret-for-keyfile"
    );
    assert_matches_keepassxc_export(&db, "keyfile-only.export.xml");
}

#[test]
fn password_and_key_file_database() {
    let db = open(
        "pw-and-keyfile.kdbx",
        Some(PASSWORD),
        Some("pw-and-keyfile.keyx"),
    )
    .unwrap();
    assert_eq!(
        db.root().entry("Both Entry").unwrap().password(),
        "both-factors!"
    );
    assert_matches_keepassxc_export(&db, "pw-and-keyfile.export.xml");
}

#[test]
fn wrong_password_is_reported_as_wrong_key() {
    assert!(matches!(
        open("pw-only.kdbx", Some(b"nope"), None),
        Err(Error::WrongKey)
    ));
}

#[test]
fn missing_half_of_a_two_factor_key_is_wrong_key() {
    assert!(matches!(
        open("pw-and-keyfile.kdbx", Some(PASSWORD), None),
        Err(Error::WrongKey)
    ));
    assert!(matches!(
        open("pw-and-keyfile.kdbx", None, Some("pw-and-keyfile.keyx")),
        Err(Error::WrongKey)
    ));
}

#[test]
fn wrong_key_file_is_reported_as_wrong_key() {
    assert!(matches!(
        open("keyfile-only.kdbx", None, Some("pw-and-keyfile.keyx")),
        Err(Error::WrongKey)
    ));
}

#[test]
fn non_keepass_file_is_rejected() {
    let key = CompositeKey::new(Some(PASSWORD), None).unwrap();
    assert!(matches!(
        Database::open(b"hello, definitely not a database", &key),
        Err(Error::NotKeePass)
    ));
    assert!(matches!(Database::open(b"", &key), Err(Error::NotKeePass)));
}

#[test]
fn truncated_file_is_reported_as_damaged_not_a_crash() {
    let data = read("pw-only.kdbx");
    let key = CompositeKey::new(Some(PASSWORD), None).unwrap();
    for cut in [13, 100, data.len() / 2, data.len() - 1] {
        let result = Database::open(&data[..cut], &key);
        assert!(
            matches!(result, Err(Error::Corrupt(_)) | Err(Error::WrongKey)),
            "cut at {cut}: expected an error, got {:?}",
            result.err()
        );
    }
}

#[test]
fn flipped_byte_in_payload_is_detected() {
    let mut data = read("pw-only.kdbx");
    let last = data.len() - 40;
    data[last] ^= 0x01;
    let key = CompositeKey::new(Some(PASSWORD), None).unwrap();
    assert!(Database::open(&data, &key).is_err());
}

// ---- KDBX 4 (files written by pykeepass 4.2.0; answer key exported by KeePassXC) ----

fn check_kdbx4(db: &Database, export_name: &str) {
    assert_eq!(db.format_version().to_string(), "4.0");
    let root = db.root();
    let test = root.entry("Test").expect("Test entry");
    assert_eq!(test.username(), "kdbx4-user");
    assert_eq!(test.password(), "kdbx4-works");
    assert_eq!(test.url(), "https://kdbx4.example");
    assert_eq!(test.notes(), "multi\nline");
    assert_eq!(
        test.field("TimeOtp-Secret-Base32"),
        Some("JBSWY3DPEHPK3PXP")
    );
    assert!(test.is_protected("TimeOtp-Secret-Base32"));
    assert_eq!(test.field("Plain Custom"), Some("not secret"));
    assert!(!test.is_protected("Plain Custom"));
    let unicode = root
        .group("Sub Group")
        .and_then(|g| g.entry("Unicode"))
        .expect("Sub Group/Unicode");
    assert_eq!(unicode.password(), "üñíçødé ✓ 🔑");
    assert_eq!(db.attachment_count(), 1);
    assert_matches_keepassxc_export(db, export_name);
}

#[test]
fn kdbx4_argon2id_chacha20() {
    let db = open("kdbx4-argon2id-chacha20.kdbx", Some(PASSWORD), None).unwrap();
    check_kdbx4(&db, "kdbx4-argon2id-chacha20.export.xml");
}

#[test]
fn kdbx4_argon2d_aes256_with_hex_key_file() {
    let db = open(
        "kdbx4-argon2d-aes256-keyfile.kdbx",
        Some(PASSWORD),
        Some("kdbx4-hex.key"),
    )
    .unwrap();
    check_kdbx4(&db, "kdbx4-argon2d-aes256-keyfile.export.xml");
}

#[test]
fn kdbx4_aeskdf_twofish() {
    let db = open("kdbx4-aeskdf-twofish.kdbx", Some(PASSWORD), None).unwrap();
    check_kdbx4(&db, "kdbx4-aeskdf-twofish.export.xml");
}

#[test]
fn kdbx4_wrong_password_is_caught_by_header_hmac() {
    assert!(matches!(
        open("kdbx4-argon2id-chacha20.kdbx", Some(b"nope"), None),
        Err(Error::WrongKey)
    ));
    assert!(matches!(
        open("kdbx4-argon2d-aes256-keyfile.kdbx", Some(PASSWORD), None),
        Err(Error::WrongKey)
    ));
}

#[test]
fn kdbx4_tampering_is_detected_by_block_hmac() {
    let mut data = read("kdbx4-argon2id-chacha20.kdbx");
    let i = data.len() - 50;
    data[i] ^= 0x01;
    let key = CompositeKey::new(Some(PASSWORD), None).unwrap();
    assert!(matches!(
        Database::open(&data, &key),
        Err(Error::Corrupt(_))
    ));
}

#[test]
fn kdbx41_written_by_keepassxc() {
    let db = open("kdbx41-keepassxc.kdbx", Some(PASSWORD), None).unwrap();
    assert_eq!(db.format_version().to_string(), "4.1");
    let root = db.root();
    assert_eq!(root.entry("Tagged").unwrap().password(), "tags-need-4.1");
    assert_eq!(
        root.entry("Added By KeePassXC").unwrap().password(),
        "written-by-keepassxc"
    );
    assert_eq!(root.entry("Nested").unwrap().password(), "nested ✓");
    assert!(root.group("KXC Group").is_some());

    // 4.1-only data must survive in the tree untouched (it's what forced the 4.1 format).
    let nested = db
        .xml()
        .path(&["Root", "Group"])
        .unwrap()
        .children_named("Entry");
    let nested = nested.into_iter().find(|e| {
        e.children_named("String")
            .any(|s| s.child_text("Value") == "Nested")
    });
    assert!(!nested.unwrap().child_text("PreviousParentGroup").is_empty());

    assert_matches_keepassxc_export(&db, "kdbx41-keepassxc.export.xml");
}
