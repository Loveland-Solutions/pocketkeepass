//! Changing a database: creating, editing, moving, and deleting entries and groups, on
//! `browse.kdbx` (see `fixtures/README.md`) and the other KeePassXC-written fixtures.

mod common;

use common::{KDBX3, KDBX4, PASSWORD, first_difference, open};
use pocketkeepass_core::xml::Element;
use pocketkeepass_core::{
    CompositeKey, Database, Deletion, EntryEdit, Error, FieldEdit, FieldValue, STANDARD_FIELDS,
};

/// `browse.kdbx`, upgraded from KDBX 3.1 so it can be saved.
fn database() -> Database {
    let mut db = open("browse.kdbx", Some(PASSWORD), None).unwrap();
    db.upgrade_to_kdbx4().unwrap();
    db
}

fn uuid(byte: u8) -> [u8; 16] {
    [byte; 16]
}

const ROOT: u8 = 0x01;
const EMAIL: u8 = 0x02; // in the root group, with one history item
const WORK: u8 = 0x03;
const VPN: u8 = 0x04; // in Work, with the custom field "Account ID"
const SERVERS: u8 = 0x05; // inside Work
const SERVER: u8 = 0x06; // in Servers
const BANKING: u8 = 0x07;
const BANK: u8 = 0x08; // in Banking, with a protected username
const BIN: u8 = 0x0b;
const IN_BIN: u8 = 0x0c;

fn set<'a>(key: &'a str, value: &'a str) -> FieldEdit<'a> {
    FieldEdit {
        key,
        value: FieldValue::Set(value),
        protected: false,
    }
}

fn keep(key: &str) -> FieldEdit<'_> {
    FieldEdit {
        key,
        value: FieldValue::Keep { from: key },
        protected: false,
    }
}

/// An edit with a title, a username, a password, and nothing else.
fn login<'a>(title: &'a str, username: &'a str, password: &'a str) -> EntryEdit<'a> {
    EntryEdit {
        fields: vec![
            set("Title", title),
            set("UserName", username),
            set("Password", password),
        ],
        tags: vec![],
    }
}

/// Saves and opens the result, so every test also proves the change survives a save.
fn saved(db: &Database) -> Database {
    let key = CompositeKey::new(Some(PASSWORD), None).unwrap();
    let reopened = Database::open(&db.save().unwrap(), &key).unwrap();
    assert_eq!(first_difference(db.xml(), reopened.xml()), None);
    reopened
}

fn entry_el(db: &Database, id: [u8; 16]) -> &Element {
    fn walk<'a>(el: &'a Element, id: &str) -> Option<&'a Element> {
        if el.name == "Entry" && el.child_text("UUID") == id {
            return Some(el);
        }
        if el.name == "History" {
            return None;
        }
        el.children.iter().find_map(|c| walk(c, id))
    }
    use base64::Engine;
    let id = base64::engine::general_purpose::STANDARD.encode(id);
    walk(db.xml(), &id).expect("entry")
}

fn history_titles(db: &Database, id: [u8; 16]) -> Vec<String> {
    let hit = db.find_entry(&id).unwrap();
    hit.entry.history().map(|e| e.title().to_owned()).collect()
}

fn group_names(db: &Database, group: u8) -> Vec<String> {
    let listing = db.listing(Some(&uuid(group))).unwrap();
    listing.groups.iter().map(|g| g.name().to_owned()).collect()
}

fn entry_titles(db: &Database, group: u8) -> Vec<String> {
    let listing = db.listing(Some(&uuid(group))).unwrap();
    listing
        .entries
        .iter()
        .map(|e| e.title().to_owned())
        .collect()
}

