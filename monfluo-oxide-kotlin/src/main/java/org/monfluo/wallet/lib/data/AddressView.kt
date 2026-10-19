package org.monfluo.wallet.lib.data

data class AddressView(
    val accountIndex: Int,
    val addressIndex: Int,
    val value: String,
    val used: Boolean,
    val amount: Long,
)
