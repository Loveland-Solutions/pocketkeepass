//! Auto-type: what to type for an entry, as KeePass's sequences describe it.
//!
//! The engine only works out the steps: which sequence applies (the entry's own, else the
//! nearest group's, else `{USERNAME}{TAB}{PASSWORD}{ENTER}`), whether auto-type is allowed
//! there, and what each placeholder means. Field values aren't in the steps: the app fetches
//! each one with `reveal_field` (or the one-time code with `totp_code`) as it types, and wipes
//! it after. The typing itself is the app's.
//!
//! Only the part of KeePass's syntax that means the same everywhere is understood. Key
//! combinations (`+`, `^`, `%` for Shift, Ctrl, Alt, and `( )` around them) mean Windows keys,
//! so a sequence using them is refused with a sentence saying so rather than typed wrongly;
//! so is any placeholder we don't know. Window-specific sequences aren't used.

use crate::Error;
use crate::error::Result;
use crate::model::{Database, Entry, Group};
use crate::totp::Totp;

/// KeePass's own default, used when neither the entry nor any of its groups sets one.
pub const DEFAULT_SEQUENCE: &str = "{USERNAME}{TAB}{PASSWORD}{ENTER}";

/// The most times one key can be repeated (`{TAB 3}`), and the longest delay (`{DELAY 500}`),
/// so a hostile file can't make the app type for minutes.
const MAX_REPEAT: u32 = 100;
const MAX_DELAY_MS: u32 = 10_000;

/// A key that isn't a character.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AutoTypeKey {
    Tab,
    Enter,
    Space,
    Backspace,
    Delete,
    Escape,
    Up,
    Down,
    Left,
    Right,
    Home,
    End,
    PageUp,
    PageDown,
}

/// One step of an auto-type sequence.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AutoTypeStep {
    /// Type this field's value ("UserName", "Password", a custom field's name, …).
    Field(String),
    /// Type the entry's current one-time code.
    Totp,
    /// Type this text, from the sequence itself.
    Text(String),
    /// Press a key this many times.
    Key(AutoTypeKey, u32),
    /// Wait this many milliseconds.
    Delay(u32),
    /// Select everything in the field and delete it (KeePass's `{CLEARFIELD}`).
    ClearField,
}

impl Database {
    /// The steps to auto-type an entry: `NotFound` if there's no such entry, `Invalid` with a
    /// sentence if auto-type is turned off for it or its sequence can't be typed here.
    pub fn auto_type(&self, uuid: &[u8; 16]) -> Result<Vec<AutoTypeStep>> {
        let hit = self.find_entry(uuid).ok_or(Error::NotFound)?;
        // The entry's groups, its own first, up to the root.
        let groups: Vec<Group<'_>> = std::iter::once(self.root())
            .chain(hit.path.iter().copied())
            .rev()
            .collect();
        let (enabled, own) = hit.entry.auto_type();
        if !enabled {
            return Err(Error::Invalid(
                "Auto-type is turned off for this entry.".into(),
            ));
        }
        if let Some(group) = groups.iter().find(|g| g.enable_auto_type().is_some())
            && group.enable_auto_type() == Some(false)
        {
            return Err(Error::Invalid(if self.is_recycle_bin(group) {
                "Entries in the recycle bin can't be auto-typed.".into()
            } else {
                format!("Auto-type is turned off for the group “{}”.", group.name())
            }));
        }
        let sequence = std::iter::once(own)
            .chain(groups.iter().map(|g| g.default_auto_type_sequence()))
            .map(str::trim)
            .find(|s| !s.is_empty())
            .unwrap_or(DEFAULT_SEQUENCE);
        let steps = parse_sequence(sequence)?;
        check_fields(&hit.entry, &steps)?;
        Ok(steps)
    }
}

/// Refuses a sequence that names a custom field the entry doesn't have, or a one-time code it
/// can't make, before anything is typed.
fn check_fields(entry: &Entry<'_>, steps: &[AutoTypeStep]) -> Result<()> {
    for step in steps {
        match step {
            AutoTypeStep::Field(key)
                if !crate::STANDARD_FIELDS.contains(&key.as_str())
                    && entry.field(key).is_none() =>
            {
                return Err(Error::Invalid(format!(
                    "The auto-type sequence types the field “{key}”, which this entry doesn't have."
                )));
            }
            AutoTypeStep::Totp if Totp::from_entry(entry)?.is_none() => {
                return Err(Error::Invalid(
                    "The auto-type sequence types a one-time code, but this entry has none set up."
                        .into(),
                ));
            }
            _ => {}
        }
    }
    Ok(())
}

