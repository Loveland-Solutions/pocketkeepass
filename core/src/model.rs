//! Typed, read-only views over the database's XML tree.

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;
use zeroize::Zeroizing;

use crate::error::{Result, corrupt};
use crate::kdbx::{self, Decrypted, FormatVersion, KdfCost};
use crate::key::CompositeKey;
use crate::xml::Element;

/// An unlocked KeePass database. All decrypted content is wiped from memory when it's dropped.
pub struct Database {
    pub(crate) inner: Decrypted,
    /// The composite key hash the database was opened with, kept so it can be saved again.
    key: Zeroizing<[u8; 32]>,
    /// How to take back the passkey created last, if saving it fails (see `passkey.rs`).
    pub(crate) passkey_undo: Option<crate::passkey::Undo>,
}

impl Database {
    /// Decrypts a whole `.kdbx` file. This is slow on purpose (key derivation), so call it off
    /// the UI thread.
    pub fn open(file: &[u8], key: &CompositeKey) -> Result<Self> {
        Self::open_with_key_hash(file, &key.hash())
    }

    /// Like [`open`](Self::open), with the composite key hash from [`key_hash`](Self::key_hash)
    /// instead of the password and key file. This is what quick unlock stores: it keeps working
    /// after the database is saved (by us or any other app), and stops only when the master key
    /// changes. Key derivation still runs, so this is no faster than [`open`](Self::open).
    pub fn open_with_key_hash(file: &[u8], key_hash: &[u8; 32]) -> Result<Self> {
        let key = Zeroizing::new(*key_hash);
        let inner = kdbx::decrypt(file, &key)?;
        // Make sure the basic shape is there, so the views below can rely on it.
        inner
            .xml
            .path(&["Root", "Group"])
            .ok_or_else(|| corrupt("the database has no root group"))?;
        Ok(Self {
            inner,
            key,
            passkey_undo: None,
        })
    }

    /// A database made in memory (see `create.rs`), locked with the composite key hash `key`.
    pub(crate) fn from_parts(inner: Decrypted, key: Zeroizing<[u8; 32]>) -> Self {
        Self {
            inner,
            key,
            passkey_undo: None,
        }
    }

    /// Encrypts the database into a complete `.kdbx` file, locked with the key it was opened
    /// with. Like opening, this is slow on purpose (key derivation), so call it off the UI
    /// thread.
    ///
    /// The file is checked before it's returned: it's read back and compared with the database
    /// in memory, and any difference is an error rather than a file. The caller still has to
    /// replace the old file safely (write to a temporary file, then swap).
    ///
    /// Only KDBX 4 is written. A KDBX 3 database gives [`crate::Error::NeedsUpgrade`] until
    /// [`upgrade_to_kdbx4`](Self::upgrade_to_kdbx4) has been called.
    pub fn save(&self) -> Result<Vec<u8>> {
        kdbx::encrypt(&self.inner, &self.key)
    }

    /// The composite key hash this database was opened with. It's as good as the password and
    /// key file for opening the database, so treat it like them: never log it, and wipe copies.
    pub fn key_hash(&self) -> &[u8; 32] {
        &self.key
    }

    /// What this database's key derivation costs to run (see [`KdfCost`]).
    pub(crate) fn kdf_cost(&self) -> Result<KdfCost> {
        self.inner.header.kdf_cost()
    }

    /// Whether this is a KDBX 3 database, which has to be upgraded before it can be saved.
    pub fn needs_upgrade(&self) -> bool {
        self.inner.version.major < 4
    }

    /// Converts a KDBX 3 database to KDBX 4.0 in memory, so that the next [`save`](Self::save)
    /// writes a KDBX 4 file. Nothing in the database is lost, and the cipher and key derivation
    /// settings stay the same. Does nothing if the database is already KDBX 4.
    ///
    /// Never call this without asking the user: once saved, the file can no longer be opened by
    /// apps that only understand KDBX 3. If this fails, the database is unchanged.
    pub fn upgrade_to_kdbx4(&mut self) -> Result<()> {
        kdbx::upgrade_to_kdbx4(&mut self.inner)
    }

    pub fn format_version(&self) -> FormatVersion {
        self.inner.version
    }

    pub fn name(&self) -> &str {
        self.inner
            .xml
            .child("Meta")
            .map(|m| m.child_text("DatabaseName"))
            .unwrap_or("")
    }

