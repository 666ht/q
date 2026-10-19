package org.monfluo.wallet.lib.data

data class AccountView(
    val accountIndex: Int,
    val addresses: List<AddressView>,
)
