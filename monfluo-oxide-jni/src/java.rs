#![allow(non_snake_case, non_upper_case_globals)]

use std::{borrow::Cow, fmt::Debug, ops::Deref, str::FromStr, sync::OnceLock};

use jni::{
    Env,
    objects::{JByteArray, JClass, JMethodID, JObject, JString, JValue},
    refs::Global,
    signature::{RuntimeFieldSignature, RuntimeMethodSignature},
    strings::{JNIString, MUTF8Chars},
    sys::{jboolean, jint, jlong},
};

#[rustfmt::skip]
mod constants {
    use const_format::concatcp as concat;

    macro_rules! classj {
        ($class:expr) => {{
            const_format::str_replace!($class, ".", "/")
        }};
    }

    macro_rules! java_class {
        ($class:expr) => {{
            const_format::concatcp!("L", classj!($class), ";")
        }};
    }
    
    macro_rules! java_array {
        ($type:expr) => {{
            const_format::concatcp!("[", $type)
        }};
    }

    macro_rules! java_args {
        () => {
            "()"
        };
        ($($value:expr),+ $(,)?) => {
            const_format::concatcp!("(", $($value), *, ")")
        };
    }
    
    macro_rules! java_constructor {
        () => {
            const_format::concatcp!(java_args!(), JAVA_VOID)
        };
        ($($value:expr),+ $(,)?) => {
            const_format::concatcp!(java_args!($($value), *), JAVA_VOID)
        };
    }

    pub const JAVA_VOID: &str = "V";
    pub const JAVA_BOOLEAN: &str = "Z";
    pub const JAVA_BYTE: &str = "B";
    pub const JAVA_INT: &str = "I";
    pub const JAVA_LONG: &str = "J";

    pub const FIELD__HANDLE: &str = "_handle";

    pub const CLASS__java_lang_Object: &str = "java.lang.Object";
    pub const CLASS__java_lang_String: &str = "java.lang.String";
    pub const CLASS__java_lang_Number: &str = "java.lang.Number";
    pub const CLASS__java_lang_Number__METHOD__intValue0: & str = "intValue";
    pub const CLASS__java_lang_Number__METHOD__intValue0__SIGNATURE: &str = concat!(java_args!(), JAVA_INT);
    pub const CLASS__java_lang_Integer: &str = "java.lang.Integer";
    pub const CLASS__java_lang_Integer__METHOD__init1__SIGNATURE: &str = java_constructor!(JAVA_INT);
    pub const CLASS__java_lang_Enum: &str = "java.lang.Enum";
    pub const CLASS__java_lang_Enum__METHOD__name0: &str = "name";
    pub const CLASS__java_lang_Enum__METHOD__name0__SIGNATURE: &str = concat!(java_args!(), java_class!(CLASS__java_lang_String));
    pub const CLASS__java_lang_Iterable: &str = "java.lang.Iterable";
    pub const CLASS__java_lang_Iterable__METHOD__iterator: &str = "iterator";
    pub const CLASS__java_lang_Iterable__METHOD__iterator__SIGNATURE: &str = concat!(java_args!(), java_class!(CLASS__java_util_Iterator));
    
    pub const CLASS__java_util_List: &str = "java.util.List";
    pub const CLASS__java_util_List__METHOD__add1: &str = "add";
    pub const CLASS__java_util_List__METHOD__add1__SIGNATURE: &str = concat!(java_args!(java_class!(CLASS__java_lang_Object)), JAVA_BOOLEAN);
    pub const CLASS__java_util_ArrayList: &str = "java.util.ArrayList";
    pub const CLASS__java_util_ArrayList__METHOD__init0__SIGNATURE: &str = java_constructor!();
    pub const CLASS__java_util_Iterator: &str = "java.util.Iterator";
    pub const CLASS__java_util_Iterator__METHOD__hasNext0: &str = "hasNext";
    pub const CLASS__java_util_Iterator__METHOD__hasNext0__SIGNATURE: &str = concat!(java_args!(), JAVA_BOOLEAN);
    pub const CLASS__java_util_Iterator__METHOD__next0: &str = "next";
    pub const CLASS__java_util_Iterator__METHOD__next0__SIGNATURE: &str = concat!(java_args!(), java_class!(CLASS__java_lang_Object));
    
    pub const CLASS__org_monfluo_wallet_lib_seed_SeedLanguge: &str = "org.monfluo.wallet.lib.seed.SeedLanguage";
    pub const CLASS__org_monfluo_wallet_lib_seed_Seed: &str = "org.monfluo.wallet.lib.seed.Seed";
    pub const CLASS__org_monfluo_wallet_lib_seed_Seed__METHOD__getMnemonic0: &str = "getMnemonic";
    pub const CLASS__org_monfluo_wallet_lib_seed_Seed__METHOD__getMnemonic0__SIGNATURE: &str = concat!(java_args!(), java_class!(CLASS__java_lang_String));
    pub const CLASS__org_monfluo_wallet_lib_seed_Seed__METHOD__getLanguage0: &str = "getLanguage";
    pub const CLASS__org_monfluo_wallet_lib_seed_Seed__METHOD__getLanguage0__SIGNATURE: &str = concat!(java_args!(), java_class!(CLASS__org_monfluo_wallet_lib_seed_SeedLanguge));
    pub const CLASS__org_monfluo_wallet_lib_seed_MoneroSeedLanguage: &str = "org.monfluo.wallet.lib.seed.MoneroSeedLanguage";
    pub const CLASS__org_monfluo_wallet_lib_seed_MoneroSeed: &str = "org.monfluo.wallet.lib.seed.MoneroSeed";
    pub const CLASS__org_monfluo_wallet_lib_seed_MoneroSeed__METHOD__init2__SIGNATURE: &str = java_constructor!(java_class!(CLASS__java_lang_String), java_class!(CLASS__org_monfluo_wallet_lib_seed_MoneroSeedLanguage));
    pub const CLASS__org_monfluo_wallet_lib_seed_PolyseedLanguage: &str = "org.monfluo.wallet.lib.seed.PolyseedLanguage";
    pub const CLASS__org_monfluo_wallet_lib_seed_Polyseed: &str = "org.monfluo.wallet.lib.seed.Polyseed";
    pub const CLASS__org_monfluo_wallet_lib_seed_Polyseed__METHOD__init2__SIGNATURE: &str = java_constructor!(java_class!(CLASS__java_lang_String), java_class!(CLASS__org_monfluo_wallet_lib_seed_PolyseedLanguage));
    
    pub const CLASS__org_monfluo_wallet_lib_wallet_Wallet: &str = "org.monfluo.wallet.lib.wallet.Wallet";
    pub const CLASS__org_monfluo_wallet_lib_wallet_Wallet__METHOD__init1__SIGNATURE: &str = java_constructor!(JAVA_LONG);
    pub const CLASS__org_monfluo_wallet_lib_wallet_Daemon: &str = "org.monfluo.wallet.lib.wallet.Daemon";
    pub const CLASS__org_monfluo_wallet_lib_wallet_Daemon__METHOD__init6__SIGNATURE: &str = java_constructor!(JAVA_LONG, java_class!(CLASS__java_lang_String), java_class!(CLASS__java_lang_String), java_class!(CLASS__java_lang_String), java_class!(CLASS__java_lang_String), java_class!(CLASS__java_lang_String));

    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncObserver: &str = "org.monfluo.wallet.lib.wallet.sync.WalletSyncObserver";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncObserver__METHOD__observe1: &str = "observe";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncObserver__METHOD__observe1__SIGNATURE: &str = concat!(java_args!(java_class!(CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncState)), JAVA_VOID);
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncError: &str = "org.monfluo.wallet.lib.wallet.sync.WalletSyncError";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchNodeInfo: &str = "org.monfluo.wallet.lib.wallet.sync.WalletSyncError$CouldNotFetchNodeInfo";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchNodeInfo__METHOD__init1__SIGNATURE: &str = java_constructor!(java_class!(CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceError));
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorNodeNotSynced: &str = "org.monfluo.wallet.lib.wallet.sync.WalletSyncError$NodeNotSynced";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorNodeNotSynced__METHOD__init1__SIGNATURE: &str = java_constructor!(java_class!(CLASS__org_monfluo_wallet_lib_data_NodeInfo));
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchBlocks: &str = "org.monfluo.wallet.lib.wallet.sync.WalletSyncError$CouldNotFetchBlocks";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchBlocks__METHOD__init1__SIGNATURE: &str = java_constructor!(java_class!(CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceError));
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceError: &str = "org.monfluo.wallet.lib.wallet.sync.InterfaceError";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInternalError: &str = "org.monfluo.wallet.lib.wallet.sync.InterfaceError$InternalError";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInternalError__METHOD__init1__SIGNATURE: &str = java_constructor!(java_class!(CLASS__java_lang_String));
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInterfaceErrorInner: &str = "org.monfluo.wallet.lib.wallet.sync.InterfaceError$InterfaceErrorInner";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInterfaceErrorInner__METHOD__init1__SIGNATURE: &str = java_constructor!(java_class!(CLASS__java_lang_String));
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInvalidInterface: &str = "org.monfluo.wallet.lib.wallet.sync.InterfaceError$InvalidInterface";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInvalidInterface__METHOD__init1__SIGNATURE: &str = java_constructor!(java_class!(CLASS__java_lang_String));
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncState: &str = "org.monfluo.wallet.lib.wallet.sync.WalletSyncState";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStatePreparing: &str = "org.monfluo.wallet.lib.wallet.sync.WalletSyncState$Preparing";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStatePreparing__METHOD__init2__SIGNATURE: &str = java_constructor!(JAVA_LONG, java_class!(CLASS__java_lang_Integer));
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateStarting: &str = "org.monfluo.wallet.lib.wallet.sync.WalletSyncState$Starting";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateStarting__METHOD__init3__SIGNATURE: &str = java_constructor!(JAVA_LONG, JAVA_INT, java_class!(CLASS__org_monfluo_wallet_lib_data_NodeInfo));
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateSyncing: &str = "org.monfluo.wallet.lib.wallet.sync.WalletSyncState$Syncing";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateSyncing__METHOD__init2__SIGNATURE: &str = java_constructor!(JAVA_LONG, JAVA_INT);
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFinished: &str = "org.monfluo.wallet.lib.wallet.sync.WalletSyncState$Finished";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFinished__METHOD__init2__SIGNATURE: &str = java_constructor!(JAVA_LONG, JAVA_INT);
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateCancelled: &str = "org.monfluo.wallet.lib.wallet.sync.WalletSyncState$Cancelled";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateCancelled__METHOD__init1__SIGNATURE: &str = java_constructor!(JAVA_LONG);
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFailed: &str = "org.monfluo.wallet.lib.wallet.sync.WalletSyncState$Failed";
    pub const CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFailed__METHOD__init2__SIGNATURE: &str = java_constructor!(JAVA_LONG, java_class!(CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncError));

