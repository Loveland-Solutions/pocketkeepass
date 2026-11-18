//! Changing an open database: creating, editing, moving, and deleting entries and groups.
//!
//! Every change is made in place on the XML tree, so whatever an entry or group holds that we
//! don't understand (icons, attachments, auto-type settings, other apps' custom data) is left
//! exactly as it was. Changes behave the way KeePass's own do:
//!
//! - Editing an entry first copies it into its history (oldest first, trimmed to the database's
//!   `HistoryMaxItems`), then updates its modification time. An edit that changes nothing
//!   leaves the entry alone.
//! - Deleting moves an entry or group to the recycle bin (creating the bin if needed) when the
//!   bin is turned on. Deleting from inside the bin, or with the bin turned off, deletes for
//!   good and records the deletion in `DeletedObjects`, so that other apps merging this file
//!   know it was deleted rather than never there.
//! - Moving records when it happened (`LocationChanged`) and, in KDBX 4.1, where from
//!   (`PreviousParentGroup`).
//!
//! Text that a KeePass file can't hold (most control characters) is refused with
//! [`Error::Invalid`] rather than silently dropped when saving.

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;
use zeroize::Zeroizing;

use crate::error::{Error, Result};
use crate::model::Database;
use crate::util::random_bytes;
use crate::xml::{Element, is_xml_char};

/// The fields every KeePass entry has. They're always written, even when empty.
pub const STANDARD_FIELDS: [&str; 5] = ["Title", "UserName", "Password", "URL", "Notes"];

/// What [`Database::save`] writes into `Meta/Generator` once we've changed a database.
const GENERATOR: &str = "PocketKeePass";

/// KeePass's own default for `HistoryMaxItems`, used when the database doesn't say.
const DEFAULT_HISTORY_MAX_ITEMS: i64 = 10;

/// The standard icons KeePass gives new items: a key for entries, a folder for groups, and a
/// recycle bin for the recycle bin.
const ENTRY_ICON: &str = "0";
const GROUP_ICON: &str = "48";
const RECYCLE_BIN_ICON: &str = "43";

/// Seconds from 0001-01-01 (where KeePass counts from) to 1970-01-01 (where Unix does).
const UNIX_EPOCH_IN_KEEPASS_SECONDS: i64 = 62_135_596_800;

/// Where a field's new value comes from.
pub enum FieldValue<'a> {
    /// The value the entry already has in the field with this name (its name before this edit,
    /// so a renamed field keeps its value). This lets the app leave a protected value alone
    /// without ever fetching it.
    Keep { from: &'a str },
    /// A new value.
    Set(&'a str),
}

/// One field of an entry, as it should be after an edit.
pub struct FieldEdit<'a> {
    pub key: &'a str,
    pub value: FieldValue<'a>,
    /// Whether the file should store the value encrypted. Only used for custom fields: the
    /// standard ones keep the protection they have, or get the database's default.
    pub protected: bool,
}

/// An entry's fields and tags, as they should be after an edit.
pub struct EntryEdit<'a> {
    /// Every field the entry should have, in order. Custom fields left out are deleted;
    /// standard fields left out are kept but emptied.
    pub fields: Vec<FieldEdit<'a>>,
    /// Tags. Any `;` or `,` inside one splits it, as KeePass reads them.
    pub tags: Vec<&'a str>,
}

/// What deleting did.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Deletion {
    /// Moved to the recycle bin, where it can be restored from.
    MovedToRecycleBin,
    /// Gone for good.
    Deleted,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Kind {
    Entry,
    Group,
}

/// A field ready to be written.
#[derive(PartialEq)]
struct NewField {
    key: String,
    value: Zeroizing<String>,
    protected: bool,
}

