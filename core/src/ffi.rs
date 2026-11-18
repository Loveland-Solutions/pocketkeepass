//! The bridge to the Android app. UniFFI turns everything exported here into Kotlin.
//!
//! Rules for this file:
//! - It holds no logic of its own. It only wraps the engine in types that can cross the bridge.
//! - Secrets cross as bytes, never as strings. Kotlin can wipe a `ByteArray` after use, but a
//!   JVM `String` stays in memory until the garbage collector gets to it.
//! - Opening and saving run the key derivation, which is slow on purpose. Kotlin must call them
//!   off the main thread.

use std::sync::{Arc, PoisonError, RwLock, RwLockReadGuard};

use zeroize::{Zeroize, Zeroizing};

use crate::error::{Error, Result};
use crate::model::{uuid_from_hex, uuid_to_hex};
use crate::{
    CompositeKey, Database, Entry, EntryEdit, FieldValue, Group, PasswordOptions, SearchHit,
    Symbols, Totp,
};

/// A database that has been opened with the right key and is held decrypted in memory.
///
/// Call [`lock`](Self::lock) as soon as the user is done (auto-lock, screen off, app closed).
/// That wipes the decrypted content straight away instead of whenever Kotlin's garbage
/// collector lets go of this object. Every other method fails with `Locked` afterwards.
#[derive(uniffi::Object)]
pub struct UnlockedDatabase {
    db: RwLock<Option<Database>>,
}

/// What the app shows about a database once it's open. Nothing secret.
#[derive(uniffi::Record)]
pub struct DatabaseSummary {
    /// The name stored in the database. Often empty.
    pub name: String,
    /// The KDBX version, e.g. "4.1".
    pub format_version: String,
    /// True for KDBX 3 databases: they must be upgraded (with the user's consent) to be saved.
    pub needs_upgrade: bool,
    /// Groups below the root group, at any depth.
    pub group_count: u32,
    /// Entries at any depth, not counting older versions kept in entry history.
    pub entry_count: u32,
    /// Whether deleting moves things to the recycle bin (rather than deleting them for good).
    pub recycle_bin_enabled: bool,
}

/// A group, for showing in a list or a breadcrumb trail.
#[derive(uniffi::Record)]
pub struct GroupItem {
    /// The group's UUID as 32 hex digits. Pass it to `list_group` to open the group.
    pub id: String,
    pub name: String,
    /// Direct subgroups.
    pub group_count: u32,
    /// Direct entries, not counting those in subgroups.
    pub entry_count: u32,
    pub is_recycle_bin: bool,
}

/// An entry, for showing in a list. Protected fields are left empty: a list never carries a
/// password, nor a username or URL the user chose to protect.
#[derive(uniffi::Record)]
pub struct EntryItem {
    /// The entry's UUID as 32 hex digits.
    pub id: String,
    pub title: String,
    pub username: String,
    pub url: String,
}

/// One group's contents, as the app shows them.
#[derive(uniffi::Record)]
pub struct GroupListing {
    pub group: GroupItem,
    /// True for the root group, whose name is usually not worth showing.
    pub is_root: bool,
    /// The groups from the root down to this group's parent, for "up" and breadcrumbs. Empty
    /// for the root group.
    pub parents: Vec<GroupItem>,
    /// Subgroups in file order, with the recycle bin last.
    pub groups: Vec<GroupItem>,
    /// Entries in file order.
    pub entries: Vec<EntryItem>,
}

/// An entry that matched a search.
#[derive(uniffi::Record)]
pub struct SearchResult {
    pub entry: EntryItem,
    /// Group names from below the root down to the entry's group. Empty for root entries.
    pub group_path: Vec<String>,
}

/// What is asking autofill for a username and password.
#[derive(uniffi::Enum)]
pub enum FillTarget {
    /// An Android app, by package name.
    App { package: String },
    /// A web page. Only build this from what a browser the app has verified reports: any other
    /// app could claim to be showing any site.
    Web {
        /// The page's host name, e.g. "login.example.com".
        domain: String,
        /// The page was loaded over plain `http`. Entries saved with an `https` URL aren't
        /// offered then.
        plain_http: bool,
    },
}

/// Who is asking for a passkey. See the engine's `webauthn::Client`.
#[derive(uniffi::Enum)]
pub enum PasskeyClient {
    /// A browser the app has verified (package name and signing certificate), which supplies
    /// the SHA-256 of its own client data.
    Browser { client_data_hash: Vec<u8> },
    /// Any other app: its package name, and the SHA-256 of each of its signing certificates,
    /// the current one first.
    App {
        package: String,
        certificates: Vec<Vec<u8>>,
    },
}

/// Whether the app asking may use a passkey.
#[derive(uniffi::Enum)]
pub enum AppTrust {
    /// A verified browser.
    Browser,
    /// The app is linked to the entry with a matching certificate.
    Linked,
    /// Not linked yet: ask the user, then `link_app`.
    NotLinked,
    /// An app with this name is linked, but signed by someone else. Refuse, don't ask.
    OtherSigner,
}

/// A passkey that can answer a sign-in. Nothing secret.
#[derive(uniffi::Record)]
pub struct PasskeyChoice {
    pub result: SearchResult,
    /// The site the passkey is for.
    pub rp_id: String,
    /// The account name the site gave.
    pub username: String,
    /// Why the passkey can't be used, or null if it can.
    pub unusable_reason: Option<String>,
    pub trust: AppTrust,
}

/// What a request to create a passkey is for, to show before asking the user.
#[derive(uniffi::Record)]
pub struct PasskeyCreation {
    /// The site, e.g. "example.com".
    pub rp_id: String,
    /// The site's name for itself. May be empty.
    pub rp_name: String,
    pub user_name: String,
    pub user_display_name: String,
    /// Entries for the same site without a passkey, that the new one could be added to.
    pub candidates: Vec<SearchResult>,
}

/// Where a new passkey goes.
#[derive(uniffi::Enum)]
pub enum PasskeyTarget {
    NewEntry { group_id: String },
    Entry { entry_id: String },
}

/// A passkey just made.
#[derive(uniffi::Record)]
pub struct CreatedPasskey {
    pub entry_id: String,
    /// The response to hand back to the site. Not secret.
    pub response_json: String,
}

/// An entry's passkey, for the entry screen. Nothing secret.
#[derive(uniffi::Record)]
pub struct PasskeyInfo {
    pub rp_id: String,
    pub username: String,
    /// Why the passkey can't be used, or null if it can.
    pub unusable_reason: Option<String>,
    /// The fields making up the passkey and its app links. The entry screen can leave them out
    /// since it shows the passkey; they're still in `fields`, and must stay in an edit.
    pub fields: Vec<String>,
}

