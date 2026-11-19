//! Bringing in another copy of the same database: the file as another app or device saved it.
//!
//! Two phones sharing one file through Dropbox or Google Drive each change their own copy, so
//! when one of them saves, the other has to take in what changed ([`Database::sync_from`]). The
//! two versions are always merged, never simply swapped: the file can also be an older copy
//! (a laptop that was offline uploading over ours, or someone with access to the storage
//! putting yesterday's file back), and whatever we hold that it lacks has to survive. If ours
//! adds nothing to theirs, the result is exactly their version, and nothing needs saving.
//!
//! Merging works the way KeePass's own "Synchronize" does, by UUID and by the dates every
//! entry and group carries:
//!
//! - An entry or group on only one side is kept, unless the other side recorded deleting it
//!   (`DeletedObjects`) after it was last changed.
//! - An entry on both sides takes the newer version (`LastModificationTime`). The older one goes
//!   into its history, and the two histories are combined, so no version of a password is lost.
//! - Where an entry or group lives follows whichever side moved it last (`LocationChanged`).
//! - Deletions from both sides are combined. Something deleted on one side but changed on the
//!   other after the deletion is kept.
//! - Database settings with their own change dates (name, recycle bin) take the newer side.
//!   Custom icons and attachments from the other side are brought along.
//!
//! As everywhere else in the engine, elements are copied whole, so whatever an entry holds that
//! we don't understand comes along unchanged.

use std::collections::{HashMap, HashSet};

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;

use crate::edit::{
    Kind, ROOT_GROUP_DEPTH, after, at, at_mut, child_or_append, meta_mut, remove_at,
    set_child_text, uuid_of,
};
use crate::error::{Error, Result};
use crate::kdbx::{InnerBinary, seconds_since_year_1};
use crate::model::Database;
use crate::xml::Element;

/// What changed in the open database when another version of its file was taken in. Nothing
/// secret: only counts, for telling the user.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct SyncChanges {
    pub entries_added: u32,
    pub entries_changed: u32,
    pub entries_removed: u32,
    /// Groups added, renamed, moved, or removed.
    pub groups_changed: u32,
}

impl SyncChanges {
    pub fn is_empty(&self) -> bool {
        *self == Self::default()
    }
}

/// Database settings that carry their own change date, with that date's element.
const META_SETTINGS: &[(&[&str], &str)] = &[
    (&["DatabaseName"], "DatabaseNameChanged"),
    (&["DatabaseDescription"], "DatabaseDescriptionChanged"),
    (&["DefaultUserName"], "DefaultUserNameChanged"),
    (
        &["RecycleBinEnabled", "RecycleBinUUID"],
        "RecycleBinChanged",
    ),
    (&["EntryTemplatesGroup"], "EntryTemplatesGroupChanged"),
];

impl Database {
    /// Fails with `Invalid` unless `theirs` is another version of this database: one with the
    /// same root group.
    fn same_database(&self, theirs: &Database) -> Result<()> {
        let root_of = |db: &Database| {
            db.locate_root()
                .and_then(|p| uuid_of(at(&db.inner.xml, &p)))
        };
        let our_root = root_of(self);
        if our_root.is_none() || our_root != root_of(theirs) {
            return Err(Error::Invalid(
                "The file now holds a different database, so it can't be merged.".into(),
            ));
        }
        Ok(())
    }

    /// Replaces the database with `theirs`, another version of its file opened with the same
    /// key, and says what that changed. Only for a database that can't hold anything of its own
    /// worth keeping: one still in KDBX 3 that hasn't been changed here. Everything else goes
    /// through [`sync_from`](Self::sync_from). Fails with `Invalid` if `theirs` is a different
    /// database. On an error, nothing has changed.
    pub fn replace_with(&mut self, theirs: Database) -> Result<SyncChanges> {
        self.same_database(&theirs)?;
        let before = self.snapshot();
        *self = theirs;
        Ok(compare(&before, &self.snapshot()))
    }

