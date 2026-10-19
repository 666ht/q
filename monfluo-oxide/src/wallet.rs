mod atomic_fs;
mod encryption;
mod io;
mod scan;
mod secrets;
mod state;
mod sync;
pub mod view;

use monero_wallet::interface::FeePriority;
use monero_wallet::send::SendError;
use monero_wallet::transaction::NotPruned;
use monero_wallet::transaction::PotentiallyPruned;
pub use sync::SyncError;
pub use sync::WalletSyncObserver;
pub use sync::WalletSyncState;
use tokio::runtime::Runtime;
use tokio::sync::Mutex;
use tokio::sync::RwLock;
use tokio::sync::RwLockReadGuard;
use tokio::sync::RwLockWriteGuard;
use tokio::task::JoinHandle;
use tracing::debug;

use crate::MoneroBytes;
use crate::transport::MonfluoTransport;
use crate::wallet::scan::create_scanner;
use crate::wallet::scan::process_scan_result;
use crate::wallet::secrets::WalletSecrets;
pub use crate::wallet::state::OutgoingPayment;
use crate::wallet::state::OutgoingPaymentType;
pub use crate::wallet::state::OutgoingTransfer;
pub use crate::wallet::state::TransactionWrapper;
use crate::wallet::state::WalletState;
use chrono::NaiveDateTime;
use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
use monero_daemon_rpc::MoneroDaemon;
use monero_wallet::OutputWithDecoys;
use monero_wallet::ed25519::Point;
use monero_wallet::interface::{FeeRate, ProvidesUnvalidatedFeeRates, PublishTransaction};
use monero_wallet::ringct::RctType;
use monero_wallet::send::{Change, SignableTransaction};
use monero_wallet::transaction::Transaction;
use monero_wallet::{
    ViewPair,
    address::{MoneroAddress, Network, SubaddressIndex},
};
use monero_wallet_util::seed::Seed;
use rand::RngCore;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

const WALLETS_DIRECTORY_FILE_NAME: &str = "wallets";
const WALLET_SECRETS_FILE_NAME: &str = "secrets.bin";
const WALLET_DATA_FILE_NAME: &str = "data.bin";

pub type Daemon = Arc<MoneroDaemon<MonfluoTransport>>;

#[derive(Zeroize, ZeroizeOnDrop)]
pub struct Wallet {
    #[zeroize(skip)]
    path: PathBuf,
    // Retain the same password used to open/create the wallet, so sync/close
    // writes state encrypted with the key expected on the next open.
    password: Zeroizing<String>,
    secrets: WalletSecrets,
    #[zeroize(skip)]
    state: RwLock<WalletState>,
    #[zeroize(skip)]
    sync_handle: Mutex<Option<SyncHandle>>,
}

struct SyncHandle {
    handle: JoinHandle<()>,
    cancellation_token: Arc<AtomicBool>,
}

#[derive(Debug, Copy, Clone)]
pub enum WalletStatus {
    NotConnected,
    Connecting,
    Connected,
    StartingSynchronization,
    Synchronizing { target: u32, updated_at: NaiveDateTime },
    Synchronized { finished_at: NaiveDateTime },
}