/// Everything the entry screen shows, except protected values: those cross the bridge only when
/// the user asks to see or copy one, through `reveal_field`.
#[derive(uniffi::Record)]
pub struct EntryDetails {
    /// The entry's UUID as 32 hex digits.
    pub id: String,
    /// Empty if the title is missing or protected.
    pub title: String,
    /// Whether the title is protected, so an editor knows to leave it alone unless the user
    /// changes it. (Almost never: KeePass doesn't protect titles by default.)
    pub title_is_protected: bool,
    /// Group names from below the root down to the entry's group. Empty for root entries.
    pub group_path: Vec<String>,
    pub is_in_recycle_bin: bool,
    /// Username, password, and URL first, then custom fields in file order, then notes. The
    /// title isn't repeated here. Standard fields are always listed, even when the file leaves
    /// them out.
    pub fields: Vec<EntryField>,
    /// Split at `;` and `,`, trimmed, empty ones left out.
    pub tags: Vec<String>,
    /// Whether the entry makes TOTP codes. Fetch the current one with `totp_code`.
    pub totp: TotpStatus,
    /// The entry's passkey, if it has one.
    pub passkey: Option<PasskeyInfo>,
}

/// An entry's TOTP setup. Nothing secret.
#[derive(uniffi::Enum)]
pub enum TotpStatus {
    /// The entry has no TOTP settings.
    None,
    /// The entry makes codes.
    Ready {
        /// How long each code lasts, in seconds. A new code starts whenever the Unix time is a
        /// multiple of this.
        period_seconds: u32,
        /// How many characters a code has.
        length: u32,
        /// The fields holding the settings (`otp`, or KeePass's `TimeOtp-*`, and so on), which
        /// the entry screen can leave out since it shows the code instead. They're still in
        /// `fields`, and must stay in an edit.
        fields: Vec<String>,
    },
    /// The entry has TOTP settings that can't be used, and this sentence says why.
    Invalid { reason: String },
}

/// One field of an entry.
#[derive(uniffi::Record)]
pub struct EntryField {
    /// "UserName", "Password", "URL", "Notes", or a custom field's name.
    pub key: String,
    /// The value, or "" if the field is protected. Fetch a protected value with `reveal_field`.
    pub value: String,
    pub is_protected: bool,
    /// Whether the value is empty, so the app can tell an empty protected field from one with
    /// something to show or copy without fetching it.
    pub is_empty: bool,
}

/// One field of an entry, as it should be after an edit. See `create_entry` and `update_entry`.
#[derive(uniffi::Record)]
pub struct FieldEdit {
    /// "Title", "UserName", "Password", "URL", "Notes", or a custom field's name.
    pub key: String,
    /// The new value as UTF-8 bytes, or null to keep the value the entry already has (under
    /// `previous_key`). Leave protected values the user didn't change as null, so they never
    /// have to cross the bridge. Wiped on this side after use.
    pub value: Option<Vec<u8>>,
    /// The field's name before this edit, if it was renamed. Null means the same as `key`.
    pub previous_key: Option<String>,
    /// Whether the file stores the value encrypted. Only used for custom fields: the standard
    /// ones keep their own protection (or get the database's default).
    pub is_protected: bool,
}

/// What deleting did.
#[derive(uniffi::Enum, Debug, PartialEq, Eq)]
pub enum Deletion {
    /// Moved to the recycle bin, where it can be restored from.
    MovedToRecycleBin,
    /// Gone for good.
    Deleted,
}

/// A group's own details, for editing it.
#[derive(uniffi::Record)]
pub struct GroupDetails {
    pub id: String,
    pub name: String,
    pub notes: String,
    /// True for the root group, which can't be deleted or moved.
    pub is_root: bool,
}

/// A group to choose from when moving something.
#[derive(uniffi::Record)]
pub struct GroupChoice {
    pub id: String,
    pub name: String,
    /// How many groups lie between this one and the root group: 0 for the root itself.
    pub depth: u32,
    pub is_recycle_bin: bool,
    /// Whether it's inside the recycle bin (at any depth).
    pub is_in_recycle_bin: bool,
}

#[uniffi::export]
impl UnlockedDatabase {
    /// Decrypts a whole `.kdbx` file. `password` is the master password as UTF-8 bytes;
    /// `key_file` is the content of the key file. At least one of them is needed.
    ///
    /// The copies of `password` and `key_file` on this side of the bridge are wiped before this
    /// returns. The caller should wipe its own.
    #[uniffi::constructor]
    pub fn open(
        file: Vec<u8>,
        password: Option<Vec<u8>>,
        key_file: Option<Vec<u8>>,
    ) -> Result<Arc<Self>> {
        let password = password.map(Zeroizing::new);
        let key_file = key_file.map(Zeroizing::new);
        let key = CompositeKey::new(
            password.as_ref().map(|p| p.as_slice()),
            key_file.as_ref().map(|k| k.as_slice()),
        )?;
        let db = Database::open(&file, &key)?;
        Ok(Arc::new(Self {
            db: RwLock::new(Some(db)),
        }))
    }

    /// Decrypts a whole `.kdbx` file with a composite key hash from `key_hash`, for quick
    /// unlock. Fails with `WrongKey` if the hash doesn't open the file (the master key changed
    /// since it was stored) or isn't 32 bytes. Like `open`, this runs the key derivation.
    ///
    /// This side's copy of `key_hash` is wiped before this returns. The caller should wipe its own.
    #[uniffi::constructor]
    pub fn open_with_key_hash(file: Vec<u8>, key_hash: Vec<u8>) -> Result<Arc<Self>> {
        let key_hash = Zeroizing::new(key_hash);
        let key_hash: &[u8; 32] = key_hash
            .as_slice()
            .try_into()
            .map_err(|_| Error::WrongKey)?;
        let db = Database::open_with_key_hash(&file, key_hash)?;
        Ok(Arc::new(Self {
            db: RwLock::new(Some(db)),
        }))
    }

    /// The composite key hash this database was opened with, for quick unlock to store
    /// (encrypted). It opens the database as well as the password and key file do, so the
    /// caller must wipe it as soon as it's encrypted.
    pub fn key_hash(&self) -> Result<Vec<u8>> {
        self.with(|db| Ok(db.key_hash().to_vec()))
    }

