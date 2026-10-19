package org.monfluo.wallet.lib.data

data class TransactionView(
    val transactionHash: MoneroBytes,
    val timestamp: Long,
    val fee: Long,
    val confirmations: Int,
    val blockInfo: TransactionBlockInfo?,
    val inputs: List<TransactionViewInput>,
    val spends: List<TransactionViewSpend>,
    val outgoingViewKey: MoneroBytes?,
) {
    val direction: TransactionDirection = if (spends.isNotEmpty()) TransactionDirection.SPEND else TransactionDirection.INPUT

    val nonChangeSpends = spends.filter { it.type != TransactionViewSpendType.Change }

    val inputAmount: Long = inputs.sumOf { it.amount }
    val nonChangeSpendAmount: Long = nonChangeSpends.sumOf { it.amount }
    val spendAmount: Long = spends.sumOf { it.amount }
}

enum class TransactionDirection {
    INPUT,
    SPEND,
}