#[derive(Debug, thiserror::Error)]
pub enum WalletError {
    #[error("Io: {0}")]
    Io(#[from] std::io::Error),
    #[error("Secrets: {0}")]
    Secrets(#[from] secrets::SecretsError),
    #[error("Bad wallet name: {0}")]
    BadWalletName(String),
    #[error("Bad spend key")]
    BadSpendKey(Zeroizing<MoneroBytes>),
    #[error("Bad view key")]
    BadViewKey(Zeroizing<MoneroBytes>),
}

#[derive(Debug, Copy, Clone)]
pub struct Payment {
    pub address: MoneroAddress,
    pub amount: u64,
}

impl Wallet {
    pub async fn create<P: AsRef<Path>>(monfluo_directory: P, network: Network, wallet_name: &str, password: &str, seed: Seed, restore_height: Option<u32>, birthday: u64) -> Result<(), WalletError> {
        let key = seed.key();
        let spend_key = crate::util::bytes_to_scalar(&key).unwrap();
        let view_key = crate::util::bytes_to_scalar(&crate::crypto::spend_key_to_view_key(&key)).unwrap();
        let secrets = WalletSecrets::new(spend_key, view_key, network, restore_height, birthday);
        let state = WalletState::new();
        let wallet_dir_path = Self::get_wallet_directory(monfluo_directory, wallet_name)?;
        tokio::fs::create_dir_all(&wallet_dir_path).await?;
        let secrets_file_path = wallet_dir_path.join(WALLET_SECRETS_FILE_NAME);
        let state_file_path = wallet_dir_path.join(WALLET_DATA_FILE_NAME);
        secrets.store(&secrets_file_path, password).await.unwrap();
        state.store(&state_file_path, password).await.unwrap();
        Ok(())
    }

    pub async fn open<P: AsRef<Path>>(monfluo_directory: P, wallet_name: &str, password: &str) -> Result<Self, WalletError> {
        let wallet_dir_path = Self::get_wallet_directory(monfluo_directory, wallet_name)?;
        let secrets_file_path = wallet_dir_path.join(WALLET_SECRETS_FILE_NAME);
        let state_file_path = wallet_dir_path.join(WALLET_DATA_FILE_NAME);
        let secrets = WalletSecrets::load(&secrets_file_path, password).await?;
        let state = RwLock::new(
            WalletState::load(&state_file_path, password)
                .await
                .expect("Failed to load wallet state"),
        );

        Ok(Self {
            path: wallet_dir_path,
            password: Zeroizing::new(password.to_owned()),
            secrets,
            state,
            sync_handle: Mutex::new(None),
        })
    }

    pub async fn list<P: AsRef<Path>>(monfluo_directory: P) -> Result<Vec<String>, WalletError> {
        let wallets_directory = Self::get_wallets_directory(monfluo_directory);

        let mut directories = match tokio::fs::read_dir(wallets_directory).await {
            Ok(directories) => directories,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(Vec::new());
            }
            Err(e) => {
                return Err(e.into());
            }
        };

        let mut wallets = Vec::new();
        loop {
            match directories.next_entry().await {
                Ok(Some(wallet_directory)) => {
                    if let Some(wallet) = wallet_directory.file_name().to_str() {
                        wallets.push(wallet.to_owned());
                    }
                }
                Ok(None) => break,
                Err(e) => {
                    return Err(e.into());
                }
            }
        }

        Ok(wallets)
    }

    pub async fn delete<P: AsRef<Path>>(monfluo_directory: P, wallet_name: &str) -> Result<bool, WalletError> {
        let wallet_dir_path = Self::get_wallet_directory(monfluo_directory, wallet_name)?;
        match tokio::fs::remove_dir_all(wallet_dir_path).await {
            Ok(()) => Ok(true),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
            Err(e) => Err(e.into()),
        }
    }

    pub async fn store(&self) {
        let state_file_path = self.path.join(WALLET_DATA_FILE_NAME);
        self.read_state()
            .await
            .store(&state_file_path, self.password.as_str())
            .await
            .unwrap();
    }

    pub fn name(&self) -> Option<&str> {
        self.path
            .file_name()
            .and_then(|file_name| file_name.to_str())
    }

    pub fn sync<O: sync::WalletSyncObserver + Send + 'static>(self: Arc<Self>, runtime: &Runtime, daemon: Daemon, observer: O) {
        let wallet = self.clone();
        let Ok(mut sync_state_guard) = self.sync_handle.try_lock() else {
            panic!("TODO: return Err. Cannot take lock on a sync state guard");
        };
        if let Some(sync_state) = &mut *sync_state_guard
            && !sync_state.handle.is_finished()
        {
            panic!("TODO: return Err. Sync is already in progress");
        }
        let cancellation_token = Arc::new(AtomicBool::new(false));
        let handle = runtime.spawn(sync::sync(wallet, daemon, observer, cancellation_token.clone()));
        *sync_state_guard = Some(SyncHandle {
            handle,
            cancellation_token,
        })
    }

