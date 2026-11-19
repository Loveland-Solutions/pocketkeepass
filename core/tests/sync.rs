//! Taking in another phone's version of the same file (merging and replacing), and duplicating
//! entries. On `browse.kdbx`, `passkey.kdbx`, and `kdbx3-attachment.kdbx` (see
//! `fixtures/README.md`).

mod common;

use common::{PASSWORD, first_difference, open};
use pocketkeepass_core::xml::Element;
use pocketkeepass_core::{
    CompositeKey, Database, EntryEdit, Error, FieldEdit, FieldValue, SyncChanges, has_passkey,
};

const ROOT: u8 = 0x01;
const EMAIL: u8 = 0x02; // in the root group, with one history item
const WORK: u8 = 0x03;
const VPN: u8 = 0x04; // in Work
const BANKING: u8 = 0x07;
const BANK: u8 = 0x08; // in Banking

fn uuid(byte: u8) -> [u8; 16] {
    [byte; 16]
}

fn key() -> CompositeKey {
    CompositeKey::new(Some(PASSWORD), None).unwrap()
}

/// `browse.kdbx` as a KDBX 4 file, the way both phones would have it before either changes it.
fn shared_file() -> Vec<u8> {
    let mut db = open("browse.kdbx", Some(PASSWORD), None).unwrap();
    db.upgrade_to_kdbx4().unwrap();
    db.save().unwrap()
}

fn phone(file: &[u8]) -> Database {
    Database::open(file, &key()).unwrap()
}

/// Saves and opens the result, so every test also proves a merged database saves cleanly.
fn saved(db: &Database) -> Database {
    let reopened = Database::open(&db.save().unwrap(), &key()).unwrap();
    assert_eq!(first_difference(db.xml(), reopened.xml()), None);
    reopened
}

fn set<'a>(key: &'a str, value: &'a str) -> FieldEdit<'a> {
    FieldEdit {
        key,
        value: FieldValue::Set(value),
        protected: false,
    }
}

fn login<'a>(title: &'a str, password: &'a str) -> EntryEdit<'a> {
    EntryEdit {
        fields: vec![set("Title", title), set("Password", password)],
        tags: vec![],
    }
}

fn titles(db: &Database, group: u8) -> Vec<String> {
    let listing = db.listing(Some(&uuid(group))).unwrap();
    listing
        .entries
        .iter()
        .map(|e| e.title().to_owned())
        .collect()
}

fn title(db: &Database, id: [u8; 16]) -> Option<String> {
    db.find_entry(&id).map(|hit| hit.entry.title().to_owned())
}

fn history_titles(db: &Database, id: [u8; 16]) -> Vec<String> {
    let hit = db.find_entry(&id).unwrap();
    hit.entry.history().map(|e| e.title().to_owned()).collect()
}

fn parent_of(db: &Database, id: [u8; 16]) -> Vec<String> {
    db.find_entry(&id)
        .unwrap()
        .path
        .iter()
        .map(|g| g.name().to_owned())
        .collect()
}

/// Changes an entry's title, keeping its other fields.
fn retitle(db: &mut Database, id: [u8; 16], new_title: &str) {
    let hit = db.find_entry(&id).unwrap();
    let keys: Vec<String> = hit.entry.field_keys().map(str::to_owned).collect();
    let fields = keys
        .iter()
        .map(|k| FieldEdit {
            key: k,
            value: if k == "Title" {
                FieldValue::Set(new_title)
            } else {
                FieldValue::Keep { from: k }
            },
            protected: hit.entry.is_protected(k),
        })
        .collect();
    let edit = EntryEdit {
        fields,
        tags: vec![],
    };
    assert!(db.update_entry(&id, &edit).unwrap());
}

#[test]
fn changes_on_both_phones_are_all_kept() {
    let file = shared_file();
    let (mut mine, mut jens) = (phone(&file), phone(&file));

    let my_new = mine
        .create_entry(&uuid(ROOT), &login("Netflix", "a"))
        .unwrap();
    retitle(&mut mine, uuid(VPN), "VPN (mine)");
    let her_new = jens
        .create_entry(&uuid(WORK), &login("Slack", "b"))
        .unwrap();
    retitle(&mut jens, uuid(BANK), "Bank (hers)");
    jens.delete_entry(&uuid(EMAIL)).unwrap(); // to the recycle bin: a move
    let her_group = jens.create_group(&uuid(BANKING), "Cards", "").unwrap();

    let changes = mine.merge_from(phone(&jens.save().unwrap())).unwrap();
    let mine = saved(&mine);

    assert_eq!(title(&mine, my_new).as_deref(), Some("Netflix"));
    assert_eq!(title(&mine, her_new).as_deref(), Some("Slack"));
    assert_eq!(title(&mine, uuid(VPN)).as_deref(), Some("VPN (mine)"));
    assert_eq!(title(&mine, uuid(BANK)).as_deref(), Some("Bank (hers)"));
    assert!(titles(&mine, WORK).contains(&"Slack".to_owned()));
    assert_eq!(parent_of(&mine, uuid(EMAIL)).last().unwrap(), "Recycle Bin");
    assert!(mine.listing(Some(&her_group)).is_some());
    assert_eq!(
        changes,
        SyncChanges {
            entries_added: 1,
            entries_changed: 2, // her edit, and the email entry she moved
            entries_removed: 0,
            groups_changed: 1, // her new group (the file already has a recycle bin)
        }
    );

    // And the other way round, Jen's phone gets the same entries.
    let mut jens = jens;
    jens.merge_from(phone(&mine.save().unwrap())).unwrap();
    for id in [my_new, her_new, uuid(VPN), uuid(BANK)] {
        assert_eq!(title(&jens, id), title(&mine, id));
    }
}

