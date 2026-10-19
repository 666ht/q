use std::{path::PathBuf, str::FromStr, sync::Arc};

use crate::java::JavaStringHelper;
use jni::{
    EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    jni_mangle,
    objects::{JClass, JObject, JString},
};
use monfluo_oxide::{monero_wallet::address::Network, transport::MonfluoTransport};

#[jni_mangle("org.monfluo.wallet.lib.Monfluo", "monfluoVersion")]
pub fn monfluo_version<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JObject<'local> {
    env.with_env(|env| {
        let version = env.new_string(monfluo_oxide::VERSION).unwrap();
        Ok::<JObject<'local>, jni::errors::Error>(version.into())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.Monfluo", "listWallets")]
pub fn list_wallets<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    java_monfluo_directory: JString,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let monfluo_directory = PathBuf::from_str(
                &crate::java::JavaStringHelper::from_jstring(env, &java_monfluo_directory)
                    .to_cow_str(),
            )
            .unwrap();
            let wallets = monfluo_oxide::wallet::Wallet::list(monfluo_directory)
                .await
                .unwrap();
            let list = crate::java::new_arraylist(env, cache);
            for wallet in wallets {
                let wallet = env
                    .new_string(wallet)
                    .expect("Failed to create java string for a wallet path");
                crate::java::list_add(env, cache, &list, &JObject::from(wallet));
            }
            Ok::<JObject, jni::errors::Error>(list)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.Monfluo", "deleteWallet")]
pub fn delete_wallet<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    java_monfluo_directory: JString,
    java_wallet_name: JString,
) {
    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let monfluo_directory = PathBuf::from_str(
                &crate::java::JavaStringHelper::from_jstring(env, &java_monfluo_directory)
                    .to_cow_str(),
            )
            .unwrap();
            let wallet_name = crate::java::JavaStringHelper::from_jstring(env, &java_wallet_name);
            monfluo_oxide::wallet::Wallet::delete(monfluo_directory, &wallet_name.to_cow_str())
                .await
                .unwrap();
            Ok::<(), jni::errors::Error>(())
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.Monfluo", "createWallet")]
pub fn create_wallet<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    java_monfluo_directory: JString,
    java_network: JObject,
    java_name: JString,
    java_password: JString,
    java_seed_language: JObject<'local>,
    _java_seed_offset: JString,
) {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let monfluo_directory = PathBuf::from_str(
                &crate::java::JavaStringHelper::from_jstring(env, &java_monfluo_directory)
                    .to_cow_str(),
            )
            .unwrap();
            let wallet_name = crate::java::JavaStringHelper::from_jstring(env, &java_name);
            let password = crate::java::JavaStringHelper::from_jstring(env, &java_password);
            let network = crate::java::from_java_network(env, cache, &java_network);

            let seed_type = crate::java::from_java_seed_language(env, cache, &java_seed_language);
            let (seed, birthday) = match seed_type {
                monfluo_oxide::monero_wallet_util::seed::SeedType::Original(language) => {
                    let seed = monfluo_oxide::monero_wallet_util::seed::Seed::Original(
                        monfluo_oxide::generate_seed(language).await,
                    );
                    let birthday = monfluo_oxide::current_timestamp();
                    (seed, birthday)
                }
                monfluo_oxide::monero_wallet_util::seed::SeedType::Polyseed(language) => {
                    let polyseed = monfluo_oxide::generate_polyseed(language).await;
                    let birthday = polyseed.birthday() * 1000;
                    let seed = monfluo_oxide::monero_wallet_util::seed::Seed::Polyseed(polyseed);
                    (seed, birthday)
                }
            };

            monfluo_oxide::wallet::Wallet::create(
                monfluo_directory,
                network,
                &wallet_name.to_cow_str(),
                &password.to_cow_str(),
                seed,
                None,
                birthday,
            )
            .await
            .unwrap();

            Ok::<(), jni::errors::Error>(())
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>();
}

#[jni_mangle("org.monfluo.wallet.lib.Monfluo", "restoreWallet")]
pub fn restore_wallet<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    java_monfluo_directory: JString,
    java_network: JObject,
    java_name: JString,
    java_password: JString,
    java_seed: JObject<'local>,
    _java_seed_offset: JString,
    java_height: JObject<'local>,
) {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let monfluo_directory = PathBuf::from_str(
                &crate::java::JavaStringHelper::from_jstring(env, &java_monfluo_directory)
                    .to_cow_str(),
            )
            .unwrap();
            let wallet_name = crate::java::JavaStringHelper::from_jstring(env, &java_name);
            let password = crate::java::JavaStringHelper::from_jstring(env, &java_password);
            let network = crate::java::from_java_network(env, cache, &java_network);

            let seed = crate::java::from_java_seed(env, cache, &java_seed);
            let height = crate::java::from_java_number_int_value(env, cache, &java_height);
            let birthday = match &seed {
                monfluo_oxide::monero_wallet_util::seed::Seed::Original(_seed) => {
                    monfluo_oxide::current_timestamp()
                }
                monfluo_oxide::monero_wallet_util::seed::Seed::Polyseed(polyseed) => {
                    polyseed.birthday() * 1000
                }
            };

            monfluo_oxide::wallet::Wallet::create(
                monfluo_directory,
                network,
                &wallet_name.to_cow_str(),
                &password.to_cow_str(),
                seed,
                height.map(|height| height as u32),
                birthday,
            )
            .await
            .unwrap();

            Ok::<(), jni::errors::Error>(())
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>();
}

