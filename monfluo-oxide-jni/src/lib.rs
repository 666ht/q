#![forbid(clippy::unwrap_used)]

pub mod bridge;
pub mod java;
pub mod jni_impls;

use std::sync::LazyLock;

use tokio::runtime::Runtime;

static RUNTIME: LazyLock<Runtime> =
    LazyLock::new(|| tokio::runtime::Runtime::new().expect("Failed to create tokio runtime"));
