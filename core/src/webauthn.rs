//! The WebAuthn side of passkeys: reading the requests a site or app sends, and building the
//! responses a passkey provider returns. How passkeys are kept in entries is in `passkey.rs`.
//!
//! Requests and responses are the JSON forms Android's Credential Manager passes
//! (`PublicKeyCredentialCreationOptionsJSON`, `…RequestOptionsJSON`, and the
//! `RegistrationResponseJSON` / `AuthenticationResponseJSON` that go back), with binary values in
//! base64url. The two binary structures inside a response are built here by hand: the
//! authenticator data, and the small CBOR objects (the public key and the attestation object).
//! We only ever *write* CBOR, in fixed shapes, so there's no CBOR parser.
//!
//! New passkeys are ES256 (ECDSA on P-256 with SHA-256): nearly every site accepts them. Signing
//! also works with the other kinds KeePassXC and KeePassDX make: RS256 (RSA with PKCS#1 v1.5
//! and SHA-256) and EdDSA (Ed25519).

use base64::Engine;
use base64::alphabet;
use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
use p256::ecdsa::signature::{SignatureEncoding, Signer};
use p256::pkcs8::EncodePublicKey;
use serde_json::{Value, json};
use sha2::Sha256;

use crate::error::{Error, Result};
use crate::util::sha256;

/// Base64url without padding, as WebAuthn writes it. Decoding also accepts padding.
pub(crate) const B64URL: GeneralPurpose = GeneralPurpose::new(
    &alphabet::URL_SAFE,
    GeneralPurposeConfig::new()
        .with_encode_padding(false)
        .with_decode_padding_mode(DecodePaddingMode::Indifferent),
);

/// Plain base64, accepted when reading values another app may have stored that way.
const B64: GeneralPurpose = GeneralPurpose::new(
    &alphabet::STANDARD,
    GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent),
);

/// Identifies PocketKeePass as the kind of authenticator. Sites can look it up to show a name
/// and icon. Generated once for PocketKeePass; never change it.
pub(crate) const AAGUID: [u8; 16] = [
    0x06, 0x66, 0xa6, 0xfa, 0xe0, 0x60, 0x4f, 0x4a, 0xba, 0xce, 0xe9, 0xd1, 0x55, 0x00, 0x60, 0xe9,
];

/// COSE's number for ES256.
pub(crate) const ES256: i64 = -7;

/// What Google's guide says to send as `clientDataJSON` when a browser supplied the hash of
/// its own client data. The browser puts the real one in before passing the response on.
const CLIENT_DATA_PLACEHOLDER: &str = "<placeholder>";

// Authenticator data flags (WebAuthn §6.1).
const FLAG_UP: u8 = 0x01; // user present
const FLAG_UV: u8 = 0x04; // user verified
const FLAG_BE: u8 = 0x08; // backup eligible: the passkey can leave this device
const FLAG_BS: u8 = 0x10; // backed up
const FLAG_AT: u8 = 0x40; // attested credential data follows

/// Who is asking. This decides what goes into the client data, which the site checks.
pub enum Client<'a> {
    /// A browser the app has verified (package name and signing certificate). It builds the
    /// client data itself, with the page's origin, and hands over only its SHA-256.
    Browser { client_data_hash: &'a [u8] },
    /// Any other app. The client data is built here, with the app's own origin:
    /// `android:apk-key-hash:` and the SHA-256 of its signing certificate. The site checks that
    /// origin against the apps it allows.
    App {
        package: &'a str,
        /// SHA-256 of each signing certificate: the current one first, then any earlier ones
        /// from key rotation.
        certificates: &'a [[u8; 32]],
    },
}