impl Database {
    /// Adds an entry to a group and returns its new UUID.
    pub fn create_entry(&mut self, group: &[u8; 16], edit: &EntryEdit<'_>) -> Result<[u8; 16]> {
        let group_path = self.locate(group, Kind::Group).ok_or(Error::NotFound)?;
        let fields = self.resolve_fields(None, &edit.fields)?;
        let tags = normalize_tags(&edit.tags);
        check_text("A tag", &tags.join(";"))?;
        let uuid = self.new_uuid()?;
        let now = self.now();

        let mut entry = Element::new("Entry");
        let children = &mut entry.children;
        children.push(leaf("UUID", &BASE64.encode(uuid)));
        children.push(leaf("IconID", ENTRY_ICON));
        children.push(leaf("ForegroundColor", ""));
        children.push(leaf("BackgroundColor", ""));
        children.push(leaf("OverrideURL", ""));
        children.push(leaf("Tags", &tags.join(";")));
        children.push(new_times(&now));
        children.extend(fields.into_iter().map(string_element));
        let mut auto_type = Element::new("AutoType");
        auto_type.children.push(leaf("Enabled", "True"));
        auto_type
            .children
            .push(leaf("DataTransferObfuscation", "0"));
        children.push(auto_type);
        children.push(Element::new("History"));

        let group = at_mut(&mut self.inner.xml, &group_path);
        // Entries come before subgroups in a group, as KeePass writes them.
        let at = match group.children.iter().rposition(|c| c.name == "Entry") {
            Some(last) => last + 1,
            None => (group.children.iter())
                .position(|c| c.name == "Group")
                .unwrap_or(group.children.len()),
        };
        group.children.insert(at, entry);
        self.mark_changed();
        Ok(uuid)
    }

    /// Changes an entry's fields and tags. The entry's previous state goes into its history
    /// first. Returns false, and changes nothing, if the edit leaves everything as it was.
    pub fn update_entry(&mut self, uuid: &[u8; 16], edit: &EntryEdit<'_>) -> Result<bool> {
        let path = self.locate(uuid, Kind::Entry).ok_or(Error::NotFound)?;
        let entry = at(&self.inner.xml, &path);
        let fields = self.resolve_fields(Some(entry), &edit.fields)?;
        let tags = normalize_tags(&edit.tags);
        check_text("A tag", &tags.join(";"))?;

        let mut current = self.current_fields(entry);
        let mut wanted: Vec<&NewField> = fields.iter().collect();
        current.sort_by(|a, b| a.key.cmp(&b.key));
        wanted.sort_by(|a, b| a.key.cmp(&b.key));
        let fields_changed =
            current.len() != wanted.len() || current.iter().zip(&wanted).any(|(a, b)| a != *b);
        let tags_changed = normalize_tags(&[entry.child_text("Tags")]) != tags;
        if !fields_changed && !tags_changed {
            return Ok(false);
        }

        let max_history = self.history_max_items();
        let now = self.now();
        let entry = at_mut(&mut self.inner.xml, &path);
        let mut previous = entry.clone();
        previous.children.retain(|c| c.name != "History");

        if fields_changed {
            let first = entry.children.iter().position(|c| c.name == "String");
            let at = first.unwrap_or_else(|| after(entry, "Times"));
            entry.children.retain(|c| c.name != "String");
            let strings: Vec<Element> = fields.into_iter().map(string_element).collect();
            entry.children.splice(at..at, strings);
        }
        if tags_changed {
            match entry.child_mut("Tags") {
                Some(el) => el.text = Zeroizing::new(tags.join(";")),
                None => {
                    let at = (entry.children.iter())
                        .position(|c| c.name == "Times")
                        .unwrap_or(entry.children.len());
                    entry.children.insert(at, leaf("Tags", &tags.join(";")));
                }
            }
        }
        set_times(entry, &now, &["LastModificationTime", "LastAccessTime"]);

        let history = child_or_append(entry, "History");
        history.children.push(previous);
        if let Some(max) = max_history {
            let items = history
                .children
                .iter()
                .filter(|c| c.name == "Entry")
                .count();
            for _ in max..items {
                let oldest = history.children.iter().position(|c| c.name == "Entry");
                history.children.remove(oldest.expect("counted above"));
            }
        }
        self.mark_changed();
        Ok(true)
    }