    pub fn root(&self) -> Group<'_> {
        Group {
            el: self
                .inner
                .xml
                .path(&["Root", "Group"])
                .expect("checked in open()"),
        }
    }

    /// The recycle bin group's UUID, if the recycle bin is turned on and has been created.
    pub fn recycle_bin(&self) -> Option<[u8; 16]> {
        let meta = self.inner.xml.child("Meta")?;
        if meta
            .child_text("RecycleBinEnabled")
            .eq_ignore_ascii_case("false")
        {
            return None;
        }
        decode_uuid(meta.child_text("RecycleBinUUID")).filter(|uuid| *uuid != [0; 16])
    }

    /// Number of attachments stored in the KDBX 4 inner header.
    pub fn attachment_count(&self) -> usize {
        self.inner.binaries.len()
    }

    /// The full decrypted XML tree, for tests and diagnostics. Contains secrets.
    pub fn xml(&self) -> &Element {
        &self.inner.xml
    }

    /// A custom icon's image data (`Meta/CustomIcons`), as stored: usually PNG, but nothing
    /// checks that, so treat it as untrusted. `None` if no icon has that UUID, its data isn't
    /// valid base64, or it's bigger than [`MAX_CUSTOM_ICON_BYTES`].
    pub fn custom_icon(&self, uuid: &[u8; 16]) -> Option<Vec<u8>> {
        let icon = self
            .inner
            .xml
            .path(&["Meta", "CustomIcons"])?
            .children_named("Icon")
            .find(|icon| decode_uuid(icon.child_text("UUID")).as_ref() == Some(uuid))?;
        let data = icon.child_text("Data").trim();
        // Base64 is 4 characters for every 3 bytes: refuse a big icon before decoding it (and
        // check the exact size after).
        if data.len() > MAX_CUSTOM_ICON_BYTES.div_ceil(3) * 4 {
            return None;
        }
        BASE64
            .decode(data)
            .ok()
            .filter(|bytes| !bytes.is_empty() && bytes.len() <= MAX_CUSTOM_ICON_BYTES)
    }
}

/// How many standard icons KeePass has: `IconID` runs from 0 to 68. The images aren't in the
/// file; every app draws its own.
pub const STANDARD_ICON_COUNT: u32 = 69;
/// The standard icon of an entry that doesn't say (a key).
pub const DEFAULT_ENTRY_ICON: u32 = 0;
/// The standard icon of a group that doesn't say (a folder).
pub const DEFAULT_GROUP_ICON: u32 = 48;
/// The largest custom icon [`Database::custom_icon`] returns. KeePass and KeePassXC store
/// small images (KeePassXC scales them to 128 pixels); anything this big is a mistake or an
/// attack, and the standard icon is shown instead.
pub const MAX_CUSTOM_ICON_BYTES: usize = 256 * 1024;

/// A group's or entry's icon: a standard one, and maybe a custom image shown instead of it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Icon {
    /// 0 to 68. A missing or out-of-range `IconID` reads as the default.
    pub standard: u32,
    /// The UUID of an image in `Meta/CustomIcons`. The image may be missing; then the
    /// standard icon is the one to show.
    pub custom: Option<[u8; 16]>,
}

fn icon(el: &Element, default: u32) -> Icon {
    let standard = el
        .child_text("IconID")
        .trim()
        .parse()
        .ok()
        .filter(|id| *id < STANDARD_ICON_COUNT)
        .unwrap_or(default);
    let custom = decode_uuid(el.child_text("CustomIconUUID")).filter(|uuid| *uuid != [0; 16]);
    Icon { standard, custom }
}

#[derive(Clone, Copy)]
pub struct Group<'a> {
    el: &'a Element,
}

impl<'a> Group<'a> {
    pub fn uuid(&self) -> Option<[u8; 16]> {
        decode_uuid(self.el.child_text("UUID"))
    }

