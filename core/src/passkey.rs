//! Passkeys kept in entries, in the format KeePassXC and KeePassDX share, so a passkey made by
//! any of them works in the others.
//!
//! Each entry holds at most one passkey, as custom fields:
//!
//! | Field | Protected | Value |
//! |---|---|---|
//! | `KPEX_PASSKEY_USERNAME` | no | the account name |
//! | `KPEX_PASSKEY_CREDENTIAL_ID` | yes | base64url |
//! | `KPEX_PASSKEY_PRIVATE_KEY_PEM` | yes | PKCS#8 PEM |
//! | `KPEX_PASSKEY_RELYING_PARTY` | no | the site's rpId, e.g. `example.com` |
//! | `KPEX_PASSKEY_USER_HANDLE` | yes | base64url, the site's ID for the account |
//! | `KPEX_PASSKEY_FLAG_BE`, `_BS` | no | `1`: may leave the device, has been backed up |
//!
//! Android apps are linked to an entry the way KeePassDX does it: `AndroidApp` (then
//! `AndroidApp_1`, `AndroidApp_2`, …) holds a package name, and `AndroidApp Signature` (with the
//! same suffix, protected) the SHA-256 fingerprints of its signing certificates, as uppercase
//! hex with colons, separated by `##SIG##`. Without internet access we can't ask the site which
//! apps it trusts, so the user decides once per app, and from then on only an app signed with
//! one of those certificates can use the passkey.

use p256::ecdsa::SigningKey;
use p256::pkcs8::{DecodePrivateKey, EncodePrivateKey, LineEnding, PrivateKeyInfoRef};
use sha2::Sha256;
use zeroize::Zeroizing;

use crate::FillTarget;
use crate::browse::SearchHit;
use crate::edit::{EntryEdit, FieldEdit, FieldValue};
use crate::error::{Error, Result};
use crate::model::{Database, Entry, Group};
use crate::util::random_bytes;
use crate::webauthn::{
    AssertionRequest, B64URL, Client, CreationRequest, Credential, PasskeyKey, assertion_response,
    decode_b64, registration_response,
};
use crate::xml::Element;
use base64::Engine;

/// Every passkey field starts with this.
const PREFIX: &str = "KPEX_PASSKEY";
pub const PASSKEY_USERNAME: &str = "KPEX_PASSKEY_USERNAME";
pub const PASSKEY_CREDENTIAL_ID: &str = "KPEX_PASSKEY_CREDENTIAL_ID";
pub const PASSKEY_PRIVATE_KEY: &str = "KPEX_PASSKEY_PRIVATE_KEY_PEM";
pub const PASSKEY_RELYING_PARTY: &str = "KPEX_PASSKEY_RELYING_PARTY";
pub const PASSKEY_USER_HANDLE: &str = "KPEX_PASSKEY_USER_HANDLE";
const PASSKEY_FLAG_BE: &str = "KPEX_PASSKEY_FLAG_BE";
const PASSKEY_FLAG_BS: &str = "KPEX_PASSKEY_FLAG_BS";
/// Read only: KeePassXC's names for passkeys imported from StrongBox, which take precedence.
const STRONGBOX_CREDENTIAL_ID: &str = "KPEX_PASSKEY_GENERATED_USER_ID";
const STRONGBOX_USERNAME: &str = "KPXC_PASSKEY_USERNAME";

pub const APP_FIELD: &str = "AndroidApp";
pub const APP_SIGNATURE_FIELD: &str = "AndroidApp Signature";
const SIGNATURE_SEPARATOR: &str = "##SIG##";

/// The tag new passkey entries get, as in KeePassXC and KeePassDX.
const PASSKEY_TAG: &str = "Passkey";

const PEM_BEGIN: &str = "-----BEGIN PRIVATE KEY-----";
const PEM_END: &str = "-----END PRIVATE KEY-----";

/// Whether a passkey can be used.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PasskeyStatus {
    Ready,
    /// It can't, with a sentence saying why (another key type, a damaged key).
    Unusable(String),
}

/// Whether the app asking may use a passkey.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AppTrust {
    /// A verified browser: the site's own checks apply, nothing to link.
    Browser,
    /// The app is linked to the entry, with a matching signing certificate.
    Linked,
    /// The app isn't linked yet. Ask the user before linking it.
    NotLinked,
    /// An app with this package name is linked, but signed with another certificate. This is
    /// what an impostor looks like, so it's refused, not asked about.
    OtherSigner,
}

