package org.monfluo.wallet.lib.wallet.sync

import org.monfluo.wallet.lib.data.NodeInfo

sealed interface WalletSyncError {
    data class CouldNotFetchNodeInfo(
        val interfaceError: InterfaceError,
    ) : WalletSyncError {
        override fun toString(): String = "Could not fetch node info: $interfaceError"
    }

    data class NodeNotSynced(
        val nodeInfo: NodeInfo,
    ) : WalletSyncError {
        override fun toString(): String = "Node is synchronizing: ${nodeInfo.height - 1}/${nodeInfo.targetHeight - 1}"
    }

    data class CouldNotFetchBlocks(
        val interfaceError: InterfaceError,
    ) : WalletSyncError {
        override fun toString(): String = "Could not fetch blocks: $interfaceError"
    }
}

sealed interface InterfaceError {
    data class InternalError(
        val value: String,
    ) : InterfaceError {
        override fun toString(): String = "Internal Error: $value"
    }

    data class InterfaceErrorInner(
        val value: String,
    ) : InterfaceError {
        override fun toString(): String = "Interface Error: $value"
    }

    data class InvalidInterface(
        val value: String,
    ) : InterfaceError {
        override fun toString(): String = "Invalid Interface: $value"
    }
}
