use jni::{EnvUnowned, errors::ThrowRuntimeExAndDefault, jni_mangle, objects::JObject};
use monfluo_oxide::{
    monero_daemon_rpc::MoneroDaemon, monero_wallet::interface::ProvidesNodeInfo,
    transport::MonfluoTransport,
};

#[jni_mangle("org.monfluo.wallet.lib.wallet.Daemon", "fetchNodeInfo")]
pub fn fetch_node_info<'local>(
    mut env: EnvUnowned<'local>,
    java_daemon: JObject<'local>,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        crate::RUNTIME.block_on(async {
            let handle = crate::java::get_handle(env, cache, &java_daemon);
            let daemon = crate::bridge::from_java_handle::<MoneroDaemon<MonfluoTransport>>(handle);
            let node_info = daemon.node_info().await.unwrap();
            let java_node_info = crate::java::new_node_info(env, cache, &node_info);
            Ok::<JObject, jni::errors::Error>(java_node_info)
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
