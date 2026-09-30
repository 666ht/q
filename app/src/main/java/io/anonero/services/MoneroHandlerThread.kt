package io.anonero.services

import io.anonero.model.PendingTransaction
import io.anonero.model.Wallet
import io.anonero.model.WalletListener
import timber.log.Timber

class MoneroHandlerThread(private val wallet: Wallet, private val walletState: WalletState) :
    Thread(null, null, "MoneroHandler", THREAD_STACK_SIZE), WalletListener {
    @Synchronized override fun start() { super.start() }
    override fun run() {}

    override fun moneySpent(txId: String?, amount: Long) {
        if (walletState.isWiping()) return
    }

    override fun moneyReceived(txId: String?, amount: Long) {
        if (walletState.isWiping()) return
        Timber.tag(name).i("moneyReceived: %s", amount)
    }

    override fun unconfirmedMoneyReceived(txId: String?, amount: Long) {
        if (walletState.isWiping()) return
        Timber.tag(name).i("unconfirmedMoneyReceived: %s", amount)
    }

    override fun newBlock(height: Long) {
        if (walletState.isWiping()) return
        Timber.tag(name).i("newBlock: %s", height)
        updateSyncProgress(height)
    }

    private fun updateSyncProgress(height: Long) {
        if (walletState.isWiping() || wallet.isSynchronized) return
        val syncHeight = wallet.getBlockChainHeight()
        val daemonHeight = wallet.getDaemonBlockChainHeight()
        val left = daemonHeight - syncHeight
        if (syncHeight < 0 || left < 0) return
        val resetHeight = walletState.getResetSyncHeight()
        val progress = if (walletState.isResetSyncInProgress() && resetHeight >= 0L) {
            val total = daemonHeight - resetHeight
            if (total <= 0L) 1f
            else ((height - resetHeight).toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else if (wallet.getDaemonBlockChainTargetHeight().toDouble() == 0.0) {
            1f
        } else {
            (height.toDouble() / wallet.getDaemonBlockChainTargetHeight().toDouble()).toFloat()
        }
        walletState.syncUpdate(SyncProgress(progress, left))
    }

    override fun updated() {
        if (walletState.isWiping()) return
        // This callback is emitted from inside the native refresh operation.
        // Do not call back into wallet.refreshHistory()/coins()/balance here.
        // The completed refresh callback publishes the state after the native
        // refresh operation has returned.
        Timber.tag(name).i("updated()")
    }

    override fun refreshed() {
        if (walletState.isWiping()) return

        val status = wallet.fullStatus.connectionStatus
        val daemonHeight = wallet.getDaemonBlockChainHeight()
        val chainHeight = wallet.getBlockChainHeight()

        Timber.tag(name).i("refreshed() status:%s daemonHeight:%s chainHeight:%s", status, daemonHeight, chainHeight)

        if (status === Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            // Never replace an active restore/reset rescan with a fresh init().
            // The native refresh thread owns this scan until it completes.
            if (walletState.isResetSyncInProgress() || walletState.isRestoreSyncInProgress()) {
                Timber.tag(name).i("native rescan waiting for connection")
                return
            }

            val daemonAddress = WalletManager.instance?.getDaemonAddress()
            if (!daemonAddress.isNullOrBlank()) {
                tryRestartConnection()
            } else {
                walletState.setLoading(false)
            }
            return
        }

        val heightDiff = daemonHeight - chainHeight
        if (heightDiff >= 2) {
            // The native refresh thread is already scanning. Re-starting it and
            // refreshing history from this callback only creates re-entrant work.
            return
        }

        if (!wallet.isSynchronized) updateSyncProgress(chainHeight)

        // Native wallet refresh has completed. Mark synchronization done now,
        // then publish balance/history asynchronously after this JNI callback
        // returns to native code.
        wallet.setSynchronized()
        walletState.syncUpdate(SyncProgress(1f, 0L))
        walletState.setLoading(false)
        walletState.publishAfterNativeRefresh()
    }

    private fun tryRestartConnection() {
        wallet.init(0)
        wallet.startRefresh()
        walletState.update()
    }

    fun sendTx(pendingTx: PendingTransaction): Boolean = pendingTx.commit("", true)

    interface Listener {
        fun onRefresh(walletSynced: Boolean)
        fun onConnectionFail()
        fun onNewBlockFound(block: Long)
    }

    companion object {
        const val THREAD_STACK_SIZE = (5 * 1024 * 1024).toLong()
    }
}
