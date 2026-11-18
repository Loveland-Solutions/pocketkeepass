//! Browsing groups and searching entries, on `browse.kdbx` (see `fixtures/README.md`).

mod common;

use common::{PASSWORD, assert_matches_keepassxc_export, open};
use passthekey_core::{Database, uuid_from_hex, uuid_to_hex};

fn database() -> Database {
    open("browse.kdbx", Some(PASSWORD), None).unwrap()
}

fn uuid(byte: u8) -> [u8; 16] {
    [byte; 16]
}

fn titles(db: &Database, query: &str) -> Vec<String> {
    db.search(query)
        .iter()
        .map(|hit| hit.entry.title().to_owned())
        .collect()
}

#[test]
fn the_fixture_matches_keepassxc() {
    assert_matches_keepassxc_export(&database(), "browse.export.xml");
}

#[test]
fn the_root_lists_its_groups_with_the_recycle_bin_last() {
    let db = database();
    let root = db.listing(None).unwrap();
    assert!(root.parents.is_empty());
    let groups: Vec<_> = root.groups.iter().map(|g| g.name()).collect();
    // In the file, the recycle bin comes second.
    assert_eq!(groups, ["Work", "Banking", "Archive", "Recycle Bin"]);
    assert!(db.is_recycle_bin(&root.groups[3]));
    assert!(!db.is_recycle_bin(&root.groups[0]));
    let entries: Vec<_> = root.entries.iter().map(|e| e.title()).collect();
    assert_eq!(entries, ["Email"]);
}

#[test]
fn a_nested_group_knows_its_way_back_up() {
    let db = database();
    let servers = db.listing(Some(&uuid(5))).unwrap();
    assert_eq!(servers.group.name(), "Servers");
    let parents: Vec<_> = servers.parents.iter().map(|g| g.name()).collect();
    assert_eq!(parents, ["Root", "Work"]);
    assert!(servers.groups.is_empty());
    assert_eq!(servers.entries[0].title(), "Database Server");

    assert!(db.listing(Some(&uuid(0xee))).is_none());
    // An entry's UUID isn't a group.
    assert!(db.listing(Some(&uuid(2))).is_none());
}

#[test]
fn the_recycle_bin_is_only_found_when_enabled_and_set() {
    assert_eq!(database().recycle_bin(), Some(uuid(0x0b)));
    // KeePassXC writes an all-zero UUID until something is deleted.
    let db = open("pw-only.kdbx", Some(PASSWORD), None).unwrap();
    assert_eq!(db.recycle_bin(), None);
}

#[test]
fn search_matches_every_word_in_any_visible_field_ignoring_case() {
    let db = database();
    // Title, username, and URL. Not the recycle bin, the unsearchable "Archive", or history.
    assert_eq!(titles(&db, "email"), ["Email"]);
    assert_eq!(titles(&db, "EXAMPLE.COM"), ["Email", "Crédit Union"]);
    assert_eq!(titles(&db, "vpn"), ["VPN"]);
    // Notes, with words matched anywhere and in any order.
    assert_eq!(titles(&db, "  row rack 4 "), ["Database Server"]);
    assert_eq!(titles(&db, "rack 5"), Vec::<String>::new());
    // Tags, and non-ASCII case folding.
    assert_eq!(titles(&db, "money"), ["Crédit Union"]);
    assert_eq!(titles(&db, "CRÉDIT"), ["Crédit Union"]);
}

#[test]
fn search_never_looks_at_protected_fields_or_history() {
    let db = database();
    for query in [
        "hunter2",
        "correct horse",
        "jo-secret",
        "old-password",
        "Old Mail Title",
    ] {
        assert!(titles(&db, query).is_empty(), "{query}");
    }
    // Custom fields aren't searched either, for now.
    assert!(titles(&db, "W-1234").is_empty());
    assert!(titles(&db, "").is_empty());
    assert!(titles(&db, "   ").is_empty());
}

#[test]
fn search_hits_say_where_the_entry_lives() {
    let db = database();
    let hits = db.search("admin");
    let path: Vec<_> = hits[0].path.iter().map(|g| g.name()).collect();
    assert_eq!(path, ["Work", "Servers"]);
    assert!(db.search("personal")[0].path.is_empty());
}

#[test]
fn protected_fields_read_as_empty_through_unprotected_field() {
    let db = database();
    let banking = db.listing(Some(&uuid(7))).unwrap();
    let entry = banking.entries[0];
    assert_eq!(entry.unprotected_field("UserName"), "");
    assert_eq!(entry.username(), "jo-secret-user");
    assert_eq!(entry.unprotected_field("Password"), "");
    assert_eq!(entry.unprotected_field("URL"), "https://cu.example.com");
    assert_eq!(entry.unprotected_field("Missing"), "");
}

#[test]
fn uuids_round_trip_through_hex() {
    let id = [
        0x00, 0x01, 0xab, 0xff, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 0x7f,
    ];
    let hex = uuid_to_hex(&id);
    assert_eq!(hex, "0001abff0405060708090a0b0c0d0e7f");
    assert_eq!(uuid_from_hex(&hex), Some(id));
    assert_eq!(uuid_from_hex(&hex.to_uppercase()), Some(id));
    for bad in [
        "",
        "00",
        "+001abff0405060708090a0b0c0d0e7f",
        "zz01abff0405060708090a0b0c0d0e7f",
    ] {
        assert_eq!(uuid_from_hex(bad), None, "{bad}");
    }
    assert_eq!(uuid_from_hex(&format!("{hex}00")), None);
}
