//! Converting a KDBX 3 database to KDBX 4 in memory, so that it can be saved.
//!
//! The two formats hold the same content, arranged differently in three places:
//!
//! | | KDBX 3 | KDBX 4 |
//! |---|---|---|
//! | Dates | ISO 8601 text | base64 of the seconds since year 1, as a little-endian `i64` |
//! | Attachments | base64 in the XML, under `Meta/Binaries`, referenced by ID | raw in the inner header, referenced by position |
//! | Key derivation and value protection settings | separate outer header fields | a KDF dictionary in the outer header; the rest in the inner header |
//!
//! Everything else in the XML tree is left exactly as it is.

use std::collections::HashMap;

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;
use zeroize::Zeroizing;

use super::crypto::KDF_AES;
use super::header::{self, FormatVersion, OuterHeader, VariantDictionary};
use super::{Decrypted, InnerBinary, gunzip};
use crate::error::{Error, Result, corrupt};
use crate::xml::Element;

/// Every element that holds a date. All of them are format-defined names, never user data.
const DATE_ELEMENTS: &[&str] = &[
    // Meta
    "DatabaseNameChanged",
    "DatabaseDescriptionChanged",
    "DefaultUserNameChanged",
    "MasterKeyChanged",
    "RecycleBinChanged",
    "EntryTemplatesGroupChanged",
    "SettingsChanged",
    // Times, under every group and entry
    "CreationTime",
    "LastModificationTime",
    "LastAccessTime",
    "ExpiryTime",
    "LocationChanged",
    // DeletedObjects
    "DeletionTime",
];

/// Does nothing to a database that's already KDBX 4. On an error the database is unchanged.
pub(crate) fn upgrade_to_kdbx4(db: &mut Decrypted) -> Result<()> {
    match db.header.version.major {
        4 => return Ok(()),
        3 => {}
        _ => return Err(Error::UnsupportedVersion(db.header.version.to_string())),
    }

    // First everything that can fail, without touching the database.
    let header = upgraded_header(&db.header)?;
    let (binaries, positions) = pooled_binaries(&db.xml)?;
    check(&db.xml, &positions)?;

    // Then the changes themselves, none of which can fail any more.
    let _ = db.xml.walk_mut(&mut |el| {
        if is_date(el)
            && let Ok(converted) = kdbx4_date(&el.text)
        {
            el.text = Zeroizing::new(converted);
        }
        if let Some(position) = binary_ref(el).and_then(|id| positions.get(id)) {
            let position = position.to_string();
            for (name, value) in &mut el.attrs {
                if name == "Ref" {
                    value.clone_from(&position);
                }
            }
        }
        Ok(())
    });
    if let Some(meta) = db.xml.child_mut("Meta") {
        meta.children.retain(|c| c.name != "Binaries");
    }
    db.binaries = binaries;
    db.version = header.version;
    db.header = header;
    Ok(())
}

/// The KDBX 4.0 header for the same cipher, compression, and key derivation. KDBX 3 always
/// uses AES-KDF, and the upgrade keeps it, with the same number of rounds: changing how hard
/// the key is to derive is a separate decision from changing the file format.
fn upgraded_header(old: &OuterHeader) -> Result<OuterHeader> {
    let mut kdf = VariantDictionary::new();
    kdf.set("$UUID", VariantDictionary::BYTES, KDF_AES.to_vec());
    kdf.set(
        "R",
        VariantDictionary::U64,
        old.transform_rounds()?.to_le_bytes().to_vec(),
    );
    kdf.set(
        "S",
        VariantDictionary::BYTES,
        old.transform_seed()?.to_vec(),
    );

    // These moved into the KDF dictionary and the inner header. Saving fills in fresh values.
    const KDBX3_ONLY: [u8; 5] = [
        header::TRANSFORM_SEED,
        header::TRANSFORM_ROUNDS,
        header::PROTECTED_STREAM_KEY,
        header::STREAM_START_BYTES,
        header::INNER_STREAM_ID,
    ];
    let mut new = OuterHeader {
        version: FormatVersion { major: 4, minor: 0 },
        fields: old
            .fields
            .iter()
            .filter(|(id, _)| !KDBX3_ONLY.contains(id))
            .cloned()
            .collect(),
        len: 0, // not from a file
    };
    new.set(header::KDF_PARAMETERS, kdf.to_bytes());
    Ok(new)
}

