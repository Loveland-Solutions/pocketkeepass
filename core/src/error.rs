/// Everything that can go wrong opening a database, worded so the app can show it to a person.
#[derive(Debug, thiserror::Error)]
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
}

pub type Result<T> = std::result::Result<T, Error>;

pub(crate) fn corrupt(what: impl Into<String>) -> Error {
    Error::Corrupt(what.into())
}