#[test]
fn a_new_entry_has_every_standard_field_and_a_protected_password() {
    let mut db = database();
    let mut edit = login("Router", "admin", "s3cret");
    edit.fields.push(FieldEdit {
        key: "PIN",
        value: FieldValue::Set("1234"),
        protected: true,
    });
    edit.tags = vec!["home; network", "home"];
    let id = db.create_entry(&uuid(WORK), &edit).unwrap();

    let db = saved(&db);
    let hit = db.find_entry(&id).unwrap();
    assert_eq!(
        hit.path.iter().map(|g| g.name()).collect::<Vec<_>>(),
        ["Work"]
    );
    let entry = hit.entry;
    assert_eq!(
        (entry.title(), entry.username(), entry.password()),
        ("Router", "admin", "s3cret")
    );
    assert!(entry.is_protected("Password") && entry.is_protected("PIN"));
    assert!(!entry.is_protected("UserName"));
    let keys: Vec<_> = entry.field_keys().collect();
    assert_eq!(
        keys,
        ["Title", "UserName", "Password", "PIN", "URL", "Notes"]
    );
    assert_eq!(entry.tags(), "home;network");
    assert_eq!(entry.history().count(), 0);
    // After the group's existing entries, before its subgroups.
    assert_eq!(entry_titles(&db, WORK), ["VPN", "Router"]);
    let el = entry_el(&db, id);
    let names: Vec<_> = el.children.iter().map(|c| c.name.as_str()).collect();
    assert_eq!(
        names[..7],
        [
            "UUID",
            "IconID",
            "ForegroundColor",
            "BackgroundColor",
            "OverrideURL",
            "Tags",
            "Times"
        ]
    );
    // The engine says it changed the file, the way every KeePass app does.
    assert_eq!(
        db.xml().path(&["Meta", "Generator"]).unwrap().text.as_str(),
        "PocketKeePass"
    );
}

#[test]
fn new_entries_get_unique_ids_and_appear_in_search() {
    let mut db = database();
    let a = db
        .create_entry(&uuid(ROOT), &login("Alpha site", "", ""))
        .unwrap();
    let b = db
        .create_entry(&uuid(ROOT), &login("Alpha app", "", ""))
        .unwrap();
    assert_ne!(a, b);
    assert_eq!(db.search("alpha").len(), 2);
    assert!(matches!(
        db.create_entry(&uuid(0xee), &login("x", "", "")),
        Err(Error::NotFound)
    ));
}

#[test]
fn editing_keeps_the_old_version_in_history() {
    let mut db = database();
    assert_eq!(history_titles(&db, uuid(EMAIL)), ["Old Mail Title"]);
    let edit = EntryEdit {
        fields: vec![
            set("Title", "Mail"),
            keep("UserName"),
            keep("Password"),
            keep("URL"),
            keep("Notes"),
        ],
        tags: vec!["personal"],
    };
    assert!(db.update_entry(&uuid(EMAIL), &edit).unwrap());

    let db = saved(&db);
    let email = db.find_entry(&uuid(EMAIL)).unwrap().entry;
    assert_eq!(email.title(), "Mail");
    // Kept without the app ever reading it.
    assert_eq!(email.password(), "hunter2-mailbox");
    assert!(email.is_protected("Password"));
    assert_eq!(email.notes(), "Personal inbox");
    assert_eq!(
        history_titles(&db, uuid(EMAIL)),
        ["Old Mail Title", "Email"]
    );
    // The history copy has no history of its own.
    let old = email.history().last().unwrap();
    assert_eq!(old.password(), "hunter2-mailbox");
    assert_eq!(old.history().count(), 0);
}

#[test]
fn an_edit_that_changes_nothing_leaves_the_entry_alone() {
    let mut db = database();
    let before = entry_el(&db, uuid(VPN)).clone();
    // Same fields in another order, and the same tags spelled differently.
    let edit = EntryEdit {
        fields: vec![
            keep("Account ID"),
            keep("Password"),
            set("Title", "VPN"),
            set("UserName", "jo.work"),
            set("URL", "vpn.example.org"),
            set("Notes", ""),
        ],
        tags: vec![],
    };
    assert!(!db.update_entry(&uuid(VPN), &edit).unwrap());
    assert_eq!(first_difference(&before, entry_el(&db, uuid(VPN))), None);
}

