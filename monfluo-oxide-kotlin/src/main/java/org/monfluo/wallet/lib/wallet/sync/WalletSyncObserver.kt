package org.monfluo.wallet.lib.wallet.sync

interface WalletSyncObserver {
    fun observe(state: WalletSyncState)
}