    pub async fn close(&self) {
        let Ok(mut sync_state_guard) = self.sync_handle.try_lock() else {
            panic!("TODO: return Err. Cannot take lock on a sync state guard");
        };
        if let Some(sync_state) = sync_state_guard.take() {
            sync_state
                .cancellation_token
                .store(true, std::sync::atomic::Ordering::Relaxed);
            let _ = sync_state.handle.await;
        }
        self.store().await;
    }

    pub(crate) async fn read_state(&self) -> RwLockReadGuard<'_, WalletState> {
        self.state.read().await
    }

    pub(crate) async fn write_state(&self) -> RwLockWriteGuard<'_, WalletState> {
        self.state.write().await
    }

    pub async fn current_height(&self) -> Option<u32> {
        self.state.read().await.current_height()
    }

    pub async fn create_transaction(&self, daemon: Daemon, payments: Vec<Payment>, fee_priority: FeePriority) -> (SignableTransaction, OutgoingTransfer) {
        let state = self.read_state().await;
        self.create_transaction_inner(&state, daemon, payments, fee_priority)
            .await
    }

    async fn create_transaction_inner(&self, state: &WalletState, daemon: Daemon, payments: Vec<Payment>, fee_priority: FeePriority) -> (SignableTransaction, OutgoingTransfer) {
        let current_height = state
            .current_height()
            .expect("Attempted to create sweep transaction from unsynced wallet");
        let rct_type: RctType = RctType::ClsagBulletproofPlus;
        let fee_rate: FeeRate = daemon.fee_rate(fee_priority).await.unwrap();
        let outgoing_view_key: Zeroizing<MoneroBytes> = Self::create_outgoing_view_key().await;
        let mut available_wallet_outputs_iter = state.find_available_wallet_outputs().into_iter();
        let payments_sum = payments.iter().map(|payment| payment.amount).sum::<u64>();
        let mut inputs_sum = 0;
        let mut inputs = Vec::new();
        while inputs_sum < payments_sum
            && let Some(available_wallet_output) = available_wallet_outputs_iter.next()
        {
            let input: OutputWithDecoys = OutputWithDecoys::new(&mut *crate::get_rng_mut().await, &*daemon, 16, current_height as usize, available_wallet_output.inner.clone())
                .await
                .unwrap();
            inputs_sum += input.commitment().amount;
            inputs.push(input);
        }
        let change: Change = Change::new(self.view_pair(), None);
        let data: Vec<Vec<u8>> = vec![];
        let raw_payments = payments
            .into_iter()
            .map(|payment| (payment.address, payment.amount))
            .collect::<Vec<_>>();
        loop {
            match SignableTransaction::new(rct_type, outgoing_view_key.clone(), inputs.clone(), raw_payments.clone(), change.clone(), data.clone(), fee_rate) {
                Ok(signable_transaction) => {
                    let outgoing_transfer = Self::create_outgoing_transfer(&outgoing_view_key, &raw_payments, self.address(None), inputs_sum, payments_sum, signable_transaction.necessary_fee());
                    return (signable_transaction, outgoing_transfer);
                }
                Err(SendError::NotEnoughFunds {
                    inputs: _,
                    outputs: needed_amount,
                    necessary_fee: _,
                }) => {
                    while inputs_sum < needed_amount
                        && let Some(available_wallet_output) = available_wallet_outputs_iter.next()
                    {
                        let input: OutputWithDecoys = OutputWithDecoys::new(&mut *crate::get_rng_mut().await, &*daemon, 16, current_height as usize, available_wallet_output.inner.clone())
                            .await
                            .unwrap();
                        inputs.push(input);
                    }
                    if inputs_sum < needed_amount {
                        // TODO: return error instead of panicking
                        panic!("Not enough funds to create transaction. Available funds: {}, required funds: {}", inputs_sum, needed_amount);
                    }
                }
                Err(e) => {
                    // TODO: return error instead of panicking
                    panic!("Failed to create transaction: {e:?}");
                }
            }
        }
    }

    pub async fn create_sweep_transaction(&self, daemon: Daemon, address: MoneroAddress, fee_priority: FeePriority) -> (SignableTransaction, OutgoingTransfer) {
        let state = self.read_state().await;
        self.create_sweep_transaction_inner(&state, daemon, address, fee_priority)
            .await
    }

    async fn create_sweep_transaction_inner(&self, state: &WalletState, daemon: Daemon, address: MoneroAddress, fee_priority: FeePriority) -> (SignableTransaction, OutgoingTransfer) {
        let current_height = state
            .current_height()
            .expect("Attempted to create sweep transaction from unsynced wallet");
        let rct_type: RctType = RctType::ClsagBulletproofPlus;
        let fee_rate: FeeRate = daemon.fee_rate(fee_priority).await.unwrap();
        let outgoing_view_key: Zeroizing<MoneroBytes> = Self::create_outgoing_view_key().await;
        let mut inputs_sum = 0;
        let mut inputs = Vec::new();
        for available_wallet_output in state.find_available_wallet_outputs() {
            let input: OutputWithDecoys = OutputWithDecoys::new(&mut *crate::get_rng_mut().await, &*daemon, 16, current_height as usize, available_wallet_output.inner.clone())
                .await
                .unwrap();
            inputs_sum += input.commitment().amount;
            inputs.push(input);
        }
        debug!("Total funds from {} inputs in sweep transactions: {}", inputs.len(), inputs_sum);
        let change: Change = Change::new(self.view_pair(), None);
        let data: Vec<Vec<u8>> = vec![];
        let test_raw_payments = vec![(address, inputs_sum)];
        let necessary_fee = match SignableTransaction::new(rct_type, outgoing_view_key.clone(), inputs.clone(), test_raw_payments, change.clone(), data.clone(), fee_rate) {
            Ok(_probe_transaction) => {
                // TODO: return error instead of panicking
                panic!("Successfully created probe transaction with 0 fee?");
            }
            Err(SendError::NotEnoughFunds {
                inputs: _,
                outputs: _,
                necessary_fee,
            }) => {
                let Some(necessary_fee) = necessary_fee else {
                    // TODO: return error instead of panicking
                    panic!("No necessary fee information from probe transaction");
                };
                necessary_fee
            }
            Err(e) => {
                // TODO: return error instead of panicking
                panic!("Failed to create transaction: {e:?}");
            }
        };
        debug!("Necessary fee for sweep: {}", necessary_fee);
        if necessary_fee > inputs_sum {
            // TODO: return error instead of panicking
            panic!("Cannot sweep because there is not enough funds to cover network fee");
        }
        let sweep_amount = inputs_sum - necessary_fee;
        let raw_payments = vec![(address, sweep_amount)];
        match SignableTransaction::new(rct_type, outgoing_view_key.clone(), inputs, raw_payments.clone(), change, data, fee_rate) {
            Ok(signable_transaction) => {
                let outgoing_transfer = Self::create_outgoing_transfer(&outgoing_view_key, &raw_payments, self.address(None), inputs_sum, sweep_amount, signable_transaction.necessary_fee());
                (signable_transaction, outgoing_transfer)
            }
            Err(e) => {
                // TODO: return error instead of panicking
                panic!("Failed to create transaction: {e:?}");
            }
        }
    }

    async fn create_outgoing_view_key() -> Zeroizing<MoneroBytes> {
        let mut array: MoneroBytes = [0; 32];
        crate::get_rng_mut().await.fill_bytes(&mut array);
        Zeroizing::new(array)
    }

    fn create_outgoing_transfer(outgoing_view_key: &MoneroBytes, raw_payments: &[(MoneroAddress, u64)], change_address: MoneroAddress, inputs_sum: u64, payments_sum: u64, fee: u64) -> OutgoingTransfer {
        let mut payments = Vec::with_capacity(raw_payments.len() + 1);
        for raw_payment in raw_payments {
            payments.push(OutgoingPayment {
                address: raw_payment.0.to_string(),
                amount: raw_payment.1,
                r#type: OutgoingPaymentType::Payment,
            });
        }
        let change_amount = inputs_sum - payments_sum - fee;
        if change_amount > 0 {
            payments.push(OutgoingPayment {
                address: change_address.to_string(),
                amount: inputs_sum - payments_sum - fee,
                r#type: OutgoingPaymentType::Change,
            });
        }
        OutgoingTransfer {
            outgoing_view_key: *outgoing_view_key,
            payments,
        }
    }

    pub async fn sign_tranasction(&self, transaction: SignableTransaction) -> Transaction {
        transaction
            .sign(&mut *crate::get_rng_mut().await, self.secrets.spend_key())
            .unwrap()
    }

    pub async fn send_transaction(&self, daemon: Daemon, transaction: TransactionWrapper<NotPruned>) {
        let mut state = self.state.write().await;
        self.send_transaction_inner(&mut state, daemon, transaction)
            .await
    }

    async fn send_transaction_inner(&self, state: &mut WalletState, daemon: Daemon, transaction: TransactionWrapper<NotPruned>) {
        daemon
            .publish_transaction(&transaction.inner)
            .await
            .unwrap();
        let scanner = create_scanner(state, self);
        let transaction = transaction.prune();
        let timelocked = scanner
            .scan_transaction(0, transaction.hash, &transaction.inner)
            .expect("Failed to scan sent transaction");
        let mut transactions = HashMap::new();
        transactions.insert(transaction.hash, transaction);
        process_scan_result(state, self.secrets.spend_key(), timelocked, transactions);
    }

    pub async fn register_new_account(&self) -> u32 {
        let mut state = self.write_state().await;
        state.addresses.register_new_account()
    }

    pub async fn register_new_address(&self, account_index: u32) -> u32 {
        let mut state = self.write_state().await;
        state
            .addresses
            .increment_last_tracked_address(account_index)
    }

    pub async fn balance_view(&self) -> view::balance::BalanceView {
        let state = self.read_state().await;
        view::balance_view(&state)
    }

    pub async fn account_view(&self, account_index: u32) -> view::account::AccountView {
        let state = self.read_state().await;
        view::account_view(&state, self, account_index)
    }

    pub async fn accounts_view(&self) -> Vec<view::account::AccountView> {
        let state = self.read_state().await;
        view::accounts_view(&state, self)
    }

    pub async fn transaction_views(&self) -> Vec<view::transaction::TransactionView> {
        let state = self.read_state().await;
        view::transaction_views(&state, self)
    }

    pub async fn create_transaction_view_of<P: PotentiallyPruned>(&self, raw_transaction: &TransactionWrapper<P>) -> view::transaction::TransactionView {
        let state = self.read_state().await;
        view::transaction_view_of(&state, self, raw_transaction)
    }

    pub fn spend_key(&self) -> Zeroizing<MoneroBytes> {
        Zeroizing::new(From::from(**self.secrets.spend_key()))
    }

    pub fn view_key(&self) -> Zeroizing<MoneroBytes> {
        Zeroizing::new(From::from(**self.secrets.view_key()))
    }

    pub fn address(&self, subaddress_index: Option<SubaddressIndex>) -> MoneroAddress {
        let network = self.secrets.network();
        if let Some(subaddress_index) = subaddress_index { self.view_pair().subaddress(network, subaddress_index) } else { self.view_pair().legacy_address(network) }
    }

    pub fn get_wallets_directory<P: AsRef<Path>>(monfluo_directory: P) -> PathBuf {
        monfluo_directory.as_ref().join(WALLETS_DIRECTORY_FILE_NAME)
    }

    pub fn get_wallet_directory<P: AsRef<Path>>(monfluo_directory: P, wallet_name: &str) -> Result<PathBuf, WalletError> {
        let wallet_name_path = PathBuf::from(wallet_name);
        if wallet_name_path.is_absolute() || wallet_name_path.parent() != Some(Path::new("")) {
            return Err(WalletError::BadWalletName(wallet_name.to_owned()));
        }
        Ok(Self::get_wallets_directory(monfluo_directory).join(wallet_name_path))
    }

    fn view_pair(&self) -> ViewPair {
        ViewPair::new(Point::from(&(**self.secrets.spend_key()).into() * ED25519_BASEPOINT_TABLE), self.secrets.view_key().clone()).expect("failed to create view pair")
    }
}

