//! `GET /api/files/{id}` and `HEAD /api/files/{id}`.
//!
//! GET streams the ciphertext and increments the download counter.
//! HEAD only tests existence.

use axum::{
    body::Body,
    extract::{Path, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use tokio_util::io::ReaderStream;

use crate::{
    error::{AppError, AppResult},
    storage::fs as fs_store,
    AppState,
};

pub async fn get_file(
    State(state): State<AppState>,
    Path(file_id): Path<String>,
) -> AppResult<Response> {
    let rec = state.db.get(&file_id).await?.ok_or(AppError::NotFound)?;
    let now = chrono::Utc::now().timestamp();
    if rec.expires_at <= now || rec.downloads_count >= rec.max_downloads {
        fs_store::remove_if_exists(std::path::Path::new(&rec.path)).await;
        return Err(AppError::Gone);
    }

    let new_count = state.db.try_increment_downloads(&file_id).await?;
    let Some(new_count) = new_count else {
        return Err(AppError::Gone);
    };

    let file = tokio::fs::File::open(&rec.path).await.map_err(|e| {
        tracing::error!(file_id = %file_id, error = %e, "ciphertext file missing");
        AppError::Internal(e.into())
    })?;
    let stream = ReaderStream::new(file);
    let body = Body::from_stream(stream);

    let mut resp = Response::new(body);
    resp.headers_mut().insert(
        header::CONTENT_TYPE,
        header::HeaderValue::from_static("application/octet-stream"),
    );
    resp.headers_mut().insert(
        header::CONTENT_LENGTH,
        header::HeaderValue::from_str(&rec.size_bytes.to_string()).unwrap(),
    );
    *resp.status_mut() = StatusCode::OK;

    if new_count >= rec.max_downloads {
        let path = rec.path.clone();
        tokio::spawn(async move {
            fs_store::remove_if_exists(std::path::Path::new(&path)).await;
        });
    }
    Ok(resp)
}

pub async fn head_file(
    State(state): State<AppState>,
    Path(file_id): Path<String>,
) -> AppResult<Response> {
    let rec = state.db.get(&file_id).await?.ok_or(AppError::NotFound)?;
    let now = chrono::Utc::now().timestamp();
    if rec.expires_at <= now || rec.downloads_count >= rec.max_downloads {
        return Err(AppError::Gone);
    }
    let mut headers = HeaderMap::new();
    headers.insert(
        header::CONTENT_LENGTH,
        header::HeaderValue::from_str(&rec.size_bytes.to_string()).unwrap(),
    );
    Ok((StatusCode::OK, headers).into_response())
}