#[jni_mangle("org.monfluo.wallet.lib.Monfluo", "openWallet")]
pub fn open_wallet<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    java_monfluo_directory: JString,
    java_name: JString,
    java_password: JString,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let monfluo_directory = PathBuf::from_str(
                &crate::java::JavaStringHelper::from_jstring(env, &java_monfluo_directory)
                    .to_cow_str(),
            )
            .expect("Failed to convert java monfluo directory to PathBuf");
            let wallet_name = crate::java::JavaStringHelper::from_jstring(env, &java_name);
            let password = crate::java::JavaStringHelper::from_jstring(env, &java_password);

            let wallet = monfluo_oxide::wallet::Wallet::open(
                monfluo_directory,
                &wallet_name.to_cow_str(),
                &password.to_cow_str(),
            )
            .await
            .expect("Failed to open wallet");
            let wallet_handle = crate::bridge::into_java_handle(Arc::new(wallet));
            let java_wallet = crate::java::new_wallet(env, cache, wallet_handle);

            Ok::<JObject, jni::errors::Error>(java_wallet)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.Monfluo", "createDaemon")]
pub fn create_daemon<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    java_node_name: JString,
    java_node_address: JString,
    java_node_username: JString,
    java_node_password: JString,
    java_node_proxy: JString,
) {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let node_name = crate::java::JavaStringHelper::from_jstring(env, &java_node_name);
            let node_address = crate::java::JavaStringHelper::from_jstring(env, &java_node_address);
            let node_username =
                crate::java::JavaStringHelper::from_nullable_jstring(env, &java_node_username);
            let node_password =
                crate::java::JavaStringHelper::from_nullable_jstring(env, &java_node_password);
            let node_proxy =
                crate::java::JavaStringHelper::from_nullable_jstring(env, &java_node_proxy);

            let address_text = node_address.to_cow_str();
            let address_url = if address_text.contains("://") {
                address_text.into_owned()
            } else {
                format!("http://{address_text}")
            };
            let mut address_url = url::Url::parse(&address_url)
                .expect("Invalid node RPC URL");

            let username = node_username.as_ref().map(JavaStringHelper::to_cow_str);
            let password = node_password.as_ref().map(JavaStringHelper::to_cow_str);
            if let Some(username) = username.as_deref().filter(|value| !value.is_empty()) {
                address_url.set_username(username)
                    .expect("Invalid node RPC username");
                address_url.set_password(Some(password.as_deref().unwrap_or("")))
                    .expect("Invalid node RPC password");
            }

            let proxy_text = node_proxy.as_ref().map(JavaStringHelper::to_cow_str);
            let proxy = proxy_text.as_deref()
                .filter(|value| !value.trim().is_empty())
                .map(|value| {
                    let proxy_url = if value.contains("://") {
                        value.to_owned()
                    } else {
                        format!("socks5h://{value}")
                    };
                    reqwest::Proxy::all(proxy_url)
                        .expect("Invalid wallet proxy URL")
                });

            let daemon = MonfluoTransport::new(address_url.to_string(), proxy)
                .await
                .unwrap();
            let daemon_handle = crate::bridge::into_java_handle(Arc::new(daemon));
            let java_daemon = crate::java::new_daemon(
                env,
                cache,
                daemon_handle,
                &node_name.to_cow_str(),
                &node_address.to_cow_str(),
                node_username
                    .as_ref()
                    .map(JavaStringHelper::to_cow_str)
                    .as_deref(),
                node_password
                    .as_ref()
                    .map(JavaStringHelper::to_cow_str)
                    .as_deref(),
                node_proxy
                    .as_ref()
                    .map(JavaStringHelper::to_cow_str)
                    .as_deref(),
            );

            Ok::<JObject, jni::errors::Error>(java_daemon)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>();
}