    pub fn name(&self) -> &'a str {
        self.el.child_text("Name")
    }

    pub fn notes(&self) -> &'a str {
        self.el.child_text("Notes")
    }

    pub fn icon(&self) -> Icon {
        icon(self.el, DEFAULT_GROUP_ICON)
    }

    /// The group's own search setting: `Some(false)` keeps its entries (and those of its
    /// subgroups, unless they say otherwise) out of search results, `None` inherits the parent's.
    pub fn enable_searching(&self) -> Option<bool> {
        let value = self.el.child_text("EnableSearching").trim();
        if value.eq_ignore_ascii_case("true") {
            Some(true)
        } else if value.eq_ignore_ascii_case("false") {
            Some(false)
        } else {
            None // "null", or missing
        }
    }

    pub fn groups(&self) -> impl Iterator<Item = Group<'a>> + 'a {
        self.el.children_named("Group").map(|el| Group { el })
    }

    pub fn entries(&self) -> impl Iterator<Item = Entry<'a>> + 'a {
        self.el.children_named("Entry").map(|el| Entry { el })
    }

    /// Finds a direct child group by name.
    pub fn group(&self, name: &str) -> Option<Group<'a>> {
        self.groups().find(|g| g.name() == name)
    }

    /// Finds a direct child entry by title.
    pub fn entry(&self, title: &str) -> Option<Entry<'a>> {
        self.entries().find(|e| e.title() == title)
    }
}

#[derive(Clone, Copy)]
pub struct Entry<'a> {
    el: &'a Element,
}

impl<'a> Entry<'a> {
    pub fn uuid(&self) -> Option<[u8; 16]> {
        decode_uuid(self.el.child_text("UUID"))
    }

    /// Any field by key, standard ("Title", "UserName", "Password", "URL", "Notes") or custom
    /// (e.g. "otp").
    pub fn field(&self, key: &str) -> Option<&'a str> {
        self.el
            .children_named("String")
            .find(|s| s.child_text("Key") == key)
            .map(|s| s.child_text("Value"))
    }

    /// All field keys in file order.
    pub fn field_keys(&self) -> impl Iterator<Item = &'a str> + 'a {
        self.el
            .children_named("String")
            .map(|s| s.child_text("Key"))
    }

    /// Whether the file marks this field as protected (passwords, usually).
    pub fn is_protected(&self, key: &str) -> bool {
        self.el
            .children_named("String")
            .find(|s| s.child_text("Key") == key)
            .and_then(|s| s.child("Value"))
            .is_some_and(Element::is_protected)
    }

    /// A field's value, or "" if it's missing or protected. Use this for anything shown in
    /// lists or searched, so protected values (passwords, and any other field the user chose to
    /// protect) never leave the engine by accident.
    pub fn unprotected_field(&self, key: &str) -> &'a str {
        self.el
            .children_named("String")
            .find(|s| s.child_text("Key") == key)
            .and_then(|s| s.child("Value"))
            .filter(|v| !v.is_protected())
            .map_or("", |v| v.text.as_str())
    }

    /// Every field in file order, standard and custom, protected or not. For the entry screen,
    /// which shows protected values only when the user asks to see them.
    pub fn fields(&self) -> impl Iterator<Item = Field<'a>> + 'a {
        self.el.children_named("String").map(|s| {
            let value = s.child("Value");
            Field {
                key: s.child_text("Key"),
                value: value.map_or("", |v| v.text.as_str()),
                protected: value.is_some_and(Element::is_protected),
            }
        })
    }

    pub fn icon(&self) -> Icon {
        icon(self.el, DEFAULT_ENTRY_ICON)
    }

    /// The entry's tags as stored, separated by `;` or `,`.
    pub fn tags(&self) -> &'a str {
        self.el.child_text("Tags")
    }

    pub fn title(&self) -> &'a str {
        self.field("Title").unwrap_or("")
    }

    pub fn username(&self) -> &'a str {
        self.field("UserName").unwrap_or("")
    }

    pub fn url(&self) -> &'a str {
        self.field("URL").unwrap_or("")
    }

    pub fn notes(&self) -> &'a str {
        self.field("Notes").unwrap_or("")
    }

    /// The password. Only call this when it's actually needed (copy, autofill, reveal).
    pub fn password(&self) -> &'a str {
        self.field("Password").unwrap_or("")
    }

    /// Previous versions of this entry, oldest first.
    pub fn history(&self) -> impl Iterator<Item = Entry<'a>> + 'a {
        self.el
            .child("History")
            .into_iter()
            .flat_map(|h| h.children_named("Entry"))
            .map(|el| Entry { el })
    }
}

