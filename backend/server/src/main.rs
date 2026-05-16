//! SafeShare backend entry point.

use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

use safeshare_server::{app, build_state, config::Config, ttl};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::registry()
        .with(EnvFilter::try_from_default_env().unwrap_or_else(|_| "safeshare=info".into()))
        .with(tracing_subscriber::fmt::layer().compact())
        .init();

    let config = Config::from_env()?;
    tracing::info!(port = config.port, data_dir = %config.data_dir.display(), "starting safeshare");

    let state = build_state(config).await?;

    {
        let state = state.clone();
        tokio::spawn(async move { ttl::run(state).await });
    }

    let listener =
        tokio::net::TcpListener::bind((state.config.bind.as_str(), state.config.port)).await?;
    tracing::info!("listening on {}", listener.local_addr()?);
    axum::serve(listener, app(state)).await?;
    Ok(())
}
