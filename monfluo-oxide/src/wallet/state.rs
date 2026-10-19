use std::{collections::HashMap, path::Path};

use monero_wallet::{
    WalletOutput,
    transaction::{NotPruned, PotentiallyPruned},
};
use monero_wallet::{
    ed25519::{CompressedPoint, Scalar},
    transaction::{Input, Pruned, Transaction},
};
use serde::{Deserialize, Serialize};
use zeroize::{Zeroize, ZeroizeOnDrop};

use crate::MoneroBytes;

#[derive(Debug)]
pub enum Error {}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Blockchain {
    start_block_number: u32,
    hashes: Vec<MoneroBytes>,
}

impl Blockchain {
    pub fn new(start_block_number: u32, hashes: Vec<MoneroBytes>) -> Self {
        Self {
            start_block_number,
            hashes,
        }
    }

    pub fn start_block_number(&self) -> u32 {
        self.start_block_number
    }

    pub fn last(&self) -> Option<&MoneroBytes> {
        self.hashes.last()
    }

    pub fn push(&mut self, hash: MoneroBytes) {
        self.hashes.push(hash);
    }

    pub fn hashes(&self) -> &[MoneroBytes] {
        &self.hashes
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrackedAddresses {
    // index = account index, value = last generated address index for that account
    tracked_addresses: Vec<u32>,
}

impl TrackedAddresses {
    pub fn new() -> Self {
        Self {
            tracked_addresses: vec![0],
        }
    }

    pub fn addresses(&self) -> &Vec<u32> {
        &self.tracked_addresses
    }

    pub fn register_new_account(&mut self) -> u32 {
        self.tracked_addresses.push(0);
        (self.tracked_addresses.len() - 1) as u32
    }

    pub fn increment_last_tracked_address(&mut self, account: u32) -> u32 {
        while self.tracked_addresses.len() < account as usize {
            self.tracked_addresses.push(0);
        }
        self.tracked_addresses[account as usize] += 1;
        self.tracked_addresses[account as usize]
    }

    pub fn set_last_tracked_address(&mut self, account: u32, address: u32) {
        while self.tracked_addresses.len() < account as usize {
            self.tracked_addresses.push(0);
        }
        if self.tracked_addresses[account as usize] < address {
            self.tracked_addresses[account as usize] = address;
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct BlockIdentifier {
    pub height: u32,
    #[serde(serialize_with = "serde_utils::serialize_as_hex")]
    #[serde(deserialize_with = "serde_utils::deserialize_from_hex")]
    pub hash: MoneroBytes,
}

impl BlockIdentifier {
    pub fn new(height: u32, hash: MoneroBytes) -> Self {
        Self {
            height,
            hash,
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct TransactionBlockInfo {
    pub block: BlockIdentifier,
    pub timestamp: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OutgoingTransfer {
    #[serde(serialize_with = "serde_utils::serialize_as_hex")]
    #[serde(deserialize_with = "serde_utils::deserialize_from_hex")]
    pub outgoing_view_key: MoneroBytes,
    pub payments: Vec<OutgoingPayment>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum OutgoingPaymentType {
    Payment,
    Change,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OutgoingPayment {
    pub address: String,
    pub amount: u64,
    pub r#type: OutgoingPaymentType,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransactionWrapper<P: PotentiallyPruned = Pruned> {
    #[serde(serialize_with = "serde_utils::serialize_as_hex")]
    #[serde(deserialize_with = "serde_utils::deserialize_from_hex")]
    pub inner: Transaction<P>,
    #[serde(serialize_with = "serde_utils::serialize_as_hex")]
    #[serde(deserialize_with = "serde_utils::deserialize_from_hex")]
    pub hash: MoneroBytes,
    // None if the transaction is pending
    pub block_info: Option<TransactionBlockInfo>,
    // same as the block timestamp if synced from the blockchain, otherwise either the time we created this transaction or the time we first fetched it from the mempool
    pub first_seen_timestamp: u64,
    // Some if this transaction was created by us
    pub outgoing_transfer: Option<OutgoingTransfer>,
}

impl<P: PotentiallyPruned> TransactionWrapper<P> {
    pub fn new(transaction: Transaction<P>, hash: MoneroBytes, block_info: Option<TransactionBlockInfo>, first_seen_timestamp: u64, outgoing_transfer: Option<OutgoingTransfer>) -> Self {
        Self {
            inner: transaction,
            hash,
            block_info,
            first_seen_timestamp,
            outgoing_transfer,
        }
    }

    pub fn is_mempool_transaction(&self) -> bool {
        self.block_info.is_none()
    }
}

impl TransactionWrapper<NotPruned> {
    pub fn prune(self) -> TransactionWrapper<Pruned> {
        let TransactionWrapper {
            inner,
            hash,
            block_info,
            first_seen_timestamp,
            outgoing_transfer,
        } = self;
        TransactionWrapper {
            inner: inner.pruned_with_prunable().0,
            hash,
            block_info,
            first_seen_timestamp,
            outgoing_transfer,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize, Zeroize, ZeroizeOnDrop)]
pub struct WalletOutputId {
    pub transaction_hash: MoneroBytes,
    pub transaction_index: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Zeroize, ZeroizeOnDrop)]
pub struct WalletOutputWrapper {
    #[serde(serialize_with = "serde_utils::serialize_as_hex")]
    #[serde(deserialize_with = "serde_utils::deserialize_from_hex")]
    pub inner: WalletOutput,
    #[serde(serialize_with = "serde_utils::serialize_as_hex")]
    #[serde(deserialize_with = "serde_utils::deserialize_from_hex")]
    pub cached_key_image: CompressedPoint,
}

impl WalletOutputWrapper {
    pub fn id(&self) -> WalletOutputId {
        WalletOutputId {
            transaction_hash: self.inner.transaction(),
            transaction_index: self.inner.index_in_transaction(),
        }
    }

    pub fn is_locked(&self, block_height: u32, current_height: u32, current_timestamp: u64) -> bool {
        if current_height < block_height + crate::CONFIRMATIONS - 1 {
            return true;
        }
        match self.inner.additional_timelock() {
            monero_wallet::transaction::Timelock::None => false,
            monero_wallet::transaction::Timelock::Block(block_height) => (current_height as usize) < block_height,
            monero_wallet::transaction::Timelock::Time(timelock_timestamp_seconds) => current_timestamp < timelock_timestamp_seconds * 1000,
        }
    }

    pub fn is_frozen(&self) -> bool {
        // TODO: impement freezing/thawing enotes
        false
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Zeroize, ZeroizeOnDrop)]
pub struct WalletState {
    #[zeroize(skip)]
    pub blockchain: Option<Blockchain>,
    #[zeroize(skip)]
    pub addresses: TrackedAddresses,
    #[serde(serialize_with = "serde_utils::serialize_hashmap_as_vec")]
    #[serde(deserialize_with = "serde_utils::deserialize_hashmap_as_vec")]
    #[zeroize(skip)]
    pub outputs: HashMap<WalletOutputId, WalletOutputWrapper>,
    #[serde(serialize_with = "serde_utils::serialize_hashmap_as_vec")]
    #[serde(deserialize_with = "serde_utils::deserialize_hashmap_as_vec")]
    #[zeroize(skip)]
    pub transactions: HashMap<MoneroBytes, TransactionWrapper>,
    #[zeroize(skip)]
    #[serde(skip)]
    pub cache: WalletStateCache,
}

#[derive(Debug, Clone, Default, Zeroize, ZeroizeOnDrop)]
pub struct WalletStateCache {
    #[zeroize(skip)]
    pub key_image_to_output: HashMap<CompressedPoint, WalletOutputWrapper>,
    #[zeroize(skip)]
    pub key_image_to_spend_transaction_input: HashMap<CompressedPoint, (MoneroBytes, u64)>,
    #[zeroize(skip)]
    pub hash_to_transaction: HashMap<MoneroBytes, TransactionWrapper>,
    #[zeroize(skip)]
    pub mempool_transactions: HashMap<MoneroBytes, Transaction<Pruned>>,
    pub last_sync_daemon_time: Option<u64>,
}

impl WalletState {
    pub fn new() -> Self {
        Self {
            blockchain: None,
            addresses: TrackedAddresses::new(),
            outputs: HashMap::new(),
            transactions: HashMap::new(),
            cache: Default::default(),
        }
    }

    pub async fn load(path: &Path, password: &str) -> Result<Self, Error> {
        let bytes = tokio::fs::read(path).await.unwrap();
        let decrypted_bytes = super::encryption::decrypt(bytes, password).unwrap();
        let mut state: Self = serde_json::from_slice(&decrypted_bytes).unwrap();
        for output in state.outputs.values().cloned().collect::<Vec<_>>() {
            state.add_output_inner(output);
        }
        let mut transactions_to_add = Vec::new();
        for transaction in state.transactions.values() {
            let relevant_inputs = state.find_transaction_relevant_inputs(transaction);
            if !relevant_inputs.is_empty() {
                transactions_to_add.push((transaction.clone(), relevant_inputs));
            }
        }
        for (transaction_to_add, relevant_inputs) in transactions_to_add {
            state.add_transaction_inner(transaction_to_add, relevant_inputs);
        }
        Ok(state)
    }

    pub async fn store(&self, path: &Path, password: &str) -> Result<(), Error> {
        let bytes = serde_json::to_vec(&self).unwrap();
        let encrypted_bytes = super::encryption::encrypt(bytes, password).unwrap();
        super::atomic_fs::write_atomically(path, &encrypted_bytes)
            .await
            .unwrap();
        Ok(())
    }

    pub fn current_height(&self) -> Option<u32> {
        self.blockchain
            .as_ref()
            .map(|blockchain| blockchain.start_block_number + (blockchain.hashes.len() as u32).saturating_sub(1))
    }

    pub fn account_addresses(&self) -> &TrackedAddresses {
        &self.addresses
    }

    pub fn outputs(&self) -> &HashMap<WalletOutputId, WalletOutputWrapper> {
        &self.outputs
    }

    pub fn add_output(&mut self, output: WalletOutput, spend_key: &Scalar) {
        let cached_key_image = crate::crypto::key_image_of_output(&output, spend_key);
        let output = WalletOutputWrapper {
            inner: output,
            cached_key_image,
        };
        self.outputs.insert(output.id(), output.clone());
        self.add_output_inner(output);
    }

    fn add_output_inner(&mut self, output: WalletOutputWrapper) {
        self.cache
            .key_image_to_output
            .insert(output.cached_key_image, output);
    }

    fn copy_existing_transaction_details(&self, transaction: &mut TransactionWrapper) {
        if let Some(existing_transaction) = self.transactions.get(&transaction.hash) {
            transaction.first_seen_timestamp = existing_transaction.first_seen_timestamp;
            transaction.outgoing_transfer = existing_transaction.outgoing_transfer.clone();
        }
    }

    pub fn add_transaction(&mut self, mut transaction: TransactionWrapper) {
        self.copy_existing_transaction_details(&mut transaction);
        self.transactions
            .insert(transaction.hash, transaction.clone());
        self.add_transaction_inner(transaction, HashMap::new());
    }

    pub fn add_transaction_if_spends_output(&mut self, mut transaction: TransactionWrapper) -> HashMap<u64, CompressedPoint> {
        self.copy_existing_transaction_details(&mut transaction);
        let relevant_inputs = self.find_transaction_relevant_inputs(&transaction);
        if !relevant_inputs.is_empty() {
            self.transactions
                .insert(transaction.hash, transaction.clone());
            self.add_transaction_inner(transaction.clone(), relevant_inputs.clone());
        }
        relevant_inputs
    }

    fn add_transaction_inner(&mut self, transaction: TransactionWrapper, relevant_inputs: HashMap<u64, CompressedPoint>) {
        self.cache
            .hash_to_transaction
            .insert(transaction.hash, transaction.clone());
        for (input_index, key_image) in relevant_inputs {
            self.cache
                .key_image_to_spend_transaction_input
                .insert(key_image, (transaction.hash, input_index));
        }
    }

    fn find_transaction_relevant_inputs(&self, transaction: &TransactionWrapper) -> HashMap<u64, CompressedPoint> {
        let mut relevant_inputs = HashMap::new();

        for (input_index, input) in transaction.inner.prefix().inputs.iter().enumerate() {
            if let Input::ToKey {
                amount: _,
                key_offsets: _,
                key_image,
            } = input
                && self.cache.key_image_to_output.contains_key(key_image)
            {
                relevant_inputs.insert(input_index as u64, *key_image);
            }
        }

        relevant_inputs
    }

    pub(crate) fn find_available_wallet_outputs(&self) -> Vec<&WalletOutputWrapper> {
        let current_height = self.current_height();
        let current_timestamp = crate::current_timestamp();
        let mut available_wallet_outputs = self
            .outputs
            .values()
            .filter(|output| {
                self.transactions[&output.inner.transaction()]
                    .block_info
                    .zip(current_height)
                    .is_some_and(|(block_info, current_height)| !output.is_locked(block_info.block.height, current_height, current_timestamp))
            })
            .filter(|output| !output.is_frozen())
            .filter(|output| {
                !self
                    .cache
                    .key_image_to_spend_transaction_input
                    .contains_key(&output.cached_key_image)
            })
            .collect::<Vec<_>>();
        // TODO: this will select lowest-amount enotes first, is this the strategy we want to use?
        available_wallet_outputs.sort_by_key(|output| output.inner.commitment().amount);
        available_wallet_outputs
    }
}

mod serde_utils {
    use monero_wallet::{
        WalletOutput,
        block::Block,
        ed25519::CompressedPoint,
        transaction::{PotentiallyPruned, Transaction},
    };
    use serde::{Deserialize, Deserializer, Serialize, Serializer};
    use std::{collections::HashMap, hash::Hash, io::Read};
    use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

    use crate::util::HexFormatted;

    #[derive(Zeroize, ZeroizeOnDrop)]
    pub enum Bytes<'a, const N: usize = 0> {
        Vec(Vec<u8>),
        Array([u8; N]),
        #[zeroize(skip)]
        Slice(&'a [u8]),
    }

    impl<'a, const N: usize> Bytes<'a, N> {
        fn as_slice(&'a self) -> &'a [u8] {
            match self {
                Bytes::Vec(vec) => vec,
                Bytes::Array(array) => array,
                Bytes::Slice(slice) => slice,
            }
        }
    }

    pub trait SerializableToBytes<'a, const N: usize = 0> {
        fn serialize_to_bytes(&'a self) -> Bytes<'a, N>;
    }

    impl<'a> SerializableToBytes<'a, 0> for WalletOutput {
        fn serialize_to_bytes(&'a self) -> Bytes<'a> {
            Bytes::Vec(self.serialize())
        }
    }

    impl<'a, P: PotentiallyPruned> SerializableToBytes<'a, 0> for Transaction<P> {
        fn serialize_to_bytes(&'a self) -> Bytes<'a> {
            Bytes::Vec(self.serialize())
        }
    }

    impl<'a> SerializableToBytes<'a, 0> for Block {
        fn serialize_to_bytes(&'a self) -> Bytes<'a> {
            Bytes::Vec(self.serialize())
        }
    }

    impl<'a, const N: usize> SerializableToBytes<'a, N> for [u8; N] {
        fn serialize_to_bytes(&'a self) -> Bytes<'a, N> {
            Bytes::Slice(self)
        }
    }

    impl<'a> SerializableToBytes<'a, 32> for CompressedPoint {
        fn serialize_to_bytes(&'a self) -> Bytes<'a, 32> {
            Bytes::Array(self.to_bytes())
        }
    }

    pub trait DeserializableFromRead: Sized {
        fn read<R: Read>(r: &mut R) -> std::io::Result<Self>;
    }

    impl DeserializableFromRead for WalletOutput {
        fn read<R: Read>(r: &mut R) -> std::io::Result<WalletOutput> {
            WalletOutput::read(r)
        }
    }

    impl<P: PotentiallyPruned> DeserializableFromRead for Transaction<P> {
        fn read<R: Read>(r: &mut R) -> std::io::Result<Transaction<P>> {
            Transaction::<P>::read(r)
        }
    }

    impl DeserializableFromRead for Block {
        fn read<R: Read>(r: &mut R) -> std::io::Result<Block> {
            Block::read(r)
        }
    }

    impl DeserializableFromRead for CompressedPoint {
        fn read<R: Read>(r: &mut R) -> std::io::Result<CompressedPoint> {
            CompressedPoint::read(r)
        }
    }

    impl<const N: usize> DeserializableFromRead for [u8; N] {
        fn read<R: Read>(r: &mut R) -> std::io::Result<[u8; N]> {
            let mut array: [u8; N] = [0; N];
            r.read_exact(&mut array)?;
            Ok(array)
        }
    }

    pub fn serialize_as_hex<'a, const N: usize, T: SerializableToBytes<'a, N>, S>(value: &'a T, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        let bytes: Bytes<'a, N> = value.serialize_to_bytes();
        let hex: Zeroizing<String> = Zeroizing::new(HexFormatted::borrowed(bytes.as_slice()).to_string());
        serializer.serialize_str(&hex)
    }

    pub fn deserialize_from_hex<'de, T: DeserializableFromRead, D>(deserializer: D) -> Result<T, D::Error>
    where
        D: Deserializer<'de>,
    {
        let hex: Zeroizing<String> = Zeroizing::new(String::deserialize(deserializer)?);
        let bytes: Zeroizing<Vec<u8>> = crate::util::hex_to_bytes(&hex).unwrap();
        let wallet_output = T::read(&mut bytes.as_slice()).unwrap();
        Ok(wallet_output)
    }

    pub fn serialize_hashmap_as_vec<K, V, S>(map: &HashMap<K, V>, serializer: S) -> Result<S::Ok, S::Error>
    where
        K: Serialize,
        V: Serialize,
        S: Serializer,
    {
        let entries: Vec<(&K, &V)> = map.iter().collect();
        entries.serialize(serializer)
    }

    pub fn deserialize_hashmap_as_vec<'de, K, V, D>(deserializer: D) -> Result<HashMap<K, V>, D::Error>
    where
        K: Deserialize<'de> + Eq + Hash,
        V: Deserialize<'de>,
        D: Deserializer<'de>,
    {
        let entries: Vec<(K, V)> = Vec::deserialize(deserializer)?;
        Ok(entries.into_iter().collect())
    }
}