/// Reads a KeePass auto-type sequence.
pub fn parse_sequence(sequence: &str) -> Result<Vec<AutoTypeStep>> {
    let mut steps = Vec::new();
    let mut text = String::new();
    let mut chars = sequence.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '{' => {
                // `{}}` is a literal "}", the only placeholder with a "}" inside.
                let mut inner = String::new();
                if chars.peek() == Some(&'}') {
                    chars.next();
                    if chars.next() != Some('}') {
                        return Err(invalid("“{}” isn't a placeholder."));
                    }
                    text.push('}');
                    continue;
                }
                loop {
                    match chars.next() {
                        Some('}') => break,
                        Some(c) => inner.push(c),
                        None => return Err(invalid("A “{” isn't closed by a “}”.")),
                    }
                }
                match placeholder(&inner)? {
                    Placeholder::Text(c) => text.push(c),
                    Placeholder::Step(step) => {
                        flush(&mut text, &mut steps);
                        steps.push(step);
                    }
                    Placeholder::Nothing => {}
                }
            }
            '~' => {
                flush(&mut text, &mut steps);
                steps.push(AutoTypeStep::Key(AutoTypeKey::Enter, 1));
            }
            '+' | '^' | '%' | '(' | ')' => {
                return Err(invalid(&format!(
                    "The auto-type sequence uses “{c}”, which means a key combination in KeePass. \
                     PocketKeePass can't type key combinations; to type the character itself, \
                     write {{{c}}}."
                )));
            }
            '}' => {
                return Err(invalid(
                    "A “}” has no “{” before it. To type it, write {}}.",
                ));
            }
            c => text.push(c),
        }
    }
    flush(&mut text, &mut steps);
    Ok(steps)
}

enum Placeholder {
    Text(char),
    Step(AutoTypeStep),
    /// Understood, but nothing to do (`{DELAY=n}`, KeePass's delay between keys).
    Nothing,
}

fn placeholder(inner: &str) -> Result<Placeholder> {
    // A single character in braces is that character, typed as it is: `{+}`, `{~}`, `{{}`.
    let mut it = inner.chars();
    if let (Some(c), None) = (it.next(), it.next()) {
        return Ok(Placeholder::Text(c));
    }
    if let Some(name) = inner.strip_prefix("S:") {
        return Ok(Placeholder::Step(AutoTypeStep::Field(name.to_owned())));
    }
    let upper = inner.to_ascii_uppercase();
    if upper.starts_with("DELAY=") {
        return Ok(Placeholder::Nothing);
    }
    let (name, count) = match upper.split_once(' ') {
        Some((name, count)) => {
            let count: u32 = count
                .trim()
                .parse()
                .map_err(|_| invalid(&format!("“{{{inner}}}” isn't a placeholder.")))?;
            (name, Some(count))
        }
        None => (upper.as_str(), None),
    };
    let field = |key: &str| {
        if count.is_some() {
            Err(invalid(&format!("“{{{inner}}}” isn't a placeholder.")))
        } else {
            Ok(Placeholder::Step(AutoTypeStep::Field(key.to_owned())))
        }
    };
    let key = |key: AutoTypeKey| {
        let count = count.unwrap_or(1);
        if count > MAX_REPEAT {
            return Err(invalid(&format!(
                "“{{{inner}}}” repeats a key more than {MAX_REPEAT} times."
            )));
        }
        Ok(Placeholder::Step(AutoTypeStep::Key(key, count)))
    };
    match name {
        "TITLE" => field("Title"),
        "USERNAME" => field("UserName"),
        "PASSWORD" => field("Password"),
        "URL" => field("URL"),
        "NOTES" => field("Notes"),
        "TOTP" | "TIMEOTP" if count.is_none() => Ok(Placeholder::Step(AutoTypeStep::Totp)),
        "CLEARFIELD" if count.is_none() => Ok(Placeholder::Step(AutoTypeStep::ClearField)),
        "DELAY" => match count {
            Some(ms) if ms <= MAX_DELAY_MS => Ok(Placeholder::Step(AutoTypeStep::Delay(ms))),
            _ => Err(invalid(&format!(
                "“{{{inner}}}” needs a delay of up to {MAX_DELAY_MS} milliseconds."
            ))),
        },
        "TAB" => key(AutoTypeKey::Tab),
        "ENTER" => key(AutoTypeKey::Enter),
        "SPACE" => key(AutoTypeKey::Space),
        "BACKSPACE" | "BS" | "BKSP" => key(AutoTypeKey::Backspace),
        "DELETE" | "DEL" => key(AutoTypeKey::Delete),
        "ESC" => key(AutoTypeKey::Escape),
        "UP" => key(AutoTypeKey::Up),
        "DOWN" => key(AutoTypeKey::Down),
        "LEFT" => key(AutoTypeKey::Left),
        "RIGHT" => key(AutoTypeKey::Right),
        "HOME" => key(AutoTypeKey::Home),
        "END" => key(AutoTypeKey::End),
        "PGUP" => key(AutoTypeKey::PageUp),
        "PGDN" => key(AutoTypeKey::PageDown),
        _ => Err(invalid(&format!(
            "The auto-type sequence uses “{{{inner}}}”, which PocketKeePass can't type yet."
        ))),
    }
}

