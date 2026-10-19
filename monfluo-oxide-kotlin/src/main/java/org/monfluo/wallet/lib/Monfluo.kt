package org.monfluo.wallet.lib

import org.monfluo.wallet.lib.data.Network
import org.monfluo.wallet.lib.seed.Seed
import org.monfluo.wallet.lib.seed.SeedLanguage
import org.monfluo.wallet.lib.wallet.Daemon
import org.monfluo.wallet.lib.wallet.Wallet

object Monfluo {
    external fun monfluoVersion(): String

    external fun listWallets(monfluoDirectory: String): List<String>

    external fun deleteWallet(
        monfluoDirectory: String,
        name: String,
    )

    external fun renameWallet(
        monfluoDirectory: String,
        name: String,
        newName: String,
    )

    external fun createWallet(
        monfluoDirectory: String,
        network: Network,
        name: String,
        password: String,
        seedLanguage: SeedLanguage,
        seedOffset: String?,
    )

    external fun <L : SeedLanguage> restoreWallet(
        monfluoDirectory: String,
        network: Network,
        name: String,
        password: String,
        seed: Seed<L>,
        seedOffset: String?,
        height: Int?,
    )

    external fun restoreWalletFromKeys(
        monfluoDirectory: String,
        network: Network,
        name: String,
        password: String,
        privateSpendKey: String,
        privateViewKey: String,
        height: Int?,
    )

    external fun openWallet(
        monfluoDirectory: String,
        name: String,
        password: String,
    ): Wallet

    external fun createDaemon(
        nodeName: String,
        nodeAddress: String,
        nodeUsername: String?,
        nodePassword: String?,
        nodeProxy: String?,
    ): Daemon
}