    pub const CLASS__org_monfluo_wallet_lib_data_Network: &str = "org.monfluo.wallet.lib.data.Network";
    pub const CLASS__org_monfluo_wallet_lib_data_FeePriority: &str = "org.monfluo.wallet.lib.data.FeePriority";
    pub const CLASS__org_monfluo_wallet_lib_data_Address: &str = "org.monfluo.wallet.lib.data.Address";
    pub const CLASS__org_monfluo_wallet_lib_data_Address__METHOD__init2__SIGNATURE: &str = java_constructor!(java_class!(CLASS__java_lang_String), java_class!(CLASS__org_monfluo_wallet_lib_data_Network));
    pub const CLASS__org_monfluo_wallet_lib_data_Address__FIELD__value: &str = "value";
    pub const CLASS__org_monfluo_wallet_lib_data_Address__FIELD__network: &str = "network";
    pub const CLASS__org_monfluo_wallet_lib_data_Balance: &str = "org.monfluo.wallet.lib.data.Balance";
    pub const CLASS__org_monfluo_wallet_lib_data_Balance__METHOD__init4__SIGNATURE: &str = java_constructor!(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    pub const CLASS__org_monfluo_wallet_lib_data_AccountView: &str = "org.monfluo.wallet.lib.data.AccountView";
    pub const CLASS__org_monfluo_wallet_lib_data_AccountView__METHOD__init2__SIGNATURE: &str = java_constructor!(JAVA_INT, java_class!(CLASS__java_util_List));
    pub const CLASS__org_monfluo_wallet_lib_data_AddressView: &str = "org.monfluo.wallet.lib.data.AddressView";
    pub const CLASS__org_monfluo_wallet_lib_data_AddressView__METHOD__init5__SIGNATURE: &str = java_constructor!(JAVA_INT, JAVA_INT, java_class!(CLASS__java_lang_String), JAVA_BOOLEAN, JAVA_LONG);
    pub const CLASS__org_monfluo_wallet_lib_data_MoneroBytes: &str = "org.monfluo.wallet.lib.data.MoneroBytes";
    pub const CLASS__org_monfluo_wallet_lib_data_MoneroBytes__METHOD__init1__SIGNATURE: &str = java_constructor!(java_array!(JAVA_BYTE));
    pub const CLASS__org_monfluo_wallet_lib_data_MoneroBytes__FIELD__hash: &str = "hash";
    pub const CLASS__org_monfluo_wallet_lib_data_Payment: &str = "org.monfluo.wallet.lib.data.Payment";
    pub const CLASS__org_monfluo_wallet_lib_data_Payment__FIELD__address: &str = "address";
    pub const CLASS__org_monfluo_wallet_lib_data_Payment__FIELD__amount: &str = "amount";
    pub const CLASS__org_monfluo_wallet_lib_data_TransactionBlockInfo: &str = "org.monfluo.wallet.lib.data.TransactionBlockInfo";
    pub const CLASS__org_monfluo_wallet_lib_data_TransactionBlockInfo__METHOD__init2__SIGNATURE: &str = java_constructor!(JAVA_INT, java_class!(CLASS__org_monfluo_wallet_lib_data_MoneroBytes));
    pub const CLASS__org_monfluo_wallet_lib_data_TransactionViewInput: &str = "org.monfluo.wallet.lib.data.TransactionViewInput";
    pub const CLASS__org_monfluo_wallet_lib_data_TransactionViewInput__METHOD__init2__SIGNATURE: &str = java_constructor!(java_class!(CLASS__java_lang_String), JAVA_LONG);
    pub const CLASS__org_monfluo_wallet_lib_data_TransactionViewSpend: &str = "org.monfluo.wallet.lib.data.TransactionViewSpend";
    pub const CLASS__org_monfluo_wallet_lib_data_TransactionViewSpend__METHOD__init3__SIGNATURE: &str = java_constructor!(java_class!(CLASS__java_lang_String), JAVA_LONG, java_class!(CLASS__org_monfluo_wallet_lib_data_TransactionViewSpendType));
    pub const CLASS__org_monfluo_wallet_lib_data_TransactionViewSpendType: &str = "org.monfluo.wallet.lib.data.TransactionViewSpendType";
    pub const CLASS__org_monfluo_wallet_lib_data_TransactionView: &str = "org.monfluo.wallet.lib.data.TransactionView";
    pub const CLASS__org_monfluo_wallet_lib_data_TransactionView__METHOD__init8__SIGNATURE: &str = java_constructor!(java_class!(CLASS__org_monfluo_wallet_lib_data_MoneroBytes), JAVA_LONG, JAVA_LONG, JAVA_INT, java_class!(CLASS__org_monfluo_wallet_lib_data_TransactionBlockInfo), java_class!(CLASS__java_util_List), java_class!(CLASS__java_util_List), java_class!(CLASS__org_monfluo_wallet_lib_data_MoneroBytes));

    pub const CLASS__org_monfluo_wallet_lib_data_NodeInfo: &str = "org.monfluo.wallet.lib.data.NodeInfo";
    pub const CLASS__org_monfluo_wallet_lib_data_NodeInfo__METHOD__init2__SIGNATURE: &str = java_constructor!(JAVA_INT, JAVA_INT);

    pub const FIELD_SIGNATURE__LONG: &str = concat!(JAVA_LONG);
    pub const FIELD_SIGNATURE__BYTE_ARRAY: &str = java_array!(JAVA_BYTE);
    pub const FIELD_SIGNATURE__java_lang_String: &str = java_class!(CLASS__java_lang_String);
    pub const FIELD_SIGNATURE__org_monfluo_wallet_lib_seed_MoneroSeedLanguage: &str = java_class!(CLASS__org_monfluo_wallet_lib_seed_MoneroSeedLanguage);
    pub const FIELD_SIGNATURE__org_monfluo_wallet_lib_seed_PolyseedLanguage: &str = java_class!(CLASS__org_monfluo_wallet_lib_seed_MoneroSeedLanguage);
    pub const FIELD_SIGNATURE__org_monfluo_wallet_lib_data_Network: &str = java_class!(CLASS__org_monfluo_wallet_lib_data_Network);
    pub const FIELD_SIGNATURE__org_monfluo_wallet_lib_data_FeePriority: &str = java_class!(CLASS__org_monfluo_wallet_lib_data_FeePriority);
    pub const FIELD_SIGNATURE__org_monfluo_wallet_lib_data_TransactionViewSpendType: &str = java_class!(CLASS__org_monfluo_wallet_lib_data_TransactionViewSpendType);
    pub const FIELD_SIGNATURE__org_monfluo_wallet_lib_data_Address: &str = java_class!(CLASS__org_monfluo_wallet_lib_data_Address);
}

use constants::*;
use monfluo_oxide::{
    MoneroBytes,
    monero_wallet::{
        address::{MoneroAddress, Network},
        interface::{FeePriority, InterfaceError, NodeInfo},
    },
    wallet::{
        Payment, SyncError, WalletSyncState,
        view::{
            account::AccountView,
            address::AddressView,
            balance::BalanceView,
            transaction::{
                TransactionBlockInfoView, TransactionView, TransactionViewInput,
                TransactionViewSpend, TransactionViewSpendType,
            },
        },
    },
};
use zeroize::Zeroizing;

#[rustfmt::skip]
macro_rules! create_new_string {
    ($env:expr, $name:expr) => {{
        $env.new_string($name).expect(const_format::concatcp!("Failed to make a java string from ", stringify!($name)))
    }};
}

#[rustfmt::skip]
macro_rules! cast_to {
    ($env:expr, $name:expr, $type:ident) => {{
        $type::cast_local($env, $name).expect(const_format::concatcp!("Failed to cast ", stringify!($name), " value to ", stringify!($type)))
    }};
}

#[rustfmt::skip]
macro_rules! cast_to_string {
    ($env:expr, $name:expr) => {{
        cast_to!($env, $name, JString)
    }};
}

#[rustfmt::skip]
macro_rules! cast_to_byte_array {
    ($env:expr, $name:expr) => {{
        cast_to!($env, $name, JByteArray)
    }};
}

#[rustfmt::skip]
macro_rules! load_class {
    ($env:expr, $name:ident) => {{
        tracing::trace!("{}", const_format::concatcp!("Loading class \"", $name, "\""));
        let class = $env.load_class(JNIString::from($name))
            .expect(const_format::concatcp!(
                "Could not load class with name ",
                $name
            ));
        tracing::trace!("{}", const_format::concatcp!("Loaded class \"", $name, "\""));
        class
    }};
}

#[rustfmt::skip]
macro_rules! load_class_as_global {
    ($env:expr, $name:ident) => {{
        let class = load_class!($env, $name);
        $env.new_global_ref(class)
            .expect(const_format::concatcp!(
                "Could not create a global reference for class ",
                $name
            ))
    }};
}

#[rustfmt::skip]
macro_rules! create_runtime_field_signature {
    ($env:expr, $name:ident) => {
        RuntimeFieldSignature::from_str($name)
            .expect(const_format::concatcp!(
                "Could not create runtime field signature ",
                $name,
            ))
    };
}

#[rustfmt::skip]
macro_rules! create_runtime_method_signature {
    ($env:expr, $name:ident) => {
        RuntimeMethodSignature::from_str($name)
            .expect(const_format::concatcp!(
                "Could not create runtime method signature ",
                $name,
            ))
    };
}

#[rustfmt::skip]
macro_rules! find_method {
    ($env:expr, $class:ident, $name:ident, $signature:ident) => {{
        let class = load_class!($env, $class);
        let name = JNIString::from($name);
        let runtime_method_signature = create_runtime_method_signature!($env, $signature);
        $env.get_method_id(class, name, runtime_method_signature.method_signature())
            .expect(const_format::concatcp!(
                "Could not find method with name ",
                $name,
                " and signature ",
                $signature,
                " in class ",
                $class,
            ))
    }};
}

#[rustfmt::skip]
macro_rules! create_new_object {
    ($env:expr, $cache:expr, $class:ident, $signature:ident, $arguments:expr) => {{
        tracing::trace!("{}", const_format::concatcp!("Creating new object of class ", stringify!($class), " with constructor signature ", stringify!($signature), " (\"", $signature, "\") and arguments ", stringify!($arguments)));
        let class: &JClass = $cache.$class.as_ref();
        let runtime_method_signature = create_runtime_method_signature!($env, $signature);
        $env.new_object(class, runtime_method_signature.method_signature(), $arguments)
            .expect(concat!(
                "Could not create new object for class ",
                stringify!($class),
                " and signature ",
                stringify!($signature)
            ))
    }};
}

#[rustfmt::skip]
macro_rules! get_field_raw {
    ($env:expr, $object:expr, $field:expr, $signature:expr) => {{
        $env.get_field($object, $field, $signature.field_signature())
            .expect(concat!(
                "Could not get field ",
                stringify!($field),
                " with signature ",
                stringify!($signature),
                " from object ",
                stringify!($object),
            ))
    }};
}

#[rustfmt::skip]
macro_rules! get_static_field_raw {
    ($env:expr, $class:expr, $field:expr, $signature:expr) => {{
        let class: &JClass<'_> = $class.as_ref();
        $env.get_static_field(class, $field, $signature.field_signature())
            .expect(concat!(
                "Could not get static field ",
                stringify!($field),
                " with signature ",
                stringify!($signature),
                " from class ",
                stringify!($class),
            ))
    }};
}

#[rustfmt::skip]
macro_rules! call_cached_method_raw {
    ($env:expr, $cache:expr, $object:expr, $method:ident, bool, $arguments:expr) => {{
        unsafe {
            $env.call_method_unchecked(
                $object,
                $cache.$method,
                jni::signature::ReturnType::Primitive(
                    jni::signature::Primitive::Boolean
                ),
                $arguments,
            )
            .expect(const_format::concatcp!(
                "Could not call bool method ",
                stringify!($method),
                " with arguments ",
                stringify!($arguments)
            ))
        }
    }};

    ($env:expr, $cache:expr, $object:expr, $method:ident, i8, $arguments:expr) => {{
        unsafe {
            $env.call_method_unchecked(
                $object,
                $cache.$method,
                jni::signature::ReturnType::Primitive(
                    jni::signature::Primitive::Byte
                ),
                $arguments,
            )
            .expect(const_format::concatcp!(
                "Could not call i8 method ",
                stringify!($method),
                " with arguments ",
                stringify!($arguments)
            ))
        }
    }};

    ($env:expr, $cache:expr, $object:expr, $method:ident, i16, $arguments:expr) => {{
        unsafe {
            $env.call_method_unchecked(
                $object,
                $cache.$method,
                jni::signature::ReturnType::Primitive(
                    jni::signature::Primitive::Short
                ),
                $arguments,
            )
            .expect(const_format::concatcp!(
                "Could not call i16 method ",
                stringify!($method),
                " with arguments ",
                stringify!($arguments)
            ))
        }
    }};

    ($env:expr, $cache:expr, $object:expr, $method:ident, i32, $arguments:expr) => {{
        unsafe {
            $env.call_method_unchecked(
                $object,
                $cache.$method,
                jni::signature::ReturnType::Primitive(
                    jni::signature::Primitive::Int
                ),
                $arguments,
            )
            .expect(const_format::concatcp!(
                "Could not call i32 method ",
                stringify!($method),
                " with arguments ",
                stringify!($arguments)
            ))
        }
    }};

    ($env:expr, $cache:expr, $object:expr, $method:ident, i64, $arguments:expr) => {{
        unsafe {
            $env.call_method_unchecked(
                $object,
                $cache.$method,
                jni::signature::ReturnType::Primitive(
                    jni::signature::Primitive::Long
                ),
                $arguments,
            )
            .expect(const_format::concatcp!(
                "Could not call i64 method ",
                stringify!($method),
                " with arguments ",
                stringify!($arguments)
            ))
        }
    }};

    ($env:expr, $cache:expr, $object:expr, $method:ident, f32, $arguments:expr) => {{
        unsafe {
            $env.call_method_unchecked(
                $object,
                $cache.$method,
                jni::signature::ReturnType::Primitive(
                    jni::signature::Primitive::Float
                ),
                $arguments,
            )
            .expect(const_format::concatcp!(
                "Could not call f32 method ",
                stringify!($method),
                " with arguments ",
                stringify!($arguments)
            ))
        }
    }};

    ($env:expr, $cache:expr, $object:expr, $method:ident, f64, $arguments:expr) => {{
        unsafe {
            $env.call_method_unchecked(
                $object,
                $cache.$method,
                jni::signature::ReturnType::Primitive(
                    jni::signature::Primitive::Double
                ),
                $arguments,
            )
            .expect(const_format::concatcp!(
                "Could not call f64 method ",
                stringify!($method),
                " with arguments ",
                stringify!($arguments)
            ))
        }
    }};

    ($env:expr, $cache:expr, $object:expr, $method:ident, char, $arguments:expr) => {{
        unsafe {
            $env.call_method_unchecked(
                $object,
                $cache.$method,
                jni::signature::ReturnType::Primitive(
                    jni::signature::Primitive::Char
                ),
                $arguments,
            )
            .expect(const_format::concatcp!(
                "Could not call char method ",
                stringify!($method),
                " with arguments ",
                stringify!($arguments)
            ))
        }
    }};

    (
        $env:expr,
        $cache:expr,
        $object:expr,
        $method:ident,
        jobject,
        $arguments:expr
    ) => {{
        unsafe {
            $env.call_method_unchecked(
                $object,
                $cache.$method,
                jni::signature::ReturnType::Object,
                $arguments,
            )
            .expect(const_format::concatcp!(
                "Could not call jobject method ",
                stringify!($method),
                " with arguments ",
                stringify!($arguments)
            ))
        }
    }};

    (
        $env:expr,
        $cache:expr,
        $object:expr,
        $method:ident,
        jarray,
        $arguments:expr
    ) => {{
        unsafe {
            $env.call_method_unchecked(
                $object,
                $cache.$method,
                jni::signature::ReturnType::Array,
                $arguments,
            )
            .expect(const_format::concatcp!(
                "Could not call jarray method ",
                stringify!($method),
                " with arguments ",
                stringify!($arguments)
            ))
        }
    }};

    (
        $env:expr,
        $cache:expr,
        $object:expr,
        $method:ident,
        unit,
        $arguments:expr
    ) => {{
        unsafe {
            $env.call_method_unchecked(
                $object,
                $cache.$method,
                jni::signature::ReturnType::Primitive(
                    jni::signature::Primitive::Void
                ),
                $arguments,
            )
            .expect(const_format::concatcp!(
                "Could not call unit method ",
                stringify!($method),
                " with arguments ",
                stringify!($arguments)
            ))
        }
    }};
}

macro_rules! cast_jvalue {
    ($raw:expr, bool) => {{
        match $raw {
            jni::objects::JValueOwned::Bool(value) => value == jni::sys::JNI_TRUE,
            other => panic!("Expected JValueOwned::Bool, got {:?}", other),
        }
    }};

    ($raw:expr, i8) => {{
        match $raw {
            jni::objects::JValueOwned::Byte(value) => value,
            other => panic!("Expected JValueOwned::Byte, got {:?}", other),
        }
    }};

    ($raw:expr, i16) => {{
        match $raw {
            jni::objects::JValueOwned::Short(value) => value,
            other => panic!("Expected JValueOwned::Short, got {:?}", other),
        }
    }};

    ($raw:expr, i32) => {{
        match $raw {
            jni::objects::JValueOwned::Int(value) => value,
            other => panic!("Expected JValueOwned::Int, got {:?}", other),
        }
    }};

    ($raw:expr, i64) => {{
        match $raw {
            jni::objects::JValueOwned::Long(value) => value,
            other => panic!("Expected JValueOwned::Long, got {:?}", other),
        }
    }};

    ($raw:expr, f32) => {{
        match $raw {
            jni::objects::JValueOwned::Float(value) => value,
            other => panic!("Expected JValueOwned::Float, got {:?}", other),
        }
    }};

    ($raw:expr, f64) => {{
        match $raw {
            jni::objects::JValueOwned::Double(value) => value,
            other => panic!("Expected JValueOwned::Double, got {:?}", other),
        }
    }};

    ($raw:expr, char) => {{
        match $raw {
            jni::objects::JValueOwned::Char(value) => value,
            other => panic!("Expected JValueOwned::Char, got {:?}", other),
        }
    }};

    ($raw:expr, jobject) => {{
        match $raw {
            jni::objects::JValueOwned::Object(value) => value,
            other => panic!("Expected JValueOwned::Object, got {:?}", other),
        }
    }};

    ($raw:expr, jarray) => {{
        match $raw {
            jni::objects::JValueOwned::Object(value) => value,
            other => panic!("Expected JValueOwned::Object for array, got {:?}", other),
        }
    }};

    ($raw:expr, unit) => {{
        match $raw {
            jni::objects::JValueOwned::Void => (),
            other => panic!("Expected JValueOwned::Void, got {:?}", other),
        }
    }};
}

macro_rules! call_cached_method {
    ($env:expr, $cache:expr, $object:expr, $method:ident, $type:ident, $arguments:expr) => {{
        let raw = call_cached_method_raw!($env, $cache, $object, $method, $type, $arguments);

        cast_jvalue!(raw, $type)
    }};
}

#[rustfmt::skip]
macro_rules! get_field {
    ($env:expr, $object:expr, $type:ident, $field:expr, $signature:expr) => {{
        let raw = get_field_raw!($env, $object, $field, $signature);
        cast_jvalue!(
            raw,
            $type
        )
    }};
}

#[rustfmt::skip]
macro_rules! get_static_field {
    ($env:expr, $class:expr, $type:ident, $field:expr, $signature:expr) => {{
        let raw = get_static_field_raw!($env, $class, $field, $signature);
        cast_jvalue!(
            raw,
            $type
        )
    }};
}

#[rustfmt::skip]
pub struct JniCache {
    pub class__java_lang_Number__method__intValue0: JMethodID,
    pub class__java_lang_Enum__method__name0: JMethodID,
    pub class__java_lang_Iterable__method__iterator: JMethodID,
    pub class__java_util_List__method__add1: JMethodID,
    pub class__java_util_Iterator__method__hasNext0: JMethodID,
    pub class__java_util_Iterator__method__next0: JMethodID,
    pub class__java_lang_Integer: Global<JClass<'static>>,
    pub class__java_util_ArrayList: Global<JClass<'static>>,
    pub class__java_util_Iterator: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_WalletSyncObserver__method__observe1: JMethodID,
    pub class__org_monfluo_wallet_lib_seed_Seed__method__getMnemonic0: JMethodID,
    pub class__org_monfluo_wallet_lib_seed_Seed__method__getLanguage0: JMethodID,
    pub class__org_monfluo_wallet_lib_data_Network: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_data_FeePriority: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_data_Address: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_data_Balance: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_data_AccountView: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_data_AddressView: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_data_NodeInfo: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_data_MoneroBytes: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_data_Payment: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_data_TransactionBlockInfo: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_data_TransactionViewInput: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_data_TransactionViewSpend: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_data_TransactionViewSpendType: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_data_TransactionView: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_seed_MoneroSeedLanguage: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_seed_MoneroSeed: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_seed_PolyseedLanguage: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_seed_Polyseed: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_Wallet: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_Daemon: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchNodeInfo: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorNodeNotSynced: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchBlocks: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_WalletSyncError: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_InterfaceError: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInternalError: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInterfaceErrorInner: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInvalidInterface: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStatePreparing: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateStarting: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateSyncing: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFinished: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateCancelled: Global<JClass<'static>>,
    pub class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFailed: Global<JClass<'static>>,
    pub field_signature__long: RuntimeFieldSignature,
    pub field_signature__byte_array: RuntimeFieldSignature,
    pub field_signature__java_lang_String: RuntimeFieldSignature,
    pub field_signature__org_monfluo_wallet_lib_seed_MoneroSeedLanguage: RuntimeFieldSignature,
    pub field_signature__org_monfluo_wallet_lib_seed_PolyseedLanguage: RuntimeFieldSignature,
    pub field_signature__org_monfluo_wallet_lib_data_Network: RuntimeFieldSignature,
    pub field_signature__org_monfluo_wallet_lib_data_FeePriority: RuntimeFieldSignature,
    pub field_signature__org_monfluo_wallet_lib_data_TransactionViewSpendType: RuntimeFieldSignature,
    pub field_signature__org_monfluo_wallet_lib_data_Address: RuntimeFieldSignature,
    pub field__handle: JNIString,
    pub class__org_monfluo_wallet_lib_data_Address__field__value: JNIString,
    pub class__org_monfluo_wallet_lib_data_Address__field__network: JNIString,
    pub class__org_monfluo_wallet_lib_data_MoneroBytes__field__hash: JNIString,
    pub class__org_monfluo_wallet_lib_data_Payment__field__address: JNIString,
    pub class__org_monfluo_wallet_lib_data_Payment__field__amount: JNIString,
}

impl Debug for JniCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("JniCache").finish()
    }
}