/// Decodes the attachments under `Meta/Binaries`, in file order. Also returns where each
/// KDBX 3 attachment ID ends up in that list, which is how KDBX 4 refers to it.
fn pooled_binaries(xml: &Element) -> Result<(Vec<InnerBinary>, HashMap<String, usize>)> {
    let mut binaries = Vec::new();
    let mut positions = HashMap::new();
    let pool = xml.path(&["Meta", "Binaries"]);
    for el in pool.into_iter().flat_map(|p| p.children_named("Binary")) {
        let id = el
            .attr("ID")
            .ok_or_else(|| corrupt("an attachment has no ID"))?;
        let (flags, data) = if el.is_protected() {
            // Already decrypted in place when the file was opened. Bit 0 keeps it protected.
            (1, Zeroizing::new(el.text.as_bytes().to_vec()))
        } else {
            let text: String = el.text.split_ascii_whitespace().collect();
            let data = Zeroizing::new(
                BASE64
                    .decode(text)
                    .map_err(|_| corrupt("an attachment isn't valid base64"))?,
            );
            let compressed = el
                .attr("Compressed")
                .is_some_and(|v| v.eq_ignore_ascii_case("true"));
            let damaged = |_| corrupt("a compressed attachment is damaged");
            (
                0,
                if compressed {
                    gunzip(&data).map_err(damaged)?
                } else {
                    data
                },
            )
        };
        positions.insert(id.trim().to_owned(), binaries.len());
        binaries.push(InnerBinary { flags, data });
    }
    Ok((binaries, positions))
}

/// Makes sure every date can be converted and every attachment reference leads somewhere. A
/// reference that leads nowhere can't just be kept: as a KDBX 4 position it could end up
/// pointing at a different attachment.
fn check(el: &Element, positions: &HashMap<String, usize>) -> Result<()> {
    if is_date(el) {
        kdbx4_date(&el.text)?;
    }
    if binary_ref(el).is_some_and(|id| !positions.contains_key(id)) {
        return Err(corrupt("an entry refers to an attachment that isn't there"));
    }
    el.children.iter().try_for_each(|c| check(c, positions))
}

fn is_date(el: &Element) -> bool {
    el.children.is_empty() && !el.text.trim().is_empty() && DATE_ELEMENTS.contains(&&*el.name)
}

/// The attachment ID an entry's `<Binary><Value Ref="…"/></Binary>` refers to.
fn binary_ref(el: &Element) -> Option<&str> {
    if el.name != "Value" {
        return None;
    }
    el.attr("Ref").map(str::trim)
}

/// Converts a KDBX 3 date (`2026-11-17T13:18:09Z`) to its KDBX 4 form.
fn kdbx4_date(iso: &str) -> Result<String> {
    let seconds =
        seconds_since_year_1(iso.trim()).ok_or_else(|| corrupt("a date can't be read"))?;
    Ok(BASE64.encode(seconds.to_le_bytes()))
}

