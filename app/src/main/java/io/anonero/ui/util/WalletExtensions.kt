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
    val lastUsedSubAddress = getLastUnusedIndex()
    val address = this.getSubaddressObject(lastUsedSubAddress + 1)
    if (address.label.isEmpty()) {
        this.addSubaddress(getAccountIndex(), "Subaddress #"+address.addressIndex)
        this.store()
    }
    return address
}

fun Wallet.getAllUsedSubAddresses(): List<Subaddress> {
    val subAddresses = arrayListOf<Subaddress>()
    for (i in 0 until this.numSubAddresses) {
        subAddresses.add(this.getSubaddressObject(i))
    }
    this.getLatestSubAddress().let {
        if (subAddresses.indexOf(it) == -1) subAddresses.add(it)
    }
    return subAddresses
        .apply {
            this.removeIf { it.addressIndex == 0 && it.totalAmount != 0L }
        }
        .distinctBy { it.address }.sortedBy { it.addressIndex }
}