/// A request to create a passkey (`PublicKeyCredentialCreationOptionsJSON`).
pub(crate) struct CreationRequest {
    pub rp_id: String,
    pub rp_name: String,
    pub user_id: Vec<u8>,
    pub user_name: String,
    pub user_display_name: String,
    pub challenge: Vec<u8>,
    /// Whether the site accepts ES256 keys.
    pub accepts_es256: bool,
    /// Credential IDs the site already has for this account. Making another would be a
    /// duplicate.
    pub exclude: Vec<Vec<u8>>,
    /// The site asked whether the passkey is discoverable (`credProps`).
    pub cred_props: bool,
}

/// A request to sign in with a passkey (`PublicKeyCredentialRequestOptionsJSON`).
pub(crate) struct AssertionRequest {
    pub rp_id: String,
    pub challenge: Vec<u8>,
    /// The passkeys the site will accept. Empty means any of the user's passkeys for the site.
    pub allow: Vec<Vec<u8>>,
}

fn bad_request(what: &str) -> Error {
    Error::Invalid(format!("The sign-in request isn't valid: {what}."))
}

fn parse_json(json: &str) -> Result<Value> {
    let value: Value = serde_json::from_str(json).map_err(|_| bad_request("it isn't JSON"))?;
    if !value.is_object() {
        return Err(bad_request("it isn't a JSON object"));
    }
    Ok(value)
}

fn string<'a>(value: &'a Value, key: &str) -> Option<&'a str> {
    value.get(key).and_then(Value::as_str)
}

fn binary(value: &Value, key: &str) -> Result<Vec<u8>> {
    let text = string(value, key).ok_or_else(|| bad_request(&format!("“{key}” is missing")))?;
    decode_b64(text).ok_or_else(|| bad_request(&format!("“{key}” isn't base64url")))
}

/// Decodes base64url (with or without padding), or failing that, plain base64.
pub(crate) fn decode_b64(text: &str) -> Option<Vec<u8>> {
    let text = text.trim();
    B64URL.decode(text).or_else(|_| B64.decode(text)).ok()
}

fn credential_ids(value: &Value, key: &str) -> Result<Vec<Vec<u8>>> {
    let Some(list) = value.get(key) else {
        return Ok(Vec::new());
    };
    let list = list
        .as_array()
        .ok_or_else(|| bad_request(&format!("“{key}” isn't a list")))?;
    list.iter()
        .filter(|c| string(c, "type").is_none_or(|t| t == "public-key"))
        .map(|c| binary(c, "id"))
        .collect()
}

/// An rpId is a domain name. Lowercase it, and refuse anything that can't be one.
fn rp_id(text: Option<&str>) -> Result<String> {
    let id = text
        .unwrap_or("")
        .trim()
        .trim_end_matches('.')
        .to_ascii_lowercase();
    let valid = !id.is_empty()
        && id.len() <= 253
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'.')
        && !id.starts_with('.')
        && !id.contains("..");
    if valid {
        Ok(id)
    } else {
        Err(bad_request("the site's name (rpId) isn't a domain name"))
    }
}

impl CreationRequest {
    pub fn parse(json: &str) -> Result<Self> {
        let value = parse_json(json)?;
        let rp = value
            .get("rp")
            .ok_or_else(|| bad_request("“rp” is missing"))?;
        let user = value
            .get("user")
            .ok_or_else(|| bad_request("“user” is missing"))?;
        let user_id = binary(user, "id")?;
        if user_id.is_empty() || user_id.len() > 64 {
            return Err(bad_request("the user ID isn't 1 to 64 bytes"));
        }
        let accepts_es256 = value
            .get("pubKeyCredParams")
            .and_then(Value::as_array)
            .is_some_and(|params| {
                params.iter().any(|p| {
                    string(p, "type").is_none_or(|t| t == "public-key")
                        && p.get("alg").and_then(Value::as_i64) == Some(ES256)
                })
            });
        let cred_props = value
            .get("extensions")
            .and_then(|e| e.get("credProps"))
            .and_then(Value::as_bool)
            .unwrap_or(false);
        Ok(Self {
            rp_id: rp_id(string(rp, "id"))?,
            rp_name: string(rp, "name").unwrap_or("").trim().to_owned(),
            user_id,
            user_name: string(user, "name").unwrap_or("").trim().to_owned(),
            user_display_name: string(user, "displayName").unwrap_or("").trim().to_owned(),
            challenge: binary(&value, "challenge")?,
            accepts_es256,
            exclude: credential_ids(&value, "excludeCredentials")?,
            cred_props,
        })
    }
}