    /// Adds a group inside `parent` and returns its new UUID.
    pub fn create_group(&mut self, parent: &[u8; 16], name: &str, notes: &str) -> Result<[u8; 16]> {
        let parent_path = self.locate(parent, Kind::Group).ok_or(Error::NotFound)?;
        check_text("The name", name)?;
        check_text("The notes", notes)?;
        let uuid = self.new_uuid()?;
        let now = self.now();
        let group = new_group(&uuid, name, notes, GROUP_ICON, &now, true);
        at_mut(&mut self.inner.xml, &parent_path)
            .children
            .push(group);
        self.mark_changed();
        Ok(uuid)
    }

    /// Renames a group and changes its notes. Returns false, and changes nothing, if both are
    /// the same as before.
    pub fn update_group(&mut self, uuid: &[u8; 16], name: &str, notes: &str) -> Result<bool> {
        let path = self.locate(uuid, Kind::Group).ok_or(Error::NotFound)?;
        check_text("The name", name)?;
        check_text("The notes", notes)?;
        let now = self.now();
        let group = at_mut(&mut self.inner.xml, &path);
        if group.child_text("Name") == name && group.child_text("Notes") == notes {
            return Ok(false);
        }
        let name_at = after(group, "UUID");
        set_child_text(group, "Name", name, Some(name_at));
        let notes_at = after(group, "Name");
        set_child_text(group, "Notes", notes, Some(notes_at));
        set_times(group, &now, &["LastModificationTime", "LastAccessTime"]);
        self.mark_changed();
        Ok(true)
    }

    /// Moves an entry to the recycle bin, or deletes it for good if it's already there or the
    /// recycle bin is turned off.
    pub fn delete_entry(&mut self, uuid: &[u8; 16]) -> Result<Deletion> {
        let path = self.locate(uuid, Kind::Entry).ok_or(Error::NotFound)?;
        if self.recycle_bin_enabled() && !self.is_in_recycle_bin(&path) {
            let bin = self.ensure_recycle_bin()?;
            self.move_item(uuid, Kind::Entry, &bin)?;
            return Ok(Deletion::MovedToRecycleBin);
        }
        let now = self.now();
        remove_at(&mut self.inner.xml, &path);
        self.record_deletions(&[*uuid], &now);
        self.mark_changed();
        Ok(Deletion::Deleted)
    }

    /// Moves a group, with everything in it, to the recycle bin, or deletes it for good if
    /// it's already there, the recycle bin is turned off, or it is (or holds) the recycle bin.
    /// The root group can't be deleted.
    pub fn delete_group(&mut self, uuid: &[u8; 16]) -> Result<Deletion> {
        let path = self.locate(uuid, Kind::Group).ok_or(Error::NotFound)?;
        if path.len() == ROOT_GROUP_DEPTH {
            return Err(Error::Invalid("The top group can't be deleted.".into()));
        }
        let group = at(&self.inner.xml, &path);
        let mut inside = Vec::new();
        collect_uuids(group, &mut inside);
        let holds_bin = self.recycle_bin().is_some_and(|bin| inside.contains(&bin));
        if self.recycle_bin_enabled() && !holds_bin && !self.is_in_recycle_bin(&path) {
            let bin = self.ensure_recycle_bin()?;
            self.move_item(uuid, Kind::Group, &bin)?;
            return Ok(Deletion::MovedToRecycleBin);
        }
        let now = self.now();
        remove_at(&mut self.inner.xml, &path);
        self.record_deletions(&inside, &now);
        if holds_bin {
            // The next deletion creates a new recycle bin.
            let meta = meta_mut(&mut self.inner.xml);
            set_child_text(meta, "RecycleBinUUID", &BASE64.encode([0u8; 16]), None);
            set_child_text(meta, "RecycleBinChanged", &now, None);
        }
        self.mark_changed();
        Ok(Deletion::Deleted)
    }

