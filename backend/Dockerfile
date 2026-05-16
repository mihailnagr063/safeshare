# Build context: backend/
# Usage:
#   cd backend && docker build -t safeshare-backend .

FROM rust:1.75-slim AS builder
WORKDIR /build

# Copy workspace manifests first for layer caching.
COPY Cargo.toml Cargo.lock ./
COPY ssf1/Cargo.toml ssf1/Cargo.toml
COPY server/Cargo.toml server/Cargo.toml
COPY ssf1-tool/Cargo.toml ssf1-tool/Cargo.toml

# Dummy sources to pre-build dependencies.
RUN mkdir -p ssf1/src server/src ssf1-tool/src && \
    echo 'pub fn dummy(){}' > ssf1/src/lib.rs && \
    echo 'fn main(){}' > server/src/main.rs && \
    touch server/src/lib.rs && \
    echo 'fn main(){}' > ssf1-tool/src/main.rs && \
    cargo build --release -p safeshare-server && \
    rm -rf ssf1/src server/src ssf1-tool/src

# Copy real sources.
COPY ssf1/ ssf1/
COPY server/ server/
COPY ssf1-tool/ ssf1-tool/

# Touch sources so cargo rebuilds them (not the deps).
RUN touch ssf1/src/lib.rs server/src/main.rs server/src/lib.rs
RUN cargo build --release -p safeshare-server

# --- runtime image ---
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /build/target/release/safeshare-server /usr/local/bin/safeshare-server
ENV SAFESHARE_DATA_DIR=/data/files
ENV SAFESHARE_DB_URL=sqlite:///data/safeshare.db
VOLUME ["/data"]
EXPOSE 8080
CMD ["safeshare-server"]
