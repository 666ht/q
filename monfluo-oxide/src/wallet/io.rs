use std::path::Path;

use serde::{Serialize, de::DeserializeOwned};

#[derive(Debug, thiserror::Error)]
pub enum WalletIoError {
    #[error("Io: {0}")]
    Io(#[from] std::io::Error),
    #[error("Encryption: {0}")]
    Encryption(#[from] crate::wallet::encryption::EncryptionError),
    #[error("Serde: {0}")]
    Serde(#[from] serde_json::Error),
    #[error("Atomic write: {0}")]
    AtomicWrite(#[from] crate::wallet::atomic_fs::AtomicWriteError),
}

pub async fn write_json_to_file<S: Serialize>(value: &S, path: &Path, password: &str) -> Result<(), WalletIoError> {
    let bytes = serde_json::to_vec(value)?;
    let encrypted_bytes = super::encryption::encrypt(bytes, password)?;
    super::atomic_fs::write_atomically(path, &encrypted_bytes).await?;
    Ok(())
}

pub async fn read_json_from_file<D: DeserializeOwned>(path: &Path, password: &str) -> Result<D, WalletIoError> {
    let bytes = tokio::fs::read(path).await?;
    let decrypted_bytes = super::encryption::decrypt(bytes, password)?;
    let state = serde_json::from_slice(&decrypted_bytes)?;
    Ok(state)
}