    pub fn summary(&self) -> Result<DatabaseSummary> {
        fn count(group: Group<'_>, groups: &mut u32, entries: &mut u32) {
            *entries += group.entries().count() as u32;
            for child in group.groups() {
                *groups += 1;
                count(child, groups, entries);
            }
        }
        self.with(|db| {
            let (mut group_count, mut entry_count) = (0, 0);
            count(db.root(), &mut group_count, &mut entry_count);
            Ok(DatabaseSummary {
                name: db.name().to_owned(),
                format_version: db.format_version().to_string(),
                needs_upgrade: db.needs_upgrade(),
                group_count,
                entry_count,
                recycle_bin_enabled: db.recycle_bin_enabled(),
            })
        })
    }

    /// Lists a group's subgroups and entries: the root group if `id` is null. Fails with
    /// `NotFound` if there's no group with that id.
    pub fn list_group(&self, id: Option<String>) -> Result<GroupListing> {
        self.with(|db| {
            let uuid = match id {
                None => None,
                Some(id) => Some(uuid_from_hex(&id).ok_or(Error::NotFound)?),
            };
            let listing = db.listing(uuid.as_ref()).ok_or(Error::NotFound)?;
            let item = |g: &Group<'_>| group_item(db, g);
            Ok(GroupListing {
                group: item(&listing.group),
                is_root: listing.parents.is_empty(),
                parents: listing.parents.iter().map(item).collect(),
                groups: listing.groups.iter().map(item).collect(),
                entries: listing.entries.iter().map(entry_item).collect(),
            })
        })
    }

    /// Searches every entry's title, username, URL, notes, and tags. See the engine's
    /// `Database::search` for exactly what's searched and what isn't (passwords never are).
    pub fn search(&self, query: String) -> Result<Vec<SearchResult>> {
        self.with(|db| Ok(db.search(&query).iter().map(search_result).collect()))
    }

    /// The entries linked to the app or web page asking for autofill, best matches first. See
    /// the engine's `Database::autofill_matches` for the rules. Like every list, the results
    /// carry no protected values: fetch the chosen entry's username and password with
    /// `reveal_field`.
    pub fn autofill_matches(&self, target: FillTarget) -> Result<Vec<SearchResult>> {
        self.with(|db| {
            let target = match &target {
                FillTarget::App { package } => crate::FillTarget::App { package },
                FillTarget::Web { domain, plain_http } => crate::FillTarget::Web {
                    domain,
                    plain_http: *plain_http,
                },
            };
            Ok(db
                .autofill_matches(target)
                .iter()
                .map(search_result)
                .collect())
        })
    }

    /// The entry with this id, for the entry screen. Fails with `NotFound` if there's none.
    /// Protected values are left out; see `reveal_field`.
    pub fn entry(&self, id: String) -> Result<EntryDetails> {
        self.with(|db| {
            let hit = db
                .find_entry(&uuid_from_hex(&id).ok_or(Error::NotFound)?)
                .ok_or(Error::NotFound)?;
            Ok(EntryDetails {
                id: entry_id(&hit.entry),
                title: hit.entry.unprotected_field("Title").to_owned(),
                title_is_protected: hit.entry.is_protected("Title"),
                group_path: hit.path.iter().map(|g| g.name().to_owned()).collect(),
                is_in_recycle_bin: hit.path.iter().any(|g| db.is_recycle_bin(g)),
                fields: entry_fields(&hit.entry),
                tags: hit
                    .entry
                    .tags()
                    .split([';', ','])
                    .map(str::trim)
                    .filter(|t| !t.is_empty())
                    .map(str::to_owned)
                    .collect(),
                totp: match Totp::from_entry(&hit.entry) {
                    Ok(None) => TotpStatus::None,
                    Ok(Some(totp)) => TotpStatus::Ready {
                        period_seconds: totp.period(),
                        length: totp.length(),
                        fields: totp.fields().to_vec(),
                    },
                    Err(e) => TotpStatus::Invalid {
                        reason: e.to_string(),
                    },
                },
                passkey: crate::passkey_of(&hit.entry).map(|p| PasskeyInfo {
                    rp_id: p.rp_id,
                    username: p.username.to_owned(),
                    unusable_reason: unusable_reason(p.status),
                    fields: p.fields.iter().map(|f| (*f).to_owned()).collect(),
                }),
            })
        })
    }

    /// The entry's TOTP code for a moment in time, given in seconds since 1970 (UTC), as ASCII
    /// bytes. Fails with `NotFound` if the entry is gone or has no TOTP settings, and with
    /// `Invalid` (a sentence for the user) if its settings can't be used. The caller should wipe
    /// the bytes as soon as it's done with them.
    pub fn totp_code(&self, entry_id: String, unix_seconds: u64) -> Result<Vec<u8>> {
        self.with(|db| {
            let hit = db
                .find_entry(&uuid_from_hex(&entry_id).ok_or(Error::NotFound)?)
                .ok_or(Error::NotFound)?;
            let totp = Totp::from_entry(&hit.entry)?.ok_or(Error::NotFound)?;
            Ok(totp.code_at(unix_seconds).as_bytes().to_vec())
        })
    }

    /// One field's value as UTF-8 bytes, protected or not: for showing or copying it when the
    /// user asks. Fails with `NotFound` if the entry or the field doesn't exist. The caller
    /// should wipe the bytes as soon as it's done with them.
    pub fn reveal_field(&self, entry_id: String, key: String) -> Result<Vec<u8>> {
        self.with(|db| {
            let hit = db
                .find_entry(&uuid_from_hex(&entry_id).ok_or(Error::NotFound)?)
                .ok_or(Error::NotFound)?;
            let field = hit.entry.fields().find(|f| f.key == key);
            // A standard field the file leaves out is just empty: `entry` lists it anyway.
            match field {
                Some(f) => Ok(f.value.as_bytes().to_vec()),
                None if STANDARD_FIELDS.contains(&key.as_str()) => Ok(Vec::new()),
                None => Err(Error::NotFound),
            }
        })
    }

    /// The passkeys that can answer a sign-in request (Credential Manager's request JSON), in
    /// file order. Nothing secret: sign with `assert_passkey` once the user has picked one.
    pub fn passkeys_for(
        &self,
        request_json: String,
        client: PasskeyClient,
    ) -> Result<Vec<PasskeyChoice>> {
        with_client(&client, |client| {
            self.with(|db| {
                Ok(db
                    .passkeys_for(&request_json, client)?
                    .into_iter()
                    .map(|p| PasskeyChoice {
                        result: search_result(&p.hit),
                        rp_id: p.rp_id,
                        username: p.username.to_owned(),
                        unusable_reason: unusable_reason(p.status),
                        trust: p.trust.into(),
                    })
                    .collect())
            })
        })
    }