/// A passkey offered for a sign-in, and where its entry lives. Nothing secret.
pub struct PasskeyHit<'a> {
    pub hit: SearchHit<'a>,
    /// The site the passkey is for, lowercase.
    pub rp_id: String,
    /// The account name, as the site gave it.
    pub username: &'a str,
    pub status: PasskeyStatus,
    pub trust: AppTrust,
}

/// What the app shows before creating a passkey.
pub struct CreationSummary<'a> {
    pub rp_id: String,
    /// The site's name for itself. May be empty.
    pub rp_name: String,
    pub user_name: String,
    pub user_display_name: String,
    /// Entries for the same site without a passkey yet, that the new one could be added to.
    pub candidates: Vec<SearchHit<'a>>,
}

/// Where a new passkey goes.
#[derive(Clone, Copy, Debug)]
pub enum PasskeyTarget {
    /// A new entry in this group.
    NewEntry { group: [u8; 16] },
    /// An existing entry that has no passkey yet.
    Entry([u8; 16]),
}

pub struct CreatedPasskey {
    pub entry: [u8; 16],
    /// The `RegistrationResponseJSON` to hand back to the site. Not secret.
    pub response_json: String,
}

/// How to take back the last passkey created (see [`Database::undo_passkey`]).
pub(crate) struct Undo {
    entry: [u8; 16],
    /// The entry before the passkey was added, or `None` if the entry is new.
    before: Option<Element>,
}

/// A passkey as stored in an entry. Holds plaintext secrets (borrowed from the open database).
struct Stored<'a> {
    rp_id: String,
    username: &'a str,
    credential_id: Vec<u8>,
    user_handle: &'a str,
    pem: &'a str,
    backup_eligible: bool,
    backed_up: bool,
}

/// An entry's passkey, for the entry screen. Nothing secret.
pub struct PasskeyView<'a> {
    pub rp_id: String,
    pub username: &'a str,
    pub status: PasskeyStatus,
    /// The entry's fields that make up the passkey and its app links, which the entry screen
    /// can leave out since it shows the passkey instead. An edit must keep them.
    pub fields: Vec<&'a str>,
}

/// The entry's passkey, if it has one. An entry with passkey fields that don't make a passkey
/// (some missing) has none.
pub fn passkey_of<'a>(entry: &Entry<'a>) -> Option<PasskeyView<'a>> {
    let passkey = stored(entry)?;
    Some(PasskeyView {
        status: match load_key(passkey.pem) {
            Ok(_) => PasskeyStatus::Ready,
            Err(reason) => PasskeyStatus::Unusable(reason),
        },
        rp_id: passkey.rp_id,
        username: passkey.username,
        fields: entry.field_keys().filter(|k| is_passkey_field(k)).collect(),
    })
}

/// Whether an entry has any passkey fields at all, usable or not.
pub fn has_passkey(entry: &Entry<'_>) -> bool {
    entry.field_keys().any(|k| k.starts_with(PREFIX))
}

/// Whether a field is part of a passkey or an app link: the app hides these from the entry's
/// field list and shows the passkey as a whole instead.
pub fn is_passkey_field(key: &str) -> bool {
    key.starts_with(PREFIX) || key == STRONGBOX_USERNAME || key.starts_with(APP_SIGNATURE_FIELD)
}

fn stored<'a>(entry: &Entry<'a>) -> Option<Stored<'a>> {
    let field = |key| entry.field(key).filter(|v| !v.trim().is_empty());
    let rp_id = field(PASSKEY_RELYING_PARTY)?.trim().to_ascii_lowercase();
    let credential_id = field(STRONGBOX_CREDENTIAL_ID).or_else(|| field(PASSKEY_CREDENTIAL_ID))?;
    let flag =
        |key| field(key).is_none_or(|v| v.trim() == "1" || v.trim().eq_ignore_ascii_case("true"));
    Some(Stored {
        rp_id,
        username: field(STRONGBOX_USERNAME)
            .or_else(|| field(PASSKEY_USERNAME))
            .unwrap_or(""),
        credential_id: decode_b64(credential_id)?,
        user_handle: field(PASSKEY_USER_HANDLE).unwrap_or(""),
        pem: field(PASSKEY_PRIVATE_KEY)?,
        backup_eligible: flag(PASSKEY_FLAG_BE),
        backed_up: flag(PASSKEY_FLAG_BS),
    })
}

