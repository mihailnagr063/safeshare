//! `DELETE /api/files/{id}` - delete a file before its TTL expires.

use axum::{
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
};
use sha2::{Digest, Sha256};

use crate::{
    error::{AppError, AppResult},
    storage::fs as fs_store,
    AppState,
};

pub async fn delete_file(
    State(state): State<AppState>,
    Path(file_id): Path<String>,
    headers: HeaderMap,
) -> AppResult<StatusCode> {
    let token_hex = headers
        .get("x-safeshare-owner-token")
        .ok_or_else(|| AppError::Forbidden)?
        .to_str()
        .map_err(|_| AppError::Forbidden)?;
    let token_bytes = hex::decode(token_hex).map_err(|_| AppError::Forbidden)?;
    if token_bytes.len() != 32 {
        return Err(AppError::Forbidden);
    }
    let hash = Sha256::digest(&token_bytes).to_vec();

    let rec = state.db.get(&file_id).await?.ok_or(AppError::NotFound)?;
    if !constant_time_eq(&hash, &rec.owner_token_hash) {
        return Err(AppError::Forbidden);
    }
    fs_store::remove_if_exists(std::path::Path::new(&rec.path)).await;
    state.db.delete(&file_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}