impl AssertionRequest {
    pub fn parse(json: &str) -> Result<Self> {
        let value = parse_json(json)?;
        Ok(Self {
            rp_id: rp_id(string(&value, "rpId"))?,
            challenge: binary(&value, "challenge")?,
            allow: credential_ids(&value, "allowCredentials")?,
        })
    }
}

/// The client data and its hash, as the response carries them.
struct ClientData {
    /// base64url of `clientDataJSON`, or the placeholder for a browser.
    encoded: String,
    hash: [u8; 32],
}

impl Client<'_> {
    fn client_data(&self, kind: &str, challenge: &[u8]) -> Result<ClientData> {
        match self {
            Client::Browser { client_data_hash } => Ok(ClientData {
                encoded: CLIENT_DATA_PLACEHOLDER.to_owned(),
                hash: (*client_data_hash)
                    .try_into()
                    .map_err(|_| bad_request("the browser's client data hash isn't SHA-256"))?,
            }),
            Client::App { package, .. } => {
                let json = app_client_data_json(kind, challenge, &self.app_origin()?, package);
                Ok(ClientData {
                    encoded: B64URL.encode(&json),
                    hash: *sha256(&[json.as_bytes()]),
                })
            }
        }
    }

    /// `android:apk-key-hash:` and the base64url SHA-256 of the app's current signing
    /// certificate: how WebAuthn names an Android app as an origin.
    pub(crate) fn app_origin(&self) -> Result<String> {
        match self {
            Client::App { certificates, .. } => {
                let current = certificates
                    .first()
                    .ok_or_else(|| bad_request("the app has no signing certificate"))?;
                Ok(format!("android:apk-key-hash:{}", B64URL.encode(current)))
            }
            Client::Browser { .. } => Err(bad_request("a browser has no app origin")),
        }
    }
}

/// `clientDataJSON` for an app, with the members in the order WebAuthn's own serialization
/// uses (`type`, `challenge`, `origin`), so even a site that checks it byte by byte accepts it.
fn app_client_data_json(kind: &str, challenge: &[u8], origin: &str, package: &str) -> String {
    let quote = |s: &str| Value::from(s).to_string();
    format!(
        "{{\"type\":{},\"challenge\":{},\"origin\":{},\"crossOrigin\":false,\"androidPackageName\":{}}}",
        quote(kind),
        quote(&B64URL.encode(challenge)),
        quote(origin),
        quote(package),
    )
}

/// A passkey's private key, of any kind PocketKeePass can sign with. Each wipes itself when
/// dropped.
pub(crate) enum PasskeyKey {
    /// COSE -7: ECDSA on P-256 with SHA-256. The only kind we make.
    Es256(p256::ecdsa::SigningKey),
    /// COSE -257: RSA with PKCS#1 v1.5 padding and SHA-256.
    Rs256(rsa::pkcs1v15::SigningKey<Sha256>),
    /// COSE -8: Ed25519.
    EdDsa(ed25519_dalek::SigningKey),
}

impl PasskeyKey {
    /// A signature over `message`, encoded the way WebAuthn sends each kind: DER for ECDSA, the
    /// raw bytes for RSA and Ed25519.
    pub fn sign(&self, message: &[u8]) -> Result<Vec<u8>> {
        match self {
            PasskeyKey::Es256(key) => {
                let signature: p256::ecdsa::Signature = key.sign(message);
                Ok(signature.to_der().as_bytes().to_vec())
            }
            PasskeyKey::Rs256(key) => key
                .try_sign(message)
                .map(|signature| signature.to_vec())
                .map_err(|_| Error::Invalid("The passkey couldn't sign the request.".into())),
            PasskeyKey::EdDsa(key) => Ok(key.sign(message).to_bytes().to_vec()),
        }
    }
}

