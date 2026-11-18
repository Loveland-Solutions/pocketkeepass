//! Passkeys on `passkey.kdbx` (see `fixtures/README.md`): finding them for a sign-in, signing,
//! creating them, and linking apps, in the format KeePassXC and KeePassDX share.
//!
//! Signatures are checked with the public key from OpenSSL (which made the fixture's keys) as
//! well as with RustCrypto, and KeePassXC reads the passkeys we create.

mod common;

use std::path::PathBuf;
use std::process::{Command, Stdio};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64URL;
use common::{PASSWORD, assert_matches_keepassxc_export, open};
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use p256::pkcs8::DecodePublicKey;
use pocketkeepass_core::{
    AppTrust, Client, CompositeKey, Database, Error, PasskeyStatus, PasskeyTarget,
};
use serde_json::Value;

const ROOT: [u8; 16] = [0x01; 16];
const EXAMPLE: [u8; 16] = [0x31; 16];
const BOB: [u8; 16] = [0x32; 16];
const LINKED_APP: [u8; 16] = [0x36; 16];
const PASSWORD_ONLY: [u8; 16] = [0x37; 16];
const DELETED: [u8; 16] = [0x39; 16];

const HASH: [u8; 32] = [0x5a; 32];
const BROWSER: Client<'static> = Client::Browser {
    client_data_hash: &HASH,
};
const APP_CERT: [[u8; 32]; 1] = [[0xaa; 32]];
const OTHER_CERT: [[u8; 32]; 1] = [[0xbb; 32]];

fn database() -> Database {
    open("passkey.kdbx", Some(PASSWORD), None).unwrap()
}

fn app<'a>(package: &'a str, certificates: &'a [[u8; 32]]) -> Client<'a> {
    Client::App {
        package,
        certificates,
    }
}

fn get(rp_id: &str, allow: &[&[u8]]) -> String {
    let allow: Vec<Value> = allow
        .iter()
        .map(|id| serde_json::json!({"type": "public-key", "id": B64URL.encode(id)}))
        .collect();
    serde_json::json!({
        "rpId": rp_id,
        "challenge": B64URL.encode(b"a challenge from the site"),
        "allowCredentials": allow,
        "userVerification": "required",
    })
    .to_string()
}

fn create(rp_id: &str, exclude: &[&[u8]]) -> String {
    let exclude: Vec<Value> = exclude
        .iter()
        .map(|id| serde_json::json!({"type": "public-key", "id": B64URL.encode(id)}))
        .collect();
    serde_json::json!({
        "rp": {"id": rp_id, "name": "Example Site"},
        "user": {"id": B64URL.encode(b"new-user"), "name": "new@example.com", "displayName": "New"},
        "challenge": B64URL.encode(b"register me"),
        "pubKeyCredParams": [{"type": "public-key", "alg": -7}, {"type": "public-key", "alg": -257}],
        "excludeCredentials": exclude,
        "authenticatorSelection": {"residentKey": "required", "userVerification": "required"},
    })
    .to_string()
}

fn titles(db: &Database, request: &str, client: &Client<'_>) -> Vec<String> {
    db.passkeys_for(request, client)
        .unwrap()
        .iter()
        .map(|p| p.hit.entry.title().to_owned())
        .collect()
}

fn b64(value: &Value) -> Vec<u8> {
    B64URL.decode(value.as_str().unwrap()).unwrap()
}

/// The public key the fixture stores for an entry, as OpenSSL wrote it.
fn public_key(db: &Database, entry: &[u8; 16]) -> VerifyingKey {
    use p256::pkcs8::DecodePrivateKey;
    *p256::ecdsa::SigningKey::from_pkcs8_pem(&private_pem(db, entry))
        .unwrap()
        .verifying_key()
}

