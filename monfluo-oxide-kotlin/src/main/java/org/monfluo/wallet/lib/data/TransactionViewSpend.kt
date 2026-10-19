package org.monfluo.wallet.lib.data

data class TransactionViewSpend(
    val address: String?,
    val amount: Long,
    val type: TransactionViewSpendType,
)