    /// Takes in `theirs`, the database's file as another app or device left it. Says what that
    /// changed here, and whether the result holds anything their version doesn't: if so it
    /// exists in memory only, and has to be saved.
    ///
    /// Ours is merged into theirs, not the other way round, so that when ours adds nothing the
    /// result is their version exactly as it was read, and two phones never save back and forth
    /// over differences that mean nothing. Their header (cipher, key derivation settings) is
    /// kept for the same reason. A KDBX 3 file from their side is upgraded first, which always
    /// counts as something to save: we were upgraded already, with the user's agreement.
    ///
    /// Fails with `Invalid` if `theirs` is a different database, and with `NeedsUpgrade` if
    /// this one is still KDBX 3. On an error, nothing has changed.
    pub fn sync_from(&mut self, mut theirs: Database) -> Result<(SyncChanges, bool)> {
        if self.needs_upgrade() {
            return Err(Error::NeedsUpgrade);
        }
        self.same_database(&theirs)?;
        let upgraded = theirs.needs_upgrade();
        theirs.upgrade_to_kdbx4()?;

        let before = self.snapshot();
        let as_read = theirs.inner.xml.clone();
        let attachments = theirs.inner.binaries.len();
        theirs.merge_core(self);
        let ours_added = upgraded
            || theirs.inner.binaries.len() != attachments
            || !same_tree(&theirs.inner.xml, &as_read);
        if ours_added {
            theirs.mark_changed();
        }
        theirs.passkey_undo = self.passkey_undo.take();
        *self = theirs;
        Ok((compare(&before, &self.snapshot()), ours_added))
    }

    /// Merges `theirs`, another version of this database's file, into this one, keeping the
    /// changes of both (see the module's documentation). Fails with `Invalid` if `theirs` is a
    /// different database (its root group isn't the same group), and with `NeedsUpgrade` if
    /// this one is still KDBX 3. On an error, nothing has changed.
    pub fn merge_from(&mut self, mut theirs: Database) -> Result<SyncChanges> {
        if self.needs_upgrade() {
            return Err(Error::NeedsUpgrade);
        }
        self.same_database(&theirs)?;
        // Their dates and attachments have to be in the same form as ours.
        theirs.upgrade_to_kdbx4()?;

        let before = self.snapshot();
        self.merge_core(&theirs);
        let changes = compare(&before, &self.snapshot());
        self.mark_changed();
        Ok(changes)
    }

    /// The merge itself. Both databases are KDBX 4 and have the same root group. Where `theirs`
    /// has nothing this one lacks, this one's tree is left exactly as it was.
    fn merge_core(&mut self, theirs: &Database) {
        let their_minor = theirs.inner.version.minor;
        if their_minor > self.inner.version.minor {
            // A 4.1 file may hold 4.1-only elements; say so in the header we write.
            self.inner.version.minor = their_minor;
            self.inner.header.version.minor = their_minor;
        }

        let mut binaries = HashMap::new();
        let mut import = |el: &mut Element, ours: &mut Vec<InnerBinary>| {
            import_binaries(el, &theirs.inner.binaries, ours, &mut binaries);
        };

        let deleted = self.merge_deleted_objects(theirs);
        let max_history = self.history_max_items();
        let their_root_path = theirs.locate_root().expect("checked by the caller");
        let their_root = at(&theirs.inner.xml, &their_root_path);

        // The root group itself: its name and notes.
        let our_root_path = self.locate_root().expect("checked by the caller");
        let ours = at_mut(&mut self.inner.xml, &our_root_path);
        if time(their_root, "LastModificationTime") > time(ours, "LastModificationTime") {
            replace_group_fields(ours, their_root);
        }

        let mut resurrected = HashSet::new();
        for item in their_items(their_root) {
            let uuid = item.uuid;
            match self.locate(&uuid, item.kind) {
                Some(path) => {
                    let ours = at(&self.inner.xml, &path);
                    let parent = uuid_of(at(&self.inner.xml, &path[..path.len() - 1]));
                    let relocate = parent != Some(item.parent)
                        && time(item.el, "LocationChanged") > time(ours, "LocationChanged");
                    let ours = at_mut(&mut self.inner.xml, &path);
                    match item.kind {
                        Kind::Group => {
                            if time(item.el, "LastModificationTime")
                                > time(ours, "LastModificationTime")
                            {
                                replace_group_fields(ours, item.el);
                            }
                        }
                        Kind::Entry => {
                            let mut theirs = item.el.clone();
                            import(&mut theirs, &mut self.inner.binaries);
                            merge_entry(ours, theirs, max_history);
                        }
                    }
                    if relocate {
                        self.relocate(&uuid, item.kind, &item.parent, item.el);
                    }
                }
                None => {
                    let deleted_at = deleted.get(&uuid).copied();
                    if deleted_at.is_some_and(|at| at >= latest(item.el)) {
                        continue; // deleted here after their last change
                    }
                    if deleted_at.is_some() {
                        resurrected.insert(uuid);
                    }
                    let mut el = match item.kind {
                        Kind::Group => own_fields(item.el),
                        Kind::Entry => item.el.clone(),
                    };
                    import(&mut el, &mut self.inner.binaries);
                    let parent = self
                        .locate(&item.parent, Kind::Group)
                        .unwrap_or_else(|| our_root_path.clone());
                    insert(at_mut(&mut self.inner.xml, &parent), el, item.kind);
                }
            }
        }

        self.apply_deletions(&deleted);
        self.forget_deletions(&resurrected);
        self.merge_meta(theirs);
    }