/// The private key the fixture stores for an entry (as OpenSSL wrote it), with its line breaks
/// put back if the entry left them out.
fn private_pem(db: &Database, entry: &[u8; 16]) -> String {
    let pem = db
        .find_entry(entry)
        .unwrap()
        .entry
        .field("KPEX_PASSKEY_PRIVATE_KEY_PEM")
        .unwrap();
    {
        let body: String = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .split_whitespace()
            .collect();
        let lines: Vec<&str> = body
            .as_bytes()
            .chunks(64)
            .map(|c| std::str::from_utf8(c).unwrap())
            .collect();
        format!(
            "-----BEGIN PRIVATE KEY-----\n{}\n-----END PRIVATE KEY-----\n",
            lines.join("\n")
        )
    }
}

/// Checks a sign-in response's signature, and returns its authenticator data.
fn verify_assertion(response: &str, key: &VerifyingKey, client_data_hash: &[u8]) -> Vec<u8> {
    let response: Value = serde_json::from_str(response).unwrap();
    let r = &response["response"];
    let auth_data = b64(&r["authenticatorData"]);
    let mut signed = auth_data.clone();
    signed.extend_from_slice(client_data_hash);
    let der = b64(&r["signature"]);
    key.verify(&signed, &Signature::from_der(&der).unwrap())
        .expect("the signature verifies");
    verify_with_openssl(key, &signed, &der);
    auth_data
}

