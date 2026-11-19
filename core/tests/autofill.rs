//! Matching entries to apps and web pages for autofill, on `autofill.kdbx` (see
//! `fixtures/README.md`).

mod common;

use common::{PASSWORD, assert_matches_keepassxc_export, open};
use pocketkeepass_core::{Database, FillTarget};

fn database() -> Database {
    open("autofill.kdbx", Some(PASSWORD), None).unwrap()
}

fn titles(db: &Database, target: FillTarget<'_>) -> Vec<String> {
    db.autofill_matches(target)
        .iter()
        .map(|hit| hit.entry.title().to_owned())
        .collect()
}

fn web(db: &Database, domain: &str) -> Vec<String> {
    let plain_http = false;
    titles(db, FillTarget::Web { domain, plain_http })
}

fn app(db: &Database, package: &str) -> Vec<String> {
    titles(db, FillTarget::App { package })
}

const NOTHING: [&str; 0] = [];

#[test]
fn the_fixture_matches_keepassxc() {
    assert_matches_keepassxc_export(&database(), "autofill.export.xml");
}

#[test]
fn a_site_offers_its_own_entry() {
    let db = database();
    // Not the copies in the recycle bin or in the group hidden from search, and not the
    // entry's older version in its history (old.example.com).
    assert_eq!(web(&db, "example.com"), ["Example"]);
    assert_eq!(web(&db, "www.example.com"), ["Example"]);
    assert_eq!(web(&db, "EXAMPLE.com."), ["Example"]);
    assert_eq!(web(&db, "old.example.com"), ["Example"]); // as a subdomain, like any other
}

#[test]
fn a_subdomain_offers_its_own_entry_first_then_the_parent_domains() {
    let db = database();
    // "Example Login" comes later in the file, but it's the exact match.
    assert_eq!(web(&db, "login.example.com"), ["Example Login", "Example"]);
    assert_eq!(web(&db, "admin.example.com"), ["Example Admin", "Example"]);
    assert_eq!(
        web(&db, "eu.admin.example.com"),
        ["Example", "Example Admin"] // both parent domains, so in file order
    );
    // An entry for a subdomain is never offered on the parent site or on a sibling.
    assert_eq!(web(&db, "example.org"), NOTHING);
    assert_eq!(web(&db, "shop.example.org"), ["Shop"]);
    assert_eq!(web(&db, "cart.shop.example.org"), ["Shop"]);
}

#[test]
fn look_alike_sites_get_nothing() {
    let db = database();
    for domain in [
        "example.com.evil.net",
        "notexample.com",
        "example.co",
        "com",
        "",
        "exa mple.com",
        "example.com/",
        "evil.net/example.com",
        "evil.net?example.com",
        "evil.net#example.com",
        "example.com@evil.net",
    ] {
        assert_eq!(web(&db, domain), NOTHING, "{domain:?}");
    }
}

#[test]
fn urls_without_a_scheme_and_extra_urls_count() {
    let db = database();
    assert_eq!(web(&db, "plain.example.net"), ["No Scheme"]);
    assert_eq!(web(&db, "both.example.com"), ["Site And App", "Example"]);
    assert_eq!(web(&db, "other.example.com"), ["Site And App", "Example"]);
}

#[test]
fn a_page_loaded_over_plain_http_only_gets_entries_that_allow_it() {
    let db = database();
    let http = |domain| {
        let plain_http = true;
        titles(&db, FillTarget::Web { domain, plain_http })
    };
    assert_eq!(http("example.com"), NOTHING); // saved as https
    assert_eq!(http("login.example.com"), NOTHING);
    assert_eq!(http("shop.example.org"), ["Shop"]); // saved as http
    assert_eq!(http("plain.example.net"), ["No Scheme"]); // saved without a scheme
}

#[test]
fn a_protected_url_matches_nothing() {
    let db = database();
    // Its own host isn't found. The parent domain's entry still is.
    assert_eq!(web(&db, "secret.example.com"), ["Example"]);
}

#[test]
fn other_kinds_of_urls_are_not_websites() {
    let db = database();
    assert_eq!(web(&db, "ssh example.com"), NOTHING);
    assert_eq!(web(&db, "ssh"), NOTHING);
    assert_eq!(web(&db, "com.example.app"), NOTHING);
}

#[test]
fn an_app_only_gets_entries_that_name_its_package() {
    let db = database();
    assert_eq!(app(&db, "com.example.app"), ["App Link"]);
    assert_eq!(app(&db, "com.example.both"), ["Site And App"]);
    assert_eq!(app(&db, "com.example.dx"), ["KeePassDX Style"]);
    assert_eq!(app(&db, "com.example.dx2"), ["KeePassDX Style"]);
    for package in [
        "com.example",
        "com.example.app.evil",
        "com.example.App",
        "example.com",
        "com.example.shop",
        "org.example.shop",
        "",
        " ",
    ] {
        assert_eq!(app(&db, package), NOTHING, "{package:?}");
    }
}

