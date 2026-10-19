package org.monfluo.wallet.lib.wallet.sync

import org.monfluo.wallet.lib.data.NodeInfo

sealed interface WalletSyncState {
    val timestamp: Long
    val currentHeight: Int?
    val isTerminal: Boolean

    data class Preparing(
        override val timestamp: Long,
        override val currentHeight: Int?,
    ) : WalletSyncState {
        override val isTerminal: Boolean = false
    }

    data class Starting(
        override val timestamp: Long,
        override val currentHeight: Int,
        val nodeInfo: NodeInfo,
    ) : WalletSyncState {
        override val isTerminal: Boolean = false
    }

    data class Syncing(
        override val timestamp: Long,
        override val currentHeight: Int,
    ) : WalletSyncState {
        override val isTerminal: Boolean = false
    }

    data class Finished(
        override val timestamp: Long,
        val height: Int,
    ) : WalletSyncState {
        override val currentHeight: Int = height
        override val isTerminal: Boolean = true
    }

    data class Cancelled(
        override val timestamp: Long,
    ) : WalletSyncState {
        override val currentHeight: Int? = null
        override val isTerminal: Boolean = true
    }

    data class Failed(
        override val timestamp: Long,
        val error: WalletSyncError,
    ) : WalletSyncState {
        override val currentHeight: Int? = null
        override val isTerminal: Boolean = true
    }
}