/// Everything the signing steps need about one passkey.
pub(crate) struct Credential<'a> {
    pub id: &'a [u8],
    pub key: &'a PasskeyKey,
    pub backup_eligible: bool,
    pub backed_up: bool,
}

impl Credential<'_> {
    fn flags(&self) -> u8 {
        // Every use is with the user present and verified: the app asks for the screen lock or
        // a biometric (or the database was just unlocked) before it signs anything.
        let mut flags = FLAG_UP | FLAG_UV;
        if self.backup_eligible {
            flags |= FLAG_BE;
            if self.backed_up {
                flags |= FLAG_BS;
            }
        }
        flags
    }
}

/// The authenticator data: the site's rpId hash, the flags, and a signature counter of 0 (a
/// passkey that lives in a synced file can't keep a reliable counter, and 0 tells the site not
/// to check). On registration the new credential follows.
fn authenticator_data(rp_id: &str, flags: u8, attested: Option<(&[u8], &[u8])>) -> Vec<u8> {
    let mut data =
        Vec::with_capacity(37 + attested.map_or(0, |(id, key)| 18 + id.len() + key.len()));
    data.extend_from_slice(&*sha256(&[rp_id.as_bytes()]));
    data.push(if attested.is_some() {
        flags | FLAG_AT
    } else {
        flags
    });
    data.extend_from_slice(&0u32.to_be_bytes());
    if let Some((id, cose_key)) = attested {
        data.extend_from_slice(&AAGUID);
        data.extend_from_slice(&(id.len() as u16).to_be_bytes());
        data.extend_from_slice(id);
        data.extend_from_slice(cose_key);
    }
    data
}

/// Builds the response to a creation request, for a key that's just been made.
pub(crate) fn registration_response(
    request: &CreationRequest,
    credential: &Credential<'_>,
    client: &Client<'_>,
) -> Result<String> {
    let PasskeyKey::Es256(key) = credential.key else {
        return Err(Error::PasskeyUnsupported(
            "New passkeys are always P-256 (ES256).".into(),
        ));
    };
    let client_data = client.client_data("webauthn.create", &request.challenge)?;
    let public = p256::PublicKey::from(key.verifying_key());
    let cose_key = cose_es256_key(&public.to_sec1_bytes());
    let auth_data = authenticator_data(
        &request.rp_id,
        credential.flags(),
        Some((credential.id, &cose_key)),
    );
    let spki = public
        .to_public_key_der()
        .map_err(|_| Error::Invalid("The new passkey's public key couldn't be encoded.".into()))?;
    let id = B64URL.encode(credential.id);
    let mut extensions = json!({});
    if request.cred_props {
        extensions = json!({ "credProps": { "rk": true } });
    }
    Ok(json!({
        "id": id,
        "rawId": id,
        "type": "public-key",
        "authenticatorAttachment": "platform",
        "response": {
            "clientDataJSON": client_data.encoded,
            "attestationObject": B64URL.encode(attestation_object(&auth_data)),
            "authenticatorData": B64URL.encode(&auth_data),
            "publicKey": B64URL.encode(spki.as_bytes()),
            "publicKeyAlgorithm": ES256,
            "transports": ["internal", "hybrid"],
        },
        "clientExtensionResults": extensions,
    })
    .to_string())
}

