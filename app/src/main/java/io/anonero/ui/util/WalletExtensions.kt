package io.anonero.ui.util

import io.anonero.model.Subaddress
import io.anonero.model.Wallet

fun Wallet.getLastUnusedIndex(): Int {
    var lastUsedSubaddress = 0
    val subaddress = arrayListOf<Subaddress>()
    for (i in 0 until this.numSubAddresses) {
        subaddress.add(this.getSubaddressObject(i))
    }
    for (info in this.history?.all ?: listOf()) {
        if (info.addressIndex > lastUsedSubaddress) lastUsedSubaddress = info.addressIndex
    }
    return lastUsedSubaddress
}

fun Wallet.getLatestSubAddress(): Subaddress {
    val index = getLastUnusedIndex().coerceAtMost((numSubAddresses - 1).coerceAtLeast(0))
    return getSubaddressObject(index)
}

fun Wallet.getAllUsedSubAddresses(): List<Subaddress> {
    return (0 until this.numSubAddresses)
        .map { this.getSubaddressObject(it) }
        .filterNot { it.addressIndex == 0 && it.totalAmount != 0L }
        .distinctBy { it.address }
        .sortedBy { it.addressIndex }
}