static JNI_CACHE: OnceLock<JniCache> = OnceLock::new();

#[rustfmt::skip]
pub fn init_cache<'env>(env: &mut Env<'env>) {
    let class__java_lang_Number__method__intValue0 = find_method!(env, CLASS__java_lang_Number, CLASS__java_lang_Number__METHOD__intValue0, CLASS__java_lang_Number__METHOD__intValue0__SIGNATURE);
    let class__java_lang_Enum__method__name0 = find_method!(env, CLASS__java_lang_Enum, CLASS__java_lang_Enum__METHOD__name0, CLASS__java_lang_Enum__METHOD__name0__SIGNATURE);
    let class__java_lang_Iterable__method__iterator = find_method!(env, CLASS__java_lang_Iterable, CLASS__java_lang_Iterable__METHOD__iterator, CLASS__java_lang_Iterable__METHOD__iterator__SIGNATURE);
    let class__java_util_List__method__add1 = find_method!(env, CLASS__java_util_List, CLASS__java_util_List__METHOD__add1, CLASS__java_util_List__METHOD__add1__SIGNATURE);
    let class__java_util_Iterator__method__hasNext0 = find_method!(env, CLASS__java_util_Iterator, CLASS__java_util_Iterator__METHOD__hasNext0, CLASS__java_util_Iterator__METHOD__hasNext0__SIGNATURE);
    let class__java_util_Iterator__method__next0 = find_method!(env, CLASS__java_util_Iterator, CLASS__java_util_Iterator__METHOD__next0, CLASS__java_util_Iterator__METHOD__next0__SIGNATURE);
    let class__org_monfluo_wallet_lib_wallet_WalletSyncObserver__method__observe1 = find_method!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncObserver, CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncObserver__METHOD__observe1, CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncObserver__METHOD__observe1__SIGNATURE);
    let class__org_monfluo_wallet_lib_seed_Seed__method__getMnemonic0 = find_method!(env, CLASS__org_monfluo_wallet_lib_seed_Seed, CLASS__org_monfluo_wallet_lib_seed_Seed__METHOD__getMnemonic0, CLASS__org_monfluo_wallet_lib_seed_Seed__METHOD__getMnemonic0__SIGNATURE);
    let class__org_monfluo_wallet_lib_seed_Seed__method__getLanguage0 = find_method!(env, CLASS__org_monfluo_wallet_lib_seed_Seed, CLASS__org_monfluo_wallet_lib_seed_Seed__METHOD__getLanguage0, CLASS__org_monfluo_wallet_lib_seed_Seed__METHOD__getLanguage0__SIGNATURE);

    let class__java_lang_Integer = load_class_as_global!(env, CLASS__java_lang_Integer);
    let class__java_util_ArrayList = load_class_as_global!(env, CLASS__java_util_ArrayList);
    let class__java_util_Iterator = load_class_as_global!(env, CLASS__java_util_Iterator);
    let class__org_monfluo_wallet_lib_data_Network = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_Network);
    let class__org_monfluo_wallet_lib_data_FeePriority = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_FeePriority);
    let class__org_monfluo_wallet_lib_data_Address = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_Address);
    let class__org_monfluo_wallet_lib_data_Balance = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_Balance);
    let class__org_monfluo_wallet_lib_data_AccountView = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_AccountView);
    let class__org_monfluo_wallet_lib_data_AddressView = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_AddressView);
    let class__org_monfluo_wallet_lib_data_NodeInfo = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_NodeInfo);
    let class__org_monfluo_wallet_lib_data_MoneroBytes = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_MoneroBytes);
    let class__org_monfluo_wallet_lib_data_Payment = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_Payment);
    let class__org_monfluo_wallet_lib_data_TransactionBlockInfo = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_TransactionBlockInfo);
    let class__org_monfluo_wallet_lib_data_TransactionViewInput = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_TransactionViewInput);
    let class__org_monfluo_wallet_lib_data_TransactionViewSpend = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_TransactionViewSpend);
    let class__org_monfluo_wallet_lib_data_TransactionViewSpendType = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_TransactionViewSpendType);
    let class__org_monfluo_wallet_lib_data_TransactionView = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_data_TransactionView);
    let class__org_monfluo_wallet_lib_seed_MoneroSeedLanguage = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_seed_MoneroSeedLanguage);
    let class__org_monfluo_wallet_lib_seed_MoneroSeed = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_seed_MoneroSeed);
    let class__org_monfluo_wallet_lib_seed_PolyseedLanguage = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_seed_PolyseedLanguage);
    let class__org_monfluo_wallet_lib_seed_Polyseed = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_seed_Polyseed);
    let class__org_monfluo_wallet_lib_wallet_Wallet = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_Wallet);
    let class__org_monfluo_wallet_lib_wallet_Daemon = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_Daemon);
    let class__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchNodeInfo = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchNodeInfo);
    let class__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorNodeNotSynced = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorNodeNotSynced);
    let class__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchBlocks = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchBlocks);
    let class__org_monfluo_wallet_lib_wallet_sync_WalletSyncError = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncError);
    let class__org_monfluo_wallet_lib_wallet_sync_InterfaceError = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceError);
    let class__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInternalError = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInternalError);
    let class__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInterfaceErrorInner = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInterfaceErrorInner);
    let class__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInvalidInterface = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInvalidInterface);
    let class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStatePreparing = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStatePreparing);
    let class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateStarting = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateStarting);
    let class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateSyncing = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateSyncing);
    let class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFinished = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFinished);
    let class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateCancelled = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateCancelled);
    let class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFailed = load_class_as_global!(env, CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFailed);

    let field_signature__long = create_runtime_field_signature!(env, FIELD_SIGNATURE__LONG);
    let field_signature__byte_array = create_runtime_field_signature!(env, FIELD_SIGNATURE__BYTE_ARRAY);
    let field_signature__java_lang_String = create_runtime_field_signature!(env, FIELD_SIGNATURE__java_lang_String);
    let field_signature__org_monfluo_wallet_lib_seed_MoneroSeedLanguage = create_runtime_field_signature!(env, FIELD_SIGNATURE__org_monfluo_wallet_lib_seed_MoneroSeedLanguage);
    let field_signature__org_monfluo_wallet_lib_seed_PolyseedLanguage = create_runtime_field_signature!(env, FIELD_SIGNATURE__org_monfluo_wallet_lib_seed_PolyseedLanguage);
    let field_signature__org_monfluo_wallet_lib_data_Network = create_runtime_field_signature!(env, FIELD_SIGNATURE__org_monfluo_wallet_lib_data_Network);
    let field_signature__org_monfluo_wallet_lib_data_FeePriority = create_runtime_field_signature!(env, FIELD_SIGNATURE__org_monfluo_wallet_lib_data_FeePriority);
    let field_signature__org_monfluo_wallet_lib_data_TransactionViewSpendType = create_runtime_field_signature!(env, FIELD_SIGNATURE__org_monfluo_wallet_lib_data_TransactionViewSpendType);
    let field_signature__org_monfluo_wallet_lib_data_Address = create_runtime_field_signature!(env, FIELD_SIGNATURE__org_monfluo_wallet_lib_data_Address);

    let field__handle = JNIString::new(FIELD__HANDLE);
    let class__org_monfluo_wallet_lib_data_Address__field__value = JNIString::new(CLASS__org_monfluo_wallet_lib_data_Address__FIELD__value);
    let class__org_monfluo_wallet_lib_data_Address__field__network = JNIString::new(CLASS__org_monfluo_wallet_lib_data_Address__FIELD__network);
    let class__org_monfluo_wallet_lib_data_MoneroBytes__field__hash = JNIString::new(CLASS__org_monfluo_wallet_lib_data_MoneroBytes__FIELD__hash);
    let class__org_monfluo_wallet_lib_data_Payment__field__address = JNIString::new(CLASS__org_monfluo_wallet_lib_data_Payment__FIELD__address);
    let class__org_monfluo_wallet_lib_data_Payment__field__amount = JNIString::new(CLASS__org_monfluo_wallet_lib_data_Payment__FIELD__amount);

    JNI_CACHE
        .set(JniCache {
            class__java_lang_Number__method__intValue0,
            class__java_lang_Enum__method__name0,
            class__java_lang_Iterable__method__iterator,
            class__java_util_List__method__add1,
            class__java_util_Iterator__method__hasNext0,
            class__java_util_Iterator__method__next0,
            class__org_monfluo_wallet_lib_wallet_WalletSyncObserver__method__observe1,
            class__org_monfluo_wallet_lib_seed_Seed__method__getMnemonic0,
            class__org_monfluo_wallet_lib_seed_Seed__method__getLanguage0,

            class__java_lang_Integer,
            class__java_util_ArrayList,
            class__java_util_Iterator,

            class__org_monfluo_wallet_lib_data_Network,
            class__org_monfluo_wallet_lib_data_FeePriority,
            class__org_monfluo_wallet_lib_data_Address,
            class__org_monfluo_wallet_lib_data_Balance,
            class__org_monfluo_wallet_lib_data_AccountView,
            class__org_monfluo_wallet_lib_data_AddressView,
            class__org_monfluo_wallet_lib_data_NodeInfo,
            class__org_monfluo_wallet_lib_data_MoneroBytes,
            class__org_monfluo_wallet_lib_data_Payment,
            class__org_monfluo_wallet_lib_data_TransactionBlockInfo,
            class__org_monfluo_wallet_lib_data_TransactionViewInput,
            class__org_monfluo_wallet_lib_data_TransactionViewSpend,
            class__org_monfluo_wallet_lib_data_TransactionViewSpendType,
            class__org_monfluo_wallet_lib_data_TransactionView,
            class__org_monfluo_wallet_lib_seed_MoneroSeedLanguage,
            class__org_monfluo_wallet_lib_seed_MoneroSeed,
            class__org_monfluo_wallet_lib_seed_PolyseedLanguage,
            class__org_monfluo_wallet_lib_seed_Polyseed,
            class__org_monfluo_wallet_lib_wallet_Wallet,
            class__org_monfluo_wallet_lib_wallet_Daemon,
            class__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchNodeInfo,
            class__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorNodeNotSynced,
            class__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchBlocks,
            class__org_monfluo_wallet_lib_wallet_sync_WalletSyncError,
            class__org_monfluo_wallet_lib_wallet_sync_InterfaceError,
            class__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInternalError,
            class__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInterfaceErrorInner,
            class__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInvalidInterface,
            class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStatePreparing,
            class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateStarting,
            class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateSyncing,
            class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFinished,
            class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateCancelled,
            class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFailed,

            field_signature__long,
            field_signature__byte_array,
            field_signature__java_lang_String,
            field_signature__org_monfluo_wallet_lib_seed_MoneroSeedLanguage,
            field_signature__org_monfluo_wallet_lib_seed_PolyseedLanguage,
            field_signature__org_monfluo_wallet_lib_data_Network,
            field_signature__org_monfluo_wallet_lib_data_FeePriority,
            field_signature__org_monfluo_wallet_lib_data_TransactionViewSpendType,
            field_signature__org_monfluo_wallet_lib_data_Address,

            field__handle,
            class__org_monfluo_wallet_lib_data_Address__field__value,
            class__org_monfluo_wallet_lib_data_Address__field__network,
            class__org_monfluo_wallet_lib_data_MoneroBytes__field__hash,
            class__org_monfluo_wallet_lib_data_Payment__field__address,
            class__org_monfluo_wallet_lib_data_Payment__field__amount,
        })
        .expect("Failed to initialize JNI cache");
}

