use crate::MoneroBytes;

#[derive(Debug, Clone)]
pub struct TransactionBlockInfoView {
    pub block_height: u32,
    pub block_hash: MoneroBytes,
}

#[derive(Debug, Clone)]
pub struct TransactionView {
    pub transaction_hash: MoneroBytes,
    pub timestamp: u64,
    pub fee: u64,
    pub confirmations: u32,
    pub block_info: Option<TransactionBlockInfoView>,
    pub inputs: Vec<TransactionViewInput>,
    pub spends: Vec<TransactionViewSpend>,
    pub outgoing_view_key: Option<MoneroBytes>,
}

#[derive(Debug, Clone)]
pub struct TransactionViewInput {
    pub address: String,
    pub amount: u64,
}

#[derive(Debug, Clone, Copy, Default)]
pub enum TransactionViewSpendType {
    Payment,
    Change,
    #[default]
    Unknown,
}

#[derive(Debug, Clone)]
pub struct TransactionViewSpend {
    pub address: Option<String>,
    pub amount: u64,
    pub r#type: TransactionViewSpendType,
}