/// The PEM rebuilt from just its base64, wrapped at 64 columns. Other apps sometimes store it
/// without the line breaks.
fn normalize_pem(pem: &str) -> Option<Zeroizing<String>> {
    let start = pem.find(PEM_BEGIN)? + PEM_BEGIN.len();
    let end = start + pem[start..].find(PEM_END)?;
    let body: Zeroizing<String> = Zeroizing::new(
        pem[start..end]
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect(),
    );
    let mut out = Zeroizing::new(String::with_capacity(body.len() + body.len() / 64 + 64));
    out.push_str(PEM_BEGIN);
    out.push('\n');
    for line in body.as_bytes().chunks(64) {
        out.push_str(std::str::from_utf8(line).ok()?);
        out.push('\n');
    }
    out.push_str(PEM_END);
    out.push('\n');
    Some(out)
}

/// RSA keys shorter than this aren't used: 2048 bits is what KeePassXC makes, and the least any
/// current guidance accepts.
const MIN_RSA_BITS: usize = 2048;

/// The signing key, or a sentence saying why it can't be used.
fn load_key(pem: &str) -> std::result::Result<PasskeyKey, String> {
    let damaged = || "This passkey's key is damaged.".to_owned();
    let pem = normalize_pem(pem).ok_or_else(damaged)?;
    if let Ok(key) = SigningKey::from_pkcs8_pem(&pem) {
        return Ok(PasskeyKey::Es256(key));
    }
    if let Ok(key) =
        <ed25519_dalek::SigningKey as ed25519_dalek::pkcs8::DecodePrivateKey>::from_pkcs8_pem(&pem)
    {
        return Ok(PasskeyKey::EdDsa(key));
    }
    if let Ok(key) = <rsa::PrivateKey as rsa::pkcs8::DecodePrivateKey>::from_pkcs8_pem(&pem) {
        let bits = rsa::traits::PublicKeyParts::size(&key) * 8;
        if bits < MIN_RSA_BITS {
            return Err(format!(
                "This passkey's RSA key is too short ({bits} bits) to be safe."
            ));
        }
        return Ok(PasskeyKey::Rs256(rsa::pkcs1v15::SigningKey::<Sha256>::new(
            key,
        )));
    }
    // Say which kind of key it is, if it's a kind we know of.
    let body: Zeroizing<String> = Zeroizing::new(
        pem.lines()
            .filter(|l| !l.starts_with("-----"))
            .collect::<String>(),
    );
    let der = Zeroizing::new(decode_b64(&body).ok_or_else(damaged)?);
    let info = PrivateKeyInfoRef::try_from(der.as_slice()).map_err(|_| damaged())?;
    let kind = match info.algorithm.oid.to_string().as_str() {
        "1.2.840.10045.2.1" => "an elliptic curve other than P-256",
        "2.16.840.1.101.3.4.3.17" | "2.16.840.1.101.3.4.3.18" | "2.16.840.1.101.3.4.3.19" => {
            "ML-DSA"
        }
        "1.3.101.113" => "Ed448",
        _ => return Err(damaged()),
    };
    Err(format!(
        "This passkey uses {kind} keys, which PocketKeePass can't use yet."
    ))
}

/// The app links stored in an entry: (package, certificate fingerprints), one per suffix.
fn app_links<'a>(entry: &Entry<'a>) -> Vec<(String, &'a str, Vec<[u8; 32]>)> {
    entry
        .field_keys()
        .filter_map(|key| {
            let suffix = key.strip_prefix(APP_FIELD)?;
            (suffix.is_empty() || suffix.starts_with('_')).then_some(suffix)
        })
        .filter_map(|suffix| {
            let package = entry.field(&format!("{APP_FIELD}{suffix}"))?.trim();
            let signatures = entry
                .field(&format!("{APP_SIGNATURE_FIELD}{suffix}"))
                .map(parse_fingerprints)
                .unwrap_or_default();
            Some((suffix.to_owned(), package, signatures))
        })
        .collect()
}

fn parse_fingerprints(text: &str) -> Vec<[u8; 32]> {
    text.split(SIGNATURE_SEPARATOR)
        .filter_map(|fp| {
            let hex: String = fp
                .chars()
                .filter(|c| *c != ':' && !c.is_whitespace())
                .collect();
            crate::util::hex_decode(&hex)?.try_into().ok()
        })
        .collect()
}

