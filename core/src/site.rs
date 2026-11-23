//! Passkey requests as Apple's AuthenticationServices hands them to a credential provider:
//! fields rather than WebAuthn JSON, and always with the hash of client data the system built
//! itself (Safari for a web page, iOS for an app, after checking the app's associated domains).
//! So every request here is answered like a verified browser's ([`Client::Browser`]): the
//! signature covers that hash, and no client data is made here.
//!
//! The engine's passkey code works on WebAuthn's JSON (what Android's Credential Manager
//! sends), so these turn the fields into that JSON, and the response JSON back into the raw
//! pieces Apple's API wants. The challenge isn't given (it's inside the hashed client data), and
//! a [`Client::Browser`] request never uses it, so a placeholder fills its place.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64URL;
use serde_json::{Value, json};

use crate::error::{Error, Result};
use crate::webauthn::decode_b64;

/// Stands in for the challenge, which only the client data (already hashed) holds.
const NO_CHALLENGE: &str = "AA";

/// The WebAuthn request JSON for a sign-in at `rp_id`, accepting only `allowed` credentials
/// (any of the user's for the site if empty).
pub(crate) fn assertion_json(rp_id: &str, allowed: &[Vec<u8>]) -> String {
    let allow: Vec<Value> = allowed
        .iter()
        .map(|id| json!({ "type": "public-key", "id": B64URL.encode(id) }))
        .collect();
    json!({
        "rpId": rp_id,
        "challenge": NO_CHALLENGE,
        "allowCredentials": allow,
        "userVerification": "required",
    })
    .to_string()
}

/// The WebAuthn request JSON to create a passkey at `rp_id` for an account. `algorithms` are
/// the COSE algorithms the site accepts, `excluded` the credentials it already has.
pub(crate) fn creation_json(
    rp_id: &str,
    user_name: &str,
    user_handle: &[u8],
    algorithms: &[i64],
    excluded: &[Vec<u8>],
) -> String {
    let params: Vec<Value> = algorithms
        .iter()
        .map(|alg| json!({ "type": "public-key", "alg": alg }))
        .collect();
    let exclude: Vec<Value> = excluded
        .iter()
        .map(|id| json!({ "type": "public-key", "id": B64URL.encode(id) }))
        .collect();
    json!({
        "rp": { "id": rp_id, "name": rp_id },
        "user": { "id": B64URL.encode(user_handle), "name": user_name, "displayName": user_name },
        "challenge": NO_CHALLENGE,
        "pubKeyCredParams": params,
        "excludeCredentials": exclude,
        "authenticatorSelection": { "residentKey": "required", "userVerification": "required" },
    })
    .to_string()
}

/// One binary member of a response the engine just made.
fn member(response: &Value, key: &str) -> Result<Vec<u8>> {
    response
        .get(key)
        .and_then(Value::as_str)
        .and_then(decode_b64)
        .ok_or_else(|| Error::Invalid(format!("The passkey response has no {key}.")))
}

fn parse(json: &str) -> Result<Value> {
    serde_json::from_str(json)
        .map_err(|_| Error::Invalid("The passkey response isn't JSON.".into()))
}

/// What a sign-in hands back: credential ID, authenticator data, signature, user handle.
pub(crate) struct Assertion {
    pub credential_id: Vec<u8>,
    pub authenticator_data: Vec<u8>,
    pub signature: Vec<u8>,
    pub user_handle: Vec<u8>,
}

pub(crate) fn assertion(response_json: &str) -> Result<Assertion> {
    let value = parse(response_json)?;
    let response = value.get("response").unwrap_or(&Value::Null);
    Ok(Assertion {
        credential_id: member(&value, "rawId")?,
        authenticator_data: member(response, "authenticatorData")?,
        signature: member(response, "signature")?,
        user_handle: member(response, "userHandle")?,
    })
}

/// What a new passkey hands back: its credential ID and attestation object.
pub(crate) struct Registration {
    pub credential_id: Vec<u8>,
    pub attestation_object: Vec<u8>,
}

pub(crate) fn registration(response_json: &str) -> Result<Registration> {
    let value = parse(response_json)?;
    let response = value.get("response").unwrap_or(&Value::Null);
    Ok(Registration {
        credential_id: member(&value, "rawId")?,
        attestation_object: member(response, "attestationObject")?,
    })
}