/// Builds the response to a sign-in request: a signature over the authenticator data and the
/// client data hash.
pub(crate) fn assertion_response(
    request: &AssertionRequest,
    credential: &Credential<'_>,
    user_handle: &[u8],
    client: &Client<'_>,
) -> Result<String> {
    let client_data = client.client_data("webauthn.get", &request.challenge)?;
    let auth_data = authenticator_data(&request.rp_id, credential.flags(), None);
    let mut signed = Vec::with_capacity(auth_data.len() + 32);
    signed.extend_from_slice(&auth_data);
    signed.extend_from_slice(&client_data.hash);
    let signature = credential.key.sign(&signed)?;
    let id = B64URL.encode(credential.id);
    Ok(json!({
        "id": id,
        "rawId": id,
        "type": "public-key",
        "authenticatorAttachment": "platform",
        "response": {
            "clientDataJSON": client_data.encoded,
            "authenticatorData": B64URL.encode(&auth_data),
            "signature": B64URL.encode(&signature),
            "userHandle": B64URL.encode(user_handle),
        },
        "clientExtensionResults": {},
    })
    .to_string())
}

// --- CBOR (RFC 8949), only the pieces the two objects below need. ---

/// A CBOR head: major type and argument, in the shortest form (as CTAP2's canonical form
/// requires).
fn cbor_head(out: &mut Vec<u8>, major: u8, value: u64) {
    let major = major << 5;
    match value {
        0..=23 => out.push(major | value as u8),
        24..=0xff => out.extend_from_slice(&[major | 24, value as u8]),
        0x100..=0xffff => {
            out.push(major | 25);
            out.extend_from_slice(&(value as u16).to_be_bytes());
        }
        0x1_0000..=0xffff_ffff => {
            out.push(major | 26);
            out.extend_from_slice(&(value as u32).to_be_bytes());
        }
        _ => {
            out.push(major | 27);
            out.extend_from_slice(&value.to_be_bytes());
        }
    }
}

fn cbor_int(out: &mut Vec<u8>, value: i64) {
    if value >= 0 {
        cbor_head(out, 0, value as u64);
    } else {
        cbor_head(out, 1, (-1 - value) as u64);
    }
}

fn cbor_bytes(out: &mut Vec<u8>, bytes: &[u8]) {
    cbor_head(out, 2, bytes.len() as u64);
    out.extend_from_slice(bytes);
}

fn cbor_text(out: &mut Vec<u8>, text: &str) {
    cbor_head(out, 3, text.len() as u64);
    out.extend_from_slice(text.as_bytes());
}

fn cbor_map(out: &mut Vec<u8>, entries: u64) {
    cbor_head(out, 5, entries);
}

/// The public key as a COSE_Key (RFC 9053): an EC2 key on P-256 for ES256. The map keys are in
/// CTAP2's canonical order: 1, 3, -1, -2, -3. `point` is the uncompressed SEC1 form
/// (`04 ‖ x ‖ y`).
fn cose_es256_key(point: &[u8]) -> Vec<u8> {
    assert_eq!(point.len(), 65, "P-256 points are written uncompressed");
    let (x, y) = point[1..].split_at(32);
    let mut out = Vec::with_capacity(77);
    cbor_map(&mut out, 5);
    cbor_int(&mut out, 1); // kty
    cbor_int(&mut out, 2); // EC2
    cbor_int(&mut out, 3); // alg
    cbor_int(&mut out, ES256);
    cbor_int(&mut out, -1); // crv
    cbor_int(&mut out, 1); // P-256
    cbor_int(&mut out, -2); // x
    cbor_bytes(&mut out, x);
    cbor_int(&mut out, -3); // y
    cbor_bytes(&mut out, y);
    out
}

