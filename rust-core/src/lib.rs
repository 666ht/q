//! Rust wallet-core entry points for the Android migration.
//!
//! Android-facing APIs stay compatible. This file starts replacing individual
//! wallet primitives with Rust; wallet creation, state, scanning and transaction
//! handling still remain on the legacy wallet2 backend until each is ported.

use jni::{
    objects::{JClass, JString},
    sys::{jboolean, JNI_FALSE, JNI_TRUE},
    JNIEnv,
};
use monero_seed::{Language, Seed};
use zeroize::Zeroizing;

// Keep the Monfluo crate in the Android build while its wallet-facing API is ported.
use monfluo_oxide as _;

const ABI_VERSION: u32 = 1;

/// ABI version checked by the existing native library when it loads this module.
#[no_mangle]
pub extern "C" fn anonero_rust_core_abi_version() -> u32 {
    ABI_VERSION
}

/// Validate a Monero subaddress index shape at the native ABI boundary.
/// The primary address index (0, 0) is not a subaddress.
#[no_mangle]
pub extern "C" fn anonero_rust_core_subaddress_index_is_valid(
    account: u32,
    address: u32,
) -> u8 {
    u8::from(account != 0 || address != 0)
}

/// Return whether a phrase is a valid standard Monero seed in any supported language.
/// Secret phrase copies are zeroized when the parser returns.
fn is_valid_monero_seed(phrase: &str) -> bool {
    const LANGUAGES: [Language; 13] = [
        Language::Chinese,
        Language::English,
        Language::Dutch,
        Language::French,
        Language::Spanish,
        Language::German,
        Language::Italian,
        Language::Portuguese,
        Language::Japanese,
        Language::Russian,
        Language::Esperanto,
        Language::Lojban,
        Language::DeprecatedEnglish,
    ];

    LANGUAGES.into_iter().any(|language| {
        Seed::from_string(language, Zeroizing::new(phrase.to_owned())).is_ok()
    })
}

/// JNI entry point invoked by WalletManager before legacy recovery.
/// The caller deliberately does not reject a phrase based on this result yet;
/// wallet2 remains the compatibility fallback while migration is in progress.
#[no_mangle]
pub extern "system" fn Java_io_anonero_model_WalletManager_validateSeedWithRust(
    mut env: JNIEnv,
    _class: JClass,
    phrase: JString,
) -> jboolean {
    let phrase: String = match env.get_string(&phrase) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };

    if is_valid_monero_seed(&phrase) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
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

    #[test]
    fn validates_a_known_monero_seed() {
        let phrase = "washing thirsty occur lectures tuesday fainted toxic adapt abnormal memoir nylon mostly building shrugged online ember northern ruby woes dauntless boil family illness inroads northern";
        assert!(is_valid_monero_seed(phrase));
    }

    #[test]
    fn rejects_a_phrase_that_is_not_a_monero_seed() {
        assert!(!is_valid_monero_seed("this is not a valid monero seed"));
    }
}
