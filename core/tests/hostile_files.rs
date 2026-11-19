//! Files made to hurt whoever opens them. The header of a KDBX file can't be checked before the
//! key derivation it describes has run, so anyone who can write the file (a shared folder, a
//! cloud account) can put anything there without knowing the master key. Opening such a file
//! must fail quickly with an error, never hang, use up memory, or crash.

mod common;

use std::sync::mpsc;
use std::time::Duration;

use common::{PASSWORD, read};
use pocketkeepass_core::{CompositeKey, Database, Error};
use sha2::{Digest, Sha256};

fn find(haystack: &[u8], needle: &[u8]) -> usize {
    haystack
        .windows(needle.len())
        .position(|w| w == needle)
        .expect("pattern not found")
}

/// Sets a UInt64 item of a KDBX 4 file's key derivation settings, and fixes the header's
/// checksum (which needs no key) so that the change isn't caught by it.
fn set_kdf_u64(file: &mut [u8], key: u8, value: u64) {
    let pattern = [0x05, 1, 0, 0, 0, key, 8, 0, 0, 0];
    let at = find(&file[..400], &pattern) + pattern.len();
    file[at..at + 8].copy_from_slice(&value.to_le_bytes());
    // The header ends with field 0, whose value is "\r\n\r\n".
    let end = find(file, &[0, 4, 0, 0, 0, 0x0D, 0x0A, 0x0D, 0x0A]) + 9;
    let hash = Sha256::digest(&file[..end]);
    file[end..end + 32].copy_from_slice(&hash);
}

/// Opens `file` with the right password. Panics if that's still running after ten seconds.
fn open(file: Vec<u8>) -> Result<(), Error> {
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        let key = CompositeKey::new(Some(PASSWORD), None).unwrap();
        let _ = tx.send(Database::open(&file, &key).map(|_| ()));
    });
    rx.recv_timeout(Duration::from_secs(10))
        .expect("opening the file didn't finish")
}

fn refused_as_too_much_work(file: Vec<u8>) {
    match open(file) {
        Err(Error::Corrupt(reason)) => assert!(reason.contains("more work"), "{reason}"),
        other => panic!("{other:?}"),
    }
}

#[test]
fn endless_aes_kdf_rounds_are_refused() {
    let mut file = read("kdbx4-aeskdf-twofish.kdbx");
    assert!(open(file.clone()).is_ok());
    set_kdf_u64(&mut file, b'R', u64::MAX);
    refused_as_too_much_work(file);

    // KDBX 3: header field 6 holds the rounds.
    let mut file = read("pw-only.kdbx");
    let at = find(&file[..222], &[6, 8, 0]) + 3;
    file[at..at + 8].copy_from_slice(&u64::MAX.to_le_bytes());
    refused_as_too_much_work(file);
}

#[test]
fn endless_argon2_work_is_refused() {
    let original = read("kdbx4-argon2id-chacha20.kdbx");
    assert!(open(original.clone()).is_ok());

    let mut passes = original.clone();
    set_kdf_u64(&mut passes, b'I', u64::from(u32::MAX));
    refused_as_too_much_work(passes);

    let mut memory = original.clone();
    set_kdf_u64(&mut memory, b'M', 64 << 30); // 64 GiB
    refused_as_too_much_work(memory);

    // Each within its own limit, but 1 GiB a thousand times over is not.
    let mut both = original;
    set_kdf_u64(&mut both, b'M', 1 << 30);
    set_kdf_u64(&mut both, b'I', 1000);
    refused_as_too_much_work(both);
}

/// The header's checksum needs no key, so damage to the header is reported before the key
/// derivation runs with whatever the damaged settings say.
#[test]
fn a_damaged_header_is_reported_before_any_key_derivation() {
    let mut file = read("kdbx4-aeskdf-twofish.kdbx");
    let pattern = [0x05, 1, 0, 0, 0, b'R', 8, 0, 0, 0];
    let at = find(&file[..400], &pattern) + pattern.len();
    file[at..at + 8].copy_from_slice(&(1u64 << 29).to_le_bytes()); // allowed, but seconds of work
    let started = std::time::Instant::now();
    match open(file) {
        Err(Error::Corrupt(reason)) => assert!(reason.contains("stored hash"), "{reason}"),
        other => panic!("{other:?}"),
    }
    assert!(started.elapsed() < Duration::from_millis(500));
}

/// A KDBX 3 file tells a right key from a wrong one by its 32 start bytes. A file that gives
/// fewer would check less, or nothing.
#[test]
fn kdbx3_start_bytes_must_be_complete() {
    let file = read("pw-only.kdbx");
    // Header field 9 (stream start bytes), length 32: declare it empty and drop the bytes.
    let at = find(&file[..222], &[9, 32, 0]);
    let mut short = file[..at].to_vec();
    short.extend_from_slice(&[9, 0, 0]);
    short.extend_from_slice(&file[at + 3 + 32..]);
    match open(short) {
        Err(Error::Corrupt(reason)) => assert!(reason.contains("start bytes"), "{reason}"),
        other => panic!("{other:?}"),
    }
}

/// Everything that walks the XML tree recurses, so nesting without limit would run out of
/// stack, which closes the app. Any file can be picked as a key file, and it's tried as XML.
#[test]
fn deeply_nested_xml_is_refused_not_fatal() {
    let depth = 200_000;
    let mut xml = String::with_capacity(depth * 7);
    for _ in 0..depth {
        xml.push_str("<a>");
    }
    for _ in 0..depth {
        xml.push_str("</a>");
    }
    assert!(pocketkeepass_core::xml::parse(&xml).is_err());
    let survived = std::thread::Builder::new()
        .stack_size(512 * 1024) // smaller than any thread the app runs the engine on
        .spawn(move || CompositeKey::new(None, Some(xml.as_bytes())).is_ok())
        .unwrap()
        .join();
    assert!(survived.is_ok());

    // What's allowed is still far deeper than any real database.
    let fine = format!("{}{}", "<a>".repeat(400), "</a>".repeat(400));
    assert!(pocketkeepass_core::xml::parse(&fine).is_ok());
}
