use axum::{
    body::Body,
    extract::{Path, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
};
use base64::Engine;
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

    let filename_b64 = filename_to_base64(&rec.filename);

    let mut resp = Response::new(body);
    resp.headers_mut().insert(
        header::CONTENT_TYPE,
        header::HeaderValue::from_static("application/octet-stream"),
    );
    resp.headers_mut().insert(
        header::CONTENT_LENGTH,
        header::HeaderValue::from_str(&rec.size_bytes.to_string()).unwrap(),
    );
    resp.headers_mut().insert(
        "x-safeshare-filename",
        HeaderValue::from_str(&filename_b64).unwrap(),
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
    let filename_b64 = filename_to_base64(&rec.filename);
    let mut headers = HeaderMap::new();
    headers.insert(
        header::CONTENT_LENGTH,
        header::HeaderValue::from_str(&rec.size_bytes.to_string()).unwrap(),
    );
    headers.insert(
        "x-safeshare-filename",
        HeaderValue::from_str(&filename_b64).unwrap(),
    );
    Ok((StatusCode::OK, headers).into_response())
}

fn filename_to_base64(name: &str) -> String {
    if name.is_empty() {
        return String::new();
    }
    let engine = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    engine.encode(name.as_bytes())
}
