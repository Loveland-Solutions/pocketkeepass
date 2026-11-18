//! The KeePass engine behind PocketKeePass.
//!
//! Everything KeePass-specific is implemented here, from the file format to key handling to the
//! data model. Only the cryptographic primitives (AES, ChaCha20, Salsa20, Twofish, Argon2, SHA-1,
//! SHA-2, HMAC) come from the RustCrypto crates.
//!
//! Open a database with [`Database::open`], using a [`CompositeKey`] built from the user's
//! password and/or key file. Turn it back into a `.kdbx` file with [`Database::save`].
//!
//! The Android app doesn't use these types directly. It goes through the bridge in [`ffi`].

mod autofill;
mod browse;
mod edit;
mod error;
pub mod ffi;
mod generator;
mod kdbx;
mod key;
mod model;
mod passkey;
mod totp;
mod util;
mod webauthn;
pub mod xml;

pub use autofill::FillTarget;
pub use browse::{Listing, SearchHit};
pub use edit::{Deletion, EntryEdit, FieldEdit, FieldValue, STANDARD_FIELDS};
pub use error::Error;
pub use generator::{PasswordOptions, Symbols};
pub use kdbx::FormatVersion;
pub use key::CompositeKey;
pub use model::{Database, Entry, Field, Group, uuid_from_hex, uuid_to_hex};
pub use passkey::{
    AppTrust, CreatedPasskey, CreationSummary, PasskeyHit, PasskeyStatus, PasskeyTarget,
    has_passkey, is_passkey_field,
};
pub use totp::{OTP_FIELD, Totp, TotpAlgorithm};
pub use webauthn::Client;

uniffi::setup_scaffolding!();
