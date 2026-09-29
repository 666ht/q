package io.anonero.model

data class CoinsInfo(
    val globalOutputIndex: Long,
    val spent: Boolean,
    val key: String,
    val amount: Long,
    val hash: String,
    val pub_key: String,
    val frozen: Boolean,
    val creationTime: Long,
) : Comparable<CoinsInfo> {

    override fun compareTo(other: CoinsInfo): Int {
        val b1: Long = this.amount
        val b2 = other.amount
        if (b1 > b2) {
            return -1
        } else if (b1 < b2) {
            return 1
        } else {
            return this.hash.compareTo(other.hash)
        }
    }
}