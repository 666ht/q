// this module contains structs suitable for wallet frontend (presenting data to the user)

pub mod account;
pub mod address;
pub mod balance;
pub mod enote;
pub mod transaction;

use std::collections::HashMap;

use monero_wallet::{address::SubaddressIndex, transaction::PotentiallyPruned};

use crate::{
    MoneroBytes,
    wallet::{
        TransactionWrapper, Wallet,
        state::{OutgoingPaymentType, WalletOutputWrapper, WalletState},
        view::transaction::{TransactionBlockInfoView, TransactionViewInput, TransactionViewSpend, TransactionViewSpendType},
    },
};

pub fn balance_view(state: &WalletState) -> balance::BalanceView {
    let current_height = state.current_height();
    let current_timestamp = crate::current_timestamp();
    let mut total = 0;
    let mut unlocked = 0;
    let mut pending = 0;
    let mut frozen = 0;
    for output in state.outputs.values().filter(|output| {
        !state
            .cache
            .key_image_to_spend_transaction_input
            .contains_key(&output.cached_key_image)
    }) {
        let block_height = state.transactions[&output.inner.transaction()]
            .block_info
            .map(|block_info| block_info.block.height);
        let amount = output.inner.commitment().amount;
        total += amount;
        if output.is_frozen() {
            frozen += amount;
        } else if block_height
            .zip(current_height)
            .is_none_or(|(block_height, current_height)| output.is_locked(block_height, current_height, current_timestamp))
        {
            pending += amount;
        } else {
            unlocked += amount;
        }
    }
    balance::BalanceView {
        total,
        unlocked,
        pending,
        frozen,
    }
}

pub fn accounts_view(state: &WalletState, wallet: &Wallet) -> Vec<account::AccountView> {
    let last_address_index = (state.account_addresses().addresses().len() - 1) as u32;
    (0..=last_address_index)
        .map(|account_index| account_view(state, wallet, account_index))
        .collect()
}

pub fn account_view(state: &WalletState, wallet: &Wallet, account_index: u32) -> account::AccountView {
    let last_address_index = state
        .account_addresses()
        .addresses()
        .get(account_index as usize)
        .copied();
    account::AccountView {
        account_index,
        addresses: last_address_index
            .map(|last_address_index| {
                (0..=last_address_index)
                    .map(|address_index| address_view(state, wallet, account_index, address_index))
                    .collect()
            })
            .unwrap_or_default(),
    }
}

pub fn address_view(state: &WalletState, wallet: &Wallet, account_index: u32, address_index: u32) -> address::AddressView {
    let subaddres_index = SubaddressIndex::new(account_index, address_index);
    let mut used: bool = false;
    let mut amount: u64 = 0;

    for output in state.outputs.values() {
        if output.inner.subaddress() == subaddres_index {
            used = true;
            if !state
                .cache
                .key_image_to_spend_transaction_input
                .contains_key(&output.cached_key_image)
            {
                amount += output.inner.commitment().amount;
            }
        }
    }

    address::AddressView {
        account_index,
        address_index,
        value: wallet
            .address(SubaddressIndex::new(account_index, address_index))
            .to_string(),
        used,
        amount,
    }
}

pub fn transaction_views(state: &WalletState, wallet: &Wallet) -> Vec<transaction::TransactionView> {
    let mut raw_transactions = state.transactions.values().collect::<Vec<_>>();
    raw_transactions.sort_by_key(|raw_transaction| (raw_transaction.first_seen_timestamp, raw_transaction.hash));
    transaction_views_of(state, wallet, raw_transactions)
}

pub fn transaction_view_of<P: PotentiallyPruned>(state: &WalletState, wallet: &Wallet, raw_transaction: &TransactionWrapper<P>) -> transaction::TransactionView {
    transaction_views_of(state, wallet, vec![raw_transaction]).remove(0)
}

pub fn transaction_views_of<P: PotentiallyPruned>(state: &WalletState, wallet: &Wallet, raw_transactions: Vec<&TransactionWrapper<P>>) -> Vec<transaction::TransactionView> {
    let current_height = state.current_height();

    let mut wallet_inputs = HashMap::<MoneroBytes, Vec<&WalletOutputWrapper>>::new();
    let mut wallet_outputs = HashMap::<MoneroBytes, Vec<&WalletOutputWrapper>>::new();

    for output in state.outputs.values() {
        wallet_inputs
            .entry(output.inner.transaction())
            .or_default()
            .push(output);
    }

    for (key_image, &(transaction_hash, _input_index)) in state.cache.key_image_to_spend_transaction_input.iter() {
        wallet_outputs
            .entry(transaction_hash)
            .or_default()
            .push(&state.cache.key_image_to_output[key_image]);
    }

    raw_transactions
        .into_iter()
        .map(|raw_transaction| {
            let inputs = wallet_inputs
                .get(&raw_transaction.hash)
                .map(Vec::as_slice)
                .unwrap_or_default()
                .iter()
                .map(|wallet_output| TransactionViewInput {
                    address: wallet.address(wallet_output.inner.subaddress()).to_string(),
                    amount: wallet_output.inner.commitment().amount,
                })
                .collect();

            let spends = raw_transaction
                .outgoing_transfer
                .as_ref()
                .map(|outgoing_transfer| {
                    outgoing_transfer
                        .payments
                        .iter()
                        .map(|outgoing_payment| TransactionViewSpend {
                            address: Some(outgoing_payment.address.clone()),
                            amount: outgoing_payment.amount,
                            r#type: match outgoing_payment.r#type {
                                OutgoingPaymentType::Payment => TransactionViewSpendType::Payment,
                                OutgoingPaymentType::Change => TransactionViewSpendType::Change,
                            },
                        })
                        .collect::<Vec<_>>()
                })
                .unwrap_or_else(|| {
                    wallet_outputs
                        .get(&raw_transaction.hash)
                        .map(Vec::as_slice)
                        .unwrap_or_default()
                        .iter()
                        .map(|wallet_output| TransactionViewSpend {
                            address: None,
                            amount: wallet_output.inner.commitment().amount,
                            r#type: TransactionViewSpendType::Unknown,
                        })
                        .collect()
                });

            transaction::TransactionView {
                transaction_hash: raw_transaction.hash,
                timestamp: raw_transaction
                    .block_info
                    .as_ref()
                    .map_or(raw_transaction.first_seen_timestamp, |block_info| block_info.timestamp),
                fee: raw_transaction.inner.fee(),
                confirmations: raw_transaction
                    .block_info
                    .as_ref()
                    .zip(current_height)
                    .map_or(0, |(block_info, current_height)| current_height.saturating_sub(block_info.block.height - 1)),
                block_info: raw_transaction
                    .block_info
                    .map(|block_info| TransactionBlockInfoView {
                        block_height: block_info.block.height,
                        block_hash: block_info.block.hash,
                    }),
                inputs,
                spends,
                outgoing_view_key: raw_transaction
                    .outgoing_transfer
                    .as_ref()
                    .map(|outgoing_transfer| outgoing_transfer.outgoing_view_key),
            }
        })
        .collect()
}