    /// Moves an entry into another group. Returns false if it's already there.
    pub fn move_entry(&mut self, uuid: &[u8; 16], to_group: &[u8; 16]) -> Result<bool> {
        self.move_item(uuid, Kind::Entry, to_group)
    }

    /// Moves a group, with everything in it, into another group. Returns false if it's already
    /// there. The root group can't be moved, and no group can be moved into itself or one of
    /// its own subgroups.
    pub fn move_group(&mut self, uuid: &[u8; 16], to_group: &[u8; 16]) -> Result<bool> {
        self.move_item(uuid, Kind::Group, to_group)
    }

    fn move_item(&mut self, uuid: &[u8; 16], kind: Kind, to_group: &[u8; 16]) -> Result<bool> {
        let path = self.locate(uuid, kind).ok_or(Error::NotFound)?;
        let target = self.locate(to_group, Kind::Group).ok_or(Error::NotFound)?;
        if kind == Kind::Group {
            if path.len() == ROOT_GROUP_DEPTH {
                return Err(Error::Invalid("The top group can't be moved.".into()));
            }
            if target.starts_with(&path) {
                return Err(Error::Invalid(
                    "A group can't be moved into itself or one of its own subgroups.".into(),
                ));
            }
        }
        let parent = &path[..path.len() - 1];
        if parent == target.as_slice() {
            return Ok(false);
        }
        let previous_parent = at(&self.inner.xml, parent).child_text("UUID").to_owned();
        let now = self.now();
        let keeps_previous_parent = self.inner.version.major > 4
            || (self.inner.version.major == 4 && self.inner.version.minor >= 1);

        let mut item = remove_at(&mut self.inner.xml, &path);
        set_times(&mut item, &now, &["LocationChanged"]);
        if keeps_previous_parent {
            let at = after(&item, "Times");
            set_child_text(&mut item, "PreviousParentGroup", &previous_parent, Some(at));
        }
        // Removing the item may have shifted the target's position, so look it up again.
        let target = self
            .locate(to_group, Kind::Group)
            .expect("the target isn't inside what was removed");
        let group = at_mut(&mut self.inner.xml, &target);
        let at = match kind {
            Kind::Group => group.children.len(),
            Kind::Entry => match group.children.iter().rposition(|c| c.name == "Entry") {
                Some(last) => last + 1,
                None => (group.children.iter())
                    .position(|c| c.name == "Group")
                    .unwrap_or(group.children.len()),
            },
        };
        group.children.insert(at, item);
        self.mark_changed();
        Ok(true)
    }

    /// The recycle bin's UUID, creating the bin first (in the root group) if there isn't one.
    fn ensure_recycle_bin(&mut self) -> Result<[u8; 16]> {
        if let Some(bin) = self.recycle_bin()
            && self.locate(&bin, Kind::Group).is_some()
        {
            return Ok(bin);
        }
        let uuid = self.new_uuid()?;
        let now = self.now();
        let mut bin = new_group(&uuid, "Recycle Bin", "", RECYCLE_BIN_ICON, &now, false);
        // Nothing in the bin is offered by search or autofill, as in KeePass.
        for (name, value) in [("EnableAutoType", "false"), ("EnableSearching", "false")] {
            if let Some(el) = bin.child_mut(name) {
                el.text = Zeroizing::new(value.to_owned());
            }
        }
        let root_path = self
            .locate_root()
            .expect("checked when the database was opened");
        at_mut(&mut self.inner.xml, &root_path).children.push(bin);
        let meta = meta_mut(&mut self.inner.xml);
        set_child_text(meta, "RecycleBinUUID", &BASE64.encode(uuid), None);
        set_child_text(meta, "RecycleBinChanged", &now, None);
        Ok(uuid)
    }