/// An app that shows a web page isn't a browser we can verify. It's offered an entry only if
/// the entry is linked to the app and also saved for the site the app says it's showing: so not
/// on a page it merely displays (a link someone sent, opened in its in-app browser).
#[test]
fn a_web_page_inside_an_app_needs_both_the_app_and_the_site() {
    let db = database();
    let inside = |package, domain| {
        let plain_http = false;
        titles(
            &db,
            FillTarget::AppWeb {
                package,
                domain,
                plain_http,
            },
        )
    };
    // "Site And App" is saved for both.example.com and linked to com.example.both. "Example",
    // for example.com, matches that page too, but isn't linked to the app.
    assert_eq!(app(&db, "com.example.both"), ["Site And App"]);
    assert_eq!(web(&db, "both.example.com"), ["Site And App", "Example"]);
    assert_eq!(
        inside("com.example.both", "both.example.com"),
        ["Site And App"]
    );
    assert_eq!(
        inside("com.example.both", "login.both.example.com"),
        ["Site And App"]
    );
    assert_eq!(
        inside("com.example.both", "other.example.com"),
        ["Site And App"]
    );
    // The same app showing someone else's page: nothing.
    assert_eq!(inside("com.example.both", "evil.example.net"), NOTHING);
    assert_eq!(inside("com.example.both", "example.com"), NOTHING);
    assert_eq!(
        inside("com.example.both", "both.example.com.evil.net"),
        NOTHING
    );
    assert_eq!(inside("com.example.both", ""), NOTHING);
    // Another app claiming to show the entry's site: nothing. Claiming is free.
    assert_eq!(inside("com.example.evil", "both.example.com"), NOTHING);
    assert_eq!(inside("com.example.evil", "login.example.com"), NOTHING);
    assert_eq!(inside("", "both.example.com"), NOTHING);
    // An entry linked to an app but saved for no site isn't offered on any page in it.
    assert_eq!(app(&db, "com.example.app"), ["App Link"]);
    assert_eq!(inside("com.example.app", "example.com"), NOTHING);
    // Plain http still doesn't get an entry saved as https.
    let plain_http = true;
    let target = FillTarget::AppWeb {
        package: "com.example.both",
        domain: "both.example.com",
        plain_http,
    };
    assert_eq!(titles(&db, target), NOTHING);
}

/// Allowing an app to use an entry's passkey writes `AndroidApp` with `AndroidApp Signature`.
/// That must not hand the app the entry's password as well.
#[test]
fn an_app_allowed_to_use_a_passkey_isnt_offered_the_password() {
    use pocketkeepass_core::{EntryEdit, FieldEdit, FieldValue};
    let mut db = database();
    let root = db.root().uuid().unwrap();
    let field = |key, value, protected| FieldEdit {
        key,
        value: FieldValue::Set(value),
        protected,
    };
    let fingerprint = ["AB"; 32].join(":");
    let fields = vec![
        field("Title", "Pinned", false),
        field("Password", "secret", true),
        // Pinned with a certificate: a passkey permission.
        field("AndroidApp", "com.example.pinned", false),
        field("AndroidApp Signature", &fingerprint, true),
        // No certificate: an autofill link, as before.
        field("AndroidApp_1", "com.example.plain", false),
        // Not app fields at all, whatever they hold.
        field("AndroidApplication", "com.example.other", false),
        field("AndroidApp Signature_2", "com.example.sig", false),
    ];
    let edit = EntryEdit {
        fields,
        tags: vec![],
    };
    db.create_entry(&root, &edit).unwrap();
    assert_eq!(app(&db, "com.example.pinned"), NOTHING);
    assert_eq!(app(&db, "com.example.plain"), ["Pinned"]);
    assert_eq!(app(&db, "com.example.other"), NOTHING);
    assert_eq!(app(&db, "com.example.sig"), NOTHING);
}

#[test]
fn matches_know_where_they_live() {
    let db = database();
    let domain = "login.example.com";
    let plain_http = false;
    let hits = db.autofill_matches(FillTarget::Web { domain, plain_http });
    let paths: Vec<Vec<_>> = hits
        .iter()
        .map(|hit| hit.path.iter().map(|g| g.name()).collect())
        .collect();
    assert_eq!(paths, [vec!["Sites"], vec![]]);
}