#[test]
fn custom_fields_can_be_renamed_protected_and_removed() {
    let mut db = database();
    let edit = EntryEdit {
        fields: vec![
            set("Title", "VPN"),
            keep("UserName"),
            keep("Password"),
            FieldEdit {
                key: "Employee ID",
                value: FieldValue::Keep { from: "Account ID" },
                protected: true,
            },
        ],
        tags: vec!["work"],
    };
    assert!(db.update_entry(&uuid(VPN), &edit).unwrap());
    let db = saved(&db);
    let vpn = db.find_entry(&uuid(VPN)).unwrap().entry;
    assert_eq!(vpn.field("Employee ID"), Some("W-1234"));
    assert!(vpn.is_protected("Employee ID"));
    assert_eq!(vpn.field("Account ID"), None);
    // Standard fields left out are emptied, not removed.
    assert_eq!(vpn.field("URL"), Some(""));
    assert_eq!(vpn.tags(), "work");

    // And removed altogether.
    let mut db = db;
    let edit = EntryEdit {
        fields: vec![set("Title", "VPN")],
        tags: vec![],
    };
    assert!(db.update_entry(&uuid(VPN), &edit).unwrap());
    let vpn = db.find_entry(&uuid(VPN)).unwrap().entry;
    assert_eq!(vpn.field("Employee ID"), None);
    assert_eq!(vpn.field_keys().count(), STANDARD_FIELDS.len());
    assert_eq!(vpn.password(), "");
}

#[test]
fn standard_fields_keep_their_own_protection() {
    let mut db = database();
    // "Crédit Union"'s username is protected; the edit can't take that away.
    let edit = EntryEdit {
        fields: vec![
            keep("Title"),
            FieldEdit {
                key: "UserName",
                value: FieldValue::Set("new-user"),
                protected: false,
            },
            keep("Password"),
        ],
        tags: vec!["bank"],
    };
    db.update_entry(&uuid(BANK), &edit).unwrap();
    let bank = db.find_entry(&uuid(BANK)).unwrap().entry;
    assert!(bank.is_protected("UserName"));
    assert_eq!(bank.username(), "new-user");
    assert_eq!(bank.tags(), "bank");
}

#[test]
fn history_is_trimmed_to_the_database_limit() {
    let mut db = database();
    let limit = db
        .xml()
        .path(&["Meta", "HistoryMaxItems"])
        .map_or(10, |el| el.text.trim().parse::<usize>().unwrap());
    for i in 0..limit + 3 {
        let title = format!("Email {i}");
        let edit = EntryEdit {
            fields: vec![set("Title", &title), keep("Password")],
            tags: vec![],
        };
        db.update_entry(&uuid(EMAIL), &edit).unwrap();
    }
    let history = history_titles(&db, uuid(EMAIL));
    assert_eq!(history.len(), limit);
    // The oldest went first.
    assert_eq!(history.last().unwrap(), &format!("Email {}", limit + 1));
}

#[test]
fn edits_that_a_keepass_file_cant_hold_are_refused() {
    let mut db = database();
    let before = entry_el(&db, uuid(EMAIL)).clone();
    let refused = |db: &mut Database, edit: EntryEdit<'_>| {
        let result = db.update_entry(&uuid(EMAIL), &edit);
        assert!(
            matches!(result, Err(Error::Invalid(_))),
            "{:?}",
            result.err()
        );
    };
    refused(&mut db, login("bell\u{7}", "", ""));
    refused(
        &mut db,
        EntryEdit {
            fields: vec![set("", "x")],
            tags: vec![],
        },
    );
    refused(
        &mut db,
        EntryEdit {
            fields: vec![set("  ", "x")],
            tags: vec![],
        },
    );
    refused(
        &mut db,
        EntryEdit {
            fields: vec![set("A", "1"), set("A", "2")],
            tags: vec![],
        },
    );
    refused(
        &mut db,
        EntryEdit {
            fields: vec![set("Name\u{0}", "x")],
            tags: vec![],
        },
    );
    refused(
        &mut db,
        EntryEdit {
            fields: vec![keep("Nope")],
            tags: vec![],
        },
    );
    refused(
        &mut db,
        EntryEdit {
            fields: vec![],
            tags: vec!["tab\u{1}"],
        },
    );
    assert_eq!(first_difference(&before, entry_el(&db, uuid(EMAIL))), None);

    // A protected value is stored encrypted, so it can hold anything.
    let edit = EntryEdit {
        fields: vec![set("Title", "Email"), set("Password", "pass\u{1}word")],
        tags: vec![],
    };
    db.update_entry(&uuid(EMAIL), &edit).unwrap();
    let db = saved(&db);
    assert_eq!(
        db.find_entry(&uuid(EMAIL)).unwrap().entry.password(),
        "pass\u{1}word"
    );
}