pub fn get_cache() -> &'static JniCache {
    JNI_CACHE.get().expect("JNI cache was not initialized")
}

pub struct JavaStringHelper<'env> {
    mutf8_chars: MUTF8Chars<'env, &'env JString<'env>>,
}

impl<'env> JavaStringHelper<'env> {
    pub fn try_from_jstring(
        env: &Env<'env>,
        jstring: &'env JString<'env>,
    ) -> Result<Self, jni::errors::Error> {
        let mutf8_chars = jstring.mutf8_chars(env)?;
        Ok(Self { mutf8_chars })
    }

    pub fn try_from_nullable_jstring(
        env: &Env<'env>,
        jstring: &'env JString<'env>,
    ) -> Result<Option<Self>, jni::errors::Error> {
        if jstring.is_null() {
            return Ok(None);
        }
        Self::try_from_jstring(env, jstring).map(Some)
    }

    pub fn from_jstring(env: &Env<'env>, jstring: &'env JString<'env>) -> Self {
        Self::try_from_jstring(env, jstring)
            .expect("Failed to construct JavaStringHelper from JString")
    }

    pub fn from_nullable_jstring(env: &Env<'env>, jstring: &'env JString<'env>) -> Option<Self> {
        Self::try_from_nullable_jstring(env, jstring)
            .expect("Failed to construct JavaStringHelper from nullable JString")
    }