    /// Whether the recycle bin is turned on. KeePass treats a missing setting as on.
    pub fn recycle_bin_enabled(&self) -> bool {
        let meta = self.inner.xml.child("Meta");
        !meta.is_some_and(|m| {
            m.child_text("RecycleBinEnabled")
                .trim()
                .eq_ignore_ascii_case("false")
        })
    }

    /// Whether the item at `path` is inside the recycle bin (at any depth).
    fn is_in_recycle_bin(&self, path: &[usize]) -> bool {
        let Some(bin) = self.recycle_bin() else {
            return false;
        };
        (ROOT_GROUP_DEPTH..path.len())
            .any(|len| uuid_of(at(&self.inner.xml, &path[..len])) == Some(bin))
    }

    /// Adds a `DeletedObject` for each UUID to `Root/DeletedObjects`.
    fn record_deletions(&mut self, uuids: &[[u8; 16]], now: &str) {
        let root = child_or_append(&mut self.inner.xml, "Root");
        let deleted = child_or_append(root, "DeletedObjects");
        for uuid in uuids {
            let mut object = Element::new("DeletedObject");
            object.children.push(leaf("UUID", &BASE64.encode(uuid)));
            object.children.push(leaf("DeletionTime", now));
            deleted.children.push(object);
        }
    }

    /// Records that the database was changed by us, the way every KeePass app does.
    fn mark_changed(&mut self) {
        let meta = meta_mut(&mut self.inner.xml);
        set_child_text(meta, "Generator", GENERATOR, Some(0));
    }

    /// The fields an edit asks for, checked and with kept values filled in. Standard fields the
    /// edit leaves out are added, empty.
    fn resolve_fields(
        &self,
        entry: Option<&Element>,
        edits: &[FieldEdit<'_>],
    ) -> Result<Vec<NewField>> {
        let existing = |key: &str| {
            entry
                .into_iter()
                .flat_map(|e| e.children_named("String"))
                .find(|s| s.child_text("Key") == key)
                .and_then(|s| s.child("Value"))
        };
        let protection = |key: &str| {
            existing(key).map_or_else(|| self.protects_by_default(key), Element::is_protected)
        };
        let mut fields: Vec<NewField> = Vec::with_capacity(edits.len() + STANDARD_FIELDS.len());
        for edit in edits {
            let key = edit.key;
            if key.trim().is_empty() {
                return Err(Error::Invalid("Every field needs a name.".into()));
            }
            check_text("A field name", key)?;
            if fields.iter().any(|f| f.key == key) {
                return Err(Error::Invalid(format!("Two fields are named “{key}”.")));
            }
            let value = match edit.value {
                FieldValue::Set(value) => value,
                FieldValue::Keep { from } => match existing(from) {
                    Some(value) => value.text.as_str(),
                    None if STANDARD_FIELDS.contains(&from) => "",
                    None => {
                        return Err(Error::Invalid(format!(
                            "The field “{from}” isn't in this entry anymore."
                        )));
                    }
                },
            };
            let protected = if STANDARD_FIELDS.contains(&key) {
                protection(key)
            } else {
                edit.protected
            };
            if !protected {
                check_text(&format!("“{key}”"), value)?;
            }
            fields.push(NewField {
                key: key.to_owned(),
                value: Zeroizing::new(value.to_owned()),
                protected,
            });
        }
        for key in STANDARD_FIELDS {
            if !fields.iter().any(|f| f.key == key) {
                fields.push(NewField {
                    key: key.to_owned(),
                    value: Zeroizing::default(),
                    protected: protection(key),
                });
            }
        }
        Ok(fields)
    }

    /// An entry's fields as they are, with missing standard fields counted as empty, the same
    /// way [`resolve_fields`](Self::resolve_fields) fills them in.
    fn current_fields(&self, entry: &Element) -> Vec<NewField> {
        let mut fields: Vec<NewField> = entry
            .children_named("String")
            .map(|s| NewField {
                key: s.child_text("Key").to_owned(),
                value: Zeroizing::new(s.child_text("Value").to_owned()),
                protected: s.child("Value").is_some_and(Element::is_protected),
            })
            .collect();
        for key in STANDARD_FIELDS {
            if !fields.iter().any(|f| f.key == key) {
                fields.push(NewField {
                    key: key.to_owned(),
                    value: Zeroizing::default(),
                    protected: self.protects_by_default(key),
                });
            }
        }
        fields
    }

    /// Whether the database protects this standard field in new entries
    /// (`Meta/MemoryProtection`). KeePass's defaults apply if it doesn't say: only the password.
    fn protects_by_default(&self, key: &str) -> bool {
        let setting = self
            .inner
            .xml
            .path(&["Meta", "MemoryProtection"])
            .and_then(|m| m.child(&format!("Protect{key}")));
        match setting {
            Some(el) => el.text.trim().eq_ignore_ascii_case("true"),
            None => key == "Password",
        }
    }

    /// `Meta/HistoryMaxItems`: how many old versions to keep per entry. None means no limit.
    fn history_max_items(&self) -> Option<usize> {
        let text = self
            .inner
            .xml
            .path(&["Meta", "HistoryMaxItems"])
            .map(|el| el.text.trim().to_owned());
        let max = text
            .and_then(|t| t.parse::<i64>().ok())
            .unwrap_or(DEFAULT_HISTORY_MAX_ITEMS);
        usize::try_from(max).ok() // negative means unlimited
    }

    /// The current time, written the way this database's format version writes dates.
    fn now(&self) -> String {
        let unix = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_secs() as i64);
        format_date(
            unix + UNIX_EPOCH_IN_KEEPASS_SECONDS,
            self.inner.version.major,
        )
    }