#[test]
fn the_newer_edit_wins_and_the_older_goes_into_history() {
    let file = shared_file();
    let (mut mine, mut jens) = (phone(&file), phone(&file));
    let before = history_titles(&mine, uuid(EMAIL));

    retitle(&mut jens, uuid(EMAIL), "Email (hers, older)");
    std::thread::sleep(std::time::Duration::from_millis(1100)); // dates have whole seconds
    retitle(&mut mine, uuid(EMAIL), "Email (mine, newer)");

    let theirs = jens.save().unwrap();
    mine.merge_from(phone(&theirs)).unwrap();
    let mine = saved(&mine);
    assert_eq!(
        title(&mine, uuid(EMAIL)).as_deref(),
        Some("Email (mine, newer)")
    );
    let mut expected = before.clone();
    expected.extend(["Email".to_owned(), "Email (hers, older)".to_owned()]);
    assert_eq!(history_titles(&mine, uuid(EMAIL)), expected);

    // Jen's phone takes my newer version, with the same history.
    let mut jens = phone(&theirs);
    jens.merge_from(phone(&mine.save().unwrap())).unwrap();
    assert_eq!(
        title(&jens, uuid(EMAIL)).as_deref(),
        Some("Email (mine, newer)")
    );
    assert_eq!(history_titles(&jens, uuid(EMAIL)), expected);

    // Merging again changes nothing.
    let again = jens.merge_from(phone(&mine.save().unwrap())).unwrap();
    assert!(again.is_empty(), "{again:?}");
    assert_eq!(history_titles(&jens, uuid(EMAIL)), expected);
}

#[test]
fn edits_within_the_same_second_keep_both_versions() {
    let file = shared_file();
    let (mut mine, mut jens) = (phone(&file), phone(&file));
    retitle(&mut mine, uuid(VPN), "Mine");
    retitle(&mut jens, uuid(VPN), "Hers");
    mine.merge_from(phone(&jens.save().unwrap())).unwrap();
    let current = title(&mine, uuid(VPN)).unwrap();
    let history = history_titles(&mine, uuid(VPN));
    assert!(current == "Mine" || current == "Hers");
    assert!(history.contains(&"Mine".to_owned()) || history.contains(&"Hers".to_owned()));
    assert!(history.contains(&"VPN".to_owned()));
}

#[test]
fn deletions_carry_over_unless_changed_afterwards() {
    let file = shared_file();
    let (mut mine, mut jens) = (phone(&file), phone(&file));

    // Deleted for good on her phone (a second delete empties it from the recycle bin).
    jens.delete_entry(&uuid(VPN)).unwrap();
    jens.delete_entry(&uuid(VPN)).unwrap();
    jens.delete_entry(&uuid(BANK)).unwrap();
    jens.delete_entry(&uuid(BANK)).unwrap();
    std::thread::sleep(std::time::Duration::from_millis(1100));
    // ... while I changed the bank entry after she deleted it.
    retitle(&mut mine, uuid(BANK), "Bank, changed later");
    let my_new = mine
        .create_entry(&uuid(ROOT), &login("Mine only", "x"))
        .unwrap();

    let changes = mine.merge_from(phone(&jens.save().unwrap())).unwrap();
    let mine = saved(&mine);
    assert_eq!(title(&mine, uuid(VPN)), None);
    assert_eq!(
        title(&mine, uuid(BANK)).as_deref(),
        Some("Bank, changed later")
    );
    assert_eq!(title(&mine, my_new).as_deref(), Some("Mine only"));
    assert_eq!(changes.entries_removed, 1);

    // Her phone, merging mine back: the VPN entry stays deleted, the bank entry comes back.
    let mut jens = jens;
    jens.merge_from(phone(&mine.save().unwrap())).unwrap();
    assert_eq!(title(&jens, uuid(VPN)), None);
    assert_eq!(
        title(&jens, uuid(BANK)).as_deref(),
        Some("Bank, changed later")
    );
    assert_eq!(title(&jens, my_new).as_deref(), Some("Mine only"));
}

