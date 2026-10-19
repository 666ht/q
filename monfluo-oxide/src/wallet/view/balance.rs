#[derive(Debug, Clone)]
pub struct BalanceView {
    pub total: u64,
    pub unlocked: u64,
    pub pending: u64,
    pub frozen: u64,
}