#[test]
fn groups_can_be_created_and_renamed() {
    let mut db = database();
    let id = db
        .create_group(&uuid(WORK), "Clients", "Customer logins")
        .unwrap();
    assert_eq!(group_names(&db, WORK), ["Servers", "Clients"]);
    let entry = db.create_entry(&id, &login("Acme", "jo", "pw")).unwrap();

    assert!(db.update_group(&id, "Customers", "").unwrap());
    assert!(!db.update_group(&id, "Customers", "").unwrap());
    let db = saved(&db);
    let listing = db.listing(Some(&id)).unwrap();
    assert_eq!(
        (listing.group.name(), listing.group.notes()),
        ("Customers", "")
    );
    assert_eq!(listing.entries[0].uuid(), Some(entry));
    assert!(matches!(
        database().update_group(&uuid(WORK), "Bad\u{8}", ""),
        Err(Error::Invalid(_))
    ));
}

#[test]
fn deleting_moves_to_the_recycle_bin_then_deletes_for_good() {
    let mut db = database();
    assert_eq!(
        db.delete_entry(&uuid(EMAIL)).unwrap(),
        Deletion::MovedToRecycleBin
    );
    let hit = db.find_entry(&uuid(EMAIL)).unwrap();
    assert!(db.is_recycle_bin(hit.path.last().unwrap()));
    assert!(db.search("inbox").is_empty());

    assert_eq!(db.delete_entry(&uuid(EMAIL)).unwrap(), Deletion::Deleted);
    assert_eq!(db.delete_entry(&uuid(IN_BIN)).unwrap(), Deletion::Deleted);
    assert!(db.find_entry(&uuid(EMAIL)).is_none());
    assert!(matches!(
        db.delete_entry(&uuid(EMAIL)),
        Err(Error::NotFound)
    ));

    // Recorded, so that apps merging this file know they were deleted.
    let db = saved(&db);
    let deleted = deleted_objects(&db);
    assert!(deleted.contains(&uuid(EMAIL)) && deleted.contains(&uuid(IN_BIN)));
}

fn deleted_objects(db: &Database) -> Vec<[u8; 16]> {
    use base64::Engine;
    let Some(deleted) = db.xml().path(&["Root", "DeletedObjects"]) else {
        return Vec::new();
    };
    deleted
        .children_named("DeletedObject")
        .map(|d| {
            base64::engine::general_purpose::STANDARD
                .decode(d.child_text("UUID"))
                .unwrap()
                .try_into()
                .unwrap()
        })
        .collect()
}

#[test]
fn deleting_a_group_takes_everything_in_it() {
    let mut db = database();
    assert_eq!(
        db.delete_group(&uuid(WORK)).unwrap(),
        Deletion::MovedToRecycleBin
    );
    assert_eq!(group_names(&db, BIN), ["Work"]);
    assert!(db.search("admin").is_empty());

    assert_eq!(db.delete_group(&uuid(WORK)).unwrap(), Deletion::Deleted);
    let deleted = deleted_objects(&db);
    for id in [WORK, VPN, SERVERS, SERVER] {
        assert!(deleted.contains(&uuid(id)), "{id}");
    }
    assert!(db.find_entry(&uuid(SERVER)).is_none());

    assert!(matches!(
        db.delete_group(&uuid(ROOT)),
        Err(Error::Invalid(_))
    ));
}

