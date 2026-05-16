//! Background worker that deletes expired / exhausted files.

use std::time::Duration;

use crate::{storage::fs as fs_store, AppState};

pub async fn run(state: AppState) {
    let interval = Duration::from_secs(state.config.cleanup_interval_sec);
    loop {
        tokio::time::sleep(interval).await;
        if let Err(e) = tick(&state).await {
            tracing::warn!(error = %e, "ttl worker tick failed");
        }
    }
}

async fn tick(state: &AppState) -> anyhow::Result<()> {
    let now = chrono::Utc::now().timestamp();
    let expired = state.db.list_expired(now).await?;
    if expired.is_empty() {
        return Ok(());
    }
    tracing::info!(count = expired.len(), "cleaning up expired files");
    for (file_id, path) in expired {
        fs_store::remove_if_exists(std::path::Path::new(&path)).await;
        state.db.delete(&file_id).await.ok();
    }
    Ok(())
}