    pub fn to_cow_str(&self) -> Cow<'_, str> {
        self.mutf8_chars.to_str()
    }
}

fn from_java_enum_using_from_str<'env, E: FromStr>(
    env: &mut Env<'env>,
    cache: &JniCache,
    java_enum: &JObject<'env>,
) -> E
where
    <E as FromStr>::Err: Debug,
{
    let enum_name = enum_name(env, cache, java_enum);
    let enum_name = JavaStringHelper::from_jstring(env, &enum_name);
    let enum_name = &*enum_name.to_cow_str();
    E::from_str(enum_name).unwrap_or_else(|_| {
        panic!(
            "Failed to convert java string {} to {}",
            enum_name,
            std::any::type_name::<E>()
        )
    })
}

fn to_java_enum_using_debug<'env, 'class, E: Debug>(
    env: &mut Env<'env>,
    r#enum: E,
    java_class: &Global<JClass<'class>>,
    field_signature: &RuntimeFieldSignature,
) -> JObject<'env> {
    let enum_name = format!("{:?}", r#enum);
    let enum_name = JNIString::new(&enum_name);
    get_static_field!(env, java_class, jobject, enum_name, field_signature)
}

pub fn from_java_number_int_value<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    java_number: &JObject<'env>,
) -> Option<i32> {
    if java_number.is_null() {
        return None;
    }
    Some(call_cached_method!(
        env,
        cache,
        java_number,
        class__java_lang_Number__method__intValue0,
        i32,
        &[]
    ))
}

