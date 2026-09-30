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

        if (status === Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            tryRestartConnection()
        } else {
            val heightDiff = daemonHeight - chainHeight
            if (heightDiff >= 2L) {
                tryRestartConnection()
            } else {
                if (!wallet.isSynchronized) {
                    updateSyncProgress(chainHeight)
                }
                wallet.setSynchronized()
                wallet.store()
                refresh(true)
                walletState.finishSync()
                walletState.setLoading(false)
                walletState.finishResetSync()
                walletState.finishRestoreSync()
            }
        }

        walletState.update()
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
