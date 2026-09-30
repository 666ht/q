package io.anonero.services

import io.anonero.model.PendingTransaction
import io.anonero.model.Wallet
import io.anonero.model.WalletListener
import io.anonero.model.WalletManager
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
        if (height < 0L) return

        val daemonHeight = wallet.getDaemonBlockChainHeight()
        if (daemonHeight <= 0L) return

        val resetHeight = walletState.getResetSyncHeight()
        if (walletState.isResetSyncInProgress() && resetHeight >= 0L) {
            val total = (daemonHeight - resetHeight).coerceAtLeast(1L)
            val progress = ((height - resetHeight).toDouble() / total.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
            val left = (daemonHeight - height).coerceAtLeast(0L)
            walletState.syncUpdate(SyncProgress(progress, left))
            return
        }

        val target = wallet.getDaemonBlockChainTargetHeight().takeIf { it > 0L }
            ?: daemonHeight
        val progress = (height.toDouble() / target.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
        val left = (daemonHeight - height).coerceAtLeast(0L)
        walletState.syncUpdate(SyncProgress(progress, left))
    }

    override fun updated() {
        if (walletState.isWiping()) return
        // Native emits this callback while a refresh operation is still running.
        // Do not call refreshHistory()/balance/coins here; that would re-enter
        // native wallet APIs from inside the native refresh callback.
        Timber.tag(name).i("updated()")
    }

    override fun refreshed() {
        if (walletState.isWiping()) return

        val status = wallet.fullStatus.connectionStatus
        val daemonHeight = wallet.getDaemonBlockChainHeight()
        val daemonTarget = wallet.getDaemonBlockChainTargetHeight()
        val chainHeight = wallet.getBlockChainHeight()

        Timber.tag(name).i(
            "refreshed() status:%s daemonHeight:%s daemonTarget:%s chainHeight:%s",
            status,
            daemonHeight,
            daemonTarget,
            chainHeight
        )

        if (status === Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            // During an active restore/reset scan the native refresh worker must
            // be allowed to reconnect itself; do not replace it with init(0).
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

        // The native callback is also emitted when doRefresh() skipped the
        // wallet scan because the daemon was not ready. Never treat that as
        // "sync complete", especially during restore, or the one-time restore
        // rescan can be consumed before it actually runs.
        if (daemonHeight <= 1L || daemonTarget <= 1L || daemonHeight < daemonTarget) {
            wallet.refreshAsync()
            return
        }

        // Keep scanning until the wallet has actually caught up with the daemon.
        // A native refresh callback is not itself a completion signal.
        val heightDiff = daemonHeight - chainHeight
        if (heightDiff >= 2L) {
            wallet.refreshAsync()
            return
        }

        if (!wallet.isSynchronized) {
            updateSyncProgress(chainHeight)
        }

        // This refresh pass has actually caught up. Mark the Java state synced,
        // then publish native balance/history only after the JNI callback returns.
        wallet.setSynchronized()
        walletState.finishSync()
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
