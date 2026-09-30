package io.anonero.services

import io.anonero.model.PendingTransaction
import io.anonero.model.Wallet
import io.anonero.model.WalletListener
import io.anonero.model.WalletManager
import timber.log.Timber

class MoneroHandlerThread(private val wallet: Wallet, private val walletState: WalletState) :
    Thread(null, null, "MoneroHandler", THREAD_STACK_SIZE), WalletListener {


    @Synchronized
    override fun start() {
        super.start()
    }

    override fun run() {}

    override fun moneySpent(txId: String?, amount: Long) {
        if (walletState.isWiping()) return
    }

    override fun moneyReceived(txId: String?, amount: Long) {
        if (walletState.isWiping()) return
        Timber.tag(name).i("moneyReceived: %s", amount)
        WalletManager.instance?.wallet?.store()
        refresh(false)
    }

    override fun unconfirmedMoneyReceived(txId: String?, amount: Long) {
        if (walletState.isWiping()) return
        Timber.tag(name).i("unconfirmedMoneyReceived: %s", amount)
        refresh(false)
    }

    override fun newBlock(height: Long) {
        if (walletState.isWiping()) return
        Timber.tag(name).i("newBlock: %s", height)
        updateSyncProgress(height)
    }

    private fun updateSyncProgress(height: Long) {
        if (walletState.isWiping() || wallet.isSynchronized) return
        val syncHeight = wallet.getBlockChainHeight()
        val deamonHeight = wallet.getDaemonBlockChainHeight()
        val left = deamonHeight - syncHeight
        if (syncHeight < 0 || left < 0) return
        val resetHeight = walletState.getResetSyncHeight()
        val progress = if (walletState.isResetSyncInProgress() && resetHeight >= 0L) {
            val total = deamonHeight - resetHeight
            if (total <= 0L) {
                1f
            } else {
                ((height - resetHeight).toDouble() / total.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()
            }
        } else if (wallet.getDaemonBlockChainTargetHeight().toDouble() == 0.0) {
            1f
        } else {
            (height.toDouble() / wallet.getDaemonBlockChainTargetHeight().toDouble()).toFloat()
        }
        walletState.syncUpdate(SyncProgress(progress, left))
    }

    override fun updated() {
        if (walletState.isWiping()) return
        refresh(false)
        Timber.tag(name).i("updated()")
        walletState.update()
    }

    override fun refreshed() {
        if (walletState.isWiping()) return

        val status = wallet.fullStatus.connectionStatus
        val daemonHeight = wallet.getDaemonBlockChainHeight()
        val chainHeight = wallet.getBlockChainHeight()

        Timber.tag(name).i(
            "refreshed() status:%s daemonHeight:%s chainHeight:%s",
            status,
            daemonHeight,
            chainHeight
        )

        // Match upstream ANONERO: only reconnect when a node is selected.
        // With no daemon address, stay disconnected instead of spinning init().
        if (status === Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            val daemonAddress = WalletManager.instance?.getDaemonAddress()
            if (!daemonAddress.isNullOrBlank()) {
                tryRestartConnection()
            } else {
                walletState.setLoading(false)
                walletState.update()
            }
            return
        }

        val heightDiff = daemonHeight - chainHeight
        if (heightDiff >= 2) {
            // Upstream: continue the existing refresh thread. Do NOT re-init
            // on every tick — that drops status to Disconnected and blocks
            // reaching the completion path that publishes balance/history.
            wallet.startRefresh()
            // Keep observers current while still catching up (updated()
            // may not fire often enough on some nodes).
            refresh(false)
            return
        }

        // heightDiff < 2: native refresh completed for this pass.
        if (!wallet.isSynchronized) {
            updateSyncProgress(chainHeight)
        }

        wallet.setSynchronized()
        wallet.store()

        refresh(true)
        walletState.update()
        walletState.finishResetSync()
        walletState.finishRestoreSync()

        walletState.syncUpdate(SyncProgress(1f, 0L))
        walletState.setLoading(false)
    }

    private fun tryRestartConnection() {
        wallet.init(0)
        wallet.startRefresh()
        walletState.update()
    }

    private fun refresh(walletSynced: Boolean) {
        wallet.refreshHistory()
        if (walletSynced) wallet.refreshCoins()
        walletState.update()
    }

    fun sendTx(pendingTx: PendingTransaction): Boolean {
        return pendingTx.commit("", true)
    }

    interface Listener {
        fun onRefresh(walletSynced: Boolean)
        fun onConnectionFail()
        fun onNewBlockFound(block: Long)
    }

    companion object {
        const val THREAD_STACK_SIZE = (5 * 1024 * 1024).toLong()
    }
}
