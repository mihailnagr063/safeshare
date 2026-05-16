//! SSF1 cryptographic format.
//!
//! This crate is the Rust reference implementation of the format
//! described in `docs/PROTOCOL.md`. The server does NOT use it
//! (the server is zero-knowledge and only moves opaque bytes), but
//! the `ssf1-tool` CLI uses it for encrypting, decrypting and
//! cross-testing against the Android client.
//!
//! Everything here is deliberately allocation-free where practical
//! and works in a streaming fashion, so a 1 GiB file never lives in
//! memory at once.

pub mod bip39;
pub mod code;
pub mod crockford;
pub mod error;
pub mod format;

pub use error::Ssf1Error;
pub use format::{decrypt_stream, encrypt_stream, KeyMaterial};
