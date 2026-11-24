//! Saving a login the user typed into another app or a web page, when autofill offers to.
//!
//! Android hands over what was typed in a login or sign-up form, and the app asks the user
//! whether to keep it. This decides what to ask and does the saving:
//!
//! - [`Database::login_candidates`] lists the entries already linked to that app or site (the
//!   same ones autofill offers there), each saying whether it has the same username, and the
//!   same password too. When one has both, there's nothing to save and the app asks nothing.
//! - [`Database::save_login`] puts the login in a new entry, linked to the app or site so
//!   autofill offers it there next time, or into one of those entries, whose previous version
//!   goes into its history like any edit.
//!
//! A new entry is linked in the ways autofill reads (`autofill.rs`):
//!
//! - a website: its URL, `https://host` (or `http://` for a page that came over plain http);
//! - an app: the URL `androidapp://<package>` (Keepass2Android's convention). Not KeePassDX's
//!   `AndroidApp` field: allowing an app to use a passkey adds certificates next to that field,
//!   which turns it into a passkey permission and would quietly end the autofill link;
//! - a web page in an app that isn't a verified browser: the site's URL, and the app in a
//!   `KP2A_URL` field, since such a page is only offered entries linked to both.

use crate::autofill::{FillTarget, normalize_host};
use crate::browse::SearchHit;
use crate::edit::check_text;
use crate::error::{Error, Result};
use crate::model::Database;
use crate::passkey::{Field, edit_from, keep_all, put, set};
use crate::{Entry, EntryEdit};

/// An entry already linked to where the login was typed.
pub struct LoginCandidate<'a> {
    pub hit: SearchHit<'a>,
    /// The entry's username is the one typed (ignoring spaces around either).
    pub same_username: bool,
    /// And its password is the one typed, exactly.
    pub same_password: bool,
}

/// Where a typed login goes.
pub enum LoginPlace<'a> {
    /// A new entry in this group. An empty title is replaced by the site or app.
    NewEntry { group: [u8; 16], title: &'a str },
    /// An existing entry: its password is replaced, and its username too unless none was typed.
    Entry([u8; 16]),
}

/// Custom fields holding more URLs for an entry, as autofill reads them.
const EXTRA_URL_FIELD: &str = "KP2A_URL";

impl Database {
    /// The entries linked to `target` (as [`autofill_matches`](Self::autofill_matches) finds
    /// them, best first), with how each compares to the typed login. Protected usernames and
    /// passwords are compared here, inside the engine; neither crosses to the app.
    pub fn login_candidates(
        &self,
        target: FillTarget<'_>,
        username: &str,
        password: &str,
    ) -> Vec<LoginCandidate<'_>> {
        let username = username.trim();
        self.autofill_matches(target)
            .into_iter()
            .map(|hit| {
                let same_username = hit.entry.username().trim() == username;
                let same_password = same_username && hit.entry.password() == password;
                LoginCandidate {
                    hit,
                    same_username,
                    same_password,
                }
            })
            .collect()
    }

    /// Saves a typed login, in a new entry or an existing one, and returns that entry's UUID.
    /// Fails with [`Error::Invalid`] without a password, or for a target with nothing to link
    /// to (no host, no package name). Only in memory until saved, like every change.
    pub fn save_login(
        &mut self,
        target: FillTarget<'_>,
        place: LoginPlace<'_>,
        username: &str,
        password: &str,
    ) -> Result<[u8; 16]> {
        if password.is_empty() {
            return Err(Error::Invalid("There's no password to save.".into()));
        }
        check_text("The username", username)?;
        check_text("The password", password)?;
        let username = username.trim();
        match place {
            LoginPlace::NewEntry { group, title } => {
                let links = links(target)?;
                let title = match title.trim() {
                    "" => default_title(target)?,
                    title => title.to_owned(),
                };
                let mut fields = vec![
                    set("Title", title, false),
                    set("UserName", username, false),
                    set("Password", password, true),
                    set("URL", links.url, false),
                ];
                if let Some(app) = links.app {
                    fields.push(set(&free_extra_url(&fields), app, false));
                }
                let edit = EntryEdit {
                    fields: edit_from(&fields),
                    tags: Vec::new(),
                };
                self.create_entry(&group, &edit)
            }
            LoginPlace::Entry(uuid) => {
                let hit = self.find_entry(&uuid).ok_or(Error::NotFound)?;
                let mut fields = keep_all(&hit.entry);
                if !username.is_empty() {
                    put(&mut fields, set("UserName", username, false));
                }
                put(&mut fields, set("Password", password, true));
                let tags = tags_of(&hit.entry);
                let edit = EntryEdit {
                    fields: edit_from(&fields),
                    tags: tags.iter().map(String::as_str).collect(),
                };
                self.update_entry(&uuid, &edit)?;
                Ok(uuid)
            }
        }
    }
}

/// What links a new entry to the target.
struct Links {
    url: String,
    /// `androidapp://<package>`, for a web page in an app: the entry has to be linked to both.
    app: Option<String>,
}

fn links(target: FillTarget<'_>) -> Result<Links> {
    let web = |domain: &str, plain_http: bool| -> Result<String> {
        let host = host(domain)?;
        let scheme = if plain_http { "http" } else { "https" };
        Ok(format!("{scheme}://{host}"))
    };
    Ok(match target {
        FillTarget::App { package } => Links {
            url: app_url(package)?,
            app: None,
        },
        FillTarget::Web { domain, plain_http } => Links {
            url: web(domain, plain_http)?,
            app: None,
        },
        FillTarget::AppWeb {
            package,
            domain,
            plain_http,
        } => Links {
            url: web(domain, plain_http)?,
            app: Some(app_url(package)?),
        },
    })
}

/// The host as the page reported it, in the form autofill compares hosts in. `www.` is kept:
/// it's what the site uses, and matching ignores it anyway.
fn host(domain: &str) -> Result<String> {
    let lower = domain.trim().trim_end_matches('.').to_lowercase();
    normalize_host(&lower)
        .map(|_| lower)
        .ok_or_else(|| Error::Invalid("The website's address can't be saved.".into()))
}

fn app_url(package: &str) -> Result<String> {
    let package = package.trim();
    let valid = !package.is_empty()
        && package
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_');
    if !valid {
        return Err(Error::Invalid("The app's name can't be saved.".into()));
    }
    Ok(format!("androidapp://{package}"))
}

/// A title for a new entry when the user gave none: the site without `www.`, or the app's
/// package name.
fn default_title(target: FillTarget<'_>) -> Result<String> {
    match target {
        FillTarget::App { package } => Ok(package.trim().to_owned()),
        FillTarget::Web { domain, .. } | FillTarget::AppWeb { domain, .. } => {
            let host = host(domain)?;
            Ok(host.strip_prefix("www.").unwrap_or(&host).to_owned())
        }
    }
}

/// `KP2A_URL`, or `KP2A_URL_1` and so on if that's taken.
fn free_extra_url(fields: &[Field]) -> String {
    (0..)
        .map(|n| match n {
            0 => EXTRA_URL_FIELD.to_owned(),
            n => format!("{EXTRA_URL_FIELD}_{n}"),
        })
        .find(|key| !fields.iter().any(|f| &f.key == key))
        .expect("some name is free")
}

fn tags_of(entry: &Entry<'_>) -> Vec<String> {
    entry
        .tags()
        .split([';', ','])
        .map(str::trim)
        .filter(|t| !t.is_empty())
        .map(str::to_owned)
        .collect()
}
