package org.monfluo.wallet.lib.data

data class Balance(
    val total: Long,
    val unlocked: Long,
    val pending: Long,
    val frozen: Long,
)
