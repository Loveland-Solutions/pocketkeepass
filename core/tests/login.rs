//! Saving logins typed into other apps, on `autofill.kdbx` (see `fixtures/README.md`).

mod common;

use common::{PASSWORD, open};
use pocketkeepass_core::{Database, Error, FillTarget, LoginPlace, uuid_from_hex};

fn database() -> Database {
    open("autofill.kdbx", Some(PASSWORD), None).unwrap()
}

/// The fixture's UUIDs repeat one byte: `11` is sixteen 0x11 bytes.
fn id(hex: &str) -> [u8; 16] {
    uuid_from_hex(&hex.repeat(16)).unwrap()
}

const ROOT: &str = "01";

fn web(domain: &str) -> FillTarget<'_> {
    FillTarget::Web {
        domain,
        plain_http: false,
    }
}

/// (title, same username, same password) for each candidate.
fn candidates(
    db: &Database,
    target: FillTarget<'_>,
    user: &str,
    pw: &str,
) -> Vec<(String, bool, bool)> {
    db.login_candidates(target, user, pw)
        .iter()
        .map(|c| {
            (
                c.hit.entry.title().to_owned(),
                c.same_username,
                c.same_password,
            )
        })
        .collect()
}

fn field(db: &Database, entry: &[u8; 16], key: &str) -> String {
    let hit = db.find_entry(entry).unwrap();
    hit.entry
        .fields()
        .find(|f| f.key == key)
        .map(|f| f.value.to_owned())
        .unwrap_or_default()
}

fn titles(db: &Database, target: FillTarget<'_>) -> Vec<String> {
    db.autofill_matches(target)
        .iter()
        .map(|hit| hit.entry.title().to_owned())
        .collect()
}

#[test]
fn candidates_compare_protected_usernames_and_passwords_inside_the_engine() {
    let db = database();
    // "Example"'s username and password are both protected.
    assert_eq!(
        candidates(&db, web("example.com"), " jo-protected ", "example-pw"),
        [("Example".to_owned(), true, true)]
    );
    assert_eq!(
        candidates(&db, web("example.com"), "jo-protected", "new-pw"),
        [("Example".to_owned(), true, false)]
    );
    assert_eq!(
        candidates(&db, web("example.com"), "someone-else", "example-pw"),
        [("Example".to_owned(), false, false)]
    );
    // The same entries autofill would offer, best first.
    assert_eq!(
        candidates(&db, web("login.example.com"), "x", "y")
            .into_iter()
            .map(|c| c.0)
            .collect::<Vec<_>>(),
        ["Example Login", "Example"]
    );
    assert!(candidates(&db, web("nowhere.example"), "jo", "pw").is_empty());
}

#[test]
fn a_new_login_for_a_website_is_offered_there_next_time() {
    let mut db = database();
    let target = FillTarget::Web {
        domain: "Accounts.New-Site.example.",
        plain_http: false,
    };
    let place = LoginPlace::NewEntry {
        group: id(ROOT),
        title: "",
    };
    let entry = db
        .save_login(target, place, " me@new-site.example ", "s3cret")
        .unwrap();
    assert_eq!(field(&db, &entry, "Title"), "accounts.new-site.example");
    assert_eq!(field(&db, &entry, "UserName"), "me@new-site.example");
    assert_eq!(field(&db, &entry, "Password"), "s3cret");
    assert_eq!(
        field(&db, &entry, "URL"),
        "https://accounts.new-site.example"
    );
    let hit = db.find_entry(&entry).unwrap();
    assert!(hit.entry.is_protected("Password"));
    assert_eq!(
        titles(&db, web("accounts.new-site.example")),
        ["accounts.new-site.example"]
    );
    assert_eq!(
        candidates(
            &db,
            web("accounts.new-site.example"),
            "me@new-site.example",
            "s3cret"
        ),
        [("accounts.new-site.example".to_owned(), true, true)]
    );
}

#[test]
fn a_title_is_kept_and_www_is_left_out_of_the_default() {
    let mut db = database();
    let place = |title| LoginPlace::NewEntry {
        group: id(ROOT),
        title,
    };
    let named = db
        .save_login(web("www.named.example"), place(" My Bank "), "jo", "pw")
        .unwrap();
    assert_eq!(field(&db, &named, "Title"), "My Bank");
    assert_eq!(field(&db, &named, "URL"), "https://www.named.example");
    let unnamed = db
        .save_login(web("www.named.example"), place(""), "jo", "pw")
        .unwrap();
    assert_eq!(field(&db, &unnamed, "Title"), "named.example");
}