    /// Checks a request to create a passkey and says what it's for. Fails with
    /// `PasskeyUnsupported` or `PasskeyExists` before the user is asked anything.
    pub fn passkey_creation(&self, request_json: String) -> Result<PasskeyCreation> {
        self.with(|db| {
            let summary = db.passkey_creation(&request_json)?;
            Ok(PasskeyCreation {
                candidates: summary.candidates.iter().map(search_result).collect(),
                rp_id: summary.rp_id,
                rp_name: summary.rp_name,
                user_name: summary.user_name,
                user_display_name: summary.user_display_name,
            })
        })
    }

    /// Makes a passkey and stores it (an app is linked at the same time). Only call this after
    /// the user agreed and was verified. Like every change, it's only in memory until `save`;
    /// if saving fails, call `undo_passkey`.
    pub fn create_passkey(
        &self,
        target: PasskeyTarget,
        request_json: String,
        client: PasskeyClient,
    ) -> Result<CreatedPasskey> {
        let target = match target {
            PasskeyTarget::NewEntry { group_id } => crate::PasskeyTarget::NewEntry {
                group: parse_id(&group_id)?,
            },
            PasskeyTarget::Entry { entry_id } => crate::PasskeyTarget::Entry(parse_id(&entry_id)?),
        };
        with_client(&client, |client| {
            self.with_mut(|db| {
                let created = db.create_passkey(target, &request_json, client)?;
                Ok(CreatedPasskey {
                    entry_id: uuid_to_hex(&created.entry),
                    response_json: created.response_json,
                })
            })
        })
    }

    /// Takes back the passkey `create_passkey` made last, if it went into this entry.
    pub fn undo_passkey(&self, entry_id: String) -> Result<bool> {
        let entry = parse_id(&entry_id)?;
        self.with_mut(|db| db.undo_passkey(&entry))
    }

    /// Signs a sign-in request with an entry's passkey and returns the response for the site.
    /// Only call this after the user picked the passkey and was verified.
    pub fn assert_passkey(
        &self,
        entry_id: String,
        request_json: String,
        client: PasskeyClient,
    ) -> Result<String> {
        let entry = parse_id(&entry_id)?;
        with_client(&client, |client| {
            self.with(|db| db.assert_passkey(&entry, &request_json, client))
        })
    }

    /// Links an app to an entry, after the user agreed. `certificates` are SHA-256 hashes.
    pub fn link_app(
        &self,
        entry_id: String,
        package: String,
        certificates: Vec<Vec<u8>>,
    ) -> Result<bool> {
        let entry = parse_id(&entry_id)?;
        let certificates = certificate_hashes(&certificates)?;
        self.with_mut(|db| db.link_app(&entry, &package, &certificates))
    }

    /// Adds an entry to a group and returns its id. `fields` lists every field it should have,
    /// in order; standard fields left out are added empty. Fails with `Invalid` (and a sentence
    /// saying why) for an empty or repeated field name, or text a KeePass file can't hold.
    ///
    /// Like every change, this is only in memory until `save`.
    pub fn create_entry(
        &self,
        group_id: String,
        fields: Vec<FieldEdit>,
        tags: Vec<String>,
    ) -> Result<String> {
        let group = parse_id(&group_id)?;
        self.with_mut(|db| {
            let id = with_edit(fields, &tags, |edit| db.create_entry(&group, edit))?;
            Ok(uuid_to_hex(&id))
        })
    }

    /// Changes an entry's fields and tags, keeping its previous version in its history.
    /// `fields` lists every field it should have, in order: custom fields left out are deleted.
    /// Returns false if nothing actually changed (and then nothing is touched).
    pub fn update_entry(
        &self,
        id: String,
        fields: Vec<FieldEdit>,
        tags: Vec<String>,
    ) -> Result<bool> {
        let entry = parse_id(&id)?;
        self.with_mut(|db| with_edit(fields, &tags, |edit| db.update_entry(&entry, edit)))
    }

    /// Moves an entry to the recycle bin, or deletes it for good if it's already there or the
    /// recycle bin is turned off.
    pub fn delete_entry(&self, id: String) -> Result<Deletion> {
        let entry = parse_id(&id)?;
        self.with_mut(|db| db.delete_entry(&entry).map(Deletion::from))
    }

    /// Moves an entry into another group. Returns false if it's already there.
    pub fn move_entry(&self, id: String, group_id: String) -> Result<bool> {
        let (entry, group) = (parse_id(&id)?, parse_id(&group_id)?);
        self.with_mut(|db| db.move_entry(&entry, &group))
    }

    /// A group's name and notes, for editing it.
    pub fn group_details(&self, id: String) -> Result<GroupDetails> {
        let uuid = parse_id(&id)?;
        self.with(|db| {
            let listing = db.listing(Some(&uuid)).ok_or(Error::NotFound)?;
            Ok(GroupDetails {
                id,
                name: listing.group.name().to_owned(),
                notes: listing.group.notes().to_owned(),
                is_root: listing.parents.is_empty(),
            })
        })
    }

    /// Adds a group inside another and returns its id.
    pub fn create_group(&self, parent_id: String, name: String, notes: String) -> Result<String> {
        let parent = parse_id(&parent_id)?;
        self.with_mut(|db| Ok(uuid_to_hex(&db.create_group(&parent, &name, &notes)?)))
    }

    /// Renames a group and changes its notes. Returns false if both were already so.
    pub fn update_group(&self, id: String, name: String, notes: String) -> Result<bool> {
        let group = parse_id(&id)?;
        self.with_mut(|db| db.update_group(&group, &name, &notes))
    }

    /// Moves a group, with everything in it, to the recycle bin, or deletes it for good (see
    /// the engine's `Database::delete_group`). The root group can't be deleted.
    pub fn delete_group(&self, id: String) -> Result<Deletion> {
        let group = parse_id(&id)?;
        self.with_mut(|db| db.delete_group(&group).map(Deletion::from))
    }

    /// Moves a group, with everything in it, into another group. Returns false if it's
    /// already there. Fails with `Invalid` for the root group, or a move into itself.
    pub fn move_group(&self, id: String, parent_id: String) -> Result<bool> {
        let (group, parent) = (parse_id(&id)?, parse_id(&parent_id)?);
        self.with_mut(|db| db.move_group(&group, &parent))
    }

