// TODO: wallet secrets & data should be encrytped. Current encryption is no-op

#[derive(Debug, thiserror::Error)]
pub enum EncryptionError {}

pub fn encrypt(data: Vec<u8>, _password: &str) -> Result<Vec<u8>, EncryptionError> {
    Ok(data)
}

pub fn decrypt(data: Vec<u8>, _password: &str) -> Result<Vec<u8>, EncryptionError> {
    Ok(data)
}