#[test]
fn deleting_the_recycle_bin_empties_it_and_a_new_one_appears_when_needed() {
    let mut db = database();
    assert_eq!(db.delete_group(&uuid(BIN)).unwrap(), Deletion::Deleted);
    assert_eq!(db.recycle_bin(), None);
    assert!(deleted_objects(&db).contains(&uuid(IN_BIN)));

    assert_eq!(
        db.delete_entry(&uuid(SERVER)).unwrap(),
        Deletion::MovedToRecycleBin
    );
    let db = saved(&db);
    let bin = db.recycle_bin().expect("a new recycle bin");
    let hit = db.find_entry(&uuid(SERVER)).unwrap();
    assert_eq!(hit.path.last().unwrap().uuid(), Some(bin));
    let listing = db.listing(Some(&bin)).unwrap();
    assert_eq!(listing.group.name(), "Recycle Bin");
    // Searching stays off inside it, as in KeePass.
    assert_eq!(listing.group.enable_searching(), Some(false));
    assert!(db.search("admin").is_empty());
}

#[test]
fn with_the_recycle_bin_off_deleting_is_for_good() {
    for fixture in KDBX4 {
        let mut db = fixture.open();
        let root = db.root().uuid().unwrap();
        let id = db.create_entry(&root, &login("Temp", "", "")).unwrap();
        let expected = if db.recycle_bin_enabled() {
            Deletion::MovedToRecycleBin
        } else {
            Deletion::Deleted
        };
        assert_eq!(db.delete_entry(&id).unwrap(), expected, "{}", fixture.file);
    }
}

#[test]
fn moving_records_where_from_in_kdbx_4_1() {
    let mut db = database();
    assert!(db.move_entry(&uuid(VPN), &uuid(BANKING)).unwrap());
    assert!(!db.move_entry(&uuid(VPN), &uuid(BANKING)).unwrap());
    assert!(db.move_group(&uuid(SERVERS), &uuid(ROOT)).unwrap());
    let db = saved(&db);
    assert_eq!(entry_titles(&db, BANKING), ["Crédit Union", "VPN"]);
    assert!(group_names(&db, ROOT).ends_with(&["Servers".to_owned(), "Recycle Bin".to_owned()]));

    // KDBX 4.0 has no place for it.
    assert!(
        entry_el(&db, uuid(VPN))
            .child("PreviousParentGroup")
            .is_none()
    );

    let fixture = &KDBX4[3];
    let mut db = fixture.open();
    assert_eq!(db.format_version().to_string(), "4.1");
    let root = db.root();
    let root_id = root.uuid().unwrap();
    let group_id = root.groups().next().unwrap().uuid().unwrap();
    let entry_id = root.entries().next().unwrap().uuid().unwrap();
    assert!(db.move_entry(&entry_id, &group_id).unwrap());
    let reopened = Database::open(&db.save().unwrap(), &fixture.key()).unwrap();
    use base64::Engine;
    assert_eq!(
        entry_el(&reopened, entry_id).child_text("PreviousParentGroup"),
        base64::engine::general_purpose::STANDARD.encode(root_id)
    );
}

#[test]
fn a_group_cant_move_into_itself() {
    let mut db = database();
    for (group, into) in [(WORK, WORK), (WORK, SERVERS), (ROOT, WORK)] {
        assert!(
            matches!(
                db.move_group(&uuid(group), &uuid(into)),
                Err(Error::Invalid(_))
            ),
            "{group} into {into}"
        );
    }
    assert!(matches!(
        db.move_entry(&uuid(VPN), &uuid(0xee)),
        Err(Error::NotFound)
    ));
}

#[test]
fn kdbx3_databases_get_kdbx3_dates_and_upgrade_cleanly() {
    let mut db = KDBX3[0].open();
    let root = db.root().uuid().unwrap();
    let id = db.create_entry(&root, &login("New", "me", "pw")).unwrap();
    let times = entry_el(&db, id).child("Times").unwrap();
    let created = times.child_text("CreationTime");
    assert!(created.ends_with('Z') && created.len() == 20, "{created}");

    db.upgrade_to_kdbx4().unwrap();
    let reopened = Database::open(&db.save().unwrap(), &KDBX3[0].key()).unwrap();
    assert_eq!(reopened.find_entry(&id).unwrap().entry.password(), "pw");
}
