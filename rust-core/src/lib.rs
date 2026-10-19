//! Rust wallet-core ABI smoke layer for the Android migration.
//!
//! This crate deliberately does not claim to replace wallet2 yet. It proves that
//! the Monfluo Rust crate can be built for Android arm64 and loaded by the
//! existing native library without changing the Android UI or Java/Kotlin API.

// Deliberately compile the Monfluo crate while the app-facing wallet API is still being ported.
use monfluo_oxide as _;

const ABI_VERSION: u32 = 1;

/// ABI version checked by the existing JNI library when it loads this module.
#[no_mangle]
pub extern "C" fn anonero_rust_core_abi_version() -> u32 {
    ABI_VERSION
}

/// Validate the shape of a Monero subaddress index at the native ABI boundary.
/// The primary address index (0, 0) is not a subaddress.
///
/// The protocol crate is compiled as a dependency in this smoke layer; wallet operations
/// will be migrated to the Monfluo API in later commits.
#[no_mangle]
pub extern "C" fn anonero_rust_core_subaddress_index_is_valid(
    account: u32,
    address: u32,
) -> u8 {
    u8::from(account != 0 || address != 0)
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