    /// Combines their `DeletedObjects` into ours (the later time wins) and returns them all,
    /// as UUID → deletion time.
    fn merge_deleted_objects(&mut self, theirs: &Database) -> HashMap<[u8; 16], i64> {
        let objects = |db: &Database| -> Vec<Element> {
            (db.inner.xml.path(&["Root", "DeletedObjects"]).into_iter())
                .flat_map(|d| d.children_named("DeletedObject"))
                .cloned()
                .collect()
        };
        let mut ours = objects(self);
        let theirs = objects(theirs);
        if theirs.is_empty() && ours.is_empty() {
            return HashMap::new();
        }
        let when = |el: &Element| seconds(el.child_text("DeletionTime")).unwrap_or(i64::MIN);
        for object in theirs {
            let Some(uuid) = uuid_of(&object) else {
                continue;
            };
            match ours.iter_mut().find(|o| uuid_of(o) == Some(uuid)) {
                Some(mine) if when(&object) > when(mine) => *mine = object,
                Some(_) => {}
                None => ours.push(object),
            }
        }
        let all = (ours.iter())
            .filter_map(|o| Some((uuid_of(o)?, when(o))))
            .collect();
        let root = child_or_append(&mut self.inner.xml, "Root");
        child_or_append(root, "DeletedObjects").children = ours;
        all
    }

    /// Removes what was deleted (on either side) and hasn't changed since. Groups go only once
    /// they're empty, so an entry changed after its group was deleted keeps its group.
    fn apply_deletions(&mut self, deleted: &HashMap<[u8; 16], i64>) {
        for (uuid, &when) in deleted {
            if let Some(path) = self.locate(uuid, Kind::Entry)
                && latest(at(&self.inner.xml, &path)) <= when
            {
                remove_at(&mut self.inner.xml, &path);
            }
        }
        loop {
            let mut removed = false;
            for (uuid, &when) in deleted {
                let Some(path) = self.locate(uuid, Kind::Group) else {
                    continue;
                };
                let group = at(&self.inner.xml, &path);
                let empty =
                    !(group.children.iter()).any(|c| c.name == "Entry" || c.name == "Group");
                if path.len() > ROOT_GROUP_DEPTH && empty && latest(group) <= when {
                    remove_at(&mut self.inner.xml, &path);
                    removed = true;
                }
            }
            if !removed {
                break;
            }
        }
    }

    /// Drops the deletion records of things that came back (changed after being deleted).
    fn forget_deletions(&mut self, uuids: &HashSet<[u8; 16]>) {
        if uuids.is_empty() {
            return;
        }
        let root = child_or_append(&mut self.inner.xml, "Root");
        let deleted = child_or_append(root, "DeletedObjects");
        deleted
            .children
            .retain(|o| uuid_of(o).is_none_or(|u| !uuids.contains(&u)));
    }