pub fn new_integer<'env>(env: &mut Env<'env>, cache: &JniCache, integer: i32) -> JObject<'env> {
    let java_integer = integer as jint;
    create_new_object!(
        env,
        cache,
        class__java_lang_Integer,
        CLASS__java_lang_Integer__METHOD__init1__SIGNATURE,
        &[JValue::Int(java_integer)]
    )
}

pub fn new_arraylist<'env>(env: &mut Env<'env>, cache: &JniCache) -> JObject<'env> {
    create_new_object!(
        env,
        cache,
        class__java_util_ArrayList,
        CLASS__java_util_ArrayList__METHOD__init0__SIGNATURE,
        &[]
    )
}

pub fn from_java_list<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    java_list: &JObject<'env>,
) -> Vec<JObject<'env>> {
    let mut result = vec![];
    let java_iterator = call_cached_method!(
        env,
        cache,
        java_list,
        class__java_lang_Iterable__method__iterator,
        jobject,
        &[]
    );
    while call_cached_method!(
        env,
        cache,
        &java_iterator,
        class__java_util_Iterator__method__hasNext0,
        bool,
        &[]
    ) {
        result.push(call_cached_method!(
            env,
            cache,
            &java_iterator,
            class__java_util_Iterator__method__next0,
            jobject,
            &[]
        ));
    }
    result
}

pub fn list_add<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    java_list: &JObject<'env>,
    java_object: &JObject<'env>,
) -> bool {
    call_cached_method!(
        env,
        cache,
        java_list,
        class__java_util_List__method__add1,
        bool,
        &[JValue::Object(java_object).as_jni()]
    )
}

pub fn enum_name<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    java_enum: &JObject<'env>,
) -> JString<'env> {
    let name = call_cached_method!(
        env,
        cache,
        java_enum,
        class__java_lang_Enum__method__name0,
        jobject,
        &[]
    );
    cast_to_string!(env, name)
}

pub fn get_handle<'env>(env: &mut Env<'env>, cache: &JniCache, object: &JObject<'env>) -> usize {
    let handle = get_field!(
        env,
        object,
        i64,
        &cache.field__handle,
        cache.field_signature__long
    );
    handle as usize
}

pub fn to_java_network<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    network: Network,
) -> JObject<'env> {
    to_java_enum_using_debug(
        env,
        network,
        &cache.class__org_monfluo_wallet_lib_data_Network,
        &cache.field_signature__org_monfluo_wallet_lib_data_Network,
    )
}

pub fn from_java_network<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    java_network: &JObject<'env>,
) -> Network {
    from_java_enum_using_from_str(env, cache, java_network)
}

pub fn from_java_fee_priority<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    java_fee_priority: &JObject<'env>,
) -> FeePriority {
    from_java_enum_using_from_str(env, cache, java_fee_priority)
}

pub fn to_java_transaction_view_spend_type<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    transaction_view_spend_type: TransactionViewSpendType,
) -> JObject<'env> {
    to_java_enum_using_debug(
        env,
        transaction_view_spend_type,
        &cache.class__org_monfluo_wallet_lib_data_TransactionViewSpendType,
        &cache.field_signature__org_monfluo_wallet_lib_data_TransactionViewSpendType,
    )
}

pub fn new_address<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    address: &MoneroAddress,
) -> JObject<'env> {
    let java_value = create_new_string!(env, &address.to_string());
    let java_network = to_java_network(env, cache, address.network());
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_data_Address,
        CLASS__org_monfluo_wallet_lib_data_Address__METHOD__init2__SIGNATURE,
        &[JValue::Object(&java_value), JValue::Object(&java_network)]
    )
}

pub fn from_java_address<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    address: &JObject<'env>,
) -> MoneroAddress {
    let java_value = get_field!(
        env,
        address,
        jobject,
        &cache.class__org_monfluo_wallet_lib_data_Address__field__value,
        cache.field_signature__java_lang_String
    );
    let java_value = cast_to_string!(env, java_value);
    let java_network = get_field!(
        env,
        address,
        jobject,
        &cache.class__org_monfluo_wallet_lib_data_Address__field__network,
        cache.field_signature__org_monfluo_wallet_lib_data_Network
    );
    let value = JavaStringHelper::from_jstring(env, &java_value);
    let network = from_java_network(env, cache, &java_network);
    MoneroAddress::from_str(network, &value.to_cow_str()).expect("Failed to convert Java Address")
}

pub fn new_monero_seed<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    java_mnemonic: &JString<'env>,
    java_language: &JObject<'env>,
) -> JObject<'env> {
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_seed_MoneroSeed,
        CLASS__org_monfluo_wallet_lib_seed_MoneroSeed__METHOD__init2__SIGNATURE,
        &[JValue::Object(java_mnemonic), JValue::Object(java_language)]
    )
}

pub fn new_polyseed<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    java_mnemonic: &JString<'env>,
    java_language: &JObject<'env>,
) -> JObject<'env> {
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_seed_Polyseed,
        CLASS__org_monfluo_wallet_lib_seed_Polyseed__METHOD__init2__SIGNATURE,
        &[JValue::Object(java_mnemonic), JValue::Object(java_language)]
    )
}

pub fn from_java_seed<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    java_seed: &JObject<'env>,
) -> monfluo_oxide::monero_wallet_util::seed::Seed {
    let java_mnemonic = call_cached_method!(
        env,
        cache,
        java_seed,
        class__org_monfluo_wallet_lib_seed_Seed__method__getMnemonic0,
        jobject,
        &[]
    );
    let java_mnemonic = cast_to_string!(env, java_mnemonic);
    let mnemonic = JavaStringHelper::from_jstring(env, &java_mnemonic);
    let java_language = call_cached_method!(
        env,
        cache,
        java_seed,
        class__org_monfluo_wallet_lib_seed_Seed__method__getLanguage0,
        jobject,
        &[]
    );
    let language = from_java_seed_language(env, cache, &java_language);
    match language {
        monfluo_oxide::monero_wallet_util::seed::SeedType::Original(language) => {
            let seed = monfluo_oxide::monero_wallet_util::seed::original::Seed::from_string(
                language,
                Zeroizing::new(mnemonic.to_cow_str().deref().to_owned()),
            )
            .unwrap();
            monfluo_oxide::monero_wallet_util::seed::Seed::Original(seed)
        }
        monfluo_oxide::monero_wallet_util::seed::SeedType::Polyseed(language) => {
            let seed = monfluo_oxide::monero_wallet_util::seed::polyseed::Polyseed::from_string(
                language,
                Zeroizing::new(mnemonic.to_cow_str().deref().to_owned()),
            )
            .unwrap();
            monfluo_oxide::monero_wallet_util::seed::Seed::Polyseed(seed)
        }
    }
}

