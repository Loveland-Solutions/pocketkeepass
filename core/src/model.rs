//! Typed, read-only views over the database's XML tree.

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;
use zeroize::Zeroizing;

use crate::error::{Result, corrupt};
use crate::kdbx::{self, Decrypted, FormatVersion};
use crate::key::CompositeKey;
use crate::xml::Element;

/// An unlocked KeePass database. All decrypted content is wiped from memory when it's dropped.
pub struct Database {
    inner: Decrypted,
    /// The composite key hash the database was opened with, kept so it can be saved again.
    key: Zeroizing<[u8; 32]>,
}

impl Database {
    /// Decrypts a whole `.kdbx` file. This is slow on purpose (key derivation), so call it off
    /// the UI thread.
    pub fn open(file: &[u8], key: &CompositeKey) -> Result<Self> {
        let key = key.hash();
        let inner = kdbx::decrypt(file, &key)?;
        // Make sure the basic shape is there, so the views below can rely on it.
        inner
            .xml
            .path(&["Root", "Group"])
            .ok_or_else(|| corrupt("the database has no root group"))?;
        Ok(Self { inner, key })
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

    /// Number of attachments stored in the KDBX 4 inner header.
    pub fn attachment_count(&self) -> usize {
        self.inner.binaries.len()
    }

    /// The full decrypted XML tree, for tests and diagnostics. Contains secrets.
    pub fn xml(&self) -> &Element {
        &self.inner.xml
    }
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
            .and_then(|v| v.attr("Protected"))
            .is_some_and(|p| p.eq_ignore_ascii_case("true"))
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

fn decode_uuid(text: &str) -> Option<[u8; 16]> {
    BASE64.decode(text.trim()).ok()?.try_into().ok()
}
