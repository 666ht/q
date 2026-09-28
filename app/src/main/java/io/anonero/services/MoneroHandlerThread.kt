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

    override fun run() {
    }

    override fun moneySpent(txId: String?, amount: Long) {
        if (walletState.isWiping()) return
    }

    override fun moneyReceived(txId: String?, amount: Long) {
        if (walletState.isWiping()) return
        Timber.tag(name).i("moneyReceived: %s", amount)
        WalletManager.instance?.wallet?.store()
    }

    override fun unconfirmedMoneyReceived(txId: String?, amount: Long) {
        if (walletState.isWiping()) return
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
        val progress = if (wallet.getDaemonBlockChainTargetHeight().toDouble() == 0.0) {
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
        Timber.tag(name).i("refreshed() status:\${status} daemonHeight:\${daemonHeight} chainHeight:\${chainHeight} ")
        if (status === Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            tryRestartConnection()
        } else {
            val heightDiff = daemonHeight - chainHeight
            if (heightDiff >= 2) {
                // The daemon can advance while the wallet is refreshing. Do not
                // restart/init the wallet here: that resets the refresh cycle and
                // can leave the UI progress indicator running forever.
                updateSyncProgress(chainHeight)
                return
            }

            if (!wallet.isSynchronized) {
                updateSyncProgress(chainHeight)
            }

            // The native wallet refresh has completed. Publish the native balance
            // and refreshed transaction history before ending the UI sync state.
            wallet.setSynchronized()
            walletState.syncUpdate(SyncProgress(1f, 0L))

            // Publish the completed native state, then run the normal state
            // update once more so every balance/transaction observer receives
            // the final values from the same completed wallet snapshot.
            walletState.publishAfterSync()
            walletState.update()

            // Both loading sources drive the progress UI. Clear loading only
            // after the final wallet state has been published.
            walletState.setLoading(false)

            try {
                wallet.store()
            } catch (e: Exception) {
                Timber.tag(name).e(e, "wallet store after sync failed")
            }
        }
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