    /// Database settings that are newer on their side, plus their custom icons and custom data
    /// that we don't have.
    fn merge_meta(&mut self, theirs: &Database) {
        let Some(their_meta) = theirs.inner.xml.child("Meta") else {
            return;
        };
        let ours = meta_mut(&mut self.inner.xml);
        for (fields, changed) in META_SETTINGS {
            let newer = seconds(their_meta.child_text(changed)) > seconds(ours.child_text(changed));
            if !newer {
                continue;
            }
            for name in fields.iter().chain([changed]) {
                if let Some(el) = their_meta.child(name) {
                    set_child_text(ours, name, &el.text, None);
                }
            }
        }
        for (list, item, id) in [
            ("CustomIcons", "Icon", "UUID"),
            ("CustomData", "Item", "Key"),
        ] {
            let Some(their_list) = their_meta.child(list) else {
                continue;
            };
            for el in their_list.children_named(item) {
                let key = el.child_text(id);
                let have = ours
                    .child(list)
                    .is_some_and(|l| l.children_named(item).any(|o| o.child_text(id) == key));
                if !have {
                    // Only now, so that a list we have nothing to add to isn't created empty.
                    child_or_append(ours, list).children.push(el.clone());
                }
            }
        }
    }

    /// Moves an entry or group to where their side put it, with their side's move date. Skipped
    /// if that group isn't here, or it would put a group inside itself.
    fn relocate(&mut self, uuid: &[u8; 16], kind: Kind, to: &[u8; 16], theirs: &Element) {
        let (Some(path), Some(target)) = (self.locate(uuid, kind), self.locate(to, Kind::Group))
        else {
            return;
        };
        if kind == Kind::Group && target.starts_with(&path) {
            return;
        }
        let mut item = remove_at(&mut self.inner.xml, &path);
        if let Some(moved) = theirs.path(&["Times", "LocationChanged"]) {
            let times = child_or_append(&mut item, "Times");
            set_child_text(times, "LocationChanged", &moved.text, None);
        }
        if let Some(previous) = theirs.child("PreviousParentGroup") {
            let at = after(&item, "Times");
            set_child_text(&mut item, "PreviousParentGroup", &previous.text, Some(at));
        }
        let target = self
            .locate(to, Kind::Group)
            .expect("the target isn't inside what was removed");
        insert(at_mut(&mut self.inner.xml, &target), item, kind);
    }

    /// Every entry and group (history aside), by UUID, with what tells their versions apart.
    fn snapshot(&self) -> HashMap<[u8; 16], Item> {
        fn walk(group: &Element, out: &mut HashMap<[u8; 16], Item>) {
            let id = uuid_of(group);
            for child in &group.children {
                let kind = match child.name.as_str() {
                    "Entry" => Kind::Entry,
                    "Group" => Kind::Group,
                    _ => continue,
                };
                if let Some(uuid) = uuid_of(child) {
                    out.insert(
                        uuid,
                        Item {
                            kind,
                            parent: id,
                            modified: time(child, "LastModificationTime"),
                        },
                    );
                }
                if kind == Kind::Group {
                    walk(child, out);
                }
            }
        }
        let mut out = HashMap::new();
        if let Some(root) = self.inner.xml.path(&["Root", "Group"]) {
            walk(root, &mut out);
        }
        out
    }
}

/// What a snapshot keeps of each entry and group.
#[derive(PartialEq)]
struct Item {
    kind: Kind,
    parent: Option<[u8; 16]>,
    modified: i64,
}

fn compare(before: &HashMap<[u8; 16], Item>, after: &HashMap<[u8; 16], Item>) -> SyncChanges {
    let mut changes = SyncChanges::default();
    for (uuid, now) in after {
        let counter = match (before.get(uuid), now.kind) {
            (None, Kind::Entry) => &mut changes.entries_added,
            (Some(then), Kind::Entry) if then != now => &mut changes.entries_changed,
            (None, Kind::Group) => &mut changes.groups_changed,
            (Some(then), Kind::Group) if then != now => &mut changes.groups_changed,
            _ => continue,
        };
        *counter += 1;
    }
    for (uuid, then) in before {
        if !after.contains_key(uuid) {
            match then.kind {
                Kind::Entry => changes.entries_removed += 1,
                Kind::Group => changes.groups_changed += 1,
            }
        }
    }
    changes
}

