//! Rust wallet-core ABI smoke layer for the Android migration.
//!
//! This crate deliberately does not claim to replace wallet2 yet. It proves that
//! the Rust Monero wallet crate can be built for Android arm64 and loaded by the
//! existing native library without changing the Android UI or Java/Kotlin API.

use monero_wallet::address::SubaddressIndex;

const ABI_VERSION: u32 = 1;

/// ABI version checked by the existing JNI library when it loads this module.
#[no_mangle]
pub extern "C" fn anonero_rust_core_abi_version() -> u32 {
    ABI_VERSION
}

/// Validate a Monero subaddress index using the Rust wallet library's own type.
/// The primary address index (0, 0) is not a subaddress.
#[no_mangle]
pub extern "C" fn anonero_rust_core_subaddress_index_is_valid(
    account: u32,
    address: u32,
) -> u8 {
    u8::from(SubaddressIndex::new(account, address).is_some())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn abi_version_is_stable() {
        assert_eq!(anonero_rust_core_abi_version(), 1);
    }

    #[test]
    fn primary_address_is_not_a_subaddress() {
        assert_eq!(anonero_rust_core_subaddress_index_is_valid(0, 0), 0);
        assert_eq!(anonero_rust_core_subaddress_index_is_valid(1, 0), 1);
        assert_eq!(anonero_rust_core_subaddress_index_is_valid(0, 1), 1);
    }
}
