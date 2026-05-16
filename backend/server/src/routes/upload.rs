//! `POST /api/files`

use axum::{
    body::Body,
    extract::State,
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    Json,
};
use futures::StreamExt;
use rand::RngCore;
use tokio::io::AsyncWriteExt;

use crate::{
    error::{AppError, AppResult},
    models::{FileRecord, UploadResponse},
    storage::fs as fs_store,
    AppState,
};

pub async fn post_file(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Body,
) -> AppResult<impl IntoResponse> {
    let ttl_seconds = parse_header_u64(&headers, "x-safeshare-ttl-seconds")?;
    let max_downloads = parse_header_u64(&headers, "x-safeshare-max-downloads")?;
    let owner_token_hash = parse_owner_token_hash(&headers)?;

    if ttl_seconds < state.config.ttl_min_sec || ttl_seconds > state.config.ttl_max_sec {
        return Err(AppError::InvalidRequest(format!(
            "ttl_seconds must be between {} and {}",
            state.config.ttl_min_sec, state.config.ttl_max_sec
        )));
    }
    if !(1..=100).contains(&max_downloads) {
        return Err(AppError::InvalidRequest(
            "max_downloads must be between 1 and 100".into(),
        ));
    }

    let file_id = generate_file_id();
    let final_path = fs_store::path_for(&state.config.data_dir, &file_id);
    fs_store::ensure_parent(&final_path).await?;

    let tmp_path = final_path.with_extension("part");
    let mut written: u64 = 0;
    {
        let mut file = tokio::fs::File::create(&tmp_path).await?;
        let mut stream = body.into_data_stream();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|e| AppError::Internal(e.into()))?;
            written += chunk.len() as u64;
            if written > state.config.max_file_size {
                drop(file);
                fs_store::remove_if_exists(&tmp_path).await;
                return Err(AppError::TooLarge);
            }
            file.write_all(&chunk).await?;
        }
        file.flush().await?;
    }

    tokio::fs::rename(&tmp_path, &final_path).await?;

    let now = chrono::Utc::now().timestamp();
    let record = FileRecord {
        file_id: file_id.clone(),
        owner_token_hash,
        path: final_path.to_string_lossy().into_owned(),
        size_bytes: written as i64,
        uploaded_at: now,
        expires_at: now + ttl_seconds as i64,
        max_downloads: max_downloads as i64,
        downloads_count: 0,
    };
    state.db.insert(&record).await?;

    Ok((
        StatusCode::OK,
        Json(UploadResponse {
            file_id,
            expires_at: record.expires_at,
            max_downloads: record.max_downloads,
        }),
    ))
}

fn parse_header_u64(headers: &HeaderMap, name: &str) -> AppResult<u64> {
    let value = headers
        .get(name)
        .ok_or_else(|| AppError::InvalidRequest(format!("missing header {name}")))?
        .to_str()
        .map_err(|_| AppError::InvalidRequest(format!("invalid header {name}")))?;
    value
        .parse::<u64>()
        .map_err(|_| AppError::InvalidRequest(format!("invalid header {name}")))
}

fn parse_owner_token_hash(headers: &HeaderMap) -> AppResult<Vec<u8>> {
    let value = headers
        .get("x-safeshare-owner-token")
        .ok_or_else(|| AppError::InvalidRequest("missing x-safeshare-owner-token".into()))?
        .to_str()
        .map_err(|_| AppError::InvalidRequest("invalid x-safeshare-owner-token".into()))?;

    let bytes = hex::decode(value)
        .map_err(|_| AppError::InvalidRequest("owner token must be hex".into()))?;
    if bytes.len() != 32 {
        return Err(AppError::InvalidRequest(
            "owner token hash must be 32 bytes (sha256)".into(),
        ));
    }
    Ok(bytes)
}

/// 5 random bytes rendered in Crockford Base32 (8 chars)
fn generate_file_id() -> String {
    const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    let mut bytes = [0u8; 5];
    rand::thread_rng().fill_bytes(&mut bytes);
    let mut val: u64 = 0;
    for b in bytes {
        val = (val << 8) | b as u64;
    }
    let mut out = [0u8; 8];
    for i in (0..8).rev() {
        out[i] = ALPHABET[(val & 0x1f) as usize];
        val >>= 5;
    }
    String::from_utf8(out.to_vec()).expect("ASCII")
}
