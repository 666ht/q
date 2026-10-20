package io.anonero.ui.util

import io.anonero.model.Subaddress
import io.anonero.model.Wallet

/**
 * Returns the highest subaddress index that has received a transaction.
 * Reading the wallet state must not create new subaddresses.
 */
fun Wallet.getLastUnusedIndex(): Int {
    var lastUsedSubaddress = 0
    for (info in this.history?.all ?: listOf()) {
        if (info.addressIndex > lastUsedSubaddress) {
            lastUsedSubaddress = info.addressIndex
        }
    }
    return lastUsedSubaddress
}

/**
 * Resolves the next receive address without mutating or saving the wallet.
 * New addresses are created only by the explicit add-address action.
 */
fun Wallet.getLatestSubAddress(): Subaddress {
    val nextIndex = getLastUnusedIndex() + 1
    return this.getSubaddressObject(nextIndex)
}

fun Wallet.getAllUsedSubAddresses(): List<Subaddress> {
    val subAddresses = arrayListOf<Subaddress>()

    // Show subaddresses explicitly created by the user, as well as any
    // subaddress index that already appears in transaction history. Do not
    // append the next receive address here; merely opening/refreshing this
    // list must never create another subaddress.
    val lastUsedIndex = getLastUnusedIndex()
    val addressCount = maxOf(this.numSubAddresses, lastUsedIndex + 1)
    for (i in 0 until addressCount) {
        subAddresses.add(this.getSubaddressObject(i))
    }

    return subAddresses
        .distinctBy { it.address }
        .sortedBy { it.addressIndex }
}