#[test]
fn a_plain_http_page_is_saved_as_http() {
    let mut db = database();
    let target = FillTarget::Web {
        domain: "router.local",
        plain_http: true,
    };
    let place = LoginPlace::NewEntry {
        group: id(ROOT),
        title: "",
    };
    let entry = db.save_login(target, place, "admin", "pw").unwrap();
    assert_eq!(field(&db, &entry, "URL"), "http://router.local");
    assert_eq!(titles(&db, target), ["router.local"]);
}

#[test]
fn a_new_login_for_an_app_is_linked_by_package_name() {
    let mut db = database();
    let target = FillTarget::App {
        package: "com.example.newapp",
    };
    let place = LoginPlace::NewEntry {
        group: id("02"),
        title: "New App",
    };
    let entry = db.save_login(target, place, "jo", "pw").unwrap();
    assert_eq!(field(&db, &entry, "URL"), "androidapp://com.example.newapp");
    assert_eq!(titles(&db, target), ["New App"]);
    // Not offered to a website, nor to another app.
    assert!(titles(&db, web("com.example.newapp")).is_empty());
    assert!(
        titles(
            &db,
            FillTarget::App {
                package: "com.example"
            }
        )
        .is_empty()
    );
}

#[test]
fn a_web_page_in_an_app_is_linked_to_both() {
    let mut db = database();
    let target = FillTarget::AppWeb {
        package: "com.example.reader",
        domain: "news.example",
        plain_http: false,
    };
    let place = LoginPlace::NewEntry {
        group: id(ROOT),
        title: "",
    };
    let entry = db.save_login(target, place, "jo", "pw").unwrap();
    assert_eq!(field(&db, &entry, "URL"), "https://news.example");
    assert_eq!(
        field(&db, &entry, "KP2A_URL"),
        "androidapp://com.example.reader"
    );
    assert_eq!(titles(&db, target), ["news.example"]);
    assert_eq!(titles(&db, web("news.example")), ["news.example"]);
    // Another app showing the same site doesn't get it.
    let other = FillTarget::AppWeb {
        package: "com.example.other",
        domain: "news.example",
        plain_http: false,
    };
    assert!(titles(&db, other).is_empty());
}

#[test]
fn updating_an_entry_keeps_the_rest_and_the_old_version_in_history() {
    let mut db = database();
    let example = id("11");
    let before = db.find_entry(&example).unwrap().entry.history().count();
    let saved = db
        .save_login(
            web("example.com"),
            LoginPlace::Entry(example),
            "jo-protected",
            "changed-pw",
        )
        .unwrap();
    assert_eq!(saved, example);
    assert_eq!(field(&db, &example, "Password"), "changed-pw");
    assert_eq!(field(&db, &example, "UserName"), "jo-protected");
    assert_eq!(field(&db, &example, "URL"), "https://example.com/account");
    let hit = db.find_entry(&example).unwrap();
    assert!(
        hit.entry.is_protected("UserName"),
        "the username stays protected"
    );
    assert!(hit.entry.is_protected("Password"));
    assert_eq!(hit.entry.history().count(), before + 1);
    assert_eq!(hit.entry.history().last().unwrap().password(), "example-pw");
}

#[test]
fn a_password_without_a_username_keeps_the_entry_s_username() {
    let mut db = database();
    let shop = id("12");
    db.save_login(
        web("shop.example.org"),
        LoginPlace::Entry(shop),
        "  ",
        "new-shop-pw",
    )
    .unwrap();
    assert_eq!(field(&db, &shop, "UserName"), "jo");
    assert_eq!(field(&db, &shop, "Password"), "new-shop-pw");
}

#[test]
fn nothing_is_saved_without_a_password_or_a_place_to_link() {
    let mut db = database();
    let place = || LoginPlace::NewEntry {
        group: id(ROOT),
        title: "",
    };
    assert!(matches!(
        db.save_login(web("example.com"), place(), "jo", ""),
        Err(Error::Invalid(_))
    ));
    assert!(matches!(
        db.save_login(web(" "), place(), "jo", "pw"),
        Err(Error::Invalid(_))
    ));
    assert!(matches!(
        db.save_login(
            FillTarget::App {
                package: "com.ex ample"
            },
            place(),
            "jo",
            "pw"
        ),
        Err(Error::Invalid(_))
    ));
    assert!(matches!(
        db.save_login(web("example.com"), place(), "jo", "pw\u{1}"),
        Err(Error::Invalid(_))
    ));
    assert!(matches!(
        db.save_login(
            web("example.com"),
            LoginPlace::Entry([0x99; 16]),
            "jo",
            "pw"
        ),
        Err(Error::NotFound)
    ));
    assert_eq!(titles(&db, web("example.com")), ["Example"]);
    assert_eq!(field(&db, &id("11"), "Password"), "example-pw");
}