#[test]
fn a_move_follows_whoever_moved_last() {
    let file = shared_file();
    let (mut mine, jens) = (phone(&file), phone(&file));
    let mut jens = jens;
    jens.move_entry(&uuid(EMAIL), &uuid(BANKING)).unwrap();
    retitle(&mut mine, uuid(EMAIL), "Email, edited");
    mine.merge_from(phone(&jens.save().unwrap())).unwrap();
    // My edit (newer content) and her move (the only move) both apply.
    assert_eq!(title(&mine, uuid(EMAIL)).as_deref(), Some("Email, edited"));
    assert_eq!(parent_of(&mine, uuid(EMAIL)).last().unwrap(), "Banking");
    saved(&mine);
}

#[test]
fn replacing_reports_what_the_other_phone_changed() {
    let file = shared_file();
    let (mut mine, mut jens) = (phone(&file), phone(&file));
    jens.create_entry(&uuid(ROOT), &login("New", "x")).unwrap();
    retitle(&mut jens, uuid(VPN), "VPN 2");
    jens.delete_entry(&uuid(BANK)).unwrap();
    jens.delete_entry(&uuid(BANK)).unwrap();
    let theirs = jens.save().unwrap();
    let changes = mine.replace_with(phone(&theirs));
    assert_eq!(changes.entries_added, 1);
    assert_eq!(changes.entries_changed, 1);
    assert_eq!(changes.entries_removed, 1);
    assert_eq!(first_difference(mine.xml(), phone(&theirs).xml()), None);
}

#[test]
fn a_different_database_is_not_merged() {
    let mut mine = phone(&shared_file());
    let other = open("kdbx41-keepassxc.kdbx", Some(PASSWORD), None).unwrap();
    assert!(matches!(mine.merge_from(other), Err(Error::Invalid(_))));
}

#[test]
fn attachments_come_along() {
    let mut original = open("kdbx3-attachment.kdbx", Some(PASSWORD), None).unwrap();
    original.upgrade_to_kdbx4().unwrap();
    let file = original.save().unwrap();
    let (mut mine, mut theirs) = (phone(&file), phone(&file));
    let with_attachment = find(theirs.xml(), &|e| e.child("Binary").is_some()).unwrap();
    let copy = theirs.duplicate_entry(&with_attachment).unwrap();
    let count = mine.attachment_count();
    mine.merge_from(phone(&theirs.save().unwrap())).unwrap();
    let mine = saved(&mine);
    assert_eq!(
        mine.attachment_count(),
        count,
        "the same content is stored once"
    );
    let binaries = |db: &Database, id| {
        let el = entry_el(db.xml(), id).unwrap();
        el.children_named("Binary").count()
    };
    assert_eq!(binaries(&mine, copy), binaries(&mine, with_attachment));
}

#[test]
fn duplicating_copies_everything_but_the_history() {
    let file = shared_file();
    let mut db = phone(&file);
    let copy = db.duplicate_entry(&uuid(EMAIL)).unwrap();
    let db = saved(&db);
    assert_ne!(copy, uuid(EMAIL));
    assert_eq!(title(&db, copy).as_deref(), Some("Email - Copy"));
    let (original, duplicate) = (
        db.find_entry(&uuid(EMAIL)).unwrap().entry,
        db.find_entry(&copy).unwrap().entry,
    );
    assert_eq!(original.password(), duplicate.password());
    assert_eq!(original.username(), duplicate.username());
    assert_eq!(duplicate.history().count(), 0);
    // Right after the original, in the same group.
    let root = titles(&db, ROOT);
    let at = root.iter().position(|t| t == "Email").unwrap();
    assert_eq!(root[at + 1], "Email - Copy");
}

#[test]
fn duplicating_leaves_the_passkey_behind() {
    let mut db = open("passkey.kdbx", Some(PASSWORD), None).unwrap();
    let with_passkey = find(db.xml(), &|e| {
        e.children_named("String")
            .any(|s| s.child_text("Key").starts_with("KPEX_PASSKEY"))
    })
    .unwrap();
    let copy = db.duplicate_entry(&with_passkey).unwrap();
    let hit = db.find_entry(&copy).unwrap();
    assert!(!has_passkey(&hit.entry));
    assert!(!hit.entry.tags().contains("Passkey"));
    assert!(has_passkey(&db.find_entry(&with_passkey).unwrap().entry));
}

/// The first entry (outside history) that `wanted` accepts.
fn find(el: &Element, wanted: &dyn Fn(&Element) -> bool) -> Option<[u8; 16]> {
    if el.name == "History" {
        return None;
    }
    if el.name == "Entry" && wanted(el) {
        use base64::Engine;
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(el.child_text("UUID"))
            .ok()?;
        return bytes.try_into().ok();
    }
    el.children.iter().find_map(|c| find(c, wanted))
}

fn entry_el(el: &Element, id: [u8; 16]) -> Option<&Element> {
    use base64::Engine;
    let id = base64::engine::general_purpose::STANDARD.encode(id);
    fn walk<'a>(el: &'a Element, id: &str) -> Option<&'a Element> {
        if el.name == "History" {
            return None;
        }
        if el.name == "Entry" && el.child_text("UUID") == id {
            return Some(el);
        }
        el.children.iter().find_map(|c| walk(c, id))
    }
    walk(el, &id)
}
