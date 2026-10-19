use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum AtomicWriteError {
    #[error("Io: {0}")]
    Io(#[from] std::io::Error),
}

// this should atomically write file to the filesystem
// currently it is implemented as a normal filessytem write
pub async fn write_atomically(path: &Path, data: &[u8]) -> Result<(), AtomicWriteError> {
    tokio::fs::write(path, data).await?;
    Ok(())
}
