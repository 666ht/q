#![deny(clippy::unwrap_used)]

pub mod crypto;
pub mod seed;
pub mod transport;
pub mod util;
pub mod wallet;

use std::sync::LazyLock;

use monero_wallet::address::Network;
use rand::SeedableRng;

pub use monero_daemon_rpc;
pub use monero_wallet;
pub use monero_wallet_util;
use rand_chacha::ChaCha20Rng;
use tokio::sync::{RwLock, RwLockWriteGuard};

pub type MoneroBytes = [u8; 32];

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

pub const CONFIRMATIONS: u32 = 10;
pub const TARGET_BLOCK_TIME: u64 = 120_000;

pub const MAINNET_CONSTANTS: NetworkConstants = NetworkConstants {
    genesis_block_hash: "418015bb9ae982a1975da7d79277c2705727a56894ba0fb246adaabb1f4632e3",
    genesis_block_hash_bytes: [65, 128, 21, 187, 154, 233, 130, 161, 151, 93, 167, 215, 146, 119, 194, 112, 87, 39, 165, 104, 148, 186, 15, 178, 70, 173, 170, 187, 31, 70, 50, 227],
    fork_timestamp: 1_458_748_658_000,
    fork_block: 1_009_827,
    rollback_adjustment: None,
};

pub const STAGENET_CONSTANTS: NetworkConstants = NetworkConstants {
    genesis_block_hash: "76ee3cc98646292206cd3e86f74d88b4dcc1d937088645e9b0cbca84b7ce74eb",
    genesis_block_hash_bytes: [118, 238, 60, 201, 134, 70, 41, 34, 6, 205, 62, 134, 247, 77, 136, 180, 220, 193, 217, 55, 8, 134, 69, 233, 176, 203, 202, 132, 183, 206, 116, 235],
    fork_timestamp: 1_520_937_818_000,
    fork_block: 32_000,
    rollback_adjustment: Some(60_000),
};

pub const TESTNET_CONSTANTS: NetworkConstants = NetworkConstants {
    genesis_block_hash: "48ca7cd3c8de5b6a4d53d2861fbdaedca141553559f9be9520068053cda8430b",
    genesis_block_hash_bytes: [72, 202, 124, 211, 200, 222, 91, 106, 77, 83, 210, 134, 31, 189, 174, 220, 161, 65, 85, 53, 89, 249, 190, 149, 32, 6, 128, 83, 205, 168, 67, 11],
    fork_timestamp: 1_448_285_909_000,
    fork_block: 624_634,
    rollback_adjustment: Some(342_100),
};

#[derive(Debug, Clone, Copy)]
pub struct NetworkConstants {
    pub genesis_block_hash: &'static str,
    pub genesis_block_hash_bytes: MoneroBytes,
    pub fork_timestamp: u64,
    pub fork_block: u32,
    pub rollback_adjustment: Option<u32>,
}

static RNG: LazyLock<RwLock<ChaCha20Rng>> = LazyLock::new(|| RwLock::new(ChaCha20Rng::from_entropy()));

async fn get_rng_mut<'a>() -> RwLockWriteGuard<'a, ChaCha20Rng> {
    RNG.write().await
}

pub const fn get_network_constants(network: Network) -> NetworkConstants {
    match network {
        Network::Mainnet => MAINNET_CONSTANTS,
        Network::Stagenet => STAGENET_CONSTANTS,
        Network::Testnet => TESTNET_CONSTANTS,
    }
}

pub async fn generate_seed(lang: monero_wallet_util::seed::original::Language) -> monero_wallet_util::seed::original::Seed {
    monero_wallet_util::seed::original::Seed::new(&mut *get_rng_mut().await, lang)
}

pub async fn generate_polyseed(lang: monero_wallet_util::seed::polyseed::Language) -> monero_wallet_util::seed::polyseed::Polyseed {
    monero_wallet_util::seed::polyseed::Polyseed::new(&mut *get_rng_mut().await, lang)
}

pub fn current_timestamp() -> u64 {
    chrono::Utc::now()
        .timestamp_millis()
        .try_into()
        .expect("failed to convert current timestamp into u64")
}

#[cfg(test)]
mod tests {
    use zeroize::Zeroizing;

    use crate::{MAINNET_CONSTANTS, STAGENET_CONSTANTS, TESTNET_CONSTANTS};

    #[test]
    fn verify_mainnet_genesis_block_hash_bytes() {
        assert_eq!(crate::util::hex_to_bytes(MAINNET_CONSTANTS.genesis_block_hash), Some(Zeroizing::new(MAINNET_CONSTANTS.genesis_block_hash_bytes.into())))
    }

    #[test]
    fn verify_stagenet_genesis_block_hash_bytes() {
        assert_eq!(crate::util::hex_to_bytes(STAGENET_CONSTANTS.genesis_block_hash), Some(Zeroizing::new(STAGENET_CONSTANTS.genesis_block_hash_bytes.into())))
    }

    #[test]
    fn verify_testnet_genesis_block_hash_bytes() {
        assert_eq!(crate::util::hex_to_bytes(TESTNET_CONSTANTS.genesis_block_hash), Some(Zeroizing::new(TESTNET_CONSTANTS.genesis_block_hash_bytes.into())))
    }
}
