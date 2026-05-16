//! Error type for the SSF1 crate.

#[derive(Debug, thiserror::Error)]
pub enum Ssf1Error {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("malformed header: {0}")]
    MalformedHeader(&'static str),

    #[error("authentication failed: {0}")]
    AuthFailure(&'static str),

    #[error("invalid BIP-39 phrase: {0}")]
    BadPhrase(&'static str),

    #[error("invalid Crockford Base32: {0}")]
    BadCrockford(&'static str),

    #[error("invalid transfer code: {0}")]
    BadCode(&'static str),

    #[error("invalid argument: {0}")]
    InvalidArgument(&'static str),
}
