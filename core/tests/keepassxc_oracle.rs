//! The compatibility bar: files we save must open in KeePassXC, and KeePassXC must find in them
//! exactly what it found in the original.
//!
//! These tests drive `keepassxc-cli`, so they only run where KeePassXC is installed. They look
//! for it at `$KEEPASSXC_CLI`, then at the standard macOS location, and skip themselves (with a
//! note on stderr) if it isn't there.

mod common;

use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use common::{FIXTURES, Fixture, KDBX3, KDBX4, first_difference, first_difference_by, read};
use passthekey_core::Database;
use passthekey_core::xml::{self, Element};

fn keepassxc_cli() -> Option<PathBuf> {
    let path = std::env::var_os("KEEPASSXC_CLI")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from("/Applications/KeePassXC.app/Contents/MacOS/keepassxc-cli")
        });
    if path.exists() {
        return Some(path);
    }
    eprintln!("SKIPPED: keepassxc-cli not found (set KEEPASSXC_CLI to its path)");
    None
}

/// A temporary file, deleted again when the test is done with it.
struct TempFile(PathBuf);

impl TempFile {
    fn new(name: &str, contents: &[u8]) -> Self {
        let file = Self::unwritten(name);
        std::fs::write(&file.0, contents).unwrap();
        file
    }

    /// Just the path, for KeePassXC to write to.
    fn unwritten(name: &str) -> Self {
        Self(std::env::temp_dir().join(format!("passthekey-{}-{name}", std::process::id())))
    }
}

/// Has KeePassXC pull one attachment out of a database. (Its `--stdout` option re-encodes the
/// bytes as text, so the attachment goes through a file instead.)
fn keepassxc_attachment(
    cli: &Path,
    fixture: &Fixture,
    db: &Path,
    entry: &str,
    name: &str,
) -> Vec<u8> {
    let out = TempFile::unwritten(&format!("{}-{entry}-{name}", fixture.file));
    let args = [entry, name, out.0.to_str().unwrap()];
    keepassxc(cli, "attachment-export", fixture, db, &args);
    std::fs::read(&out.0).unwrap()
}

impl Drop for TempFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

/// Runs one `keepassxc-cli` command against a database and returns what it printed.
fn keepassxc(cli: &Path, command: &str, fixture: &Fixture, db: &Path, args: &[&str]) -> Vec<u8> {
    let mut cmd = Command::new(cli);
    cmd.arg(command).arg("--quiet");
    if let Some(key_file) = fixture.key_file {
        cmd.arg("--key-file").arg(format!("{FIXTURES}/{key_file}"));
    }
    if fixture.password.is_none() {
        cmd.arg("--no-password");
    }
    cmd.arg(db).args(args);
    let mut child = cmd
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    let mut stdin = child.stdin.take().unwrap();
    if let Some(password) = fixture.password {
        stdin.write_all(password).unwrap();
        stdin.write_all(b"\n").unwrap();
    }
    drop(stdin);
    let output = child.wait_with_output().unwrap();
    assert!(
        output.status.success(),
        "{}: KeePassXC refused the file we saved: {}",
        fixture.file,
        String::from_utf8_lossy(&output.stderr)
    );
    output.stdout
}

/// KeePassXC's XML export, parsed, with the things that differ between any two exports of the
/// same content evened out:
/// - the export stamps the time it was made into a `_LAST_MODIFIED` custom data item;
/// - custom data items come out in a different order from run to run;
/// - attachment references (`<Value Ref="…"/>`) are numbered by KeePassXC's own bookkeeping.
///   Which attachment an entry holds under which name is checked through `attachment-export`.
fn parse_export(bytes: &[u8]) -> Element {
    let mut root = xml::parse(std::str::from_utf8(bytes).unwrap()).unwrap();
    root.walk_mut(&mut |el| {
        if el.name == "Item" && el.child_text("Key") == "_LAST_MODIFIED" {
            el.child_mut("Value").unwrap().text = Default::default();
        }
        if el.name == "Value" {
            for (name, value) in &mut el.attrs {
                if name == "Ref" {
                    value.clear();
                }
            }
        }
        if el.name == "CustomData" {
            el.children
                .sort_by(|a, b| a.child_text("Key").cmp(b.child_text("Key")));
        }
        Ok(())
    })
    .unwrap();
    root
}

#[test]
fn keepassxc_reads_the_same_content_from_kdbx4_files_we_saved() {
    let Some(cli) = keepassxc_cli() else { return };
    for fixture in KDBX4 {
        let saved = TempFile::new(fixture.file, &fixture.open().save().unwrap());
        let ours = parse_export(&keepassxc(&cli, "export", fixture, &saved.0, &[]));
        let original = parse_export(&read(fixture.export));
        assert_eq!(first_difference(&original, &ours), None, "{}", fixture.file);
    }
}