    /// A random UUID that nothing in the database uses yet.
    fn new_uuid(&self) -> Result<[u8; 16]> {
        loop {
            let bytes = random_bytes(16)?;
            let uuid: [u8; 16] = bytes.as_slice().try_into().expect("asked for 16 bytes");
            let taken = uuid == [0; 16]
                || self.locate(&uuid, Kind::Entry).is_some()
                || self.locate(&uuid, Kind::Group).is_some();
            if !taken {
                return Ok(uuid);
            }
        }
    }

    fn locate_root(&self) -> Option<Vec<usize>> {
        let file = &self.inner.xml;
        let root = file.children.iter().position(|c| c.name == "Root")?;
        let group = (file.children[root].children.iter()).position(|c| c.name == "Group")?;
        Some(vec![root, group])
    }

    /// The child positions leading from the top of the XML tree to a group or entry. Entry
    /// history is never searched, so an old version can't be found (or changed) by mistake.
    fn locate(&self, uuid: &[u8; 16], kind: Kind) -> Option<Vec<usize>> {
        fn search(group: &Element, uuid: &[u8; 16], kind: Kind, path: &mut Vec<usize>) -> bool {
            for (i, child) in group.children.iter().enumerate() {
                let found = match child.name.as_str() {
                    "Entry" => kind == Kind::Entry && uuid_of(child).as_ref() == Some(uuid),
                    "Group" if kind == Kind::Group && uuid_of(child).as_ref() == Some(uuid) => true,
                    "Group" => {
                        path.push(i);
                        if search(child, uuid, kind, path) {
                            return true;
                        }
                        path.pop();
                        false
                    }
                    _ => false,
                };
                if found {
                    path.push(i);
                    return true;
                }
            }
            false
        }
        let mut path = self.locate_root()?;
        let root = at(&self.inner.xml, &path);
        if kind == Kind::Group && uuid_of(root).as_ref() == Some(uuid) {
            return Some(path);
        }
        search(root, uuid, kind, &mut path).then_some(path)
    }
}

