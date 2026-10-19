#[derive(Debug, Clone)]
pub struct AddressView {
    pub account_index: u32,
    pub address_index: u32,
    pub value: String,
    pub used: bool,
    pub amount: u64,
}
