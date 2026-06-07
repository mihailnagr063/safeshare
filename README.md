# SafeShare

End-to-end encrypted file sharing between Android devices.

SafeShare uses X25519 ECDH + HKDF-SHA256 key agreement to derive a per-file
AES-128-GCM key. All encryption happens on the client - the server only stores
and serves opaque ciphertext.

## Features

- **X25519 identity keys**: each device generates a persistent keypair in
  Android Keystore
- **Contact management**: exchange public keys via QR
- **Pluggable storage providers**: the Android client supports multiple backends
  via a plugin interface. Built-in providers:
  - **SafeShare** - Rust backend, supports TTL and download limiting
  - **Yandex Disk** - Just authorize with Yandex account, no configuration required
  - Custom providers by implementing the `StorageProvider` interface