/// The root group's path is `[Root, Group]`.
const ROOT_GROUP_DEPTH: usize = 2;

fn at<'a>(mut el: &'a Element, path: &[usize]) -> &'a Element {
    for &i in path {
        el = &el.children[i];
    }
    el
}

fn at_mut<'a>(mut el: &'a mut Element, path: &[usize]) -> &'a mut Element {
    for &i in path {
        el = &mut el.children[i];
    }
    el
}

fn remove_at(file: &mut Element, path: &[usize]) -> Element {
    let (&last, parent) = path.split_last().expect("never the whole file");
    at_mut(file, parent).children.remove(last)
}

fn uuid_of(el: &Element) -> Option<[u8; 16]> {
    BASE64
        .decode(el.child_text("UUID").trim())
        .ok()?
        .try_into()
        .ok()
}

/// The UUIDs of a group and of every group and entry inside it (history excluded).
fn collect_uuids(group: &Element, out: &mut Vec<[u8; 16]>) {
    out.extend(uuid_of(group));
    for child in &group.children {
        match child.name.as_str() {
            "Entry" => out.extend(uuid_of(child)),
            "Group" => collect_uuids(child, out),
            _ => {}
        }
    }
}

/// Splits tags at `;` and `,`, trims them, and drops empty and repeated ones.
fn normalize_tags(tags: &[&str]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for tag in tags.iter().flat_map(|t| t.split([';', ','])).map(str::trim) {
        if !tag.is_empty() && !out.iter().any(|t| t == tag) {
            out.push(tag.to_owned());
        }
    }
    out
}

/// Refuses text with characters a KeePass file can't hold. `what` starts the sentence.
fn check_text(what: &str, text: &str) -> Result<()> {
    if text.chars().all(is_xml_char) {
        Ok(())
    } else {
        Err(Error::Invalid(format!(
            "{what} contains a control character, which KeePass files can't hold."
        )))
    }
}

fn leaf(name: &str, text: &str) -> Element {
    let mut el = Element::new(name);
    el.text = Zeroizing::new(text.to_owned());
    el
}

fn string_element(field: NewField) -> Element {
    let mut value = Element::new("Value");
    value.text = field.value;
    if field.protected {
        value.attrs.push(("Protected".into(), "True".into()));
    }
    let mut string = Element::new("String");
    string.children.push(leaf("Key", &field.key));
    string.children.push(value);
    string
}

fn new_times(now: &str) -> Element {
    let mut times = Element::new("Times");
    for name in [
        "CreationTime",
        "LastModificationTime",
        "LastAccessTime",
        "ExpiryTime",
    ] {
        times.children.push(leaf(name, now));
    }
    times.children.push(leaf("Expires", "False"));
    times.children.push(leaf("UsageCount", "0"));
    times.children.push(leaf("LocationChanged", now));
    times
}

/// A group the way KeePass creates one.
fn new_group(
    uuid: &[u8; 16],
    name: &str,
    notes: &str,
    icon: &str,
    now: &str,
    expanded: bool,
) -> Element {
    let mut group = Element::new("Group");
    let children = &mut group.children;
    children.push(leaf("UUID", &BASE64.encode(uuid)));
    children.push(leaf("Name", name));
    children.push(leaf("Notes", notes));
    children.push(leaf("IconID", icon));
    children.push(new_times(now));
    children.push(leaf("IsExpanded", if expanded { "True" } else { "False" }));
    children.push(leaf("DefaultAutoTypeSequence", ""));
    children.push(leaf("EnableAutoType", "null"));
    children.push(leaf("EnableSearching", "null"));
    children.push(leaf("LastTopVisibleEntry", &BASE64.encode([0u8; 16])));
    group
}

