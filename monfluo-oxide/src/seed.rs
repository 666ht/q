use strum::{EnumString, VariantArray};
use zeroize::Zeroizing;

#[derive(Debug, Clone, Copy, PartialEq, Eq, EnumString)]
pub enum SeedType {
    #[strum(serialize = "Monero", serialize = "monero", serialize = "Original", serialize = "original")]
    Monero,
    #[strum(serialize = "Polyseed", serialize = "polyseed")]
    Polyseed,
}

pub fn try_decode_seed(seed: &str, r#type: Option<SeedType>, language_str: Option<String>) -> Option<monero_wallet_util::seed::Seed> {
    if r#type.is_none() || r#type == Some(SeedType::Monero) {
        for language in monero_wallet_util::seed::original::Language::VARIANTS {
            if (language_str.is_none() || matches!(&language_str, Some(language_str) if language_str.eq_ignore_ascii_case(&language.to_string())))
                && let Some(seed) = try_decode_original_seed_with_language(Zeroizing::new(seed.to_owned()), *language)
            {
                return Some(monero_wallet_util::seed::Seed::Original(seed));
            }
        }
    }
    if r#type.is_none() || r#type == Some(SeedType::Polyseed) {
        for language in monero_wallet_util::seed::polyseed::Language::VARIANTS {
            if (language_str.is_none() || matches!(&language_str, Some(language_str) if language_str.eq_ignore_ascii_case(&language.to_string())))
                && let Some(seed) = try_decode_polyseed_with_language(Zeroizing::new(seed.to_owned()), *language)
            {
                return Some(monero_wallet_util::seed::Seed::Polyseed(seed));
            }
        }
    }
    None
}

pub fn try_decode_original_seed(words: Zeroizing<String>) -> Option<monero_wallet_util::seed::original::Seed> {
    for language in monero_wallet_util::seed::original::Language::VARIANTS {
        if let Some(seed) = try_decode_original_seed_with_language(words.clone(), *language) {
            return Some(seed);
        }
    }
    None
}

pub fn try_decode_original_seed_with_language(words: Zeroizing<String>, language: monero_wallet_util::seed::original::Language) -> Option<monero_wallet_util::seed::original::Seed> {
    monero_wallet_util::seed::original::Seed::from_string(language, words).ok()
}

pub fn try_decode_polyseed(words: Zeroizing<String>) -> Option<monero_wallet_util::seed::polyseed::Polyseed> {
    for language in monero_wallet_util::seed::polyseed::Language::VARIANTS {
        if let Some(seed) = try_decode_polyseed_with_language(words.clone(), *language) {
            return Some(seed);
        }
    }
    None
}

pub fn try_decode_polyseed_with_language(words: Zeroizing<String>, language: monero_wallet_util::seed::polyseed::Language) -> Option<monero_wallet_util::seed::polyseed::Polyseed> {
    monero_wallet_util::seed::polyseed::Polyseed::from_string(language, words).ok()
}
