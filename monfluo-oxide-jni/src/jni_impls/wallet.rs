use jni::{
    EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    jni_mangle,
    objects::{JObject, JString},
    refs::Global,
    sys::jint,
    vm::JavaVM,
};
use monfluo_oxide::{
    monero_daemon_rpc::MoneroDaemon,
    monero_wallet::transaction::NotPruned,
    transport::MonfluoTransport,
    wallet::{TransactionWrapper, Wallet, WalletSyncObserver},
};
use tokio::sync::Mutex;
use tracing::{debug, error};

static PENDING_TRANSACTION: Mutex<Option<TransactionWrapper<NotPruned>>> = Mutex::const_new(None);

struct MonfluoJavaObserver {
    vm: JavaVM,
    cache: &'static crate::java::JniCache,
    observer: Global<JObject<'static>>,
}

impl WalletSyncObserver for MonfluoJavaObserver {
    fn observe(&mut self, state: monfluo_oxide::wallet::WalletSyncState) {
        debug!("Notifying java observer about new wallet state: {state:?}");
        let result = self.vm.attach_current_thread_for_scope(|env| {
            debug!("Java VM attached for notifying java observer");
            let java_wallet_sync_state = crate::java::new_wallet_sync_state(env, self.cache, state);
            debug!("Java wallet sync state object created");
            crate::java::wallet_sync_observer_observe(
                env,
                self.cache,
                self.observer.as_obj(),
                &java_wallet_sync_state,
            );
            debug!("Java observer notified");
            Ok::<(), jni::errors::Error>(())
        });
        if let Err(e) = result {
            error!("Failed to notify java observer: {e}");
        }
    }
}

#[jni_mangle("org.monfluo.wallet.lib.wallet.Wallet", "getName")]
pub fn get_name<'local>(
    mut env: EnvUnowned<'local>,
    java_wallet: JObject<'local>,
) -> JString<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let handle = crate::java::get_handle(env, cache, &java_wallet);
            let wallet = crate::bridge::from_java_handle::<Wallet>(handle);
            let name = wallet.name();
            let java_name = name.map_or(JString::null(), |name| env.new_string(name).unwrap());

            Ok::<JString, jni::errors::Error>(java_name)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.wallet.Wallet", "getCurrentHeight")]
pub fn get_current_height<'local>(
    mut env: EnvUnowned<'local>,
    java_wallet: JObject<'local>,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let handle = crate::java::get_handle(env, cache, &java_wallet);
            let wallet = crate::bridge::from_java_handle::<Wallet>(handle);
            let height = wallet.current_height().await;
            let java_height = height.map_or(JObject::null(), |height| {
                crate::java::new_integer(env, cache, height as i32)
            });

            Ok::<JObject<'local>, jni::errors::Error>(java_height)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.wallet.Wallet", "getBalance")]
pub fn get_balance<'local>(
    mut env: EnvUnowned<'local>,
    java_wallet: JObject<'local>,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let handle = crate::java::get_handle(env, cache, &java_wallet);
            let wallet = crate::bridge::from_java_handle::<Wallet>(handle);
            let balance = wallet.balance_view().await;
            let java_balance = crate::java::new_balance(env, cache, &balance);
            Ok::<JObject, jni::errors::Error>(java_balance)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.wallet.Wallet", "getAccountView")]
pub fn get_account_view<'local>(
    mut env: EnvUnowned<'local>,
    java_wallet: JObject<'local>,
    java_account_index: jint,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let wallet_handle = crate::java::get_handle(env, cache, &java_wallet);
            let wallet = crate::bridge::from_java_handle::<Wallet>(wallet_handle);
            let account_view = wallet.account_view(java_account_index as u32).await;
            let java_account_view = crate::java::new_account_view(env, cache, &account_view);
            Ok::<JObject, jni::errors::Error>(java_account_view)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.wallet.Wallet", "getTransactionViews")]