/// Sets the named dates under `Times`, creating what's missing. A missing `Times` goes where
/// KeePass writes it: after the tags (entries) or the icon (groups).
fn set_times(el: &mut Element, now: &str, names: &[&str]) {
    if el.child("Times").is_none() {
        let at = ["Tags", "IconID", "UUID"]
            .iter()
            .find(|name| el.child(name).is_some())
            .map_or(el.children.len(), |name| after(el, name));
        el.children.insert(at, Element::new("Times"));
    }
    let times = el.child_mut("Times").expect("just made sure");
    for name in names {
        set_child_text(times, name, now, None);
    }
}

/// Sets a child's text. A missing child is inserted at `at`, or at the end if that's None.
fn set_child_text(el: &mut Element, name: &str, text: &str, at: Option<usize>) {
    if let Some(child) = el.child_mut(name) {
        child.text = Zeroizing::new(text.to_owned());
        return;
    }
    let at = at.unwrap_or(el.children.len()).min(el.children.len());
    el.children.insert(at, leaf(name, text));
}

fn child_or_append<'a>(el: &'a mut Element, name: &str) -> &'a mut Element {
    match el.children.iter().position(|c| c.name == name) {
        Some(i) => &mut el.children[i],
        None => {
            el.children.push(Element::new(name));
            el.children.last_mut().expect("just pushed")
        }
    }
}

fn meta_mut(file: &mut Element) -> &mut Element {
    if file.child("Meta").is_none() {
        file.children.insert(0, Element::new("Meta"));
    }
    file.child_mut("Meta").expect("just made sure")
}

/// The position just after the named child, or the end if there's none.
fn after(el: &Element, name: &str) -> usize {
    el.children
        .iter()
        .position(|c| c.name == name)
        .map_or(el.children.len(), |i| i + 1)
}

/// Writes a date (seconds since 0001-01-01, UTC) the way the format version stores dates:
/// KDBX 4 as base64 of a little-endian `i64`, KDBX 3 as ISO 8601 text.
fn format_date(seconds: i64, major_version: u16) -> String {
    if major_version >= 4 {
        return BASE64.encode(seconds.to_le_bytes());
    }
    let unix = seconds - UNIX_EPOCH_IN_KEEPASS_SECONDS;
    let (days, time) = (unix.div_euclid(86_400), unix.rem_euclid(86_400));
    // Howard Hinnant's days-to-civil-date algorithm (proleptic Gregorian calendar).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let day_of_era = z - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1_460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let shifted_month = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * shifted_month + 2) / 5 + 1;
    let month = if shifted_month < 10 {
        shifted_month + 3
    } else {
        shifted_month - 9
    };
    let year = year_of_era + era * 400 + i64::from(month <= 2);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        time / 3_600,
        time % 3_600 / 60,
        time % 60
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn seconds(kdbx4: &str) -> i64 {
        i64::from_le_bytes(BASE64.decode(kdbx4).unwrap().try_into().unwrap())
    }

    #[test]
    fn dates_are_written_in_each_format() {
        // The same pairs the KDBX 3 → 4 upgrade is tested with (checked with Python's datetime).
        for (iso, kdbx4) in [
            ("2026-11-17T13:18:09Z", "EUuO4g4AAAA="),
            ("1970-01-01T00:00:00Z", "APeRdw4AAAA="),
            ("0001-01-01T00:00:00Z", "AAAAAAAAAAA="),
            ("2000-02-29T23:59:59Z", "f1ROsA4AAAA="),
            ("9999-12-31T23:59:59Z", "fziGd0kAAAA="),
            ("2020-01-12T03:51:55Z", "246s1Q4AAAA="),
        ] {
            assert_eq!(format_date(seconds(kdbx4), 4), kdbx4);
            assert_eq!(format_date(seconds(kdbx4), 3), iso);
        }
    }

    #[test]
    fn tags_are_split_trimmed_and_deduplicated() {
        assert_eq!(
            normalize_tags(&[" bank ", "money;bank", "", "a, b,,c", "a"]),
            ["bank", "money", "a", "b", "c"]
        );
    }
}
