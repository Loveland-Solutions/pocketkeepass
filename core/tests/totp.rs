//! TOTP codes from `totp.kdbx` (see `fixtures/README.md`): every way KeePass apps store the
//! settings, checked against the RFC test vectors and against KeePassXC itself.

mod common;

use std::io::Write;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{SystemTime, UNIX_EPOCH};

use common::{FIXTURES, PASSWORD, assert_matches_keepassxc_export, open};
use pocketkeepass_core::{Database, Error, Totp};

fn database() -> Database {
    open("totp.kdbx", Some(PASSWORD), None).unwrap()
}

fn totp(db: &Database, title: &str) -> Result<Option<Totp>, Error> {
    let entry = db
        .root()
        .entry(title)
        .unwrap_or_else(|| panic!("no entry {title}"));
    Totp::from_entry(&entry)
}

fn code(db: &Database, title: &str, time: u64) -> String {
    let totp = totp(db, title)
        .unwrap()
        .unwrap_or_else(|| panic!("{title}: no TOTP"));
    totp.code_at(time).to_string()
}

#[test]
fn the_fixture_matches_keepassxc() {
    assert_matches_keepassxc_export(&database(), "totp.export.xml");
}

/// Every entry uses the RFC 6238 secrets, so the expected codes come from the RFC's tables
/// (RFC 4226's for 6 and 7 digits, which are the same numbers cut shorter). "KeePass Hex" uses
/// SHA-512 with the 20-byte secret, which no table covers: its code was worked out with Python's
/// `hmac` module.
#[test]
fn every_storage_format_gives_the_rfc_codes() {
    let db = database();
    let cases: [(&str, u64, &str); 10] = [
        ("Defaults", 59, "287082"),
        ("SHA-256", 119, "46119246"), // a 60-second period: counter 1 at 60 to 119
        ("SHA-512", 59, "90693936"),
        ("KeeOTP", 89, "4287082"), // 45-second period, 7 digits
        ("Legacy", 59, "94287082"),
        ("KeePass Base32", 119, "46119246"),
        ("KeePass UTF-8", 59, "287082"),
        ("KeePass Hex", 59, "342147"),
        ("KeePass Base64", 59, "4287082"),
        ("Defaults", 1_111_111_109, "081804"),
    ];
    for (title, time, expected) in cases {
        assert_eq!(code(&db, title, time), expected, "{title} at {time}");
    }
    assert_eq!(code(&db, "Steam", 59), code(&db, "Legacy Steam", 59));
    assert_eq!(code(&db, "Steam", 59).len(), 5);
}

#[test]
fn settings_are_read_from_the_right_fields() {
    let db = database();
    let fields = |title| totp(&db, title).unwrap().unwrap().fields().to_vec();
    assert_eq!(fields("Defaults"), ["otp"]);
    assert_eq!(fields("Legacy"), ["TOTP Seed", "TOTP Settings"]);
    assert_eq!(
        fields("KeePass Base32"),
        [
            "TimeOtp-Secret-Base32",
            "TimeOtp-Length",
            "TimeOtp-Period",
            "TimeOtp-Algorithm"
        ]
    );
    assert_eq!(
        fields("KeePass Hex"),
        ["TimeOtp-Secret-Hex", "TimeOtp-Algorithm"]
    );
    let t = totp(&db, "SHA-256").unwrap().unwrap();
    assert_eq!((t.period(), t.length()), (60, 8));
}

/// KeePassXC makes up a code for both of these. A wrong code is worse than none, so we say
/// what's wrong instead.
#[test]
fn unusable_settings_are_explained_not_guessed() {
    let db = database();
    let message = |title| match totp(&db, title) {
        Err(Error::Invalid(m)) => m,
        other => panic!("{title}: {other:?}"),
    };
    assert!(message("Broken").contains("Base32"));
    assert!(message("Counter").contains("HOTP"));
    assert!(totp(&db, "No TOTP").unwrap().is_none());
}

/// An `otp` field can hold anything a person types into it. Text in another script used to
/// crash the engine: checking for the `otpauth://` prefix cut the text in the middle of a
/// character.
#[test]
fn text_that_isnt_a_code_setup_is_explained_too() {
    use pocketkeepass_core::{EntryEdit, FieldEdit, FieldValue};
    let mut db = database();
    let root = db.root().uuid().unwrap();
    for (title, otp) in [
        ("Cyrillic", "нууц код 123456"),
        ("Accents", "aéééééé"),
        ("Emoji", "otpauth:/🔑🔑🔑"),
    ] {
        let fields = vec![
            FieldEdit {
                key: "Title",
                value: FieldValue::Set(title),
                protected: false,
            },
            FieldEdit {
                key: "otp",
                value: FieldValue::Set(otp),
                protected: true,
            },
        ];
        let edit = EntryEdit {
            fields,
            tags: vec![],
        };
        db.create_entry(&root, &edit).unwrap();
        assert!(
            matches!(totp(&db, title), Err(Error::Invalid(_))),
            "{title}"
        );
    }
}

/// The codes KeePassXC shows right now, for every entry it can read, against ours for the same
/// moment. Skipped if KeePassXC isn't installed.
#[test]
fn keepassxc_shows_the_same_codes() {
    let cli = std::env::var_os("KEEPASSXC_CLI")
        .map(PathBuf::from)
        .unwrap_or_else(|| "/Applications/KeePassXC.app/Contents/MacOS/keepassxc-cli".into());
    if !cli.exists() {
        eprintln!("SKIPPED: keepassxc-cli not found (set KEEPASSXC_CLI to its path)");
        return;
    }
    let now = || {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs()
    };
    let db = database();
    // KeePassXC 2.8 doesn't read KeePass's UTF-8, hex, or Base64 secrets.
    for title in [
        "Defaults",
        "SHA-256",
        "SHA-512",
        "Steam",
        "KeeOTP",
        "Legacy",
        "Legacy Steam",
        "KeePass Base32",
    ] {
        let period = totp(&db, title).unwrap().unwrap().period() as u64;
        // A code can roll over while KeePassXC runs. Try again when it did.
        let matched = (0..3).any(|_| {
            let before = now();
            let mut child = Command::new(&cli)
                .args(["show", "--quiet", "--totp"])
                .arg(format!("{FIXTURES}/totp.kdbx"))
                .arg(title)
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .spawn()
                .unwrap();
            let mut stdin = child.stdin.take().unwrap();
            stdin.write_all(PASSWORD).unwrap();
            stdin.write_all(b"\n").unwrap();
            drop(stdin);
            let output = child.wait_with_output().unwrap();
            assert!(output.status.success(), "{title}: keepassxc-cli failed");
            let after = now();
            if before / period != after / period {
                return false;
            }
            let theirs = String::from_utf8(output.stdout).unwrap();
            assert_eq!(code(&db, title, before), theirs.trim(), "{title}");
            true
        });
        assert!(
            matched,
            "{title}: the code kept changing while KeePassXC ran"
        );
    }
}