/// Parses `YYYY-MM-DDTHH:MM:SS`, optionally followed by fractional seconds (dropped, as KDBX 4
/// stores whole seconds) and by `Z` or a `±HH:MM` offset. No zone means UTC.
fn seconds_since_year_1(s: &str) -> Option<i64> {
    let b = s.as_bytes();
    if b.len() < 19 || !s.is_ascii() {
        return None;
    }
    let number = |digits: &str| -> Option<i64> {
        digits
            .bytes()
            .all(|c| c.is_ascii_digit())
            .then(|| digits.parse().ok())?
    };
    let separators = [(4, b'-'), (7, b'-'), (13, b':'), (16, b':')];
    if separators.iter().any(|&(i, c)| b[i] != c) || !matches!(b[10], b'T' | b't' | b' ') {
        return None;
    }
    let (year, month, day) = (number(&s[0..4])?, number(&s[5..7])?, number(&s[8..10])?);
    let (hour, minute, second) = (
        number(&s[11..13])?,
        number(&s[14..16])?,
        number(&s[17..19])?,
    );
    if !(1..=12).contains(&month) || hour > 23 || minute > 59 || second > 59 {
        return None;
    }
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    let days_in_month = match month {
        2 if leap => 29,
        2 => 28,
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    };
    if !(1..=days_in_month).contains(&day) {
        return None;
    }

    let mut rest = &s[19..];
    if let Some(fraction) = rest.strip_prefix('.') {
        rest = fraction.trim_start_matches(|c: char| c.is_ascii_digit());
    }
    let offset = match rest.as_bytes() {
        [] | [b'Z' | b'z'] => 0,
        [sign @ (b'+' | b'-'), _, _, b':', _, _] => {
            let seconds = (number(&rest[1..3])? * 60 + number(&rest[4..6])?) * 60;
            if *sign == b'+' { seconds } else { -seconds }
        }
        _ => return None,
    };

    // Days since 0001-01-01 in the proleptic Gregorian calendar (what .NET's DateTime counts).
    let years = year - 1;
    let before_this_year = years * 365 + years / 4 - years / 100 + years / 400;
    const BEFORE_MONTH: [i64; 12] = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334];
    let leap_day = i64::from(leap && month > 2);
    let days = before_this_year + BEFORE_MONTH[month as usize - 1] + leap_day + day - 1;
    Some(days * 86_400 + hour * 3_600 + minute * 60 + second - offset)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::xml;

    #[test]
    fn dates_convert_to_the_values_other_implementations_produce() {
        // Expected values computed independently with Python's datetime.
        for (iso, expected) in [
            ("2026-11-17T13:18:09Z", "EUuO4g4AAAA="),
            ("1970-01-01T00:00:00Z", "APeRdw4AAAA="),
            ("0001-01-01T00:00:00Z", "AAAAAAAAAAA="),
            ("2000-02-29T23:59:59Z", "f1ROsA4AAAA="),
            ("9999-12-31T23:59:59Z", "fziGd0kAAAA="),
            // Other spellings of the first one.
            ("2026-11-17T13:18:09", "EUuO4g4AAAA="),
            (" 2026-11-17T13:18:09.123Z\n", "EUuO4g4AAAA="),
            ("2026-11-17T15:18:09+02:00", "EUuO4g4AAAA="),
            ("2026-11-17T07:48:09.5-05:30", "EUuO4g4AAAA="),
        ] {
            assert_eq!(kdbx4_date(iso).unwrap(), expected, "{iso}");
        }
        // 246s1Q4AAAA= is a date KeePassXC wrote into the KDBX 4.1 fixture.
        assert_eq!(kdbx4_date("2020-01-12T03:51:55Z").unwrap(), "246s1Q4AAAA=");
    }

    #[test]
    fn things_that_are_not_dates_are_rejected() {
        for bad in [
            "",
            "yesterday",
            "2026-11-17",
            "2026-13-01T00:00:00Z",
            "2026-02-29T00:00:00Z",
            "2026-11-17T24:00:00Z",
            "2026-11-17T13:18:09X",
            "2026-11-17T13:18:09+2:00",
            "2026/11/17T13:18:09Z",
            "EUuO4g4AAAA=",
        ] {
            assert!(kdbx4_date(bad).is_err(), "{bad:?}");
        }
    }

    fn kdbx3(xml_text: &str) -> Decrypted {
        let fields = vec![
            (header::CIPHER_ID, vec![1; 16]),
            (header::COMPRESSION, 1u32.to_le_bytes().to_vec()),
            (header::MASTER_SEED, vec![2; 32]),
            (header::TRANSFORM_SEED, vec![3; 32]),
            (header::TRANSFORM_ROUNDS, 6000u64.to_le_bytes().to_vec()),
            (header::ENCRYPTION_IV, vec![4; 16]),
            (header::PROTECTED_STREAM_KEY, vec![5; 32]),
            (header::STREAM_START_BYTES, vec![6; 32]),
            (header::INNER_STREAM_ID, 2u32.to_le_bytes().to_vec()),
            (header::END, b"\r\n\r\n".to_vec()),
        ];
        let version = FormatVersion { major: 3, minor: 1 };
        Decrypted {
            version,
            xml: xml::parse(xml_text).unwrap(),
            binaries: Vec::new(),
            inner_extra: Vec::new(),
            header: OuterHeader {
                version,
                fields,
                len: 0,
            },
        }
    }

    /// "hello" gzipped, then base64-encoded.
    const HELLO_GZ: &str = "H4sIAAAAAAAAA8tIzcnJBwCGphA2BQAAAA==";

    fn sample() -> String {
        format!(
            r#"<KeePassFile>
                <Meta>
                    <DatabaseName>Sample</DatabaseName>
                    <DatabaseNameChanged>2026-11-17T13:18:09Z</DatabaseNameChanged>
                    <Binaries>
                        <Binary ID="7" Compressed="True">{HELLO_GZ}</Binary>
                        <Binary ID="2" Compressed="False">cGxhaW4=</Binary>
                    </Binaries>
                    <Unknown>2026-11-17T13:18:09Z</Unknown>
                </Meta>
                <Root><Group>
                    <Times><ExpiryTime>1970-01-01T00:00:00Z</ExpiryTime><Expires>False</Expires></Times>
                    <Entry>
                        <String><Key>Title</Key><Value>2000-02-29T23:59:59Z</Value></String>
                        <Binary><Key>b.txt</Key><Value Ref="2"/></Binary>
                        <Binary><Key>a.txt</Key><Value Ref="7"/></Binary>
                        <History><Entry><Binary><Key>a.txt</Key><Value Ref="7"/></Binary></Entry></History>
                    </Entry>
                    <DeletedObjects><DeletedObject><DeletionTime>0001-01-01T00:00:00Z</DeletionTime></DeletedObject></DeletedObjects>
                </Group></Root>
            </KeePassFile>"#
        )
    }

    #[test]
    fn upgrade_converts_dates_and_moves_attachments() {
        let mut db = kdbx3(&sample());
        upgrade_to_kdbx4(&mut db).unwrap();

        assert_eq!(db.version, FormatVersion { major: 4, minor: 0 });
        let meta = db.xml.child("Meta").unwrap();
        assert_eq!(meta.child_text("DatabaseNameChanged"), "EUuO4g4AAAA=");
        assert!(meta.child("Binaries").is_none());
        // Only known date fields are converted: not unknown elements, and never user data.
        assert_eq!(meta.child_text("Unknown"), "2026-11-17T13:18:09Z");
        let group = db.xml.path(&["Root", "Group"]).unwrap();
        let entry = group.child("Entry").unwrap();
        assert_eq!(
            entry.path(&["String", "Value"]).unwrap().text.as_str(),
            "2000-02-29T23:59:59Z"
        );
        assert_eq!(
            group.path(&["Times", "ExpiryTime"]).unwrap().text.as_str(),
            "APeRdw4AAAA="
        );
        assert_eq!(
            group.path(&["Times", "Expires"]).unwrap().text.as_str(),
            "False"
        );
        assert_eq!(
            group
                .path(&["DeletedObjects", "DeletedObject", "DeletionTime"])
                .unwrap()
                .text
                .as_str(),
            "AAAAAAAAAAA="
        );

        // Attachments keep their file order, and IDs become positions: 7 → 0, 2 → 1.
        let data: Vec<&[u8]> = db.binaries.iter().map(|b| b.data.as_slice()).collect();
        assert_eq!(data, [&b"hello"[..], b"plain"]);
        let refs = |entry: &Element| -> Vec<String> {
            entry
                .children_named("Binary")
                .map(|b| b.child("Value").unwrap().attr("Ref").unwrap().to_owned())
                .collect()
        };
        assert_eq!(refs(entry), ["1", "0"]);
        assert_eq!(refs(entry.path(&["History", "Entry"]).unwrap()), ["0"]);

        // The header describes the same key derivation in KDBX 4 terms.
        let kdf = VariantDictionary::read(db.header.kdf_parameters().unwrap()).unwrap();
        assert_eq!(kdf.bytes("$UUID"), Some(&KDF_AES[..]));
        assert_eq!(kdf.u64("R"), Some(6000));
        assert_eq!(kdf.bytes("S"), Some(&[3u8; 32][..]));
        let ids: Vec<u8> = db.header.fields.iter().map(|(id, _)| *id).collect();
        assert_eq!(
            ids,
            [
                header::CIPHER_ID,
                header::COMPRESSION,
                header::MASTER_SEED,
                header::ENCRYPTION_IV,
                header::KDF_PARAMETERS,
                header::END
            ]
        );

        // A second upgrade has nothing left to do.
        let before = db.header.to_bytes().unwrap();
        upgrade_to_kdbx4(&mut db).unwrap();
        assert_eq!(db.header.to_bytes().unwrap(), before);
        assert_eq!(db.binaries.len(), 2);
    }

    #[test]
    fn a_failed_upgrade_changes_nothing() {
        for (broken, replacement) in [
            (r#"<Value Ref="2"/>"#, r#"<Value Ref="3"/>"#),
            ("1970-01-01T00:00:00Z", "sometime"),
            ("cGxhaW4=", "not base64!"),
            (HELLO_GZ, "cGxhaW4="), // marked as compressed, but isn't
        ] {
            let text = sample().replace(broken, replacement);
            assert_ne!(text, sample());
            let mut db = kdbx3(&text);
            assert!(matches!(upgrade_to_kdbx4(&mut db), Err(Error::Corrupt(_))));

            let untouched = kdbx3(&text);
            assert!(db.version == untouched.version);
            assert!(db.header.fields == untouched.header.fields);
            assert!(db.binaries.is_empty());
            assert!(db.xml.round_trips_to(&untouched.xml));
        }
    }
}
