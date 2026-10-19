use jni::{
    EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    jni_mangle,
    objects::{JClass, JObject, JString},
};
use monfluo_oxide::monero_wallet::address::MoneroAddress;
use tracing::warn;

#[jni_mangle("org.monfluo.wallet.lib.data.Address", "fromString")]
pub fn address_from_string<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    java_address_value: JString<'local>,
) -> JObject<'local> {
    let cache = crate::java::get_cache();

    env.with_env(|env| {
        let address_value = crate::java::JavaStringHelper::from_jstring(env, &java_address_value);
        let address_value = address_value.to_cow_str();
        match MoneroAddress::from_str_with_unchecked_network(&address_value) {
            Ok(address) => Ok::<JObject<'local>, jni::errors::Error>(crate::java::new_address(
                env, cache, &address,
            )),
            Err(e) => {
                warn!("Failed to parse address {}: {:?}", &*address_value, e);
                Ok::<JObject<'local>, jni::errors::Error>(JObject::null())
            }
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