#[cfg(test)]
mod tests {
    use crate::wallet::{io::WalletIoError, secrets::SecretsError};

    use super::*;

    #[test]
    fn get_wallet_directory() {
        let result = Wallet::get_wallet_directory("/path/to/monfluo/dir", "my-wallet");
        assert!(matches!(result, Ok(path) if path.to_str() == Some("/path/to/monfluo/dir/wallets/my-wallet")));
    }

    #[test]
    fn get_wallet_directory_with_trailing_slash_in_wallets_dir() {
        let result = Wallet::get_wallet_directory("/path/to/monfluo/dir/", "my-wallet");
        assert!(matches!(result, Ok(path) if path.to_str() == Some("/path/to/monfluo/dir/wallets/my-wallet")));
    }

    #[test]
    fn get_wallet_directory_with_absolute_wallet_name() {
        let result = Wallet::get_wallet_directory("/monfluo/wallets/dir/", "/my-wallet");
        assert!(matches!(result, Err(WalletError::BadWalletName(wallet_name)) if wallet_name == "/my-wallet"));
    }

    #[test]
    fn get_wallet_directory_with_slash_in_wallet_name() {
        let result = Wallet::get_wallet_directory("/monfluo/wallets/dir/", "my-dir/my-wallet");
        assert!(matches!(result, Err(WalletError::BadWalletName(wallet_name)) if wallet_name == "my-dir/my-wallet"));
    }

    #[tokio::test]
    async fn create_and_open_wallet() {
        const WALLET_NAME: &str = "Monero Wallet";
        let monfluo_directory = tempfile::tempdir().expect("Failed to create tempdir");
        let seed = monero_wallet_util::seed::Seed::Original(crate::generate_seed(monero_wallet_util::seed::original::Language::English).await);
        Wallet::create(monfluo_directory.path(), Network::Stagenet, WALLET_NAME, "", seed.clone(), None, 1780000000000)
            .await
            .unwrap();
        let wallet = Wallet::open(monfluo_directory.path(), WALLET_NAME, "")
            .await
            .unwrap();
        assert_eq!(seed.key(), wallet.spend_key())
    }

    #[tokio::test]
    async fn open_nonexisting_wallet_returns_io_error() {
        const WALLET_NAME: &str = "Monero Wallet";
        let monfluo_directory = tempfile::tempdir().expect("Failed to create tempdir");
        let wallet_result = Wallet::open(monfluo_directory.path(), WALLET_NAME, "").await;
        assert!(matches!(wallet_result, Err(WalletError::Secrets(SecretsError::WalletIo(WalletIoError::Io(e)))) if e.kind() == std::io::ErrorKind::NotFound));
    }
}
