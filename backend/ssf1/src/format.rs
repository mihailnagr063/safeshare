//! Streaming SSF1 encrypt / decrypt.
//!
//! Both functions take a reader and a writer and do not allocate per
//! chunk beyond a single 1 MiB buffer. The byte layout is documented
//! in `docs/PROTOCOL.md` §6.

use std::io::{Read, Write};

use aes_gcm::{
    aead::{AeadInPlace, KeyInit},
    Aes128Gcm, Nonce,
};
use rand::RngCore;

use crate::error::Ssf1Error;

pub const MAGIC: [u8; 4] = *b"SSF1";
pub const VERSION: u8 = 0x01;
pub const CHUNK_SIZE: usize = 1 << 20; // 1 MiB
pub const CHUNK_SIZE_LOG2: u8 = 20;
pub const TAG_LEN: usize = 16;
pub const HEADER_LEN: usize = 24; // magic(4)+ver(1)+log2(1)+res(2)+N(4)+size(8)+R(4)
pub const NAME_NONCE_COUNTER: u64 = 0xFFFF_FFFF_FFFF_FFFE;

/// Freshly generated per-file key material.
#[derive(Debug, Clone)]
pub struct KeyMaterial {
    pub key: [u8; 16],
    pub r: [u8; 4],
}

impl KeyMaterial {
    pub fn generate() -> Self {
        let mut key = [0u8; 16];
        let mut r = [0u8; 4];
        let mut rng = rand::thread_rng();
        rng.fill_bytes(&mut key);
        rng.fill_bytes(&mut r);
        Self { key, r }
    }
}

fn build_nonce(r: &[u8; 4], counter: u64) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[..4].copy_from_slice(r);
    n[4..].copy_from_slice(&counter.to_be_bytes());
    n
}

fn chunk_aad(i: u32, total: u32, last: bool) -> [u8; 19] {
    let mut a = [0u8; 19];
    a[..10].copy_from_slice(b"SSF1:chunk");
    a[10..14].copy_from_slice(&i.to_be_bytes());
    a[14..18].copy_from_slice(&total.to_be_bytes());
    a[18] = if last { 0x01 } else { 0x00 };
    a
}

const NAME_AAD: &[u8; 9] = b"SSF1:name";

/// Encrypt `plaintext_size` bytes read from `reader` with filename
/// `filename` into `writer` using `km`.
///
/// Streams chunk-by-chunk. `plaintext_size` must match the number of
/// bytes actually produced by `reader`.
pub fn encrypt_stream<R: Read, W: Write>(
    km: &KeyMaterial,
    filename: &str,
    plaintext_size: u64,
    mut reader: R,
    mut writer: W,
) -> Result<(), Ssf1Error> {
    if plaintext_size == 0 {
        return Err(Ssf1Error::InvalidArgument("empty files are not allowed"));
    }
    if filename.len() > 255 {
        return Err(Ssf1Error::InvalidArgument("filename longer than 255 bytes"));
    }

    let cipher = Aes128Gcm::new_from_slice(&km.key).expect("16-byte key");
    let total_chunks: u32 = ((plaintext_size + CHUNK_SIZE as u64 - 1) / CHUNK_SIZE as u64)
        .try_into()
        .map_err(|_| Ssf1Error::InvalidArgument("too many chunks"))?;
    if total_chunks == 0 {
        return Err(Ssf1Error::InvalidArgument("total_chunks must be >= 1"));
    }

    // Header
    let mut hdr = [0u8; HEADER_LEN];
    hdr[..4].copy_from_slice(&MAGIC);
    hdr[4] = VERSION;
    hdr[5] = CHUNK_SIZE_LOG2;
    // bytes 6..8 reserved = 0
    hdr[8..12].copy_from_slice(&total_chunks.to_be_bytes());
    hdr[12..20].copy_from_slice(&plaintext_size.to_be_bytes());
    hdr[20..24].copy_from_slice(&km.r);
    writer.write_all(&hdr)?;

    // Filename blob
    let name_bytes = filename.as_bytes();
    let mut name_buf = Vec::with_capacity(name_bytes.len() + TAG_LEN);
    name_buf.extend_from_slice(name_bytes);
    let name_nonce = build_nonce(&km.r, NAME_NONCE_COUNTER);
    let tag = cipher
        .encrypt_in_place_detached(Nonce::from_slice(&name_nonce), NAME_AAD, &mut name_buf)
        .map_err(|_| Ssf1Error::AuthFailure("filename encryption"))?;
    name_buf.extend_from_slice(tag.as_slice());
    let name_len: u16 = name_buf
        .len()
        .try_into()
        .map_err(|_| Ssf1Error::InvalidArgument("filename too long"))?;
    writer.write_all(&name_len.to_be_bytes())?;
    writer.write_all(&name_buf)?;

    // Chunks
    let mut buffer = vec![0u8; CHUNK_SIZE + TAG_LEN];
    let mut bytes_remaining = plaintext_size;
    for i in 0..total_chunks {
        let is_last = i == total_chunks - 1;
        let want: usize = if is_last {
            bytes_remaining as usize
        } else {
            CHUNK_SIZE
        };
        buffer.resize(want, 0);
        read_exact_into(&mut reader, &mut buffer[..want])?;

        let aad = chunk_aad(i, total_chunks, is_last);
        let nonce = build_nonce(&km.r, (i as u64) + 1);
        let tag = cipher
            .encrypt_in_place_detached(Nonce::from_slice(&nonce), &aad, &mut buffer[..want])
            .map_err(|_| Ssf1Error::AuthFailure("chunk encryption"))?;

        writer.write_all(&buffer[..want])?;
        writer.write_all(tag.as_slice())?;

        bytes_remaining -= want as u64;
    }

    if bytes_remaining != 0 {
        return Err(Ssf1Error::InvalidArgument(
            "plaintext_size mismatch with actual reader length",
        ));
    }
    writer.flush()?;
    Ok(())
}

