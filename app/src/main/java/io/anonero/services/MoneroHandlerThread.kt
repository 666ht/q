package io.anonero.services

import io.anonero.model.PendingTransaction
import io.anonero.model.Wallet
import io.anonero.model.WalletListener
import io.anonero.model.WalletManager
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handy class for starting a new thread that has a looper. The looper can then be
 * used to create handler classes. Note that start() must still be called.
 * The started Thread has a stck size of STACK_SIZE (=5MB)
 */

class MoneroHandlerThread(private val wallet: Wallet, private val walletState: WalletState) :
    Thread(null, null, "MoneroHandler", THREAD_STACK_SIZE), WalletListener {

    private val syncCompletionHandled = AtomicBoolean(false)



    @Synchronized
    override fun start() {
        super.start()
    }

    override fun run() {

    }

    override fun moneySpent(txId: String?, amount: Long) {

    }

    override fun moneyReceived(txId: String?, amount: Long) {
        Timber.tag(name).i("moneyReceived: %s", amount)
        WalletManager.instance?.wallet?.store()
    }

    override fun unconfirmedMoneyReceived(txId: String?, amount: Long) {}

    override fun newBlock(height: Long) {
        Timber.tag(name).i("newBlock: %s", height)

        // Native wallet2 is authoritative. Once native synchronization reports
        // completion, finish immediately from the block callback instead of
        // waiting for another refresh cycle.
        if (wallet.nativeSynchronized) {
            completeSynchronization()
            return
        }

        // Do not recreate sync progress after synchronization has finished.
        if (!wallet.isSynchronized && !walletState.customRescanInProgress) {
            updateSyncProgress(height)
        }
    }

    private fun updateSyncProgress(height: Long) {
        val syncHeight = wallet.getBlockChainHeight()
        val deamonHeight = wallet.getDaemonBlockChainHeight()
        val customStart = walletState.customRescanStartHeight
        val customDayEnd = walletState.customRescanDayEndHeight
        val currentHeight = if (walletState.customRescanInProgress && customStart != null) {
            // During a native rescan getBlockChainHeight() can lag behind the
            // newBlock callback. Use the callback height so the progress UI
            // advances with the actual blocks being scanned.
            maxOf(height, customStart)
        } else {
            syncHeight
        }
        val effectiveTarget = if (walletState.customRescanInProgress && customDayEnd != null) {
            minOf(deamonHeight, customDayEnd)
        } else {
            deamonHeight
        }
        val left = (effectiveTarget - currentHeight).coerceAtLeast(0L)
        if (syncHeight < 0 || deamonHeight < 0) {
            return
        }

        val targetHeight = wallet.getDaemonBlockChainTargetHeight()
        val progress = if (walletState.customRescanInProgress && customStart != null) {
            // Keep the progress bar on the same target as the displayed remaining
            // blocks, so both reach completion together.
            val target = effectiveTarget
            if (target <= customStart) {
                1f
            } else {
                ((currentHeight - customStart).toDouble() /
                    (target - customStart).toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()
            }
        } else if (targetHeight.toDouble() == 0.0) {
            1f
        } else {
            (height.toDouble() / targetHeight.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
        walletState.syncUpdate(SyncProgress(progress, left))
    }

    override fun updated() {
        refresh(false)
        Timber.tag(name).i("updated()")
        walletState.update()
    }

    override fun refreshed() {
        syncCompletionHandled.set(false)
        val status = wallet.fullStatus.connectionStatus
        val daemonHeight = wallet.getDaemonBlockChainHeight()
        val chainHeight = wallet.getBlockChainHeight()
        /// height
        Timber.tag(name)
            .i("refreshed() status:${status} daemonHeight:$daemonHeight chainHeight:$chainHeight ")
        if (walletState.customRescanInProgress) {
            if (wallet.nativeSynchronized) {
                // Native wallet2 is authoritative here. Complete the sync and
                // refresh wallet data immediately when the native scan is done.
                completeSynchronization()
            } else {
                updateSyncProgress(walletState.customRescanStartHeight ?: chainHeight)
            }
            walletState.update()
            return
        }
        if (status === Wallet.ConnectionStatus.ConnectionStatus_Disconnected || status == null) {
            tryRestartConnection()
        } else {
            val heightDiff = daemonHeight - chainHeight
            if (heightDiff >= 2) {
                tryRestartConnection()
            } else {
                if (!wallet.isSynchronized) {
                    updateSyncProgress(wallet.getBlockChainHeight())
                }
                completeSynchronization()
            }

        }
        walletState.update()
    }

    private fun completeSynchronization() {
        if (!syncCompletionHandled.compareAndSet(false, true)) {
            walletState.finishSync()
            return
        }

        try {
            wallet.setSynchronized()
            walletState.customRescanStartHeight = null
            walletState.customRescanDayEndHeight = null
            walletState.customRescanInProgress = false

            // Read the final native wallet state before hiding the progress UI.
            wallet.store()
            refresh(true)
            walletState.update()
        } finally {
            walletState.finishSync()
        }
    }

    private fun tryRestartConnection() {
        wallet.init(0)
        wallet.startRefresh()
        walletState.update()
    }

    private fun refresh(walletSynced: Boolean) {
        wallet.refreshHistory()
        if (walletSynced) {
            wallet.refreshCoins()
        }
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
        // from src/cryptonote_config.h
        const val THREAD_STACK_SIZE = (5 * 1024 * 1024).toLong()
    }
}