pub fn new_wallet<'env>(env: &mut Env<'env>, cache: &JniCache, handle: usize) -> JObject<'env> {
    let java_handle = handle as jlong;
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_wallet_Wallet,
        CLASS__org_monfluo_wallet_lib_wallet_Wallet__METHOD__init1__SIGNATURE,
        &[JValue::Long(java_handle)]
    )
}

pub fn new_daemon<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    handle: usize,
    node_name: &str,
    node_address: &str,
    node_username: Option<&str>,
    node_password: Option<&str>,
    node_proxy: Option<&str>,
) -> JObject<'env> {
    let java_handle = handle as jlong;
    let java_node_name = create_new_string!(env, node_name);
    let java_node_address = create_new_string!(env, node_address);
    let java_node_username = node_username
        .map(|node_username| create_new_string!(env, node_username).into())
        .unwrap_or(JObject::null());
    let java_node_password = node_password
        .map(|node_password| create_new_string!(env, node_password).into())
        .unwrap_or(JObject::null());
    let java_node_proxy = node_proxy
        .map(|node_proxy| create_new_string!(env, node_proxy).into())
        .unwrap_or(JObject::null());
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_wallet_Daemon,
        CLASS__org_monfluo_wallet_lib_wallet_Daemon__METHOD__init6__SIGNATURE,
        &[
            JValue::Long(java_handle),
            JValue::Object(&java_node_name),
            JValue::Object(&java_node_address),
            JValue::Object(&java_node_username),
            JValue::Object(&java_node_password),
            JValue::Object(&java_node_proxy),
        ]
    )
}

pub fn new_wallet_sync_state<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    wallet_sync_state: WalletSyncState,
) -> JObject<'env> {
    match wallet_sync_state {
        WalletSyncState::Preparing {
            timestamp,
            current_height,
        } => {
            let java_timestamp = timestamp as jlong;
            let java_current_height = current_height.map_or(JObject::null(), |current_height| {
                new_integer(env, cache, current_height as i32)
            });
            create_new_object!(
                env,
                cache,
                class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStatePreparing,
                CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStatePreparing__METHOD__init2__SIGNATURE,
                &[
                    JValue::Long(java_timestamp),
                    JValue::Object(&java_current_height)
                ]
            )
        }
        WalletSyncState::Starting {
            timestamp,
            current_height,
            node_info,
        } => {
            let java_timestamp = timestamp as jlong;
            let java_current_height = current_height as jint;
            let java_node_info = new_node_info(env, cache, &node_info);
            create_new_object!(
                env,
                cache,
                class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateStarting,
                CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateStarting__METHOD__init3__SIGNATURE,
                &[
                    JValue::Long(java_timestamp),
                    JValue::Int(java_current_height),
                    JValue::Object(&java_node_info),
                ]
            )
        }
        WalletSyncState::Syncing {
            timestamp,
            current_height,
        } => {
            let java_timestamp = timestamp as jlong;
            let java_current_height = current_height as jint;
            create_new_object!(
                env,
                cache,
                class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateSyncing,
                CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateSyncing__METHOD__init2__SIGNATURE,
                &[
                    JValue::Long(java_timestamp),
                    JValue::Int(java_current_height),
                ]
            )
        }
        WalletSyncState::Finished { timestamp, height } => {
            let java_timestamp = timestamp as jlong;
            let java_height = height as jint;
            create_new_object!(
                env,
                cache,
                class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFinished,
                CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFinished__METHOD__init2__SIGNATURE,
                &[JValue::Long(java_timestamp), JValue::Int(java_height)]
            )
        }
        WalletSyncState::Cancelled { timestamp } => {
            let java_timestamp = timestamp as jlong;
            create_new_object!(
                env,
                cache,
                class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateCancelled,
                CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateCancelled__METHOD__init1__SIGNATURE,
                &[JValue::Long(java_timestamp)]
            )
        }
        WalletSyncState::Failed { timestamp, error } => {
            let java_timestamp = timestamp as jlong;
            let java_error = new_sync_error(env, cache, &error);
            create_new_object!(
                env,
                cache,
                class__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFailed,
                CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncStateFailed__METHOD__init2__SIGNATURE,
                &[JValue::Long(java_timestamp), JValue::Object(&java_error)]
            )
        }
    }
}

pub fn new_sync_error<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    sync_error: &SyncError,
) -> JObject<'env> {
    match sync_error {
        SyncError::CouldNotFetchNodeInfo { error } => {
            let java_interface_eror = new_interface_error(env, cache, error);
            create_new_object!(
                env,
                cache,
                class__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchNodeInfo,
                CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchNodeInfo__METHOD__init1__SIGNATURE,
                &[JValue::Object(&java_interface_eror)]
            )
        }
        SyncError::NodeNotSynced { node_info } => {
            let java_node_info = new_node_info(env, cache, node_info);
            create_new_object!(
                env,
                cache,
                class__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorNodeNotSynced,
                CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorNodeNotSynced__METHOD__init1__SIGNATURE,
                &[JValue::Object(&java_node_info)]
            )
        }
        SyncError::CouldNotFetchBlocks { error } => {
            let java_interface_eror = new_interface_error(env, cache, error);
            create_new_object!(
                env,
                cache,
                class__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchBlocks,
                CLASS__org_monfluo_wallet_lib_wallet_sync_WalletSyncErrorCouldNotFetchBlocks__METHOD__init1__SIGNATURE,
                &[JValue::Object(&java_interface_eror)]
            )
        }
    }
}

pub fn new_interface_error<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    interface_error: &InterfaceError,
) -> JObject<'env> {
    match interface_error {
        InterfaceError::InternalError(error_message) => {
            let java_error_message = create_new_string!(env, error_message);
            create_new_object!(
                env,
                cache,
                class__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInternalError,
                CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInternalError__METHOD__init1__SIGNATURE,
                &[JValue::Object(&java_error_message)]
            )
        }
        InterfaceError::InterfaceError(error_message) => {
            let java_error_message = create_new_string!(env, error_message);
            create_new_object!(
                env,
                cache,
                class__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInterfaceErrorInner,
                CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInterfaceErrorInner__METHOD__init1__SIGNATURE,
                &[JValue::Object(&java_error_message)]
            )
        }
        InterfaceError::InvalidInterface(error_message) => {
            let java_error_message = create_new_string!(env, error_message);
            create_new_object!(
                env,
                cache,
                class__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInvalidInterface,
                CLASS__org_monfluo_wallet_lib_wallet_sync_InterfaceErrorInvalidInterface__METHOD__init1__SIGNATURE,
                &[JValue::Object(&java_error_message)]
            )
        }
    }
}

pub fn wallet_sync_observer_observe<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    java_observer: &JObject<'env>,
    java_state: &JObject<'env>,
) {
    call_cached_method!(
        env,
        cache,
        java_observer,
        class__org_monfluo_wallet_lib_wallet_WalletSyncObserver__method__observe1,
        unit,
        &[JValue::Object(java_state).as_jni()]
    );
}

pub fn new_balance<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    balance_view: &BalanceView,
) -> JObject<'env> {
    let BalanceView {
        total,
        unlocked,
        pending,
        frozen,
    } = balance_view;
    let java_total = *total as jlong;
    let java_unlocked = *unlocked as jlong;
    let java_pending = *pending as jlong;
    let java_frozen = *frozen as jlong;
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_data_Balance,
        CLASS__org_monfluo_wallet_lib_data_Balance__METHOD__init4__SIGNATURE,
        &[
            JValue::Long(java_total),
            JValue::Long(java_unlocked),
            JValue::Long(java_pending),
            JValue::Long(java_frozen)
        ]
    )
}

pub fn new_account_view<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    account_view: &AccountView,
) -> JObject<'env> {
    let AccountView {
        account_index,
        addresses,
    } = account_view;
    let java_account_index = *account_index as jint;
    let java_addresses = new_arraylist(env, cache);
    for address in addresses {
        let java_address = new_address_view(env, cache, address);
        list_add(env, cache, &java_addresses, &java_address);
    }
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_data_AccountView,
        CLASS__org_monfluo_wallet_lib_data_AccountView__METHOD__init2__SIGNATURE,
        &[
            JValue::Int(java_account_index),
            JValue::Object(&java_addresses)
        ]
    )
}