fn flush(text: &mut String, steps: &mut Vec<AutoTypeStep>) {
    if !text.is_empty() {
        steps.push(AutoTypeStep::Text(std::mem::take(text)));
    }
}

fn invalid(sentence: &str) -> Error {
    Error::Invalid(sentence.to_owned())
}

#[cfg(test)]
mod tests {
    use super::AutoTypeKey::*;
    use super::AutoTypeStep::*;
    use super::*;

    fn field(key: &str) -> AutoTypeStep {
        Field(key.into())
    }

    #[test]
    fn the_default_sequence() {
        assert_eq!(
            parse_sequence(DEFAULT_SEQUENCE).unwrap(),
            [
                field("UserName"),
                Key(Tab, 1),
                field("Password"),
                Key(Enter, 1)
            ]
        );
    }

    #[test]
    fn placeholders_ignore_case_and_custom_fields_dont() {
        assert_eq!(
            parse_sequence("{username}{Tab 2}{S:PIN}~").unwrap(),
            [field("UserName"), Key(Tab, 2), field("PIN"), Key(Enter, 1)]
        );
    }

    #[test]
    fn text_escapes_delays_and_codes() {
        assert_eq!(
            parse_sequence("a{+}b{{}{}}{~}{DELAY 250}{DELAY=10}{TOTP}{CLEARFIELD}").unwrap(),
            [Text("a+b{}~".into()), Delay(250), Totp, ClearField]
        );
    }

    #[test]
    fn key_combinations_and_unknown_placeholders_are_refused() {
        for sequence in [
            "^v",
            "+{TAB}",
            "%(f)",
            "{APPACTIVATE x}",
            "{TAB",
            "}",
            "{TAB 101}",
            "{DELAY 20000}",
            "{USERNAME 2}",
            "{}x",
        ] {
            assert!(
                matches!(parse_sequence(sequence), Err(Error::Invalid(_))),
                "{sequence}"
            );
        }
    }

    #[test]
    fn a_database_uses_the_default_and_the_recycle_bin_refuses() {
        use crate::{CompositeKey, EntryEdit, FieldEdit, FieldValue, kdbx};
        let key = CompositeKey::new(Some(b"pw"), None).unwrap();
        let mut db = Database::create_with("Mine", &key, &kdbx::argon2id(2).unwrap()).unwrap();
        let root = db.root().uuid().unwrap();
        let set = |key, value| FieldEdit {
            key,
            value: FieldValue::Set(value),
            protected: false,
        };
        let edit = EntryEdit {
            fields: vec![set("Title", "Mail"), set("UserName", "jo")],
            tags: vec![],
        };
        let kept = db.create_entry(&root, &edit).unwrap();
        let binned = db.create_entry(&root, &edit).unwrap();
        assert_eq!(
            db.auto_type(&kept).unwrap(),
            parse_sequence(DEFAULT_SEQUENCE).unwrap()
        );
        db.delete_entry(&binned).unwrap();
        let Err(Error::Invalid(reason)) = db.auto_type(&binned) else {
            panic!()
        };
        assert!(reason.contains("recycle bin"), "{reason}");
        assert!(matches!(db.auto_type(&[9; 16]), Err(Error::NotFound)));
    }
}
