//! Crockford Base32 for the 5-byte `file_id`.
//!
//! Uses the canonical 32-character alphabet:
//!   0 1 2 3 4 5 6 7 8 9 A B C D E F G H J K M N P Q R S T V W X Y Z
//! Decoding is case-insensitive and treats I/L as 1, O as 0, per
//! Crockford's spec; U is rejected.

use crate::error::Ssf1Error;

const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

pub fn encode_5(bytes: &[u8; 5]) -> String {
    // 40 bits -> 8 base32 chars.
    let mut v: u64 = 0;
    for &b in bytes {
        v = (v << 8) | b as u64;
    }
    let mut out = [0u8; 8];
    for i in (0..8).rev() {
        out[i] = ALPHABET[(v & 0x1f) as usize];
        v >>= 5;
    }
    String::from_utf8(out.to_vec()).expect("ASCII")
}

pub fn decode_5(s: &str) -> Result<[u8; 5], Ssf1Error> {
    if s.len() != 8 {
        return Err(Ssf1Error::BadCrockford("expected 8 characters"));
    }
    let mut v: u64 = 0;
    for ch in s.chars() {
        let c = ch.to_ascii_uppercase();
        let digit = match c {
            '0' | 'O' => 0,
            '1' | 'I' | 'L' => 1,
            '2'..='9' => (c as u8 - b'0') as u64,
            'A' => 10,
            'B' => 11,
            'C' => 12,
            'D' => 13,
            'E' => 14,
            'F' => 15,
            'G' => 16,
            'H' => 17,
            'J' => 18,
            'K' => 19,
            'M' => 20,
            'N' => 21,
            'P' => 22,
            'Q' => 23,
            'R' => 24,
            'S' => 25,
            'T' => 26,
            'V' => 27,
            'W' => 28,
            'X' => 29,
            'Y' => 30,
            'Z' => 31,
            _ => return Err(Ssf1Error::BadCrockford("invalid character")),
        };
        v = (v << 5) | digit;
    }
    // 8 base32 chars = 40 bits; the high 24 bits of `v` must be zero.
    if v >> 40 != 0 {
        return Err(Ssf1Error::BadCrockford("overflow"));
    }
    let mut out = [0u8; 5];
    for i in (0..5).rev() {
        out[i] = (v & 0xff) as u8;
        v >>= 8;
    }
    Ok(out)
}
