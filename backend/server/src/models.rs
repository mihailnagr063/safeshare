//! Domain structs

use serde::Serialize;

#[derive(Debug, Clone)]
pub struct FileRecord {
    pub file_id: String,
    pub owner_token_hash: Vec<u8>,
    pub path: String,
    pub size_bytes: i64,
    pub uploaded_at: i64,
    pub expires_at: i64,
    pub max_downloads: i64,
    pub downloads_count: i64,
}

#[derive(Debug, Serialize)]
pub struct UploadResponse {
    pub file_id: String,
    pub expires_at: i64,
    pub max_downloads: i64,
}
