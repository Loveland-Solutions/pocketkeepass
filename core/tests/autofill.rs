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