/// Decrypt an SSF1 stream. Returns the recovered filename. Plaintext
/// bytes are written to `writer` as they are produced.
///
/// If `expected_r` is Some, it MUST match the header's R (defence in
/// depth: the transfer code already carries R).
pub fn decrypt_stream<R: Read, W: Write>(
    key: &[u8; 16],
    expected_r: Option<&[u8; 4]>,
    mut reader: R,
    mut writer: W,
) -> Result<String, Ssf1Error> {
    let cipher = Aes128Gcm::new_from_slice(key).expect("16-byte key");

    // Header
    let mut hdr = [0u8; HEADER_LEN];
    read_exact_into(&mut reader, &mut hdr)?;
    if hdr[..4] != MAGIC {
        return Err(Ssf1Error::MalformedHeader("magic"));
    }
    if hdr[4] != VERSION {
        return Err(Ssf1Error::MalformedHeader("version"));
    }
    if hdr[5] != CHUNK_SIZE_LOG2 {
        return Err(Ssf1Error::MalformedHeader("chunk_size_log2"));
    }
    let total_chunks = u32::from_be_bytes(hdr[8..12].try_into().unwrap());
    let plaintext_size = u64::from_be_bytes(hdr[12..20].try_into().unwrap());
    let mut r_hdr = [0u8; 4];
    r_hdr.copy_from_slice(&hdr[20..24]);

    if let Some(r_expected) = expected_r {
        if r_expected != &r_hdr {
            return Err(Ssf1Error::MalformedHeader("R mismatch with phrase"));
        }
    }

    if plaintext_size == 0 {
        return Err(Ssf1Error::MalformedHeader("plaintext_size zero"));
    }
    if total_chunks == 0 {
        return Err(Ssf1Error::MalformedHeader("total_chunks zero"));
    }
    let expected_chunks = ((plaintext_size + CHUNK_SIZE as u64 - 1) / CHUNK_SIZE as u64) as u32;
    if expected_chunks != total_chunks {
        return Err(Ssf1Error::MalformedHeader(
            "total_chunks does not match plaintext_size",
        ));
    }

    // Filename
    let mut name_len_buf = [0u8; 2];
    read_exact_into(&mut reader, &mut name_len_buf)?;
    let name_len = u16::from_be_bytes(name_len_buf) as usize;
    if name_len < TAG_LEN || name_len > 255 + TAG_LEN {
        return Err(Ssf1Error::MalformedHeader("filename length out of range"));
    }
    let mut name_buf = vec![0u8; name_len];
    read_exact_into(&mut reader, &mut name_buf)?;
    let name_nonce = build_nonce(&r_hdr, NAME_NONCE_COUNTER);
    let (name_ct, name_tag) = name_buf.split_at_mut(name_len - TAG_LEN);
    let tag_arr = aes_gcm::Tag::clone_from_slice(name_tag);
    cipher
        .decrypt_in_place_detached(Nonce::from_slice(&name_nonce), NAME_AAD, name_ct, &tag_arr)
        .map_err(|_| Ssf1Error::AuthFailure("filename"))?;
    let filename = std::str::from_utf8(name_ct)
        .map_err(|_| Ssf1Error::MalformedHeader("filename not UTF-8"))?
        .to_string();

    // Chunks
    let mut buffer = vec![0u8; CHUNK_SIZE];
    let mut bytes_remaining = plaintext_size;
    for i in 0..total_chunks {
        let is_last = i == total_chunks - 1;
        let want: usize = if is_last {
            bytes_remaining as usize
        } else {
            CHUNK_SIZE
        };
        buffer.resize(want, 0);
        read_exact_into(&mut reader, &mut buffer[..want])?;
        let mut tag_buf = [0u8; TAG_LEN];
        read_exact_into(&mut reader, &mut tag_buf)?;

        let aad = chunk_aad(i, total_chunks, is_last);
        let nonce = build_nonce(&r_hdr, (i as u64) + 1);
        let tag_arr = aes_gcm::Tag::clone_from_slice(&tag_buf);
        cipher
            .decrypt_in_place_detached(
                Nonce::from_slice(&nonce),
                &aad,
                &mut buffer[..want],
                &tag_arr,
            )
            .map_err(|_| Ssf1Error::AuthFailure("chunk"))?;

        writer.write_all(&buffer[..want])?;
        bytes_remaining -= want as u64;
    }

    // The stream MUST end exactly here.
    let mut trailing = [0u8; 1];
    match reader.read(&mut trailing) {
        Ok(0) => {}
        Ok(_) => return Err(Ssf1Error::MalformedHeader("trailing data after last chunk")),
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => {}
        Err(e) => return Err(Ssf1Error::Io(e)),
    }

    writer.flush()?;
    Ok(filename)
}

fn read_exact_into<R: Read>(r: &mut R, buf: &mut [u8]) -> Result<(), Ssf1Error> {
    r.read_exact(buf).map_err(Ssf1Error::Io)
}
