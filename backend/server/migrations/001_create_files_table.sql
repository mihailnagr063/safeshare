CREATE TABLE IF NOT EXISTS files (
    file_id           TEXT PRIMARY KEY NOT NULL,
    owner_token_hash  BLOB NOT NULL,
    path              TEXT NOT NULL,
    size_bytes        INTEGER NOT NULL,
    uploaded_at       INTEGER NOT NULL,
    expires_at        INTEGER NOT NULL,
    max_downloads     INTEGER NOT NULL,
    downloads_count   INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_files_expires_at ON files(expires_at);
