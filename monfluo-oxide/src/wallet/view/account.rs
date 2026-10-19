use crate::wallet::view::address::AddressView;

#[derive(Debug, Clone)]
pub struct AccountView {
    pub account_index: u32,
    pub addresses: Vec<AddressView>,
}
