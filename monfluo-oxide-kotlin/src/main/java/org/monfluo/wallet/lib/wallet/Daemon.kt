package org.monfluo.wallet.lib.wallet

import org.monfluo.wallet.lib.data.NodeInfo

class Daemon(
    @Suppress("unused") private val _handle: Long,
    val nodeName: String,
    val nodeAddress: String,
    val nodeUsername: String?,
    val nodePassword: String?,
    val nodeProxy: String?,
) {
    external fun fetchNodeInfo(): NodeInfo
}
