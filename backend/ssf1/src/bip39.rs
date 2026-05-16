//! BIP-39 English encoder/decoder for the transfer phrase.
//!
//! The transfer phrase encodes 176 bits:
//! - 128 bits of AES key K
//! -  32 bits of nonce prefix R
//! -  16 bits of SHA-256(K || R) checksum (first 2 bytes)
//! as 16 big-endian 11-bit groups, each mapped to a word in the
//! standard BIP-39 English wordlist.
//!
//! See `docs/PROTOCOL.md` §3.2.1.

use sha2::{Digest, Sha256};

use crate::error::Ssf1Error;

const WORDS_RAW: &str = include_str!("bip39_words.txt");

/// The BIP-39 English word list, loaded once.
pub fn wordlist() -> &'static [&'static str] {
    use std::sync::OnceLock;
    static LIST: OnceLock<Vec<&'static str>> = OnceLock::new();
    LIST.get_or_init(|| {
        let words: Vec<&str> = WORDS_RAW
            .lines()
            .map(str::trim)
            .filter(|l| !l.is_empty() && !l.contains(' '))
            .collect();
        // When the real wordlist is in place there will be 2048
        // entries; the placeholder file ships with zero usable lines.
        // Downstream code returns a clear error rather than panicking
        // if the list is the wrong size.
        words
    })
}

/// Encode 20 bytes of payload (K || R) as 16 English words.
///
/// Returns an error if the wordlist is not installed.
pub fn encode(key: &[u8; 16], r: &[u8; 4]) -> Result<Vec<&'static str>, Ssf1Error> {
    let words = wordlist();
    if words.len() != 2048 {
        return Err(Ssf1Error::BadPhrase(
            "bip39 wordlist missing or wrong size (need 2048 words)",
        ));
    }

    // Build 22-byte big-endian bit buffer: key || r || checksum(2).
    let mut payload = [0u8; 20];
    payload[..16].copy_from_slice(key);
    payload[16..].copy_from_slice(r);

    let digest = Sha256::digest(payload);
    let mut buf = [0u8; 22];
    buf[..20].copy_from_slice(&payload);
    buf[20..].copy_from_slice(&digest[..2]);

    // Slice out 16 groups of 11 bits, MSB-first. The last group
    // (i = 15) starts at bit 165, byte 20, so we need to guard the
    // `byte + 2` read: that index would be 22, one past the buffer.
    let mut out = Vec::with_capacity(16);
    for i in 0..16 {
        let bit = i * 11;
        let byte = bit / 8;
        let shift = bit % 8;
        let hi = buf[byte] as u32;
        let mid = buf[byte + 1] as u32;
        let lo = if byte + 2 < buf.len() {
            buf[byte + 2] as u32
        } else {
            0
        };
        let combined = (hi << 16) | (mid << 8) | lo; // 24 bits
                                                     // Start of the 11-bit window, measured from the MSB of `hi`:
                                                     //   MSB bit 0 corresponds to (combined >> 23) & 1
                                                     // The window begins `shift` bits in from MSB of `hi`.
        let idx = ((combined >> (24 - shift - 11)) & 0x7ff) as usize;
        out.push(words[idx]);
    }
    Ok(out)
}

/// Decode 16 words back to (K, R), verifying the checksum.
pub fn decode(phrase: &[&str]) -> Result<([u8; 16], [u8; 4]), Ssf1Error> {
    if phrase.len() != 16 {
        return Err(Ssf1Error::BadPhrase("expected 16 words"));
    }
    let words = wordlist();
    if words.len() != 2048 {
        return Err(Ssf1Error::BadPhrase(
            "bip39 wordlist missing or wrong size (need 2048 words)",
        ));
    }

    // Map each word to its 11-bit index.
    let mut indices = [0u16; 16];
    for (i, w) in phrase.iter().enumerate() {
        let idx = words
            .iter()
            .position(|x| x.eq_ignore_ascii_case(w))
            .ok_or(Ssf1Error::BadPhrase("word not in wordlist"))?;
        indices[i] = idx as u16;
    }

    // Reassemble 176 bits into 22 bytes, MSB-first.
    let mut buf = [0u8; 22];
    for (i, &idx) in indices.iter().enumerate() {
        let bit = i * 11;
        let idx = idx as u32; // 11 bits, 0..=2047
                              // Write 11 bits starting at bit position `bit`, MSB-first.
                              // Use a local 24-bit window again.
        let byte = bit / 8;
        let shift_in_window = bit % 8;
        let shift_left = 24 - 11 - shift_in_window;
        let hi = buf[byte] as u32;
        let mid = buf[byte + 1] as u32;
        let lo = if byte + 2 < buf.len() {
            buf[byte + 2] as u32
        } else {
            0
        };
        let mut window = (hi << 16) | (mid << 8) | lo;
        // Clear target bits then OR in value.
        let mask = 0x7ffu32 << shift_left;
        window = (window & !mask) | ((idx & 0x7ff) << shift_left);
        buf[byte] = ((window >> 16) & 0xff) as u8;
        buf[byte + 1] = ((window >> 8) & 0xff) as u8;
        if byte + 2 < buf.len() {
            buf[byte + 2] = (window & 0xff) as u8;
        }
    }

    let mut key = [0u8; 16];
    key.copy_from_slice(&buf[..16]);
    let mut r = [0u8; 4];
    r.copy_from_slice(&buf[16..20]);
    let checksum_got = &buf[20..22];

    let mut payload = [0u8; 20];
    payload[..16].copy_from_slice(&key);
    payload[16..].copy_from_slice(&r);
    let digest = Sha256::digest(payload);
    let checksum_want = &digest[..2];

    if checksum_got != checksum_want {
        return Err(Ssf1Error::BadPhrase("checksum mismatch"));
    }
    Ok((key, r))
}