/// One of an entry's fields.
#[derive(Clone, Copy)]
pub struct Field<'a> {
    pub key: &'a str,
    /// Plaintext, even for protected fields. Only show it when the user asks to.
    pub value: &'a str,
    /// Whether the file marks the field as protected (passwords, usually).
    pub protected: bool,
}

/// A UUID as 32 lowercase hex digits, the form the app uses to refer to groups and entries.
pub fn uuid_to_hex(uuid: &[u8; 16]) -> String {
    uuid.iter().map(|b| format!("{b:02x}")).collect()
}

/// The reverse of [`uuid_to_hex`]. Accepts either case.
pub fn uuid_from_hex(hex: &str) -> Option<[u8; 16]> {
    if hex.len() != 32 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let mut uuid = [0; 16];
    for (byte, pair) in uuid.iter_mut().zip(hex.as_bytes().chunks(2)) {
        *byte = u8::from_str_radix(std::str::from_utf8(pair).ok()?, 16).ok()?; // checked above
    }
    Some(uuid)
}

fn decode_uuid(text: &str) -> Option<[u8; 16]> {
    BASE64.decode(text.trim()).ok()?.try_into().ok()
}

#[cfg(test)]
mod icon_tests {
    use super::*;
    use crate::xml::Element;

    fn database() -> Database {
        let file = std::fs::read(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/fixtures/icons.kdbx"
        ))
        .unwrap();
        let key = CompositeKey::new(Some(b"test-password"), None).unwrap();
        Database::open(&file, &key).unwrap()
    }

    /// The entry "Bank" (`41`), the first in the root group.
    fn bank(db: &mut Database) -> &mut Element {
        let root = db.inner.xml.child_mut("Root").unwrap();
        let group = root.child_mut("Group").unwrap();
        group.child_mut("Entry").unwrap()
    }

    fn set(el: &mut Element, name: &str, text: &str) {
        match el.child_mut(name) {
            Some(child) => *child.text = text.to_owned(),
            None => {
                let mut child = Element::new(name);
                *child.text = text.to_owned();
                el.children.push(child);
            }
        }
    }

    fn bank_icon(db: &Database) -> Icon {
        db.root().entries().next().unwrap().icon()
    }

    #[test]
    fn unusable_icon_numbers_read_as_the_default() {
        let mut db = database();
        for text in ["69", "4294967296", "-1", "key", ""] {
            set(bank(&mut db), "IconID", text);
            assert_eq!(bank_icon(&db).standard, DEFAULT_ENTRY_ICON, "{text:?}");
        }
        set(bank(&mut db), "IconID", " 12 ");
        assert_eq!(bank_icon(&db).standard, 12);
        bank(&mut db).children.retain(|c| c.name != "IconID");
        assert_eq!(bank_icon(&db).standard, DEFAULT_ENTRY_ICON);
    }

    #[test]
    fn an_all_zero_or_malformed_custom_uuid_is_no_custom_icon() {
        let mut db = database();
        for text in ["AAAAAAAAAAAAAAAAAAAAAA==", "not base64", "AQID"] {
            set(bank(&mut db), "CustomIconUUID", text);
            assert_eq!(bank_icon(&db).custom, None, "{text:?}");
        }
    }

    fn set_icon_data(db: &mut Database, data: &str) {
        let icons = db.inner.xml.child_mut("Meta").unwrap();
        let icons = icons.child_mut("CustomIcons").unwrap();
        let icon = icons.child_mut("Icon").unwrap(); // 51
        set(icon, "Data", data);
    }

    #[test]
    fn oversized_or_broken_image_data_is_refused() {
        let mut db = database();
        let uuid = [0x51; 16];

        let just_fits = BASE64.encode(vec![7; MAX_CUSTOM_ICON_BYTES]);
        set_icon_data(&mut db, &just_fits);
        assert_eq!(db.custom_icon(&uuid).unwrap().len(), MAX_CUSTOM_ICON_BYTES);

        let too_big = BASE64.encode(vec![7; MAX_CUSTOM_ICON_BYTES + 1]);
        set_icon_data(&mut db, &too_big);
        assert!(db.custom_icon(&uuid).is_none());

        for data in ["", "!!!not base64!!!"] {
            set_icon_data(&mut db, data);
            assert_eq!(db.custom_icon(&uuid), None, "{data:?}");
        }
    }
}