#[test]
fn keepassxc_reads_attachments_from_files_we_saved() {
    let Some(cli) = keepassxc_cli() else { return };
    // The three pykeepass-written fixtures each attach the same small binary file to "Test".
    for fixture in &KDBX4[..3] {
        let saved = TempFile::new(
            &format!("attachment-{}", fixture.file),
            &fixture.open().save().unwrap(),
        );
        let attachment = keepassxc_attachment(&cli, fixture, &saved.0, "Test", "note.bin");
        assert_eq!(
            attachment, b"attachment bytes \x00\x01\x02",
            "{}",
            fixture.file
        );
    }
}

/// Turns a KDBX 4 date (base64 of the seconds since year 1) back into the ISO 8601 text KDBX 3
/// uses. Written separately from the engine's own conversion, so the two check each other.
fn iso_date(kdbx4: &str) -> Option<String> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(kdbx4)
        .ok()?;
    let seconds = i64::from_le_bytes(bytes.try_into().ok()?);
    let (days, time) = (seconds.div_euclid(86_400), seconds.rem_euclid(86_400));
    // Civil date from a day count (Howard Hinnant's algorithm), with day 0 = 0000-03-01.
    let z = days + 306;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    Some(format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        time / 3600,
        time / 60 % 60,
        time % 60
    ))
}

#[test]
fn keepassxc_reads_the_same_content_from_upgraded_kdbx3_files() {
    let Some(cli) = keepassxc_cli() else { return };
    assert_eq!(
        iso_date("EUuO4g4AAAA=").as_deref(),
        Some("2026-11-17T13:18:09Z")
    );
    for fixture in KDBX3 {
        let mut db = fixture.open();
        db.upgrade_to_kdbx4().unwrap();
        let saved = TempFile::new(fixture.file, &db.save().unwrap());
        let mut ours = parse_export(&keepassxc(&cli, "export", fixture, &saved.0, &[]));
        let mut original = parse_export(&read(fixture.export));

        // KeePassXC exports in the file's own format. So the original export has KDBX 3 dates
        // and lists the attachments in the XML, and ours has KDBX 4 dates and a KDBX 4-only
        // settings timestamp. Everything else must be identical. (The attachments themselves
        // are compared in the next test.)
        let drop = |export: &mut Element, name: &str| {
            let meta = export.child_mut("Meta").unwrap();
            let before = meta.children.len();
            meta.children.retain(|c| c.name != name);
            assert_eq!(
                meta.children.len(),
                before - 1,
                "{}: no Meta/{name}",
                fixture.file
            );
        };
        drop(&mut original, "Binaries");
        drop(&mut ours, "SettingsChanged");
        let difference = first_difference_by(&original, &ours, &|original, ours| {
            *original.text == *ours.text
                || iso_date(&ours.text).is_some_and(|date| date == *original.text)
        });
        assert_eq!(difference, None, "{}", fixture.file);
    }
}

#[test]
fn keepassxc_reads_attachments_from_an_upgraded_kdbx3_file() {
    let Some(cli) = keepassxc_cli() else { return };
    let fixture = &KDBX3[3];
    let mut db = fixture.open();
    db.upgrade_to_kdbx4().unwrap();
    let saved = TempFile::new("upgraded-attachments.kdbx", &db.save().unwrap());
    for (name, original) in [
        ("photo.bin", "attachment-photo.bin"),
        ("notes.txt", "attachment-notes.txt"),
    ] {
        let attachment = keepassxc_attachment(&cli, fixture, &saved.0, "With Files", name);
        assert!(attachment == read(original), "{name} came back different");
    }
}

#[test]
fn keepassxc_can_edit_a_file_we_saved_and_we_can_read_the_result() {
    let Some(cli) = keepassxc_cli() else { return };
    // One file saved as-is and one upgraded from KDBX 3.
    for fixture in [&KDBX4[0], &KDBX3[0]] {
        let mut db = fixture.open();
        db.upgrade_to_kdbx4().unwrap();
        let saved = TempFile::new(&format!("edit-{}", fixture.file), &db.save().unwrap());

        // KeePassXC rewrites the whole file with its own writer on every edit.
        let args = ["--username", "kxc", "Added Later"];
        keepassxc(&cli, "add", fixture, &saved.0, &args);

        let edited = Database::open(&std::fs::read(&saved.0).unwrap(), &fixture.key())
            .unwrap_or_else(|e| panic!("{}: {e}", fixture.file));
        assert_eq!(
            edited.root().entry("Added Later").unwrap().username(),
            "kxc"
        );
        // Everything that was there before is still there, unchanged.
        let mut entries = common::all_entries(edited.xml());
        entries.retain(|e| e["Title"] != "Added Later");
        assert_eq!(entries, common::all_entries(db.xml()), "{}", fixture.file);
    }
}