pub fn get_transaction_views<'local>(
    mut env: EnvUnowned<'local>,
    java_wallet: JObject<'local>,
    _java_account_index: jint,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let wallet_handle = crate::java::get_handle(env, cache, &java_wallet);
            let wallet = crate::bridge::from_java_handle::<Wallet>(wallet_handle);
            let transaction_views = wallet.transaction_views().await;
            let java_transactions_view =
                crate::java::new_transaction_views(env, cache, &transaction_views);

            Ok::<JObject, jni::errors::Error>(java_transactions_view)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.wallet.Wallet", "sync")]
pub fn sync<'local>(
    mut env: EnvUnowned<'local>,
    java_wallet: JObject<'local>,
    java_daemon: JObject<'local>,
    java_observer: JObject<'local>,
) {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        let vm = env.get_java_vm().expect("Failed to get Java VM");
        let global_observer = env
            .new_global_ref(java_observer)
            .expect("Failed to make a global ref of a java observer");
        crate::RUNTIME.block_on(async {
            let wallet_handle = crate::java::get_handle(env, cache, &java_wallet);
            let wallet = crate::bridge::from_java_handle::<Wallet>(wallet_handle);
            let daemon_handle = crate::java::get_handle(env, cache, &java_daemon);
            let daemon =
                crate::bridge::from_java_handle::<MoneroDaemon<MonfluoTransport>>(daemon_handle);

            let monfluo_java_observer = MonfluoJavaObserver {
                vm,
                cache,
                observer: global_observer,
            };
            wallet.sync(&crate::RUNTIME, daemon, monfluo_java_observer);
            Ok::<(), jni::errors::Error>(())
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.wallet.Wallet", "registerNewAddress")]
pub fn register_new_address<'local>(
    mut env: EnvUnowned<'local>,
    java_wallet: JObject<'local>,
    java_account_index: jint,
) -> jint {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let wallet_handle = crate::java::get_handle(env, cache, &java_wallet);
            let wallet = crate::bridge::from_java_handle::<Wallet>(wallet_handle);
            let account_index = java_account_index as u32;
            let new_address = wallet.register_new_address(account_index).await;
            Ok::<jint, jni::errors::Error>(new_address as i32)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.wallet.Wallet", "createAndSignTransaction")]
pub fn create_and_sign_transaction<'local>(
    mut env: EnvUnowned<'local>,
    java_wallet: JObject<'local>,
    java_daemon: JObject<'local>,
    java_payments: JObject<'local>,
    java_fee_priority: JObject<'local>,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let wallet_handle = crate::java::get_handle(env, cache, &java_wallet);
            let wallet = crate::bridge::from_java_handle::<Wallet>(wallet_handle);
            let daemon_handle = crate::java::get_handle(env, cache, &java_daemon);
            let daemon =
                crate::bridge::from_java_handle::<MoneroDaemon<MonfluoTransport>>(daemon_handle);
            let payments = crate::java::from_java_list(env, cache, &java_payments)
                .into_iter()
                .map(|java_payment| crate::java::from_java_payment(env, cache, &java_payment))
                .collect::<Vec<_>>();
            let fee_priority = crate::java::from_java_fee_priority(env, cache, &java_fee_priority);
            let (transaction, outgoing_transfer) = wallet
                .create_transaction(daemon, payments, fee_priority)
                .await;
            let transaction = wallet.sign_tranasction(transaction).await;
            let first_seen_timestamp = monfluo_oxide::current_timestamp();
            let pending_transaction = TransactionWrapper {
                inner: transaction.clone(),
                hash: transaction.hash(),
                block_info: None,
                first_seen_timestamp,
                outgoing_transfer: Some(outgoing_transfer.clone()),
            };
            let transaction_view = wallet
                .create_transaction_view_of(&pending_transaction)
                .await;
            *PENDING_TRANSACTION.lock().await = Some(pending_transaction);
            let java_transaction_view =
                crate::java::new_transaction_view(env, cache, &transaction_view);
            Ok::<JObject<'local>, jni::errors::Error>(java_transaction_view)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle(
    "org.monfluo.wallet.lib.wallet.Wallet",
    "createAndSignSweepTransaction"
)]
pub fn create_and_sign_sweep_transaction<'local>(
    mut env: EnvUnowned<'local>,
    java_wallet: JObject<'local>,
    java_daemon: JObject<'local>,
    java_address: JObject<'local>,
    java_fee_priority: JObject<'local>,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let wallet_handle = crate::java::get_handle(env, cache, &java_wallet);
            let wallet = crate::bridge::from_java_handle::<Wallet>(wallet_handle);
            let daemon_handle = crate::java::get_handle(env, cache, &java_daemon);
            let daemon =
                crate::bridge::from_java_handle::<MoneroDaemon<MonfluoTransport>>(daemon_handle);
            let address = crate::java::from_java_address(env, cache, &java_address);
            let fee_priority = crate::java::from_java_fee_priority(env, cache, &java_fee_priority);
            let (transaction, outgoing_transfer) = wallet
                .create_sweep_transaction(daemon, address, fee_priority)
                .await;
            let transaction = wallet.sign_tranasction(transaction).await;
            let first_seen_timestamp = monfluo_oxide::current_timestamp();
            let pending_transaction = TransactionWrapper {
                inner: transaction.clone(),
                hash: transaction.hash(),
                block_info: None,
                first_seen_timestamp,
                outgoing_transfer: Some(outgoing_transfer.clone()),
            };
            let transaction_view = wallet
                .create_transaction_view_of(&pending_transaction)
                .await;
            *PENDING_TRANSACTION.lock().await = Some(pending_transaction);
            let java_transaction_view =
                crate::java::new_transaction_view(env, cache, &transaction_view);
            Ok::<JObject<'local>, jni::errors::Error>(java_transaction_view)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.wallet.Wallet", "sendTransaction")]
pub fn send_transaction<'local>(
    mut env: EnvUnowned<'local>,
    java_wallet: JObject<'local>,
    java_daemon: JObject<'local>,
    java_transaction_hash: JObject<'local>,
) {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let wallet_handle = crate::java::get_handle(env, cache, &java_wallet);
            let wallet = crate::bridge::from_java_handle::<Wallet>(wallet_handle);
            let daemon_handle = crate::java::get_handle(env, cache, &java_daemon);
            let daemon =
                crate::bridge::from_java_handle::<MoneroDaemon<MonfluoTransport>>(daemon_handle);
            let transaction_hash =
                crate::java::from_monero_bytes(env, cache, &java_transaction_hash);
            let mut pending_transaction_guard = PENDING_TRANSACTION.lock().await;
            match &*pending_transaction_guard {
                Some(pending_tranasction) => {
                    if pending_tranasction.inner.hash() != transaction_hash {
                        panic!("Wrong pending transaction hash");
                    }
                    wallet
                        .send_transaction(daemon, pending_tranasction.clone())
                        .await;
                    *pending_transaction_guard = None;
                }
                None => panic!("There is no pending transaction"),
            }
            Ok::<(), jni::errors::Error>(())
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.wallet.Wallet", "store")]
pub fn store<'local>(mut env: EnvUnowned<'local>, java_wallet: JObject<'local>) {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let wallet_handle = crate::java::get_handle(env, cache, &java_wallet);
            let wallet = crate::bridge::from_java_handle::<Wallet>(wallet_handle);
            wallet.store().await;
            Ok::<(), jni::errors::Error>(())
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[jni_mangle("org.monfluo.wallet.lib.wallet.Wallet", "close")]
pub fn close<'local>(mut env: EnvUnowned<'local>, java_wallet: JObject<'local>) {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let wallet_handle = crate::java::get_handle(env, cache, &java_wallet);
            let wallet = crate::bridge::from_java_handle::<Wallet>(wallet_handle);
            wallet.close().await;
            crate::bridge::drop(wallet);
            Ok::<(), jni::errors::Error>(())
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
