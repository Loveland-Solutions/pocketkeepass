//! Group and entry icons, on `icons.kdbx` (see `fixtures/README.md`).

mod common;

use common::{PASSWORD, assert_matches_keepassxc_export, open};
use pocketkeepass_core::{DEFAULT_ENTRY_ICON, Database, Icon};

fn database() -> Database {
    open("icons.kdbx", Some(PASSWORD), None).unwrap()
}

fn entry_icon(db: &Database, uuid: u8) -> Icon {
    db.find_entry(&[uuid; 16]).unwrap().entry.icon()
}

fn group_icon(db: &Database, name: &str) -> Icon {
    db.root().group(name).unwrap().icon()
}

/// The PNG's signature, which every icon in the fixture starts with.
const PNG: &[u8] = b"\x89PNG\r\n\x1a\n";

#[test]
fn the_fixture_matches_keepassxc() {
    assert_matches_keepassxc_export(&database(), "icons.export.xml");
}

#[test]
fn standard_icons_are_read() {
    let db = database();
    assert_eq!(
        db.root().icon(),
        Icon {
            standard: 48,
            custom: None
        }
    );
    assert_eq!(
        entry_icon(&db, 0x41),
        Icon {
            standard: 37,
            custom: None
        }
    );
    assert_eq!(
        entry_icon(&db, 0x44),
        Icon {
            standard: 68,
            custom: None
        }
    );
    assert_eq!(
        group_icon(&db, "Mail"),
        Icon {
            standard: 19,
            custom: None
        }
    );
    assert_eq!(group_icon(&db, "Recycle Bin").standard, 43);
}

#[test]
fn custom_icons_are_found_by_uuid() {
    let db = database();
    let icon = entry_icon(&db, 0x42);
    assert_eq!(icon.custom, Some([0x51; 16]));
    assert!(db.custom_icon(&[0x51; 16]).unwrap().starts_with(PNG));

    let icon = group_icon(&db, "Games");
    assert_eq!(icon.custom, Some([0x53; 16]));
    assert!(db.custom_icon(&[0x53; 16]).unwrap().starts_with(PNG));
}

#[test]
fn a_custom_icon_missing_from_the_file_has_no_image() {
    let db = database();
    let icon = entry_icon(&db, 0x43);
    assert_eq!(icon.custom, Some([0x52; 16]));
    assert_eq!(icon.standard, DEFAULT_ENTRY_ICON);
    assert_eq!(db.custom_icon(&[0x52; 16]), None);
}

#[test]
fn listings_carry_icons() {
    let db = database();
    let listing = db.listing(None).unwrap();
    let standard: Vec<u32> = listing.entries.iter().map(|e| e.icon().standard).collect();
    assert_eq!(standard, [37, 0, 0, 68]);
    let groups: Vec<u32> = listing.groups.iter().map(|g| g.icon().standard).collect();
    assert_eq!(groups, [0, 19, 43]);
}