    /// Every group, the root first, each followed by its subgroups (file order, the recycle
    /// bin last): the order a tree of them is drawn in. For choosing where to move something.
    pub fn all_groups(&self) -> Result<Vec<GroupChoice>> {
        fn walk(
            db: &Database,
            group: Group<'_>,
            depth: u32,
            in_bin: bool,
            out: &mut Vec<GroupChoice>,
        ) {
            let is_bin = db.is_recycle_bin(&group);
            out.push(GroupChoice {
                id: id(group.uuid()),
                name: group.name().to_owned(),
                depth,
                is_recycle_bin: is_bin,
                is_in_recycle_bin: in_bin,
            });
            let (mut children, bins): (Vec<_>, Vec<_>) =
                group.groups().partition(|g| !db.is_recycle_bin(g));
            children.extend(bins);
            for child in children {
                walk(db, child, depth + 1, in_bin || is_bin, out);
            }
        }
        self.with(|db| {
            let mut out = Vec::new();
            walk(db, db.root(), 0, false, &mut out);
            Ok(out)
        })
    }

    /// Converts a KDBX 3 database to KDBX 4 in memory so that it can be saved. Ask the user
    /// first: older apps that only read KDBX 3 won't open the file once it's saved.
    pub fn upgrade_to_kdbx4(&self) -> Result<()> {
        let mut db = self.db.write().unwrap_or_else(PoisonError::into_inner);
        db.as_mut().ok_or(Error::Locked)?.upgrade_to_kdbx4()
    }

    /// Encrypts the database into the bytes of a complete `.kdbx` file. The engine has already
    /// read the result back and checked it. Replacing the old file safely is the caller's job.
    pub fn save(&self) -> Result<Vec<u8>> {
        self.with(Database::save)
    }

    /// Wipes the decrypted database from memory. Safe to call more than once.
    pub fn lock(&self) {
        *self.db.write().unwrap_or_else(PoisonError::into_inner) = None;
    }

    pub fn is_locked(&self) -> bool {
        self.read().is_none()
    }
}

impl UnlockedDatabase {
    fn read(&self) -> RwLockReadGuard<'_, Option<Database>> {
        // A panic while the lock was held doesn't make the data unusable, so ignore poisoning.
        self.db.read().unwrap_or_else(PoisonError::into_inner)
    }

    fn with<T>(&self, f: impl FnOnce(&Database) -> Result<T>) -> Result<T> {
        f(self.read().as_ref().ok_or(Error::Locked)?)
    }

    fn with_mut<T>(&self, f: impl FnOnce(&mut Database) -> Result<T>) -> Result<T> {
        let mut db = self.db.write().unwrap_or_else(PoisonError::into_inner);
        f(db.as_mut().ok_or(Error::Locked)?)
    }
}

impl From<crate::Deletion> for Deletion {
    fn from(deletion: crate::Deletion) -> Self {
        match deletion {
            crate::Deletion::MovedToRecycleBin => Deletion::MovedToRecycleBin,
            crate::Deletion::Deleted => Deletion::Deleted,
        }
    }
}

impl From<crate::AppTrust> for AppTrust {
    fn from(trust: crate::AppTrust) -> Self {
        match trust {
            crate::AppTrust::Browser => AppTrust::Browser,
            crate::AppTrust::Linked => AppTrust::Linked,
            crate::AppTrust::NotLinked => AppTrust::NotLinked,
            crate::AppTrust::OtherSigner => AppTrust::OtherSigner,
        }
    }
}

fn unusable_reason(status: crate::PasskeyStatus) -> Option<String> {
    match status {
        crate::PasskeyStatus::Ready => None,
        crate::PasskeyStatus::Unusable(reason) => Some(reason),
    }
}

fn certificate_hashes(certificates: &[Vec<u8>]) -> Result<Vec<[u8; 32]>> {
    certificates
        .iter()
        .map(|c| {
            c.as_slice()
                .try_into()
                .map_err(|_| Error::Invalid("An app certificate hash isn't SHA-256.".into()))
        })
        .collect()
}

