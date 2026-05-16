//! SQLite access layer

use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};
use sqlx::SqlitePool;
use std::str::FromStr;

use crate::models::FileRecord;

#[derive(Clone)]
pub struct Db {
    pool: SqlitePool,
}

impl Db {
    pub async fn connect(url: &str) -> anyhow::Result<Self> {
        let options = SqliteConnectOptions::from_str(url)?
            .create_if_missing(true)
            .foreign_keys(true);
        let pool = SqlitePoolOptions::new()
            .max_connections(8)
            .connect_with(options)
            .await?;
        Ok(Db { pool })
    }

    pub async fn migrate(&self) -> anyhow::Result<()> {
        sqlx::query(include_str!("../../migrations/20260101000000_init.sql"))
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn insert(&self, rec: &FileRecord) -> anyhow::Result<()> {
        sqlx::query(
            "INSERT INTO files (file_id, owner_token_hash, path, size_bytes,
                                uploaded_at, expires_at, max_downloads, downloads_count)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&rec.file_id)
        .bind(&rec.owner_token_hash)
        .bind(&rec.path)
        .bind(rec.size_bytes)
        .bind(rec.uploaded_at)
        .bind(rec.expires_at)
        .bind(rec.max_downloads)
        .bind(rec.downloads_count)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn get(&self, file_id: &str) -> anyhow::Result<Option<FileRecord>> {
        let row: Option<(String, Vec<u8>, String, i64, i64, i64, i64, i64)> = sqlx::query_as(
            "SELECT file_id, owner_token_hash, path, size_bytes,
                    uploaded_at, expires_at, max_downloads, downloads_count
             FROM files WHERE file_id = ?",
        )
        .bind(file_id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(row.map(
            |(
                file_id,
                owner_token_hash,
                path,
                size_bytes,
                uploaded_at,
                expires_at,
                max_downloads,
                downloads_count,
            )| FileRecord {
                file_id,
                owner_token_hash,
                path,
                size_bytes,
                uploaded_at,
                expires_at,
                max_downloads,
                downloads_count,
            },
        ))
    }

    /// Atomically increments `downloads_count` if it is still under
    /// `max_downloads`. Returns the new count, or None if the
    /// threshold has been reached and the row should be deleted by
    /// the caller.
    pub async fn try_increment_downloads(&self, file_id: &str) -> anyhow::Result<Option<i64>> {
        let row: Option<(i64,)> = sqlx::query_as(
            "UPDATE files
             SET downloads_count = downloads_count + 1
             WHERE file_id = ? AND downloads_count < max_downloads
             RETURNING downloads_count",
        )
        .bind(file_id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(row.map(|(c,)| c))
    }

    pub async fn delete(&self, file_id: &str) -> anyhow::Result<()> {
        sqlx::query("DELETE FROM files WHERE file_id = ?")
            .bind(file_id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    /// Returns file ids and paths for rows whose TTL has elapsed or
    /// whose download counter has been exhausted.
    pub async fn list_expired(&self, now_ts: i64) -> anyhow::Result<Vec<(String, String)>> {
        let rows: Vec<(String, String)> = sqlx::query_as(
            "SELECT file_id, path FROM files
             WHERE expires_at <= ? OR downloads_count >= max_downloads",
        )
        .bind(now_ts)
        .fetch_all(&self.pool)
        .await?;
        Ok(rows)
    }
}