/// One entry or group from their side, in an order where every group comes before what's in it.
struct TheirItem<'a> {
    kind: Kind,
    uuid: [u8; 16],
    parent: [u8; 16],
    el: &'a Element,
}

fn their_items(root: &Element) -> Vec<TheirItem<'_>> {
    fn walk<'a>(group: &'a Element, out: &mut Vec<TheirItem<'a>>) {
        let Some(parent) = uuid_of(group) else {
            return;
        };
        for child in &group.children {
            let kind = match child.name.as_str() {
                "Entry" => Kind::Entry,
                "Group" => Kind::Group,
                _ => continue,
            };
            if let Some(uuid) = uuid_of(child) {
                out.push(TheirItem {
                    kind,
                    uuid,
                    parent,
                    el: child,
                });
                if kind == Kind::Group {
                    walk(child, out);
                }
            }
        }
    }
    let mut out = Vec::new();
    walk(root, &mut out);
    out
}

/// Takes their version of an entry if it's newer, and combines the two histories either way.
fn merge_entry(ours: &mut Element, mut theirs: Element, max_history: Option<usize>) {
    let our_time = time(ours, "LastModificationTime");
    let their_time = time(&theirs, "LastModificationTime");
    let history = |el: &mut Element| -> Vec<Element> {
        el.child_mut("History")
            .map(|h| std::mem::take(&mut h.children))
            .unwrap_or_default()
            .into_iter()
            .filter(|c| c.name == "Entry")
            .collect()
    };
    let mut versions = history(ours);
    let add = |versions: &mut Vec<Element>, version: Element| {
        if !versions.iter().any(|v| same_version(v, &version)) {
            versions.push(version);
        }
    };
    for version in history(&mut theirs) {
        add(&mut versions, version);
    }

    if their_time > our_time {
        let mut previous = ours.clone();
        previous.children.retain(|c| c.name != "History");
        add(&mut versions, previous);
        // Where it lives is decided separately, by the move dates: keep ours for now.
        let location = ours.path(&["Times", "LocationChanged"]).cloned();
        let previous_parent = ours.child("PreviousParentGroup").cloned();
        theirs.children.retain(|c| c.name != "PreviousParentGroup");
        if let Some(location) = location {
            let times = child_or_append(&mut theirs, "Times");
            set_child_text(times, "LocationChanged", &location.text, None);
        }
        if let Some(previous_parent) = previous_parent {
            let at = after(&theirs, "Times");
            theirs.children.insert(at, previous_parent);
        }
        *ours = theirs;
    } else if !same_version(ours, &theirs) {
        // Ours is newer, or both were changed within the same second: keep ours, and theirs
        // in the history, so neither is lost.
        add(&mut versions, theirs);
    }

    versions.sort_by_key(|v| time(v, "LastModificationTime"));
    if let Some(max) = max_history {
        let extra = versions.len().saturating_sub(max);
        versions.drain(..extra);
    }
    if versions.is_empty() && ours.child("History").is_none() {
        return;
    }
    child_or_append(ours, "History").children = versions;
}

/// Whether two versions of an entry are the same, aside from their history, access statistics,
/// and where the entry lives. A move isn't a new version of the entry: it's settled separately,
/// by the move dates.
fn same_version(a: &Element, b: &Element) -> bool {
    const IGNORED: [&str; 5] = [
        "History",
        "LastAccessTime",
        "UsageCount",
        "LocationChanged",
        "PreviousParentGroup",
    ];
    fn same(a: &Element, b: &Element) -> bool {
        fn children(el: &Element) -> Vec<&Element> {
            (el.children.iter())
                .filter(|c| !IGNORED.contains(&c.name.as_str()))
                .collect()
        }
        let (ca, cb) = (children(a), children(b));
        a.name == b.name
            && a.attrs == b.attrs
            && *a.text == *b.text
            && ca.len() == cb.len()
            && ca.iter().zip(&cb).all(|(x, y)| same(x, y))
    }
    same(a, b)
}

