#[derive(Debug, thiserror::Error)]
pub enum Ssf1Error {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("malformed header: {0}")]
    MalformedHeader(&'static str),

    #[error("authentication failed: {0}")]
    AuthFailure(&'static str),

    #[error("invalid Crockford Base32: {0}")]
    BadCrockford(&'static str),

    #[error("invalid argument: {0}")]
    InvalidArgument(&'static str),
}