pub fn new_address_view<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    address_view: &AddressView,
) -> JObject<'env> {
    let AddressView {
        account_index,
        address_index,
        value,
        used,
        amount,
    } = address_view;
    let java_account_index = *account_index as jint;
    let java_address_index = *address_index as jint;
    let java_value = create_new_string!(env, value);
    let java_used = *used as jboolean;
    let java_amount = *amount as jlong;
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_data_AddressView,
        CLASS__org_monfluo_wallet_lib_data_AddressView__METHOD__init5__SIGNATURE,
        &[
            JValue::Int(java_account_index),
            JValue::Int(java_address_index),
            JValue::Object(&java_value),
            JValue::Bool(java_used),
            JValue::Long(java_amount)
        ]
    )
}

pub fn new_node_info<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    node_info: &NodeInfo,
) -> JObject<'env> {
    let NodeInfo {
        height,
        target_height,
        nettype: _,
        status: _,
    } = node_info;
    let java_height = *height as jint;
    let java_target_height = *target_height as jint;
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_data_NodeInfo,
        CLASS__org_monfluo_wallet_lib_data_NodeInfo__METHOD__init2__SIGNATURE,
        &[JValue::Int(java_height), JValue::Int(java_target_height)]
    )
}

pub fn new_monero_bytes<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    monero_bytes: &MoneroBytes,
) -> JObject<'env> {
    let java_array = env
        .byte_array_from_slice(monero_bytes)
        .expect("Failed to create java byte array from monero bytes");
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_data_MoneroBytes,
        CLASS__org_monfluo_wallet_lib_data_MoneroBytes__METHOD__init1__SIGNATURE,
        &[JValue::Object(&java_array)]
    )
}

pub fn from_monero_bytes<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    java_monero_hash: &JObject<'env>,
) -> MoneroBytes {
    let hash_field = get_field!(
        env,
        java_monero_hash,
        jobject,
        &cache.class__org_monfluo_wallet_lib_data_MoneroBytes__field__hash,
        cache.field_signature__byte_array
    );
    let hash_field = cast_to_byte_array!(env, hash_field);
    let bytes_vec = env
        .convert_byte_array(hash_field)
        .expect("Failed to read java monero hash into Vec");

    bytes_vec
        .try_into()
        .expect("Failed to convert monero hash Vec into MoneroHash")
}

pub fn from_java_payment<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    java_payment: &JObject<'env>,
) -> Payment {
    let java_address = get_field!(
        env,
        java_payment,
        jobject,
        &cache.class__org_monfluo_wallet_lib_data_Payment__field__address,
        &cache.field_signature__org_monfluo_wallet_lib_data_Address
    );
    let address = from_java_address(env, cache, &java_address);
    let java_amount = get_field!(
        env,
        java_payment,
        i64,
        &cache.class__org_monfluo_wallet_lib_data_Payment__field__amount,
        &cache.field_signature__long
    );
    let amount = java_amount as u64;
    Payment { address, amount }
}

pub fn new_transaction_block_info<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    transaction_block_info: &TransactionBlockInfoView,
) -> JObject<'env> {
    let TransactionBlockInfoView {
        block_height,
        block_hash,
    } = transaction_block_info;
    let java_block_height = *block_height as jint;
    let java_block_hash = new_monero_bytes(env, cache, block_hash);
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_data_TransactionBlockInfo,
        CLASS__org_monfluo_wallet_lib_data_TransactionBlockInfo__METHOD__init2__SIGNATURE,
        &[
            JValue::Int(java_block_height),
            JValue::Object(&java_block_hash)
        ]
    )
}

pub fn new_transaction_view_input<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    transaction_view_input: &TransactionViewInput,
) -> JObject<'env> {
    let TransactionViewInput { address, amount } = transaction_view_input;
    let java_address = create_new_string!(env, address);
    let java_amount = *amount as jlong;
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_data_TransactionViewInput,
        CLASS__org_monfluo_wallet_lib_data_TransactionViewInput__METHOD__init2__SIGNATURE,
        &[JValue::Object(&java_address), JValue::Long(java_amount)]
    )
}

pub fn new_transaction_view_spend<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    transaction_view_spend: &TransactionViewSpend,
) -> JObject<'env> {
    let TransactionViewSpend {
        address,
        amount,
        r#type,
    } = transaction_view_spend;
    let java_address = address
        .as_ref()
        .map_or(JString::null(), |address| create_new_string!(env, address));
    let java_amount = *amount as jlong;
    let java_transaction_view_spend_type = to_java_transaction_view_spend_type(env, cache, *r#type);
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_data_TransactionViewSpend,
        CLASS__org_monfluo_wallet_lib_data_TransactionViewSpend__METHOD__init3__SIGNATURE,
        &[
            JValue::Object(&java_address),
            JValue::Long(java_amount),
            JValue::Object(&java_transaction_view_spend_type)
        ]
    )
}

pub fn new_transaction_view<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    transaction_view: &TransactionView,
) -> JObject<'env> {
    let TransactionView {
        transaction_hash,
        timestamp,
        fee,
        inputs,
        confirmations,
        block_info,
        spends,
        outgoing_view_key,
    } = transaction_view;
    let java_transaction_hash = new_monero_bytes(env, cache, transaction_hash);
    let java_timestamp = *timestamp as jlong;
    let java_fee = *fee as jlong;
    let java_confirmations = *confirmations as jint;
    let java_block_info = block_info
        .as_ref()
        .map(|block_info| new_transaction_block_info(env, cache, block_info))
        .unwrap_or(JObject::null());
    let java_inputs = new_arraylist(env, cache);
    for input in inputs {
        let java_input = new_transaction_view_input(env, cache, input);
        list_add(env, cache, &java_inputs, &java_input);
    }
    let java_spends = new_arraylist(env, cache);
    for spend in spends {
        let java_spend = new_transaction_view_spend(env, cache, spend);
        list_add(env, cache, &java_spends, &java_spend);
    }
    let java_outgoing_view_key = outgoing_view_key.map_or(JObject::null(), |outgoing_view_key| {
        new_monero_bytes(env, cache, &outgoing_view_key)
    });
    create_new_object!(
        env,
        cache,
        class__org_monfluo_wallet_lib_data_TransactionView,
        CLASS__org_monfluo_wallet_lib_data_TransactionView__METHOD__init8__SIGNATURE,
        &[
            JValue::Object(&java_transaction_hash),
            JValue::Long(java_timestamp),
            JValue::Long(java_fee),
            JValue::Int(java_confirmations),
            JValue::Object(&java_block_info),
            JValue::Object(&java_inputs),
            JValue::Object(&java_spends),
            JValue::Object(&java_outgoing_view_key),
        ]
    )
}

pub fn new_transaction_views<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    transaction_views: &[TransactionView],
) -> JObject<'env> {
    let java_transaction_views = new_arraylist(env, cache);
    for transaction_view in transaction_views {
        let java_transaction_view = new_transaction_view(env, cache, transaction_view);
        list_add(env, cache, &java_transaction_views, &java_transaction_view);
    }
    java_transaction_views
}

pub fn to_java_monero_seed_language<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    monero_seed_language: monfluo_oxide::monero_wallet_util::seed::original::Language,
) -> JObject<'env> {
    to_java_enum_using_debug(
        env,
        monero_seed_language,
        &cache.class__org_monfluo_wallet_lib_seed_MoneroSeedLanguage,
        &cache.field_signature__org_monfluo_wallet_lib_seed_MoneroSeedLanguage,
    )
}

pub fn to_java_polyseed_language<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    polyseed_language: monfluo_oxide::monero_wallet_util::seed::polyseed::Language,
) -> JObject<'env> {
    to_java_enum_using_debug(
        env,
        polyseed_language,
        &cache.class__org_monfluo_wallet_lib_seed_PolyseedLanguage,
        &cache.field_signature__org_monfluo_wallet_lib_seed_PolyseedLanguage,
    )
}

pub fn from_java_seed_language<'env>(
    env: &mut Env<'env>,
    cache: &JniCache,
    seed_language: &JObject<'env>,
) -> monfluo_oxide::monero_wallet_util::seed::SeedType {
    let seed_language_class = env.get_object_class(seed_language).unwrap();
    let seed_language_class_name = seed_language_class.get_name(env).unwrap();
    let enum_name = enum_name(env, cache, seed_language);
    let enum_name = JavaStringHelper::from_jstring(env, &enum_name);
    match &*JavaStringHelper::from_jstring(env, &seed_language_class_name).to_cow_str() {
        CLASS__org_monfluo_wallet_lib_seed_MoneroSeedLanguage => {
            monfluo_oxide::monero_wallet_util::seed::SeedType::Original(
                monfluo_oxide::monero_wallet_util::seed::original::Language::from_str(
                    &enum_name.to_cow_str(),
                )
                .unwrap(),
            )
        }
        CLASS__org_monfluo_wallet_lib_seed_PolyseedLanguage => {
            monfluo_oxide::monero_wallet_util::seed::SeedType::Polyseed(
                monfluo_oxide::monero_wallet_util::seed::polyseed::Language::from_str(
                    &enum_name.to_cow_str(),
                )
                .unwrap(),
            )
        }
        _ => unreachable!(),
    }
}
