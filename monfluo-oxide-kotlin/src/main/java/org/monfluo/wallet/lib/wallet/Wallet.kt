package org.monfluo.wallet.lib.wallet

import org.monfluo.wallet.lib.data.AccountView
import org.monfluo.wallet.lib.data.Address
import org.monfluo.wallet.lib.data.Balance
import org.monfluo.wallet.lib.data.FeePriority
import org.monfluo.wallet.lib.data.MoneroBytes
import org.monfluo.wallet.lib.data.Payment
import org.monfluo.wallet.lib.data.TransactionView
import org.monfluo.wallet.lib.wallet.sync.WalletSyncObserver

class Wallet(
    @Suppress("unused") private val _handle: Long,
) {
    var currentAccountIndex: Int = 0

    external fun getName(): String

    external fun getCurrentHeight(): Int?

    external fun sync(
        daemon: Daemon,
        observer: WalletSyncObserver,
    )

    external fun getBalance(): Balance

    external fun getAccountView(accountIndex: Int): AccountView

    fun getAccountView(): AccountView = getAccountView(currentAccountIndex)

    external fun getTransactionViews(accountIndex: Int): List<TransactionView>

    fun getTransactionViews(): List<TransactionView> = getTransactionViews(currentAccountIndex)

    external fun registerNewAccount(): Int

    external fun registerNewAddress(account: Int): Int

    fun registerNewAddressForCurrentAccount(): Int = registerNewAddress(currentAccountIndex)

    external fun createAndSignTransaction(
        daemon: Daemon,
        payments: List<Payment>,
        feePriority: FeePriority,
    ): TransactionView

    external fun createAndSignSweepTransaction(
        daemon: Daemon,
        address: Address,
        feePriority: FeePriority,
    ): TransactionView

    external fun sendTransaction(
        daemon: Daemon,
        transactionHash: MoneroBytes,
    )

    external fun store()

    external fun close()
}