/// The same check with OpenSSL, if it's installed: a second, independent implementation.
fn verify_with_openssl(key: &VerifyingKey, message: &[u8], der_signature: &[u8]) {
    use p256::pkcs8::EncodePublicKey;
    let Ok(openssl) = Command::new("openssl").arg("version").output() else {
        eprintln!("SKIPPED: openssl not found");
        return;
    };
    assert!(openssl.status.success());
    let dir = std::env::temp_dir().join(format!(
        "pocketkeepass-passkey-{}-{}",
        std::process::id(),
        rand_suffix()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    let path = |name: &str| -> PathBuf { dir.join(name) };
    let pem = p256::PublicKey::from(key)
        .to_public_key_pem(p256::pkcs8::LineEnding::LF)
        .unwrap();
    std::fs::write(path("key.pem"), pem).unwrap();
    std::fs::write(path("message"), message).unwrap();
    std::fs::write(path("signature"), der_signature).unwrap();
    let out = Command::new("openssl")
        .args(["dgst", "-sha256", "-verify"])
        .arg(path("key.pem"))
        .arg("-signature")
        .arg(path("signature"))
        .arg(path("message"))
        .stderr(Stdio::inherit())
        .output()
        .unwrap();
    std::fs::remove_dir_all(&dir).unwrap();
    assert!(out.status.success(), "OpenSSL rejects the signature");
}

fn rand_suffix() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    NEXT.fetch_add(1, Ordering::Relaxed)
}

#[test]
fn the_fixture_matches_keepassxc() {
    assert_matches_keepassxc_export(&database(), "passkey.export.xml");
}

#[test]
fn a_site_gets_its_own_passkeys_only() {
    let db = database();
    // Not the copies in the hidden group or the recycle bin. The rpId is compared ignoring
    // case ("Example.com" in Bob's entry).
    assert_eq!(
        titles(&db, &get("example.com", &[]), &BROWSER),
        ["Example (Passkey)", "Example (Bob)"]
    );
    assert_eq!(
        titles(&db, &get("EXAMPLE.COM", &[]), &BROWSER),
        ["Example (Passkey)", "Example (Bob)"]
    );
    assert!(titles(&db, &get("login.example.com", &[]), &BROWSER).is_empty());
    assert!(titles(&db, &get("other.example", &[]), &BROWSER).is_empty());
    // The site can name the passkeys it accepts.
    assert_eq!(
        titles(&db, &get("example.com", &[&[0x32; 32]]), &BROWSER),
        ["Example (Bob)"]
    );
    assert!(titles(&db, &get("example.com", &[&[0x38; 32]]), &BROWSER).is_empty());

    let hits = db.passkeys_for(&get("example.com", &[]), &BROWSER).unwrap();
    assert_eq!(hits[0].username, "alice@example.com");
    assert_eq!(hits[0].status, PasskeyStatus::Ready);
    assert_eq!(hits[0].trust, AppTrust::Browser);
}

#[test]
fn other_apps_formats_are_read() {
    let db = database();
    // StrongBox imports: KeePassXC's override fields win.
    let hits = db
        .passkeys_for(&get("strongbox.example", &[&[0x33; 32]]), &BROWSER)
        .unwrap();
    assert_eq!(hits.len(), 1);
    assert_eq!(hits[0].username, "sb-user");
    // A PEM stored without line breaks, and a padded credential ID.
    let hits = db
        .passkeys_for(&get("unwrapped.example", &[&[0x34; 32]]), &BROWSER)
        .unwrap();
    assert_eq!(hits[0].status, PasskeyStatus::Ready);
    // Ed25519 and RSA keys (KeePassXC makes both) are used.
    for rp in ["ed.example", "rsa.example"] {
        let hits = db.passkeys_for(&get(rp, &[]), &BROWSER).unwrap();
        assert_eq!(hits[0].status, PasskeyStatus::Ready, "{rp}");
    }
    // ML-DSA (KeePassDX) is listed, with the reason it can't be used.
    let hits = db
        .passkeys_for(&get("mldsa.example", &[]), &BROWSER)
        .unwrap();
    assert_eq!(
        hits[0].status,
        PasskeyStatus::Unusable(
            "This passkey uses ML-DSA keys, which PocketKeePass can't use yet.".into()
        )
    );
    let entry = [0x3b; 16];
    assert!(matches!(
        db.assert_passkey(&entry, &get("mldsa.example", &[]), &BROWSER),
        Err(Error::PasskeyUnsupported(_))
    ));
}

#[test]
fn ed25519_and_rsa_passkeys_sign() {
    let db = database();
    for (entry, rp) in [([0x35; 16], "ed.example"), ([0x3a; 16], "rsa.example")] {
        let response = db.assert_passkey(&entry, &get(rp, &[]), &BROWSER).unwrap();
        let response: Value = serde_json::from_str(&response).unwrap();
        let mut signed = b64(&response["response"]["authenticatorData"]);
        signed.extend_from_slice(&HASH);
        let signature = b64(&response["response"]["signature"]);
        let pem = private_pem(&db, &entry);
        if rp == "ed.example" {
            use ed25519_dalek::Verifier;
            use ed25519_dalek::pkcs8::DecodePrivateKey;
            assert_eq!(signature.len(), 64);
            let key = ed25519_dalek::SigningKey::from_pkcs8_pem(&pem).unwrap();
            let signature = ed25519_dalek::Signature::from_slice(&signature).unwrap();
            key.verifying_key()
                .verify(&signed, &signature)
                .expect("Ed25519 verifies");
        } else {
            use p256::ecdsa::signature::Verifier;
            use rsa::pkcs8::DecodePrivateKey;
            assert_eq!(signature.len(), 256); // 2048 bits
            let key = rsa::PrivateKey::from_pkcs8_pem(&pem).unwrap();
            let verifying = rsa::pkcs1v15::VerifyingKey::<sha2::Sha256>::new(key.to_public_key());
            let parsed = rsa::pkcs1v15::Signature::try_from(signature.as_slice()).unwrap();
            verifying.verify(&signed, &parsed).expect("RSA verifies");
        }
        verify_with_openssl_private_key(&pem, &signed, &signature, rp == "ed.example");
    }
}

/// Checks a signature with OpenSSL, from the private key it made: `pkeyutl -rawin` for Ed25519
/// (which signs the message itself), `dgst -sha256 -prverify` for RSA and ECDSA.
fn verify_with_openssl_private_key(pem: &str, message: &[u8], signature: &[u8], ed25519: bool) {
    if Command::new("openssl").arg("version").output().is_err() {
        eprintln!("SKIPPED: openssl not found");
        return;
    }
    let dir = std::env::temp_dir().join(format!(
        "pocketkeepass-passkey-{}-{}",
        std::process::id(),
        rand_suffix()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    let path = |name: &str| -> PathBuf { dir.join(name) };
    std::fs::write(path("key.pem"), pem).unwrap();
    std::fs::write(path("message"), message).unwrap();
    std::fs::write(path("signature"), signature).unwrap();
    let mut command = Command::new("openssl");
    if ed25519 {
        command
            .args(["pkeyutl", "-verify", "-rawin", "-inkey"])
            .arg(path("key.pem"))
            .arg("-in")
            .arg(path("message"))
            .arg("-sigfile")
            .arg(path("signature"));
    } else {
        command
            .args(["dgst", "-sha256", "-prverify"])
            .arg(path("key.pem"))
            .arg("-signature")
            .arg(path("signature"))
            .arg(path("message"));
    }
    let out = command.stderr(Stdio::inherit()).output().unwrap();
    std::fs::remove_dir_all(&dir).unwrap();
    assert!(out.status.success(), "OpenSSL rejects the signature");
}

#[test]
fn signing_in_from_a_browser() {
    let db = database();
    let response = db
        .assert_passkey(&EXAMPLE, &get("example.com", &[]), &BROWSER)
        .unwrap();
    let auth_data = verify_assertion(&response, &public_key(&db, &EXAMPLE), &HASH);
    assert_eq!(auth_data[32], 0x1d); // UP, UV, BE, BS
    let response: Value = serde_json::from_str(&response).unwrap();
    assert_eq!(b64(&response["rawId"]), [0x31; 32]);
    assert_eq!(b64(&response["response"]["userHandle"]), b"alice");
    assert_eq!(response["response"]["clientDataJSON"], "<placeholder>");

    // Bob's passkey says it's neither backup eligible nor backed up.
    let response = db
        .assert_passkey(&BOB, &get("example.com", &[]), &BROWSER)
        .unwrap();
    assert_eq!(
        verify_assertion(&response, &public_key(&db, &BOB), &HASH)[32],
        0x05
    );

    // The StrongBox and unwrapped-PEM passkeys sign too.
    for (id, rp) in [
        ([0x33; 16], "strongbox.example"),
        ([0x34; 16], "unwrapped.example"),
    ] {
        let response = db.assert_passkey(&id, &get(rp, &[]), &BROWSER).unwrap();
        verify_assertion(&response, &public_key(&db, &id), &HASH);
    }
}

#[test]
fn signing_is_refused_for_the_wrong_site_or_passkey() {
    let db = database();
    let refused =
        |entry: &[u8; 16], request: String| db.assert_passkey(entry, &request, &BROWSER).is_err();
    assert!(refused(&EXAMPLE, get("evil.example", &[])));
    assert!(refused(&EXAMPLE, get("example.com", &[&[0x32; 32]])));
    assert!(refused(&PASSWORD_ONLY, get("example.com", &[])));
    assert!(matches!(
        db.assert_passkey(&DELETED, &get("example.com", &[]), &BROWSER),
        Err(Error::NotFound)
    ));
}

#[test]
fn apps_must_be_linked_with_the_right_certificate() {
    let db = database();
    let trust =
        |client: &Client<'_>| db.passkeys_for(&get("app.example", &[]), client).unwrap()[0].trust;
    assert_eq!(trust(&app("com.example.app", &APP_CERT)), AppTrust::Linked);
    // Key rotation: the linked certificate is an older one.
    assert_eq!(
        trust(&app("com.example.app", &[[0x01; 32], [0xaa; 32]])),
        AppTrust::Linked
    );
    assert_eq!(
        trust(&app("com.example.app", &OTHER_CERT)),
        AppTrust::OtherSigner
    );
    // Linked by package name only (for autofill), without a certificate: ask.
    assert_eq!(
        trust(&app("com.example.other", &APP_CERT)),
        AppTrust::NotLinked
    );
    assert_eq!(trust(&app("com.unrelated", &APP_CERT)), AppTrust::NotLinked);
    assert_eq!(trust(&BROWSER), AppTrust::Browser);

    // Only a linked app can sign, and its client data names it.
    let request = get("app.example", &[]);
    assert!(
        db.assert_passkey(&LINKED_APP, &request, &app("com.example.app", &OTHER_CERT))
            .is_err()
    );
    assert!(
        db.assert_passkey(&LINKED_APP, &request, &app("com.unrelated", &APP_CERT))
            .is_err()
    );
    let response = db
        .assert_passkey(&LINKED_APP, &request, &app("com.example.app", &APP_CERT))
        .unwrap();
    let parsed: Value = serde_json::from_str(&response).unwrap();
    let client_data = b64(&parsed["response"]["clientDataJSON"]);
    let client_json: Value = serde_json::from_slice(&client_data).unwrap();
    assert_eq!(client_json["type"], "webauthn.get");
    assert_eq!(
        client_json["origin"],
        format!("android:apk-key-hash:{}", B64URL.encode([0xaa; 32]))
    );
    assert_eq!(client_json["androidPackageName"], "com.example.app");
    assert_eq!(b64(&client_json["challenge"]), b"a challenge from the site");
    let hash = sha256(&client_data);
    verify_assertion(&response, &public_key(&db, &LINKED_APP), &hash);
}

#[test]
fn linking_an_app_adds_its_certificate() {
    let mut db = database();
    let other = app("com.example.other", &OTHER_CERT);
    assert!(
        db.link_app(&LINKED_APP, "com.example.other", &OTHER_CERT)
            .unwrap()
    );
    assert!(
        !db.link_app(&LINKED_APP, "com.example.other", &OTHER_CERT)
            .unwrap()
    );
    let entry = db.find_entry(&LINKED_APP).unwrap().entry;
    // The existing link (AndroidApp_1) got a certificate; no new link was added.
    assert_eq!(entry.field("AndroidApp_1"), Some("com.example.other"));
    assert!(entry.field("AndroidApp_2").is_none());
    assert!(entry.is_protected("AndroidApp Signature_1"));
    assert_eq!(
        entry.field("AndroidApp Signature_1"),
        Some(&*vec!["BB"; 32].join(":"))
    );
    assert_eq!(entry.history().count(), 1);
    assert!(
        db.assert_passkey(&LINKED_APP, &get("app.example", &[]), &other)
            .is_ok()
    );

    // A new app gets the next free suffix.
    let new_certs = [[0xcc; 32]];
    db.link_app(&LINKED_APP, "com.example.third", &new_certs)
        .unwrap();
    let entry = db.find_entry(&LINKED_APP).unwrap().entry;
    assert_eq!(entry.field("AndroidApp_2"), Some("com.example.third"));
    assert!(entry.field("AndroidApp Signature_2").is_some());
}

#[test]
fn creating_a_passkey_in_a_new_entry() {
    let mut db = database();
    db.upgrade_to_kdbx4().unwrap();
    let created = db
        .create_passkey(
            PasskeyTarget::NewEntry { group: ROOT },
            &create("new.example", &[]),
            &BROWSER,
        )
        .unwrap();
    let response: Value = serde_json::from_str(&created.response_json).unwrap();
    let spki = b64(&response["response"]["publicKey"]);
    let key = VerifyingKey::from_public_key_der(&spki).unwrap();
    let credential_id = b64(&response["rawId"]);
    assert_eq!(credential_id.len(), 32);

    let entry = db.find_entry(&created.entry).unwrap().entry;
    assert_eq!(entry.title(), "Example Site");
    assert_eq!(entry.username(), "new@example.com");
    assert_eq!(entry.url(), "https://new.example");
    assert_eq!(entry.tags(), "Passkey");
    assert_eq!(
        entry.field("KPEX_PASSKEY_RELYING_PARTY"),
        Some("new.example")
    );
    assert_eq!(
        entry.field("KPEX_PASSKEY_USER_HANDLE"),
        Some(&*B64URL.encode(b"new-user"))
    );
    assert_eq!(
        entry.field("KPEX_PASSKEY_CREDENTIAL_ID"),
        Some(&*B64URL.encode(&credential_id))
    );
    assert!(
        entry
            .field("KPEX_PASSKEY_PRIVATE_KEY_PEM")
            .unwrap()
            .starts_with("-----BEGIN PRIVATE KEY-----\n")
    );
    for key in [
        "KPEX_PASSKEY_CREDENTIAL_ID",
        "KPEX_PASSKEY_PRIVATE_KEY_PEM",
        "KPEX_PASSKEY_USER_HANDLE",
    ] {
        assert!(entry.is_protected(key), "{key} is protected");
    }
    assert!(!entry.is_protected("KPEX_PASSKEY_RELYING_PARTY"));
    assert!(entry.field("AndroidApp").is_none()); // a browser isn't linked

    // It signs in, with the key the site was given, after a save and a reopen.
    let file = db.save().unwrap();
    let db = Database::open(&file, &CompositeKey::new(Some(PASSWORD), None).unwrap()).unwrap();
    let request = get("new.example", &[&credential_id]);
    assert_eq!(titles(&db, &request, &BROWSER), ["Example Site"]);
    let response = db
        .assert_passkey(&created.entry, &request, &BROWSER)
        .unwrap();
    verify_assertion(&response, &key, &HASH);
    let response: Value = serde_json::from_str(&response).unwrap();
    assert_eq!(b64(&response["response"]["userHandle"]), b"new-user");
}

#[test]
fn an_app_that_creates_a_passkey_is_linked_to_it() {
    let mut db = database();
    let client = app("com.example.new", &APP_CERT);
    let created = db
        .create_passkey(
            PasskeyTarget::NewEntry { group: ROOT },
            &create("new.example", &[]),
            &client,
        )
        .unwrap();
    let response: Value = serde_json::from_str(&created.response_json).unwrap();
    let client_json: Value =
        serde_json::from_slice(&b64(&response["response"]["clientDataJSON"])).unwrap();
    assert_eq!(client_json["type"], "webauthn.create");
    assert_eq!(client_json["androidPackageName"], "com.example.new");

    let entry = db.find_entry(&created.entry).unwrap().entry;
    assert_eq!(entry.field("AndroidApp"), Some("com.example.new"));
    let hits = db.passkeys_for(&get("new.example", &[]), &client).unwrap();
    assert_eq!(hits[0].trust, AppTrust::Linked);
    assert!(
        db.assert_passkey(&created.entry, &get("new.example", &[]), &client)
            .is_ok()
    );
}

#[test]
fn adding_a_passkey_to_an_existing_entry_and_taking_it_back() {
    let mut db = database();
    let summary = db.passkey_creation(&create("example.com", &[])).unwrap();
    assert_eq!(summary.rp_id, "example.com");
    assert_eq!(summary.user_name, "new@example.com");
    // Entries for the site that don't have a passkey yet.
    let candidates: Vec<&str> = summary.candidates.iter().map(|h| h.entry.title()).collect();
    assert_eq!(candidates, ["Example Password"]);
    drop(summary);

    let before = common::all_entries(db.xml());
    let created = db
        .create_passkey(
            PasskeyTarget::Entry(PASSWORD_ONLY),
            &create("example.com", &[]),
            &BROWSER,
        )
        .unwrap();
    assert_eq!(created.entry, PASSWORD_ONLY);
    let entry = db.find_entry(&PASSWORD_ONLY).unwrap().entry;
    assert_eq!(entry.username(), "carol"); // kept, since it wasn't empty
    assert_eq!(entry.url(), "https://example.com/login");
    assert_eq!(entry.tags(), "Passkey");
    assert_eq!(entry.history().count(), 1);
    assert_eq!(
        entry.field("KPEX_PASSKEY_RELYING_PARTY"),
        Some("example.com")
    );

    // A second passkey can't go into the same entry.
    assert!(
        db.create_passkey(
            PasskeyTarget::Entry(PASSWORD_ONLY),
            &create("example.com", &[]),
            &BROWSER
        )
        .is_err()
    );

    // Saving failed: take it back, and the entry is exactly as it was.
    assert!(!db.undo_passkey(&EXAMPLE).unwrap());
    assert!(db.undo_passkey(&PASSWORD_ONLY).unwrap());
    assert_eq!(common::all_entries(db.xml()), before);
    assert!(!db.undo_passkey(&PASSWORD_ONLY).unwrap());

    // A new entry is removed altogether.
    let created = db
        .create_passkey(
            PasskeyTarget::NewEntry { group: ROOT },
            &create("new.example", &[]),
            &BROWSER,
        )
        .unwrap();
    assert!(db.undo_passkey(&created.entry).unwrap());
    assert!(db.find_entry(&created.entry).is_none());
    assert_eq!(common::all_entries(db.xml()), before);
}

#[test]
fn a_passkey_the_site_already_has_is_not_made_twice() {
    let db = database();
    // Even the copy in the recycle bin counts: the site still knows it.
    for existing in [[0x31; 32], [0x39; 32]] {
        assert!(matches!(
            db.passkey_creation(&create("example.com", &[&existing])),
            Err(Error::PasskeyExists)
        ));
    }
    // The same credential ID for another site, or one we don't have, is fine.
    assert!(
        db.passkey_creation(&create("other.example", &[&[0x31; 32]]))
            .is_ok()
    );
    assert!(
        db.passkey_creation(&create("example.com", &[&[0x77; 32]]))
            .is_ok()
    );

    let rsa_only = create("example.com", &[]).replace("\"alg\":-7", "\"alg\":-8");
    assert!(matches!(
        db.passkey_creation(&rsa_only),
        Err(Error::PasskeyUnsupported(_))
    ));
}

#[test]
fn keepassxc_reads_passkeys_we_create() {
    let Some(cli) = keepassxc_cli() else { return };
    let mut db = database();
    db.upgrade_to_kdbx4().unwrap();
    let client = app("com.example.new", &APP_CERT);
    let created = db
        .create_passkey(
            PasskeyTarget::NewEntry { group: ROOT },
            &create("new.example", &[]),
            &client,
        )
        .unwrap();
    let entry = db.find_entry(&created.entry).unwrap().entry;
    let expected: Vec<(String, String)> = entry
        .fields()
        .filter(|f| f.key.starts_with("KPEX_PASSKEY") || f.key.starts_with("AndroidApp"))
        .map(|f| (f.key.to_owned(), f.value.to_owned()))
        .collect();
    assert_eq!(expected.len(), 9);

    let path =
        std::env::temp_dir().join(format!("pocketkeepass-passkey-{}.kdbx", std::process::id()));
    std::fs::write(&path, db.save().unwrap()).unwrap();
    let run = |args: &[&str]| {
        let mut child = Command::new(&cli)
            .args(args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        use std::io::Write;
        child
            .stdin
            .take()
            .unwrap()
            .write_all(b"test-password\n")
            .unwrap();
        let out = child.wait_with_output().unwrap();
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
        String::from_utf8(out.stdout).unwrap()
    };
    let db_path = path.to_str().unwrap();
    let info = run(&["db-info", "-q", db_path]);
    assert!(info.contains("Number of passkeys: 10"), "{info}");
    for (key, value) in &expected {
        let shown = run(&["show", "-q", "-s", "-a", key, db_path, "/Example Site"]);
        assert_eq!(shown.trim_end(), value.trim_end(), "{key}");
    }
    std::fs::remove_file(&path).unwrap();
}

fn keepassxc_cli() -> Option<PathBuf> {
    let path = std::env::var_os("KEEPASSXC_CLI")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from("/Applications/KeePassXC.app/Contents/MacOS/keepassxc-cli")
        });
    if path.exists() {
        return Some(path);
    }
    eprintln!("SKIPPED: keepassxc-cli not found (set KEEPASSXC_CLI to its path)");
    None
}

fn sha256(data: &[u8]) -> [u8; 32] {
    use sha2::Digest;
    sha2::Sha256::digest(data).into()
}
