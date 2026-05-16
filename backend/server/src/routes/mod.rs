pub mod delete;
pub mod download;
pub mod health;
pub mod upload;

use axum::{routing::get, Router};

use crate::AppState;

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(health::healthz))
        .route(
            "/api/files",
            axum::routing::post(upload::post_file),
        )
        .route(
            "/api/files/:file_id",
            get(download::get_file)
                .head(download::head_file)
                .delete(delete::delete_file),
        )
        .with_state(state)
}