fn format_fingerprints(certificates: &[[u8; 32]]) -> String {
    certificates
        .iter()
        .map(|c| {
            c.iter()
                .map(|b| format!("{b:02X}"))
                .collect::<Vec<_>>()
                .join(":")
        })
        .collect::<Vec<_>>()
        .join(SIGNATURE_SEPARATOR)
}

fn trust(entry: &Entry<'_>, client: &Client<'_>) -> AppTrust {
    let Client::App {
        package,
        certificates,
    } = client
    else {
        return AppTrust::Browser;
    };
    let mut result = AppTrust::NotLinked;
    for (_, linked, signatures) in app_links(entry) {
        if linked != *package || signatures.is_empty() {
            continue; // an app linked for autofill only, without a certificate: ask
        }
        if signatures.iter().any(|s| certificates.contains(s)) {
            return AppTrust::Linked;
        }
        result = AppTrust::OtherSigner;
    }
    result
}

/// One field of an edit, owning its value.
struct Field {
    key: String,
    value: Spec,
    protected: bool,
}

enum Spec {
    Keep,
    Set(Zeroizing<String>),
}

fn set(key: &str, value: impl Into<String>, protected: bool) -> Field {
    Field {
        key: key.to_owned(),
        value: Spec::Set(Zeroizing::new(value.into())),
        protected,
    }
}

/// Every field of an existing entry, kept as it is.
fn keep_all(entry: &Entry<'_>) -> Vec<Field> {
    entry
        .fields()
        .map(|f| Field {
            key: f.key.to_owned(),
            value: Spec::Keep,
            protected: f.protected,
        })
        .collect()
}

/// Sets a field, replacing one with the same name or adding it at the end.
fn put(fields: &mut Vec<Field>, field: Field) {
    match fields.iter_mut().find(|f| f.key == field.key) {
        Some(existing) => *existing = field,
        None => fields.push(field),
    }
}

/// Links an app: adds its certificates to the link with its package name, or adds a new link
/// under the next free suffix.
fn link_fields(
    fields: &mut Vec<Field>,
    entry: Option<&Entry<'_>>,
    package: &str,
    certificates: &[[u8; 32]],
) {
    let links = entry.map(app_links).unwrap_or_default();
    let (suffix, mut known) = match links.iter().find(|(_, p, _)| *p == package) {
        Some((suffix, _, signatures)) => (suffix.clone(), signatures.clone()),
        None => {
            let taken = |s: &str| fields.iter().any(|f| f.key == format!("{APP_FIELD}{s}"));
            let suffix = (0..)
                .map(|n| {
                    if n == 0 {
                        String::new()
                    } else {
                        format!("_{n}")
                    }
                })
                .find(|s| !taken(s))
                .expect("some suffix is free");
            (suffix, Vec::new())
        }
    };
    for certificate in certificates {
        if !known.contains(certificate) {
            known.push(*certificate);
        }
    }
    put(fields, set(&format!("{APP_FIELD}{suffix}"), package, false));
    put(
        fields,
        set(
            &format!("{APP_SIGNATURE_FIELD}{suffix}"),
            format_fingerprints(&known),
            true,
        ),
    );
}

fn edit_from(fields: &[Field]) -> Vec<FieldEdit<'_>> {
    fields
        .iter()
        .map(|f| FieldEdit {
            key: &f.key,
            value: match &f.value {
                Spec::Keep => FieldValue::Keep { from: &f.key },
                Spec::Set(value) => FieldValue::Set(value),
            },
            protected: f.protected,
        })
        .collect()
}

fn tags_with_passkey(tags: &str) -> Vec<String> {
    let mut tags: Vec<String> = tags
        .split([';', ','])
        .map(str::trim)
        .filter(|t| !t.is_empty())
        .map(str::to_owned)
        .collect();
    if !tags.iter().any(|t| t.eq_ignore_ascii_case(PASSKEY_TAG)) {
        tags.push(PASSKEY_TAG.to_owned());
    }
    tags
}

