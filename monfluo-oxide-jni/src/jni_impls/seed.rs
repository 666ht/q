use std::{ops::Deref, str::FromStr};

use jni::{
    EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    jni_mangle,
    objects::{JClass, JObject, JString},
};
use zeroize::Zeroizing;

#[jni_mangle("org.monfluo.wallet.lib.seed.MoneroSeed", "fromMnemonic")]
pub fn monero_seed_from_mnemonic<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    java_mnemonic: JString<'local>,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        let mnemonic = crate::java::JavaStringHelper::from_jstring(env, &java_mnemonic);
        match monfluo_oxide::seed::try_decode_original_seed(Zeroizing::new(
            mnemonic.to_cow_str().deref().to_owned(),
        )) {
            Some(seed) => {
                let language = seed.language();
                let java_language = crate::java::to_java_monero_seed_language(env, cache, language);
                Ok::<JObject<'local>, jni::errors::Error>(crate::java::new_monero_seed(
                    env,
                    cache,
                    &java_mnemonic,
                    &java_language,
                ))
            }
            None => Ok::<JObject<'local>, jni::errors::Error>(JObject::null()),
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.seed.Polyseed", "fromMnemonic")]
pub fn polyseed_from_mnemonic<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    java_mnemonic: JString<'local>,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        let mnemonic = crate::java::JavaStringHelper::from_jstring(env, &java_mnemonic);
        match monfluo_oxide::seed::try_decode_polyseed(Zeroizing::new(
            mnemonic.to_cow_str().deref().to_owned(),
        )) {
            Some(seed) => {
                let language = seed.language();
                let java_language = crate::java::to_java_polyseed_language(env, cache, language);
                Ok::<JObject<'local>, jni::errors::Error>(crate::java::new_polyseed(
                    env,
                    cache,
                    &java_mnemonic,
                    &java_language,
                ))
            }
            None => Ok::<JObject<'local>, jni::errors::Error>(JObject::null()),
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.seed.MoneroSeedLanguage", "getWords")]
pub fn get_words_monero<'local>(
    mut env: EnvUnowned<'local>,
    java_monero_seed_language: JObject<'local>,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        let list = crate::java::new_arraylist(env, cache);
        let enum_name = crate::java::enum_name(env, cache, &java_monero_seed_language);
        let enum_name = crate::java::JavaStringHelper::from_jstring(env, &enum_name);
        let words = monfluo_oxide::monero_wallet_util::seed::original::Language::from_str(
            &enum_name.to_cow_str(),
        )
        .unwrap()
        .get_words();

        for word in words {
            let word = env
                .new_string(word)
                .expect("Failed to create java string for a monero language word");
            crate::java::list_add(env, cache, &list, &JObject::from(word));
        }

        Ok::<JObject<'local>, jni::errors::Error>(list)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.seed.PolyseedLanguage", "getWords")]
pub fn get_words_polyseed<'local>(
    mut env: EnvUnowned<'local>,
    java_polyseed_language: JObject<'local>,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        let list = crate::java::new_arraylist(env, cache);
        let enum_name = crate::java::enum_name(env, cache, &java_polyseed_language);
        let enum_name = crate::java::JavaStringHelper::from_jstring(env, &enum_name);
        let words = monfluo_oxide::monero_wallet_util::seed::polyseed::Language::from_str(
            &enum_name.to_cow_str(),
        )
        .unwrap()
        .get_words();

        for word in words {
            let word = env
                .new_string(word)
                .expect("Failed to create java string for a polyseed language word");
            crate::java::list_add(env, cache, &list, &JObject::from(word));
        }

        Ok::<JObject<'local>, jni::errors::Error>(list)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