/// Whether two trees are the same in every element, attribute, and text.
fn same_tree(a: &Element, b: &Element) -> bool {
    a.name == b.name
        && a.attrs == b.attrs
        && *a.text == *b.text
        && a.children.len() == b.children.len()
        && a.children
            .iter()
            .zip(&b.children)
            .all(|(x, y)| same_tree(x, y))
}

/// A group's own fields (name, notes, icon, dates, settings), without what's inside it.
fn own_fields(group: &Element) -> Element {
    let mut el = group.clone();
    el.children
        .retain(|c| c.name != "Entry" && c.name != "Group");
    el
}

/// Takes their name, notes, and settings for a group, keeping what's in it and where it lives.
fn replace_group_fields(ours: &mut Element, theirs: &Element) {
    let location = ours.path(&["Times", "LocationChanged"]).cloned();
    let previous_parent = ours.child("PreviousParentGroup").cloned();
    let contents: Vec<Element> = (ours.children.drain(..))
        .filter(|c| c.name == "Entry" || c.name == "Group")
        .collect();
    ours.children = own_fields(theirs).children;
    ours.children.retain(|c| c.name != "PreviousParentGroup");
    if let Some(location) = location {
        let times = child_or_append(ours, "Times");
        set_child_text(times, "LocationChanged", &location.text, None);
    }
    if let Some(previous_parent) = previous_parent {
        let at = after(ours, "Times");
        ours.children.insert(at, previous_parent);
    }
    ours.children.extend(contents);
}

/// Puts an entry after the group's last entry (entries come before subgroups, as KeePass writes
/// them), or a group at the end.
fn insert(group: &mut Element, item: Element, kind: Kind) {
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
}

/// Points an element's attachments (`<Binary><Value Ref="n"/>`, history included) at our own
/// attachment list, adding their attachments to it. The same content is stored only once.
fn import_binaries(
    el: &mut Element,
    theirs: &[InnerBinary],
    ours: &mut Vec<InnerBinary>,
    imported: &mut HashMap<usize, usize>,
) {
    if el.name == "Binary"
        && let Some(value) = el.child_mut("Value")
        && let Some(index) = value
            .attr("Ref")
            .and_then(|r| r.trim().parse::<usize>().ok())
        && let Some(binary) = theirs.get(index)
    {
        let ours_index = *imported.entry(index).or_insert_with(|| {
            let same = |b: &InnerBinary| b.flags == binary.flags && *b.data == *binary.data;
            ours.iter().position(same).unwrap_or_else(|| {
                ours.push(InnerBinary {
                    flags: binary.flags,
                    data: binary.data.clone(),
                });
                ours.len() - 1
            })
        });
        for (name, value) in &mut value.attrs {
            if name == "Ref" {
                *value = ours_index.to_string();
            }
        }
    }
    for child in &mut el.children {
        import_binaries(child, theirs, ours, imported);
    }
}

/// A date under an element's `Times`, in seconds since year 1. Missing or unreadable dates
/// count as the earliest possible, so a real date always wins over them.
fn time(el: &Element, name: &str) -> i64 {
    el.path(&["Times", name])
        .and_then(|t| seconds(&t.text))
        .unwrap_or(i64::MIN)
}

/// When an entry or group was last changed or moved, whichever is later.
fn latest(el: &Element) -> i64 {
    time(el, "LastModificationTime").max(time(el, "LocationChanged"))
}

/// Reads a date in either form: KDBX 4's base64 of a little-endian `i64`, or KDBX 3's ISO 8601.
fn seconds(text: &str) -> Option<i64> {
    let text = text.trim();
    if text.len() >= 19 && text.as_bytes()[4] == b'-' {
        return seconds_since_year_1(text);
    }
    let bytes: [u8; 8] = BASE64.decode(text).ok()?.try_into().ok()?;
    Some(i64::from_le_bytes(bytes))
}