/// Turns the bridge's passkey client into the engine's and calls `f` with it.
fn with_client<T>(
    client: &PasskeyClient,
    f: impl FnOnce(&crate::Client<'_>) -> Result<T>,
) -> Result<T> {
    match client {
        PasskeyClient::Browser { client_data_hash } => {
            f(&crate::Client::Browser { client_data_hash })
        }
        PasskeyClient::App {
            package,
            certificates,
        } => {
            let certificates = certificate_hashes(certificates)?;
            f(&crate::Client::App {
                package,
                certificates: &certificates,
            })
        }
    }
}

fn parse_id(id: &str) -> Result<[u8; 16]> {
    uuid_from_hex(id).ok_or(Error::NotFound)
}

/// Turns the bridge's field edits into the engine's and calls `f` with them. The values are
/// wiped afterwards, whatever happens.
fn with_edit<T>(
    mut fields: Vec<FieldEdit>,
    tags: &[String],
    f: impl FnOnce(&EntryEdit<'_>) -> Result<T>,
) -> Result<T> {
    let result = edit_from(&fields, tags, f);
    for field in &mut fields {
        if let Some(value) = &mut field.value {
            value.zeroize();
        }
    }
    result
}

fn edit_from<T>(
    fields: &[FieldEdit],
    tags: &[String],
    f: impl FnOnce(&EntryEdit<'_>) -> Result<T>,
) -> Result<T> {
    let edits = fields
        .iter()
        .map(|field| {
            let value =
                match &field.value {
                    Some(bytes) => FieldValue::Set(std::str::from_utf8(bytes).map_err(|_| {
                        Error::Invalid(format!("“{}” isn't valid text.", field.key))
                    })?),
                    None => FieldValue::Keep {
                        from: field.previous_key.as_deref().unwrap_or(&field.key),
                    },
                };
            Ok(crate::FieldEdit {
                key: &field.key,
                value,
                protected: field.is_protected,
            })
        })
        .collect::<Result<Vec<_>>>()?;
    f(&EntryEdit {
        fields: edits,
        tags: tags.iter().map(String::as_str).collect(),
    })
}

fn id(uuid: Option<[u8; 16]>) -> String {
    uuid.as_ref().map(uuid_to_hex).unwrap_or_default()
}

fn group_item(db: &Database, group: &Group<'_>) -> GroupItem {
    GroupItem {
        id: id(group.uuid()),
        name: group.name().to_owned(),
        group_count: group.groups().count() as u32,
        entry_count: group.entries().count() as u32,
        is_recycle_bin: db.is_recycle_bin(group),
    }
}

/// The fields every KeePass entry has, besides the title, in the order the entry screen shows
/// them. Notes come last, after custom fields.
const STANDARD_FIELDS: [&str; 4] = ["UserName", "Password", "URL", "Notes"];

fn entry_fields(entry: &Entry<'_>) -> Vec<EntryField> {
    let field = |key: &str| {
        let found = entry.fields().find(|f| f.key == key);
        let protected = found.is_some_and(|f| f.protected);
        let value = found.map_or("", |f| f.value);
        EntryField {
            key: key.to_owned(),
            value: if protected {
                String::new()
            } else {
                value.to_owned()
            },
            is_protected: protected,
            is_empty: value.is_empty(),
        }
    };
    let [username, password, url, notes] = STANDARD_FIELDS;
    let custom = entry
        .fields()
        .filter(|f| f.key != "Title" && !STANDARD_FIELDS.contains(&f.key))
        .map(|f| field(f.key));
    [username, password, url]
        .into_iter()
        .map(field)
        .chain(custom)
        .chain([field(notes)])
        .collect()
}

fn entry_id(entry: &Entry<'_>) -> String {
    id(entry.uuid())
}

fn entry_item(entry: &Entry<'_>) -> EntryItem {
    EntryItem {
        id: entry_id(entry),
        title: entry.unprotected_field("Title").to_owned(),
        username: entry.unprotected_field("UserName").to_owned(),
        url: entry.unprotected_field("URL").to_owned(),
    }
}

fn search_result(hit: &SearchHit<'_>) -> SearchResult {
    SearchResult {
        entry: entry_item(&hit.entry),
        group_path: hit.path.iter().map(|g| g.name().to_owned()).collect(),
    }
}

/// Makes a random password. It's ASCII, so the bytes are also its UTF-8. The caller wipes them.
///
/// Fails with `Invalid` (a sentence for the user) if no kind of character is chosen or the
/// length is out of range. It doesn't touch any database, and it's fast enough for the main
/// thread.
#[uniffi::export]
pub fn generate_password(options: PasswordOptions) -> Result<Vec<u8>> {
    let password = options.generate()?;
    Ok(password.to_vec())
}

/// How many bits of randomness a password made with these options has. Fails as
/// `generate_password` does.
#[uniffi::export]
pub fn password_strength_bits(options: PasswordOptions) -> Result<f64> {
    options.strength_bits()
}

/// The symbols each choice allows, for the generator to show.
#[uniffi::export]
pub fn password_symbols(symbols: Symbols) -> String {
    match symbols {
        Symbols::None => String::new(),
        Symbols::Common => crate::generator::COMMON_SYMBOLS.to_owned(),
        Symbols::All => crate::generator::ALL_SYMBOLS.to_owned(),
    }
}

/// The characters `avoid_lookalikes` leaves out, for the generator to show.
#[uniffi::export]
pub fn password_lookalikes() -> String {
    crate::generator::LOOKALIKES.to_owned()
}

/// The engine's version, for the app's About screen.
#[uniffi::export]
pub fn engine_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(name: &str) -> Vec<u8> {
        std::fs::read(format!(
            "{}/tests/fixtures/{name}",
            env!("CARGO_MANIFEST_DIR")
        ))
        .unwrap()
    }

    fn open(name: &str) -> Arc<UnlockedDatabase> {
        UnlockedDatabase::open(fixture(name), Some(b"test-password".to_vec()), None).unwrap()
    }

    #[test]
    fn summary_describes_the_database() {
        let summary = open("kdbx41-keepassxc.kdbx").summary().unwrap();
        assert_eq!(summary.name, "Passwords");
        assert_eq!(summary.format_version, "4.1");
        assert!(!summary.needs_upgrade);
        assert_eq!((summary.group_count, summary.entry_count), (1, 3));

        // History items aren't entries: "Email" was edited once, and still counts once.
        let summary = open("pw-only.kdbx").summary().unwrap();
        assert_eq!(summary.format_version, "3.1");
        assert!(summary.needs_upgrade);
        assert_eq!((summary.group_count, summary.entry_count), (1, 2));
    }

    #[test]
    fn open_reports_a_wrong_or_missing_key() {
        let file = fixture("pw-only.kdbx");
        let wrong = UnlockedDatabase::open(file.clone(), Some(b"nope".to_vec()), None);
        assert!(matches!(wrong, Err(Error::WrongKey)));
        let none = UnlockedDatabase::open(file, None, None);
        assert!(matches!(none, Err(Error::InvalidKeyFile)));
    }

    #[test]
    fn a_key_file_unlocks_through_the_bridge() {
        let db = UnlockedDatabase::open(
            fixture("keyfile-only.kdbx"),
            None,
            Some(fixture("keyfile.keyx")),
        )
        .unwrap();
        assert_eq!(db.summary().unwrap().entry_count, 1);
    }

    #[test]
    fn upgrade_then_save_gives_a_file_that_opens_again() {
        let db = open("pw-only.kdbx");
        assert!(matches!(db.save(), Err(Error::NeedsUpgrade)));
        db.upgrade_to_kdbx4().unwrap();
        let saved = db.save().unwrap();

        let reopened =
            UnlockedDatabase::open(saved, Some(b"test-password".to_vec()), None).unwrap();
        let summary = reopened.summary().unwrap();
        assert_eq!(summary.format_version, "4.0");
        assert!(!summary.needs_upgrade);
        assert_eq!(summary.entry_count, 2);
    }

    #[test]
    fn the_key_hash_opens_the_database_and_survives_a_save() {
        let file = fixture("keyfile-only.kdbx");
        let db = UnlockedDatabase::open(file.clone(), None, Some(fixture("keyfile.keyx"))).unwrap();
        let hash = db.key_hash().unwrap();
        assert_eq!(hash.len(), 32);
        let again = UnlockedDatabase::open_with_key_hash(file, hash.clone()).unwrap();
        assert_eq!(again.summary().unwrap().entry_count, 1);

        // A save makes fresh key derivation salts, and the hash still opens the result.
        let db = open("kdbx4-argon2id-chacha20.kdbx");
        let hash = db.key_hash().unwrap();
        let saved = db.save().unwrap();
        let reopened = UnlockedDatabase::open_with_key_hash(saved, hash).unwrap();
        assert!(!reopened.is_locked());

        db.lock();
        assert!(matches!(db.key_hash(), Err(Error::Locked)));
    }

    #[test]
    fn a_wrong_or_malformed_key_hash_is_a_wrong_key() {
        let file = fixture("pw-only.kdbx");
        for bad in [vec![0u8; 32], vec![0u8; 31], Vec::new()] {
            assert!(matches!(
                UnlockedDatabase::open_with_key_hash(file.clone(), bad),
                Err(Error::WrongKey)
            ));
        }
    }

    #[test]
    fn groups_are_listed_by_id_without_protected_values() {
        let db = open("browse.kdbx");
        let root = db.list_group(None).unwrap();
        assert!(root.is_root && root.parents.is_empty());
        assert_eq!(root.group.id, "01".repeat(16));
        let work = &root.groups[0];
        assert_eq!(
            (work.name.as_str(), work.group_count, work.entry_count),
            ("Work", 1, 1)
        );
        assert!(root.groups[3].is_recycle_bin);
        assert_eq!(root.entries[0].username, "me@example.com");

        let servers = db.list_group(Some("05".repeat(16))).unwrap();
        assert!(!servers.is_root);
        let parents: Vec<_> = servers.parents.iter().map(|g| g.name.as_str()).collect();
        assert_eq!(parents, ["Root", "Work"]);

        // The username of "Crédit Union" is protected, so the list leaves it out.
        let banking = db.list_group(Some(root.groups[1].id.clone())).unwrap();
        assert_eq!(banking.entries[0].username, "");
        assert_eq!(banking.entries[0].url, "https://cu.example.com");

        for bad in ["ee".repeat(16), "nonsense".to_owned()] {
            assert!(matches!(db.list_group(Some(bad)), Err(Error::NotFound)));
        }
    }

    #[test]
    fn search_results_carry_their_group_path() {
        let db = open("browse.kdbx");
        let results = db.search("admin".to_owned()).unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].entry.title, "Database Server");
        assert_eq!(results[0].group_path, ["Work", "Servers"]);
        assert!(db.search("hunter2".to_owned()).unwrap().is_empty());
    }

    #[test]
    fn autofill_matches_cross_the_bridge_without_protected_values() {
        let db = open("autofill.kdbx");
        let web = FillTarget::Web {
            domain: "login.example.com".to_owned(),
            plain_http: false,
        };
        let results = db.autofill_matches(web).unwrap();
        let titles: Vec<_> = results.iter().map(|r| r.entry.title.as_str()).collect();
        assert_eq!(titles, ["Example Login", "Example"]);
        assert_eq!(results[0].group_path, ["Sites"]);
        // "Example"'s username is protected, so the suggestion can't show it.
        assert_eq!(results[1].entry.username, "");
        assert_eq!(
            db.reveal_field(results[1].entry.id.clone(), "UserName".to_owned())
                .unwrap(),
            b"jo-protected"
        );

        let app = FillTarget::App {
            package: "com.example.app".to_owned(),
        };
        let results = db.autofill_matches(app).unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].entry.title, "App Link");
    }

    fn field<'a>(details: &'a EntryDetails, key: &str) -> &'a EntryField {
        details.fields.iter().find(|f| f.key == key).unwrap()
    }

    #[test]
    fn an_entry_comes_without_its_protected_values() {
        let db = open("browse.kdbx");
        let email = db.entry("02".repeat(16)).unwrap();
        assert_eq!(email.title, "Email");
        assert!(email.group_path.is_empty() && !email.is_in_recycle_bin);
        assert_eq!(email.tags, ["personal"]);
        let keys: Vec<_> = email.fields.iter().map(|f| f.key.as_str()).collect();
        assert_eq!(keys, ["UserName", "Password", "URL", "Notes"]);
        assert_eq!(field(&email, "UserName").value, "me@example.com");
        assert_eq!(field(&email, "Notes").value, "Personal inbox");
        let password = field(&email, "Password");
        assert!(password.is_protected && !password.is_empty);
        assert_eq!(password.value, "");

        // Custom fields come after the standard ones, and notes last, even when missing.
        let vpn = db.entry("04".repeat(16)).unwrap();
        let keys: Vec<_> = vpn.fields.iter().map(|f| f.key.as_str()).collect();
        assert_eq!(keys, ["UserName", "Password", "URL", "Account ID", "Notes"]);
        assert_eq!(field(&vpn, "Account ID").value, "W-1234");
        assert!(field(&vpn, "Notes").is_empty);
        assert_eq!(vpn.group_path, ["Work"]);

        // A protected username is held back like a password.
        let bank = db.entry("08".repeat(16)).unwrap();
        assert_eq!(bank.tags, ["bank", "money"]); // KeePassXC sorted them when it wrote the file
        let username = field(&bank, "UserName");
        assert!(username.is_protected && username.value.is_empty() && !username.is_empty);

        // The server's password is empty (KeePassXC wrote it as an empty protected value), and
        // says so without being revealed.
        let server = db.entry("06".repeat(16)).unwrap();
        let password = field(&server, "Password");
        assert!(password.is_empty && password.is_protected);

        assert!(db.entry("0c".repeat(16)).unwrap().is_in_recycle_bin);
        for bad in ["ee".repeat(16), "01".repeat(16), "nonsense".to_owned()] {
            assert!(matches!(db.entry(bad), Err(Error::NotFound)));
        }
    }

    #[test]
    fn totp_codes_cross_the_bridge_as_bytes() {
        let db = open("totp.kdbx");
        let ready = db.entry("21".repeat(16)).unwrap();
        match ready.totp {
            TotpStatus::Ready {
                period_seconds,
                length,
                fields,
            } => {
                assert_eq!((period_seconds, length), (30, 6));
                assert_eq!(fields, ["otp"]);
            }
            _ => panic!("expected TOTP"),
        }
        // The setting fields are still listed, so an edit keeps them.
        assert!(
            ready
                .fields
                .iter()
                .any(|f| f.key == "otp" && f.is_protected && f.value.is_empty())
        );
        assert_eq!(db.totp_code("21".repeat(16), 59).unwrap(), b"287082");

        let broken = db.entry("2c".repeat(16)).unwrap();
        assert!(
            matches!(broken.totp, TotpStatus::Invalid { ref reason } if reason.contains("Base32"))
        );
        assert!(matches!(
            db.totp_code("2c".repeat(16), 59),
            Err(Error::Invalid(_))
        ));

        assert!(matches!(
            db.entry("2e".repeat(16)).unwrap().totp,
            TotpStatus::None
        ));
        assert!(matches!(
            db.totp_code("2e".repeat(16), 59),
            Err(Error::NotFound)
        ));
        db.lock();
        assert!(matches!(
            db.totp_code("21".repeat(16), 59),
            Err(Error::Locked)
        ));
    }

    #[test]
    fn revealing_a_field_gives_its_bytes() {
        let db = open("browse.kdbx");
        let reveal = |id: &str, key: &str| db.reveal_field(id.repeat(16), key.to_owned());
        assert_eq!(reveal("02", "Password").unwrap(), b"hunter2-mailbox");
        assert_eq!(reveal("08", "UserName").unwrap(), b"jo-secret-user");
        assert_eq!(reveal("04", "Account ID").unwrap(), b"W-1234");
        assert_eq!(reveal("08", "Title").unwrap(), "Crédit Union".as_bytes());
        assert!(reveal("06", "Password").unwrap().is_empty());
        assert!(matches!(reveal("06", "Nope"), Err(Error::NotFound)));
        assert!(matches!(reveal("ee", "Password"), Err(Error::NotFound)));
        // History items aren't entries of their own.
        let history_uuid = "02".repeat(16);
        assert_eq!(
            reveal(&history_uuid[..2], "Password").unwrap(),
            b"hunter2-mailbox"
        );
    }

    fn edit(key: &str, value: Option<&str>) -> FieldEdit {
        FieldEdit {
            key: key.to_owned(),
            value: value.map(|v| v.as_bytes().to_vec()),
            previous_key: None,
            is_protected: false,
        }
    }

    #[test]
    fn entries_are_created_and_edited_without_fetching_protected_values() {
        let db = open("kdbx41-keepassxc.kdbx");
        let root = db.list_group(None).unwrap().group.id;
        let id = db
            .create_entry(
                root.clone(),
                vec![edit("Title", Some("Router")), edit("Password", Some("pw"))],
                vec!["home".to_owned()],
            )
            .unwrap();
        let details = db.entry(id.clone()).unwrap();
        assert_eq!(
            (details.title.as_str(), details.tags.as_slice()),
            ("Router", ["home".to_owned()].as_slice())
        );
        assert!(!details.title_is_protected);

        // The password is kept by leaving it null; the custom field is renamed.
        db.update_entry(
            id.clone(),
            vec![edit("Title", Some("Modem")), edit("Password", None)],
            vec![],
        )
        .unwrap();
        assert_eq!(
            db.reveal_field(id.clone(), "Password".to_owned()).unwrap(),
            b"pw"
        );
        assert_eq!(db.entry(id.clone()).unwrap().title, "Modem");
        let mut renamed = edit("Code", None);
        renamed.previous_key = Some("Missing".to_owned());
        assert!(matches!(
            db.update_entry(id.clone(), vec![renamed], vec![]),
            Err(Error::Invalid(_))
        ));
        let bad_utf8 = FieldEdit {
            value: Some(vec![0xff]),
            ..edit("Title", None)
        };
        assert!(matches!(
            db.update_entry(id.clone(), vec![bad_utf8], vec![]),
            Err(Error::Invalid(_))
        ));

        // The change survives a save.
        let saved = db.save().unwrap();
        let reopened =
            UnlockedDatabase::open(saved, Some(b"test-password".to_vec()), None).unwrap();
        assert_eq!(reopened.entry(id).unwrap().title, "Modem");
    }

    #[test]
    fn groups_are_created_moved_and_deleted() {
        let db = open("kdbx41-keepassxc.kdbx");
        let root = db.list_group(None).unwrap().group.id;
        assert!(db.group_details(root.clone()).unwrap().is_root);
        let group = db
            .create_group(root.clone(), "Made".to_owned(), "notes".to_owned())
            .unwrap();
        let inner = db
            .create_group(group.clone(), "Inner".to_owned(), String::new())
            .unwrap();
        let details = db.group_details(group.clone()).unwrap();
        assert_eq!(
            (
                details.name.as_str(),
                details.notes.as_str(),
                details.is_root
            ),
            ("Made", "notes", false)
        );
        assert!(
            db.update_group(group.clone(), "Renamed".to_owned(), String::new())
                .unwrap()
        );
        assert!(matches!(
            db.move_group(group.clone(), inner.clone()),
            Err(Error::Invalid(_))
        ));

        let entry = db
            .create_entry(inner.clone(), vec![edit("Title", Some("Deep"))], vec![])
            .unwrap();
        assert!(db.move_entry(entry.clone(), root.clone()).unwrap());

        let groups = db.all_groups().unwrap();
        let names: Vec<_> = groups.iter().map(|g| (g.name.as_str(), g.depth)).collect();
        assert!(names.contains(&("Renamed", 1)) && names.contains(&("Inner", 2)));
        assert_eq!(groups[0].depth, 0);

        let summary = db.summary().unwrap();
        let expected = if summary.recycle_bin_enabled {
            Deletion::MovedToRecycleBin
        } else {
            Deletion::Deleted
        };
        assert_eq!(db.delete_group(group).unwrap(), expected);
        assert_eq!(db.delete_entry(entry).unwrap(), expected);
        assert!(matches!(db.delete_group(root), Err(Error::Invalid(_))));
    }

    #[test]
    fn locking_wipes_the_database_and_refuses_everything_after() {
        let db = open("kdbx4-argon2id-chacha20.kdbx");
        assert!(!db.is_locked());
        db.lock();
        db.lock();
        assert!(db.is_locked());
        assert!(matches!(db.summary(), Err(Error::Locked)));
        assert!(matches!(db.save(), Err(Error::Locked)));
        assert!(matches!(db.upgrade_to_kdbx4(), Err(Error::Locked)));
        assert!(matches!(db.list_group(None), Err(Error::Locked)));
        assert!(matches!(db.search("x".to_owned()), Err(Error::Locked)));
        let app = |package: &str| FillTarget::App {
            package: package.to_owned(),
        };
        assert!(matches!(db.autofill_matches(app("x")), Err(Error::Locked)));
        assert!(matches!(db.entry("02".repeat(16)), Err(Error::Locked)));
        let any = || "02".repeat(16);
        assert!(matches!(
            db.create_entry(any(), vec![], vec![]),
            Err(Error::Locked)
        ));
        assert!(matches!(
            db.update_entry(any(), vec![], vec![]),
            Err(Error::Locked)
        ));
        assert!(matches!(db.delete_entry(any()), Err(Error::Locked)));
        assert!(matches!(
            db.create_group(any(), String::new(), String::new()),
            Err(Error::Locked)
        ));
        assert!(matches!(db.all_groups(), Err(Error::Locked)));
        assert!(matches!(
            db.reveal_field("02".repeat(16), "Password".to_owned()),
            Err(Error::Locked)
        ));
    }
}
