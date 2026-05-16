//! Runtime configuration, read from environment variables on startup.

use std::path::PathBuf;

#[derive(Debug, Clone)]
pub struct Config {
    pub port: u16,
    pub bind: String,
    pub data_dir: PathBuf,
    pub db_url: String,
    pub max_file_size: u64,
    pub ttl_min_sec: u64,
    pub ttl_max_sec: u64,
    pub cleanup_interval_sec: u64,
}

impl Config {
    pub fn from_env() -> anyhow::Result<Self> {
        fn env_u64(name: &str, default: u64) -> anyhow::Result<u64> {
            match std::env::var(name) {
                Ok(s) => Ok(s.parse()?),
                Err(_) => Ok(default),
            }
        }
        fn env_string(name: &str, default: &str) -> String {
            std::env::var(name).unwrap_or_else(|_| default.to_string())
        }

        Ok(Config {
            port: env_u64("SAFESHARE_PORT", 8080)? as u16,
            bind: env_string("SAFESHARE_BIND", "0.0.0.0"),
            data_dir: PathBuf::from(env_string("SAFESHARE_DATA_DIR", "./data/files")),
            db_url: env_string("SAFESHARE_DB_URL", "sqlite://./data/safeshare.db"),
            max_file_size: env_u64("SAFESHARE_MAX_FILE_SIZE", 1024 * 1024 * 1024)?,
            ttl_min_sec: env_u64("SAFESHARE_TTL_MIN_SEC", 60)?,
            ttl_max_sec: env_u64("SAFESHARE_TTL_MAX_SEC", 7 * 24 * 3600)?,
            cleanup_interval_sec: env_u64("SAFESHARE_CLEANUP_INTERVAL_SEC", 60)?,
        })
    }
}
