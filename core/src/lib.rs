//! The KeePass engine behind Pass The Key.
//!
//! Everything KeePass-specific is implemented here, from the file format to key handling to the
//! data model. Only the cryptographic primitives (AES, ChaCha20, Salsa20, Twofish, Argon2, SHA-2,
//! HMAC) come from the RustCrypto crates.
//!
//! Open a database with [`Database::open`], using a [`CompositeKey`] built from the user's
//! password and/or key file. Turn it back into a `.kdbx` file with [`Database::save`].

mod error;
mod kdbx;
mod key;
mod model;
mod util;
pub mod xml;

pub use error::Error;
pub use kdbx::FormatVersion;
pub use key::CompositeKey;
pub use model::{Database, Entry, Group};
