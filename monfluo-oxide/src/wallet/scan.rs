use std::collections::HashMap;

use monero_wallet::{Scanner, Timelocked, address::SubaddressIndex, ed25519::Scalar, transaction::Input};
use tracing::info;

use crate::{
    MoneroBytes,
    util::{HexFormatted, SubaddressIndexDisplay},
    wallet::{
        Wallet,
        state::{TransactionWrapper, WalletState},
    },
};

// how many accounts we register in the scanner
// TODO: this should be a wallet setting, so that advanced users can override it to a higher value
const ACCOUNTS_LOOKAHEAD: u32 = 50;
// how many addresses we register per account in the scanner
// TODO: this should be a wallet setting, so that advanced users can override it to a higher value
const ADDRESSES_LOOKAHEAD: u32 = 200;

pub(crate) fn create_scanner(state: &WalletState, wallet: &Wallet) -> Scanner {
    let mut scanner = Scanner::new(wallet.view_pair());
    let wallet_accounts = state.account_addresses().addresses();
    for account in 0..(wallet_accounts.len() as u32 + ACCOUNTS_LOOKAHEAD) {
        let wallet_addresses = wallet_accounts.get(account as usize).copied().unwrap_or(0);
        let from_address = if account == 0 { 1 } else { 0 };
        for address in from_address..=(wallet_addresses + ADDRESSES_LOOKAHEAD) {
            scanner.register_subaddress(SubaddressIndex::new(account, address).expect("failed to construct subbaddress index when registering addresses in scanner"));
        }
    }
    scanner
}

pub(crate) fn process_scan_result(state: &mut WalletState, spend_key: &Scalar, timelocked: Timelocked, transactions: HashMap<MoneroBytes, TransactionWrapper>) {
    let outputs = timelocked.ignore_additional_timelock();
    if !outputs.is_empty() {
        info!("Found {} outputs", outputs.len());
        for output in outputs {
            let transaction_hash = output.transaction();
            let transaction = transactions
                .get(&transaction_hash)
                .expect("Could not find transaction of a wallet output");
            info!("Found output with relative id {} to address {} and amount {} in transaction {}", output.index_on_blockchain(), SubaddressIndexDisplay(output.subaddress()), output.commitment().amount, HexFormatted::borrowed(&transaction_hash));
            if let Some(subaddress_index) = output.subaddress() {
                let account = subaddress_index.account();
                let address = subaddress_index.address();
                let new_last_tracked_address = address + 1;
                state.addresses.set_last_tracked_address(account, address);
                info!("Updated last tracked address for account {} to {}", account, new_last_tracked_address);
            }
            state.add_transaction(transaction.clone());
            state.add_output(output, spend_key);
        }
    }
}

pub(crate) fn process_transaction(state: &mut WalletState, transaction: TransactionWrapper) {
    let transaction_hash = transaction.hash;
    let relevant_inputs = state.add_transaction_if_spends_output(transaction.clone());
    for input_index in relevant_inputs.keys().copied() {
        let Input::ToKey {
            ..
        } = transaction.inner.prefix().inputs[input_index as usize]
        else {
            panic!("Wallet returned bad relevant input index {input_index} for transaction {}", HexFormatted::borrowed(&transaction_hash));
        };
        info!("Found a spend in transaction {}", HexFormatted::borrowed(&transaction_hash));
    }
}
