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

        val daemonHeight = wallet.getDaemonBlockChainHeight()
        if (daemonHeight <= 0L || height < 0L) return

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

        // Native wallet2 is authoritative about whether this refresh cycle
        // actually synchronized the wallet. Do not infer completion from
        // daemonHeight - chainHeight; during restore/rescan those values can
        // temporarily be 0/0 or reflect the old cache.
        if (!wallet.nativeSynchronized) {
            updateSyncProgress(chainHeight)
            return
        }

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
