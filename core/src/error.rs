/// Everything that can go wrong opening or saving a database, worded so the app can show it to a person.
///
/// The app sees this as `KeePassException`, one subclass per variant, with these messages.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error, name = "KeePassError")]
pub enum Error {
    #[error("The password or key file is incorrect.")]
    WrongKey,

    #[error("That key file couldn't be read.")]
    InvalidKeyFile,

    #[error("This isn't a KeePass database.")]
    NotKeePass,

    #[error("This KeePass version ({0}) isn't supported.")]
    UnsupportedVersion(String),

    #[error("This database uses a feature that isn't supported yet: {0}.")]
    Unsupported(String),

    #[error("The database is damaged: {0}.")]
    Corrupt(String),

    #[error(
        "This database uses the older KDBX 3 format. It has to be upgraded to KDBX 4 before it can be saved."
    )]
    NeedsUpgrade,

    #[error("This device couldn't provide secure random numbers, so the database wasn't saved.")]
    Random,

    #[error("The new copy of the database failed its safety check, so it wasn't saved.")]
    SaveCheckFailed,

    #[error("The database is locked.")]
    Locked,
}

pub type Result<T> = std::result::Result<T, Error>;

pub(crate) fn corrupt(what: impl Into<String>) -> Error {
    Error::Corrupt(what.into())
}
