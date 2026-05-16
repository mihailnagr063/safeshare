//! Transfer code: `<file_id>.<16 BIP-39 words>` (verbose) or
//! `<file_id>.<base64url(K||R)>` (compact).
//!
//! The verbose form is for human dictation; the compact form is for
//! QR codes and deep links (`sshare://` scheme).
//!
//! See `docs/PROTOCOL.md` §3.2 and §3.3.

use crate::{bip39, crockford, error::Ssf1Error, format::KeyMaterial};

/// Opaque transfer code + separated parts.
#[derive(Debug, Clone)]
pub struct TransferCode {
    pub file_id: [u8; 5],
    pub km: KeyMaterial,
}

impl TransferCode {
    /// BIP-39 verbose format: `<crockford_id>.<16 words>`.
    pub fn format(&self) -> Result<String, Ssf1Error> {
        let id_str = crockford::encode_5(&self.file_id);
        let words = bip39::encode(&self.km.key, &self.km.r)?;
        Ok(format!("{}.{}", id_str, words.join(" ")))
    }

    /// Compact base64url format: `<crockford_id>.<base64url(K||R)>`.
    /// Always succeeds (no wordlist needed).
    pub fn format_compact(&self) -> String {
        let id_str = crockford::encode_5(&self.file_id);
        let mut payload = [0u8; 20];
        payload[..16].copy_from_slice(&self.km.key);
        payload[16..].copy_from_slice(&self.km.r);
        let b64 = base64url_encode(&payload);
        format!("{}.{}", id_str, b64)
    }

    /// Format as a `sshare://` URI (for QR codes).
    pub fn format_sshare_uri(&self) -> String {
        format!("sshare://{}", self.format_compact())
    }

    /// Parse a BIP-39 verbose code.
    pub fn parse(s: &str) -> Result<Self, Ssf1Error> {
        let s = strip_scheme(s);
        let dot = s.find('.').ok_or(Ssf1Error::BadCode("missing '.'"))?;
        let (id_part, rest) = s.split_at(dot);
        let phrase = &rest[1..];
        let file_id = crockford::decode_5(id_part)?;
        let words: Vec<&str> = phrase.split_whitespace().collect();
        let (key, r) = bip39::decode(&words)?;
        Ok(TransferCode {
            file_id,
            km: KeyMaterial { key, r },
        })
    }

    /// Parse a compact base64url code.
    pub fn parse_compact(s: &str) -> Result<Self, Ssf1Error> {
        let s = strip_scheme(s);
        let dot = s.find('.').ok_or(Ssf1Error::BadCode("missing '.'"))?;
        let (id_part, rest) = s.split_at(dot);
        let b64 = &rest[1..];
        let file_id = crockford::decode_5(id_part)?;
        let payload = base64url_decode(b64)
            .map_err(|_| Ssf1Error::BadCode("invalid base64url"))?;
        if payload.len() != 20 {
            return Err(Ssf1Error::BadCode("compact payload must be 20 bytes"));
        }
        let mut key = [0u8; 16];
        let mut r = [0u8; 4];
        key.copy_from_slice(&payload[..16]);
        r.copy_from_slice(&payload[16..]);
        Ok(TransferCode {
            file_id,
            km: KeyMaterial { key, r },
        })
    }

    /// Auto-detect the format and parse. If the payload after the
    /// dot contains whitespace → BIP-39; otherwise → compact base64url.
    pub fn parse_any(s: &str) -> Result<Self, Ssf1Error> {
        let s = strip_scheme(s);
        let dot = s.find('.').ok_or(Ssf1Error::BadCode("missing '.'"))?;
        let after_dot = &s[dot + 1..];
        if after_dot.contains(' ') {
            Self::parse(s)
        } else {
            Self::parse_compact(s)
        }
    }
}

/// Strip `sshare://` or `https://...?c=` prefix, if present.
fn strip_scheme(s: &str) -> &str {
    let s = s.trim();
    if let Some(rest) = s.strip_prefix("sshare://") {
        return rest;
    }
    // https://host/r?c=CODE
    if let Some(pos) = s.find("?c=") {
        return &s[pos + 3..];
    }
    s
}

// --- base64url (RFC 4648 §5, no padding) ---

const B64_CHARS: &[u8; 64] =
    b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

fn base64url_encode(data: &[u8]) -> String {
    let mut out = Vec::with_capacity((data.len() * 4 + 2) / 3);
    let mut i = 0;
    while i + 2 < data.len() {
        let b0 = data[i] as u32;
        let b1 = data[i + 1] as u32;
        let b2 = data[i + 2] as u32;
        let triple = (b0 << 16) | (b1 << 8) | b2;
        out.push(B64_CHARS[((triple >> 18) & 0x3f) as usize]);
        out.push(B64_CHARS[((triple >> 12) & 0x3f) as usize]);
        out.push(B64_CHARS[((triple >> 6) & 0x3f) as usize]);
        out.push(B64_CHARS[(triple & 0x3f) as usize]);
        i += 3;
    }
    let remaining = data.len() - i;
    if remaining == 2 {
        let b0 = data[i] as u32;
        let b1 = data[i + 1] as u32;
        let triple = (b0 << 16) | (b1 << 8);
        out.push(B64_CHARS[((triple >> 18) & 0x3f) as usize]);
        out.push(B64_CHARS[((triple >> 12) & 0x3f) as usize]);
        out.push(B64_CHARS[((triple >> 6) & 0x3f) as usize]);
    } else if remaining == 1 {
        let b0 = data[i] as u32;
        let triple = b0 << 16;
        out.push(B64_CHARS[((triple >> 18) & 0x3f) as usize]);
        out.push(B64_CHARS[((triple >> 12) & 0x3f) as usize]);
    }
    String::from_utf8(out).unwrap()
}

fn base64url_decode(s: &str) -> Result<Vec<u8>, ()> {
    let mut out = Vec::with_capacity(s.len() * 3 / 4);
    let bytes: Vec<u8> = s
        .bytes()
        .map(|b| match b {
            b'A'..=b'Z' => Ok(b - b'A'),
            b'a'..=b'z' => Ok(b - b'a' + 26),
            b'0'..=b'9' => Ok(b - b'0' + 52),
            b'-' => Ok(62),
            b'_' => Ok(63),
            b'=' => Ok(0xff), // padding — skip
            _ => Err(()),
        })
        .collect::<Result<_, _>>()?;
    let clean: Vec<u8> = bytes.into_iter().filter(|&b| b != 0xff).collect();
    let mut i = 0;
    while i + 3 < clean.len() {
        let triple =
            ((clean[i] as u32) << 18)
                | ((clean[i + 1] as u32) << 12)
                | ((clean[i + 2] as u32) << 6)
                | (clean[i + 3] as u32);
        out.push(((triple >> 16) & 0xff) as u8);
        out.push(((triple >> 8) & 0xff) as u8);
        out.push((triple & 0xff) as u8);
        i += 4;
    }
    let remaining = clean.len() - i;
    if remaining == 3 {
        let triple =
            ((clean[i] as u32) << 18)
                | ((clean[i + 1] as u32) << 12)
                | ((clean[i + 2] as u32) << 6);
        out.push(((triple >> 16) & 0xff) as u8);
        out.push(((triple >> 8) & 0xff) as u8);
    } else if remaining == 2 {
        let triple = ((clean[i] as u32) << 18) | ((clean[i + 1] as u32) << 12);
        out.push(((triple >> 16) & 0xff) as u8);
    }
    Ok(out)
}
