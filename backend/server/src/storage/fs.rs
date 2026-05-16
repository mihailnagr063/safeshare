//! Filesystem layout for ciphertext blobs.

use std::path::{Path, PathBuf};

pub fn path_for(data_dir: &Path, file_id: &str) -> PathBuf {
    let shard = &file_id[..file_id.len().min(2)];
    data_dir.join(shard).join(file_id)
}

pub async fn ensure_parent(path: &Path) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    Ok(())
}

pub async fn remove_if_exists(path: &Path) {
    if path.exists() {
        if let Err(e) = tokio::fs::remove_file(path).await {
            tracing::warn!(path = %path.display(), error = %e, "failed to remove file");
        }
    }
}
