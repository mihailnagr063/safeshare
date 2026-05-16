//! Library interface for the SafeShare HTTP server.

pub mod config;
pub mod error;
pub mod models;
pub mod routes;
pub mod storage;
pub mod ttl;

use std::sync::Arc;

use crate::config::Config;
use crate::storage::db::Db;

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<Config>,
    pub db: Db,
}

pub async fn build_state(config: Config) -> anyhow::Result<AppState> {
    tokio::fs::create_dir_all(&config.data_dir).await?;
    let db = Db::connect(&config.db_url).await?;
    db.migrate().await?;
    Ok(AppState {
        config: Arc::new(config),
        db,
    })
}

pub fn app(state: AppState) -> axum::Router {
    routes::router(state)
}