/// A new P-256 key from the system's random number generator.
fn new_key() -> Result<SigningKey> {
    // A random 32-byte string is a valid key unless it's 0 or at least the group order, which
    // happens with probability about 2^-32.
    for _ in 0..8 {
        if let Ok(key) = SigningKey::from_slice(&random_bytes(32)?) {
            return Ok(key);
        }
    }
    Err(Error::Random)
}

impl Database {
    /// The passkeys a sign-in request can use, in file order: those for the request's site and,
    /// if the site lists the passkeys it accepts, only those. Entry history, the recycle bin, and
    /// groups hidden from search are left out, as in search and autofill.
    pub fn passkeys_for(
        &self,
        request_json: &str,
        client: &Client<'_>,
    ) -> Result<Vec<PasskeyHit<'_>>> {
        let request = AssertionRequest::parse(request_json)?;
        let mut hits = Vec::new();
        self.for_each_searchable(|entry, path| {
            let Some(passkey) = stored(&entry) else {
                return;
            };
            if passkey.rp_id != request.rp_id
                || (!request.allow.is_empty() && !request.allow.contains(&passkey.credential_id))
            {
                return;
            }
            let status = match load_key(passkey.pem) {
                Ok(_) => PasskeyStatus::Ready,
                Err(reason) => PasskeyStatus::Unusable(reason),
            };
            hits.push(PasskeyHit {
                rp_id: passkey.rp_id.clone(),
                username: passkey.username,
                status,
                trust: trust(&entry, client),
                hit: SearchHit {
                    entry,
                    path: path.to_vec(),
                },
            });
        });
        Ok(hits)
    }

    /// Checks a creation request and says what it's for, before the user is asked. Fails with
    /// [`Error::PasskeyUnsupported`] if the site accepts no key type we can make, and
    /// [`Error::PasskeyExists`] if one of the passkeys the site already has for this account
    /// is in the database.
    pub fn passkey_creation(&self, request_json: &str) -> Result<CreationSummary<'_>> {
        let request = CreationRequest::parse(request_json)?;
        self.check_creation(&request)?;
        let mut candidates = self.autofill_matches(FillTarget::Web {
            domain: &request.rp_id,
            plain_http: false,
        });
        candidates.retain(|hit| !has_passkey(&hit.entry));
        Ok(CreationSummary {
            rp_id: request.rp_id,
            rp_name: request.rp_name,
            user_name: request.user_name,
            user_display_name: request.user_display_name,
            candidates,
        })
    }

    fn check_creation(&self, request: &CreationRequest) -> Result<()> {
        if !request.accepts_es256 {
            return Err(Error::PasskeyUnsupported(
                "This site only accepts kinds of passkey PocketKeePass can't make yet.".into(),
            ));
        }
        if request.exclude.is_empty() {
            return Ok(());
        }
        // Any copy counts, even in the recycle bin or history: the site has it.
        let mut exists = false;
        self.for_each_entry_and_history(|entry| {
            exists |= stored(&entry).is_some_and(|p| {
                p.rp_id == request.rp_id && request.exclude.contains(&p.credential_id)
            });
        });
        if exists {
            Err(Error::PasskeyExists)
        } else {
            Ok(())
        }
    }

    /// Makes a new passkey for the request and stores it, in a new entry or an existing one
    /// without a passkey. An app (not a browser) is linked to the entry at the same time: the
    /// user agreed to give it this passkey by creating it.
    ///
    /// If saving the database then fails, call [`undo_passkey`](Self::undo_passkey), so the
    /// site doesn't end up holding a passkey we didn't keep.
    pub fn create_passkey(
        &mut self,
        target: PasskeyTarget,
        request_json: &str,
        client: &Client<'_>,
    ) -> Result<CreatedPasskey> {
        let request = CreationRequest::parse(request_json)?;
        self.check_creation(&request)?;

        let new = new_key()?;
        let pem = new
            .to_pkcs8_pem(LineEnding::LF)
            .map_err(|_| Error::Invalid("The new passkey's key couldn't be encoded.".into()))?;
        let key = PasskeyKey::Es256(new);
        let credential_id = random_bytes(32)?;
        let credential = Credential {
            id: &credential_id,
            key: &key,
            backup_eligible: true,
            backed_up: true,
        };
        let response_json = registration_response(&request, &credential, client)?;

        let passkey_fields = [
            set(PASSKEY_USERNAME, request.user_name.as_str(), false),
            set(PASSKEY_CREDENTIAL_ID, B64URL.encode(&*credential_id), true),
            Field {
                key: PASSKEY_PRIVATE_KEY.into(),
                value: Spec::Set(pem),
                protected: true,
            },
            set(PASSKEY_RELYING_PARTY, request.rp_id.as_str(), false),
            set(PASSKEY_USER_HANDLE, B64URL.encode(&request.user_id), true),
            set(PASSKEY_FLAG_BE, "1", false),
            set(PASSKEY_FLAG_BS, "1", false),
        ];

        let (entry, before) = match target {
            PasskeyTarget::NewEntry { group } => {
                let title = if request.rp_name.is_empty() {
                    &request.rp_id
                } else {
                    &request.rp_name
                };
                let mut fields = vec![
                    set("Title", title.as_str(), false),
                    set("UserName", request.user_name.as_str(), false),
                    set("URL", format!("https://{}", request.rp_id), false),
                ];
                fields.extend(passkey_fields);
                if let Client::App {
                    package,
                    certificates,
                } = client
                {
                    link_fields(&mut fields, None, package, certificates);
                }
                let edit = EntryEdit {
                    fields: edit_from(&fields),
                    tags: vec![PASSKEY_TAG],
                };
                (self.create_entry(&group, &edit)?, None)
            }
            PasskeyTarget::Entry(uuid) => {
                let hit = self.find_entry(&uuid).ok_or(Error::NotFound)?;
                let entry = hit.entry;
                if has_passkey(&entry) {
                    return Err(Error::Invalid("That entry already has a passkey.".into()));
                }
                let mut fields = keep_all(&entry);
                if entry.username().trim().is_empty() {
                    put(
                        &mut fields,
                        set("UserName", request.user_name.as_str(), false),
                    );
                }
                for field in passkey_fields {
                    put(&mut fields, field);
                }
                if let Client::App {
                    package,
                    certificates,
                } = client
                {
                    link_fields(&mut fields, Some(&entry), package, certificates);
                }
                let tags = tags_with_passkey(entry.tags());
                let edit = EntryEdit {
                    fields: edit_from(&fields),
                    tags: tags.iter().map(String::as_str).collect(),
                };
                let before = self.entry_snapshot(&uuid);
                self.update_entry(&uuid, &edit)?;
                (uuid, before)
            }
        };
        self.passkey_undo = Some(Undo { entry, before });
        Ok(CreatedPasskey {
            entry,
            response_json,
        })
    }

    /// Takes back the passkey [`create_passkey`](Self::create_passkey) made last, for when the
    /// database couldn't be saved with it: a new entry is removed, an existing one goes back to
    /// how it was. Does nothing if `entry` isn't where the last passkey went.
    pub fn undo_passkey(&mut self, entry: &[u8; 16]) -> Result<bool> {
        match self.passkey_undo.take() {
            Some(undo) if undo.entry == *entry => {
                self.restore_entry(&undo.entry, undo.before)?;
                Ok(true)
            }
            other => {
                self.passkey_undo = other;
                Ok(false)
            }
        }
    }

    /// Signs a sign-in request with an entry's passkey and returns the
    /// `AuthenticationResponseJSON` for the site. The app must have asked the user first (the
    /// response says the user was verified), and an app must be linked to the entry.
    pub fn assert_passkey(
        &self,
        entry: &[u8; 16],
        request_json: &str,
        client: &Client<'_>,
    ) -> Result<String> {
        let request = AssertionRequest::parse(request_json)?;
        let hit = self.find_entry(entry).ok_or(Error::NotFound)?;
        if hit.path.iter().any(|g| self.is_recycle_bin(g)) {
            return Err(Error::NotFound);
        }
        let passkey = stored(&hit.entry)
            .ok_or_else(|| Error::Invalid("That entry has no passkey.".into()))?;
        if passkey.rp_id != request.rp_id {
            return Err(Error::Invalid("That passkey is for another site.".into()));
        }
        if !request.allow.is_empty() && !request.allow.contains(&passkey.credential_id) {
            return Err(Error::Invalid(
                "The site didn't ask for that passkey.".into(),
            ));
        }
        match trust(&hit.entry, client) {
            AppTrust::Browser | AppTrust::Linked => {}
            AppTrust::NotLinked | AppTrust::OtherSigner => {
                return Err(Error::Invalid(
                    "This app isn't linked to that passkey.".into(),
                ));
            }
        }
        let key = load_key(passkey.pem).map_err(Error::PasskeyUnsupported)?;
        let user_handle = Zeroizing::new(decode_b64(passkey.user_handle).unwrap_or_default());
        let credential = Credential {
            id: &passkey.credential_id,
            key: &key,
            backup_eligible: passkey.backup_eligible,
            backed_up: passkey.backed_up,
        };
        assertion_response(&request, &credential, &user_handle, client)
    }

    /// Links an app to an entry, after the user agreed: from then on that app, signed with one
    /// of these certificates, may use the entry's passkey (and autofill offers the entry to it).
    /// Returns false if it was already linked with all of them.
    pub fn link_app(
        &mut self,
        entry: &[u8; 16],
        package: &str,
        certificates: &[[u8; 32]],
    ) -> Result<bool> {
        if package.trim().is_empty() || certificates.is_empty() {
            return Err(Error::Invalid(
                "The app has no package name or certificate.".into(),
            ));
        }
        let hit = self.find_entry(entry).ok_or(Error::NotFound)?;
        let mut fields = keep_all(&hit.entry);
        link_fields(&mut fields, Some(&hit.entry), package.trim(), certificates);
        let tags: Vec<String> = hit
            .entry
            .tags()
            .split([';', ','])
            .map(str::to_owned)
            .collect();
        let edit = EntryEdit {
            fields: edit_from(&fields),
            tags: tags.iter().map(String::as_str).collect(),
        };
        self.update_entry(entry, &edit)
    }

    /// Visits every entry, including the recycle bin and every entry's history.
    fn for_each_entry_and_history<'a>(&'a self, mut visit: impl FnMut(Entry<'a>)) {
        fn walk<'a>(group: Group<'a>, visit: &mut impl FnMut(Entry<'a>)) {
            for entry in group.entries() {
                visit(entry);
                entry.history().for_each(&mut *visit);
            }
            for child in group.groups() {
                walk(child, visit);
            }
        }
        walk(self.root(), &mut visit);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pem_without_line_breaks_is_repaired() {
        let key = SigningKey::from_slice(&[3; 32]).unwrap();
        let pem = key.to_pkcs8_pem(LineEnding::LF).unwrap();
        let squashed: String = pem.chars().filter(|c| *c != '\n').collect();
        assert!(load_key(&squashed).is_ok());
        assert_eq!(*normalize_pem(&squashed).unwrap(), *pem);
    }

    #[test]
    fn key_types_are_recognized() {
        // An Ed25519 PKCS#8 key (RFC 8410's example).
        let ed25519 = "-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEINTuctv5E1hK1bbY8fdp+K06/nwoy/HU++CXqI9EdVhC\n-----END PRIVATE KEY-----";
        assert!(matches!(load_key(ed25519), Ok(PasskeyKey::EdDsa(_))));
        // An Ed448 key (RFC 8410's example) is named, not called damaged.
        let ed448 = "-----BEGIN PRIVATE KEY-----\nMEcCAQAwBQYDK2VxBDsEOWyCpWLLgI0Q1jK+ichRPr9Bn1Cp4cXpGv/fhrQvl5Z8\nnCMR2pXzLqrlxomZ/ldwmJSNuhJsl6iEZw==\n-----END PRIVATE KEY-----";
        assert_eq!(
            load_key(ed448).err().unwrap(),
            "This passkey uses Ed448 keys, which PocketKeePass can't use yet."
        );
        assert_eq!(
            load_key("nonsense").err().unwrap(),
            "This passkey's key is damaged."
        );
    }

    #[test]
    fn fingerprints_round_trip_in_keepassdx_form() {
        let certs = [[0xab; 32], [0x01; 32]];
        let text = format_fingerprints(&certs);
        assert!(text.starts_with("AB:AB:"));
        assert_eq!(text.matches(SIGNATURE_SEPARATOR).count(), 1);
        assert_eq!(parse_fingerprints(&text), certs);
        assert_eq!(parse_fingerprints(&text.replace("AB", "ab")), certs);
    }

    #[test]
    fn passkey_fields_are_recognized() {
        assert!(is_passkey_field("KPEX_PASSKEY_PRIVATE_KEY_PEM"));
        assert!(is_passkey_field("AndroidApp Signature_2"));
        assert!(!is_passkey_field("AndroidApp"));
        assert!(!is_passkey_field("otp"));
    }
}
