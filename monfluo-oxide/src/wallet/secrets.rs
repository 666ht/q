use std::path::Path;

use monero_wallet::{address::Network, ed25519::Scalar};
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

use crate::{MoneroBytes, util::HexFormatted};

#[derive(Debug, thiserror::Error)]
pub enum SecretsError {
    #[error("Wallet io: {0}")]
    WalletIo(#[from] crate::wallet::io::WalletIoError),
}

#[derive(Debug, Serialize, Deserialize, Zeroize, ZeroizeOnDrop)]
pub struct WalletSecrets {
    #[serde(serialize_with = "serialize_scalar")]
    #[serde(deserialize_with = "deserialize_scalar")]
    spend_key: Zeroizing<Scalar>,
    #[serde(serialize_with = "serialize_scalar")]
    #[serde(deserialize_with = "deserialize_scalar")]
    view_key: Zeroizing<Scalar>,
    #[serde(serialize_with = "serialize_network")]
    #[serde(deserialize_with = "deserialize_network")]
    network: Network,
    restore_height: Option<u32>,
    birthday: u64,
}

impl WalletSecrets {
    pub fn new(spend_key: Zeroizing<Scalar>, view_key: Zeroizing<Scalar>, network: Network, restore_height: Option<u32>, birthday: u64) -> Self {
        Self {
            spend_key,
            view_key,
            network,
            restore_height,
            birthday,
        }
    }

    pub async fn load(path: &Path, password: &str) -> Result<Self, SecretsError> {
        Ok(super::io::read_json_from_file(path, password).await?)
    }

    pub async fn store(&self, path: &Path, password: &str) -> Result<(), SecretsError> {
        let bytes = serde_json::to_vec(&self).unwrap();
        let encrypted_bytes = super::encryption::encrypt(bytes, password).unwrap();
        super::atomic_fs::write_atomically(path, &encrypted_bytes)
            .await
            .unwrap();
        Ok(())
    }

    pub fn spend_key(&self) -> &Zeroizing<Scalar> {
        &self.spend_key
    }

    pub fn view_key(&self) -> &Zeroizing<Scalar> {
        &self.view_key
    }

    pub fn network(&self) -> Network {
        self.network
    }

    pub fn restore_height(&self) -> Option<u32> {
        self.restore_height
    }

    pub fn birthday(&self) -> u64 {
        self.birthday
    }
}

fn serialize_scalar<S>(value: &Zeroizing<Scalar>, serializer: S) -> Result<S::Ok, S::Error>
where
    S: Serializer,
{
    let bytes: Zeroizing<MoneroBytes> = Zeroizing::new(From::from(**value));
    let hex: Zeroizing<String> = Zeroizing::new(HexFormatted::borrowed(&*bytes).to_string());
    serializer.serialize_str(&hex)
}

fn deserialize_scalar<'de, D>(deserializer: D) -> Result<Zeroizing<Scalar>, D::Error>
where
    D: Deserializer<'de>,
{
    let hex: Zeroizing<String> = Zeroizing::new(String::deserialize(deserializer)?);
    let bytes: Zeroizing<Vec<u8>> = crate::util::hex_to_bytes(&hex).unwrap();
    let bytes: Zeroizing<MoneroBytes> = Zeroizing::new((&**bytes).try_into().unwrap());
    let scalar: Zeroizing<Scalar> = Zeroizing::new(Scalar::from(
        curve25519_dalek::Scalar::from_canonical_bytes(*bytes)
            .into_option()
            .unwrap(),
    ));
    Ok(scalar)
}

fn serialize_network<S>(value: &Network, serializer: S) -> Result<S::Ok, S::Error>
where
    S: Serializer,
{
    serializer.serialize_u8(*value as u8)
}

fn deserialize_network<'de, D>(deserializer: D) -> Result<Network, D::Error>
where
    D: Deserializer<'de>,
{
    let tag = u8::deserialize(deserializer)?;
    match tag {
        0 => Ok(Network::Mainnet),
        1 => Ok(Network::Stagenet),
        2 => Ok(Network::Testnet),
        _ => unreachable!("Bad network tag"),
    }
}
