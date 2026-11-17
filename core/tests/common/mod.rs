//! Helpers shared by the integration tests.
#![allow(dead_code)] // each test file uses a different subset

use std::collections::BTreeMap;

use passthekey_core::xml::{self, Element};
use passthekey_core::{CompositeKey, Database, Error};

pub const FIXTURES: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures");
pub const PASSWORD: &[u8] = b"test-password";

/// One sample database, how to unlock it, and KeePassXC's export of it.
pub struct Fixture {
    pub file: &'static str,
    pub password: Option<&'static [u8]>,
    pub key_file: Option<&'static str>,
    pub export: &'static str,
}

const fn fixture(
    file: &'static str,
    password: Option<&'static [u8]>,
    key_file: Option<&'static str>,
    export: &'static str,
) -> Fixture {
    Fixture {
        file,
        password,
        key_file,
        export,
    }
}

pub const KDBX4: &[Fixture] = &[
    fixture(
        "kdbx4-argon2id-chacha20.kdbx",
        Some(PASSWORD),
        None,
        "kdbx4-argon2id-chacha20.export.xml",
    ),
    fixture(
        "kdbx4-argon2d-aes256-keyfile.kdbx",
        Some(PASSWORD),
        Some("kdbx4-hex.key"),
        "kdbx4-argon2d-aes256-keyfile.export.xml",
    ),
    fixture(
        "kdbx4-aeskdf-twofish.kdbx",
        Some(PASSWORD),
        None,
        "kdbx4-aeskdf-twofish.export.xml",
    ),
    fixture(
        "kdbx41-keepassxc.kdbx",
        Some(PASSWORD),
        None,
        "kdbx41-keepassxc.export.xml",
    ),
];

pub const KDBX3: &[Fixture] = &[
    fixture("pw-only.kdbx", Some(PASSWORD), None, "pw-only.export.xml"),
    fixture(
        "keyfile-only.kdbx",
        None,
        Some("keyfile.keyx"),
        "keyfile-only.export.xml",
    ),
    fixture(
        "pw-and-keyfile.kdbx",
        Some(PASSWORD),
        Some("pw-and-keyfile.keyx"),
        "pw-and-keyfile.export.xml",
    ),
    fixture(
        "kdbx3-attachment.kdbx",
        Some(PASSWORD),
        None,
        "kdbx3-attachment.export.xml",
    ),
];

impl Fixture {
    pub fn key(&self) -> CompositeKey {
        let key_file = self.key_file.map(read);
        CompositeKey::new(self.password, key_file.as_deref()).unwrap()
    }

    pub fn open(&self) -> Database {
        Database::open(&read(self.file), &self.key())
            .unwrap_or_else(|e| panic!("{}: {e}", self.file))
    }
}

pub fn read(name: &str) -> Vec<u8> {
    std::fs::read(format!("{FIXTURES}/{name}")).unwrap_or_else(|e| panic!("{name}: {e}"))
}

pub fn open(db: &str, password: Option<&[u8]>, key_file: Option<&str>) -> Result<Database, Error> {
    let kf = key_file.map(read);
    let key = CompositeKey::new(password, kf.as_deref())?;
    Database::open(&read(db), &key)
}

/// Every entry (including history entries) in document order, each as its non-empty fields.
///
/// Field order and empty fields are ignored: when KeePassXC exports a file another app wrote,
/// it sorts fields alphabetically and adds empty standard fields. The values must match exactly.
pub fn all_entries(root: &Element) -> Vec<BTreeMap<String, String>> {
    fn walk(el: &Element, out: &mut Vec<BTreeMap<String, String>>) {
        if el.name == "Entry" {
            out.push(
                el.children_named("String")
                    .map(|s| {
                        (
                            s.child_text("Key").to_owned(),
                            s.child_text("Value").to_owned(),
                        )
                    })
                    .filter(|(_, v)| !v.is_empty())
                    .collect(),
            );
        }
        for c in &el.children {
            walk(c, out);
        }
    }
    let mut out = Vec::new();
    walk(root.child("Root").expect("Root"), &mut out);
    out
}

pub fn assert_matches_keepassxc_export(db: &Database, export_name: &str) {
    let export = String::from_utf8(read(export_name)).unwrap();
    let expected = all_entries(&xml::parse(&export).unwrap());
    let actual = all_entries(db.xml());
    assert!(!expected.is_empty());
    assert_eq!(
        actual, expected,
        "decrypted fields differ from KeePassXC's export"
    );
}

/// Describes the first place two XML trees differ, or `None` if they're identical in every
/// name, attribute, text, and position. Values are left out of the message on purpose.
pub fn first_difference(a: &Element, b: &Element) -> Option<String> {
    first_difference_by(a, b, &|a, b| *a.text == *b.text)
}

/// Like [`first_difference`], with the caller deciding when two elements' texts count as equal.
pub fn first_difference_by(
    a: &Element,
    b: &Element,
    same_text: &dyn Fn(&Element, &Element) -> bool,
) -> Option<String> {
    fn walk(
        a: &Element,
        b: &Element,
        path: &str,
        same_text: &dyn Fn(&Element, &Element) -> bool,
    ) -> Option<String> {
        let path = format!("{path}/{}", a.name);
        if a.name != b.name {
            return Some(format!(
                "{path}: element is named {} on the other side",
                b.name
            ));
        }
        if a.attrs != b.attrs {
            return Some(format!("{path}: attributes differ"));
        }
        if !same_text(a, b) {
            return Some(format!("{path}: text differs"));
        }
        if a.children.len() != b.children.len() {
            return Some(format!(
                "{path}: {} children vs {}",
                a.children.len(),
                b.children.len()
            ));
        }
        a.children
            .iter()
            .zip(&b.children)
            .enumerate()
            .find_map(|(i, (x, y))| walk(x, y, &format!("{path}[{i}]"), same_text))
    }
    walk(a, b, "", same_text)
}