/// `{"fmt": "none", "attStmt": {}, "authData": …}`: no attestation, the usual choice for
/// passkeys. Keys in canonical order (shorter first).
fn attestation_object(auth_data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(auth_data.len() + 32);
    cbor_map(&mut out, 3);
    cbor_text(&mut out, "fmt");
    cbor_text(&mut out, "none");
    cbor_text(&mut out, "attStmt");
    cbor_map(&mut out, 0);
    cbor_text(&mut out, "authData");
    cbor_bytes(&mut out, auth_data);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::signature::Verifier;
    use p256::ecdsa::{Signature, SigningKey, VerifyingKey};

    fn key() -> SigningKey {
        SigningKey::from_slice(&[7; 32]).unwrap()
    }

    const CREATE: &str = r#"{
        "rp": {"id": "Example.com", "name": "Example"},
        "user": {"id": "dXNlci0x", "name": "alice@example.com", "displayName": "Alice"},
        "challenge": "Y2hhbGxlbmdl",
        "pubKeyCredParams": [{"type": "public-key", "alg": -8}, {"type": "public-key", "alg": -7}],
        "excludeCredentials": [{"type": "public-key", "id": "AQID"}],
        "extensions": {"credProps": true}
    }"#;

    #[test]
    fn cbor_heads_use_the_shortest_form() {
        let mut out = Vec::new();
        for n in [0, 23, 24, 255, 256, 65535, 65536] {
            cbor_head(&mut out, 0, n);
        }
        assert_eq!(
            out,
            [
                0x00, 0x17, 0x18, 0x18, 0x18, 0xff, 0x19, 0x01, 0x00, 0x19, 0xff, 0xff, 0x1a, 0, 1,
                0, 0
            ]
        );
        let mut neg = Vec::new();
        for n in [-1, -7, -24, -25] {
            cbor_int(&mut neg, n);
        }
        assert_eq!(neg, [0x20, 0x26, 0x37, 0x38, 0x18]);
    }

    #[test]
    fn cose_key_has_the_canonical_layout() {
        let point = p256::PublicKey::from(key().verifying_key()).to_sec1_bytes();
        assert_eq!(point[0], 0x04);
        let cose = cose_es256_key(&point);
        assert_eq!(
            &cose[..10],
            [0xa5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21, 0x58, 0x20]
        );
        assert_eq!(&cose[10..42], &point[1..33]);
        assert_eq!(&cose[42..45], [0x22, 0x58, 0x20]);
        assert_eq!(&cose[45..], &point[33..]);
    }

    #[test]
    fn attestation_object_is_none_format() {
        let obj = attestation_object(&[0xaa; 3]);
        let mut expected = vec![0xa3, 0x63];
        expected.extend_from_slice(b"fmt");
        expected.push(0x64);
        expected.extend_from_slice(b"none");
        expected.push(0x67);
        expected.extend_from_slice(b"attStmt");
        expected.extend_from_slice(&[0xa0, 0x68]);
        expected.extend_from_slice(b"authData");
        expected.extend_from_slice(&[0x43, 0xaa, 0xaa, 0xaa]);
        assert_eq!(obj, expected);
    }

    #[test]
    fn parses_a_creation_request() {
        let r = CreationRequest::parse(CREATE).unwrap();
        assert_eq!(r.rp_id, "example.com");
        assert_eq!(r.rp_name, "Example");
        assert_eq!(r.user_id, b"user-1");
        assert_eq!(r.user_name, "alice@example.com");
        assert_eq!(r.challenge, b"challenge");
        assert!(r.accepts_es256);
        assert_eq!(r.exclude, vec![vec![1, 2, 3]]);
        assert!(r.cred_props);

        let rsa_only = CREATE.replace("-7}", "-257}");
        assert!(!CreationRequest::parse(&rsa_only).unwrap().accepts_es256);
    }

    #[test]
    fn refuses_bad_requests() {
        assert!(CreationRequest::parse("[]").is_err());
        assert!(CreationRequest::parse(&CREATE.replace("Example.com", "evil site/")).is_err());
        assert!(CreationRequest::parse(&CREATE.replace("dXNlci0x", "")).is_err());
        assert!(AssertionRequest::parse(r#"{"challenge": "AA"}"#).is_err());
        assert!(AssertionRequest::parse(r#"{"rpId": "a.com", "challenge": "*"}"#).is_err());
    }

    #[test]
    fn app_client_data_names_the_app() {
        let certs = [[0xab; 32]];
        let client = Client::App {
            package: "com.example.app",
            certificates: &certs,
        };
        let data = client.client_data("webauthn.get", b"hi").unwrap();
        let json = String::from_utf8(B64URL.decode(&data.encoded).unwrap()).unwrap();
        assert_eq!(
            json,
            format!(
                r#"{{"type":"webauthn.get","challenge":"aGk","origin":"android:apk-key-hash:{}","crossOrigin":false,"androidPackageName":"com.example.app"}}"#,
                B64URL.encode([0xab; 32])
            )
        );
        assert_eq!(data.hash, *sha256(&[json.as_bytes()]));
    }

    #[test]
    fn registration_then_assertion_verify() {
        let request = CreationRequest::parse(CREATE).unwrap();
        let key = PasskeyKey::Es256(key());
        let credential = Credential {
            id: &[9; 32],
            key: &key,
            backup_eligible: true,
            backed_up: true,
        };
        let hash = [5u8; 32];
        let client = Client::Browser {
            client_data_hash: &hash,
        };
        let response: Value =
            serde_json::from_str(&registration_response(&request, &credential, &client).unwrap())
                .unwrap();
        assert_eq!(response["id"], B64URL.encode([9; 32]));
        assert_eq!(
            response["response"]["clientDataJSON"],
            CLIENT_DATA_PLACEHOLDER
        );
        assert_eq!(response["response"]["publicKeyAlgorithm"], -7);
        assert_eq!(response["clientExtensionResults"]["credProps"]["rk"], true);
        let auth_data = B64URL
            .decode(response["response"]["authenticatorData"].as_str().unwrap())
            .unwrap();
        assert_eq!(&auth_data[..32], &*sha256(&[b"example.com"]));
        assert_eq!(
            auth_data[32],
            FLAG_UP | FLAG_UV | FLAG_BE | FLAG_BS | FLAG_AT
        );
        assert_eq!(&auth_data[33..37], [0; 4]);
        assert_eq!(&auth_data[37..53], AAGUID);
        assert_eq!(&auth_data[53..55], [0, 32]);
        assert_eq!(&auth_data[55..87], [9; 32]);
        let attestation = B64URL
            .decode(response["response"]["attestationObject"].as_str().unwrap())
            .unwrap();
        assert!(attestation.ends_with(&auth_data));

        // The public key the site stores verifies the signature from a later sign-in.
        let spki = B64URL
            .decode(response["response"]["publicKey"].as_str().unwrap())
            .unwrap();
        use p256::pkcs8::DecodePublicKey;
        let verifying = VerifyingKey::from_public_key_der(&spki).unwrap();

        let get = AssertionRequest::parse(r#"{"rpId": "example.com", "challenge": "AQ"}"#).unwrap();
        let certs = [[1; 32]];
        let app = Client::App {
            package: "com.example",
            certificates: &certs,
        };
        let response: Value =
            serde_json::from_str(&assertion_response(&get, &credential, b"user-1", &app).unwrap())
                .unwrap();
        let r = &response["response"];
        let auth_data = B64URL
            .decode(r["authenticatorData"].as_str().unwrap())
            .unwrap();
        assert_eq!(auth_data.len(), 37);
        assert_eq!(auth_data[32], FLAG_UP | FLAG_UV | FLAG_BE | FLAG_BS);
        let client_json = B64URL
            .decode(r["clientDataJSON"].as_str().unwrap())
            .unwrap();
        let mut signed = auth_data.clone();
        signed.extend_from_slice(&*sha256(&[&client_json]));
        let signature =
            Signature::from_der(&B64URL.decode(r["signature"].as_str().unwrap()).unwrap()).unwrap();
        verifying.verify(&signed, &signature).unwrap();
        assert_eq!(r["userHandle"], B64URL.encode(b"user-1"));
    }
}
