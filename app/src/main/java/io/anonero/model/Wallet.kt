/*
 * Copyright (c) 2017 m2049r
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.anonero.model

import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "Wallet"

class Wallet {
    var isSynchronized = false
    var isInitialized = false
    private var accountIndex = 0
    private var handle: Long = 0
    private var listenerHandle: Long = 0
    private var pendingTransaction: PendingTransaction? = null
    private var unsignedTransaction: UnsignedTransaction? = null
    var history: TransactionHistory? = null
        get() {
            if (field == null) {
                field = TransactionHistory(getHistoryJ(), accountIndex)
            }
            return field
        }
    public var coins: Coins? = null
        get() {
            if (field == null) {
                field = Coins(getCoinsJ())
            }
            return field
        }

    internal constructor(handle: Long) {
        this.handle = handle
    }

    fun getPendingTx(): PendingTransaction? {
        return pendingTransaction
    }

    fun getUnsginedTx(): UnsignedTransaction? {
        return unsignedTransaction
    }

    internal constructor(handle: Long, accountIndex: Int) {
        this.handle = handle
        this.accountIndex = accountIndex
    }

    fun getAccountIndex(): Int {
        return accountIndex
    }

    fun setAccountIndex(accountIndex: Int) {
        this.accountIndex = accountIndex
        history?.setAccountFor(this)
    }

    val name: String
        get() = getPath()?.let { File(it).name }.toString()

    external fun getSeed(offset: String?): String?
    external fun getLegacySeed(offset: String?): String?
    external fun isPolyseedSupported(offset: String?): Boolean
    external fun getSeedLanguage(): String?

    val status: Status
        get() = statusWithErrorString()
    val fullStatus: Status
        get() {
            val walletStatus = statusWithErrorString()
            walletStatus.connectionStatus = connectionStatus
            return walletStatus
        }

    private external fun statusWithErrorString(): Status

    @Synchronized
    external fun setPassword(password: String?): Boolean
    val address: String
        get() = getAddress(accountIndex)

    //TODO virtual void hardForkInfo(uint8_t &version, uint64_t &earliest_height) const = 0;
    //TODO virtual bool useForkRules(uint8_t version, int64_t early_blocks) const = 0;
    fun getAddress(accountIndex: Int): String {
        return getAddressJ(accountIndex, 0)
    }

    private fun getSubaddress(addressIndex: Int): String {
        return getAddressJ(accountIndex, addressIndex)
    }

    fun getSubaddress(accountIndex: Int, addressIndex: Int): String {
        return getAddressJ(accountIndex, addressIndex)
    }

    private external fun getAddressJ(accountIndex: Int, addressIndex: Int): String
    private fun getSubaddressObject(accountIndex: Int, subAddressIndex: Int): Subaddress {
        return Subaddress(
            accountIndex,
            subAddressIndex,
            getSubaddress(subAddressIndex),
            getSubaddressLabel(0, subAddressIndex)
        )
    }

    fun getSubaddressObject(subAddressIndex: Int): Subaddress {
        val subaddress = getSubaddressObject(accountIndex, subAddressIndex)
        var amount: Long = 0
        history?.let { history ->
            for (info in history.all) {
                if (info.addressIndex == subAddressIndex && info.direction == TransactionInfo.Direction.Direction_In) {
                    amount += info.amount
                }
            }
        }

        subaddress.amount = amount
        return subaddress
    }

    external fun getPath(): String?
    val networkType: NetworkType?
        get() = NetworkType.fromInteger(nettype())

    external fun nettype(): Int

    //    virtual bool createWatchOnly(const std::string &path, const std::string &password, const std::string &language) const = 0;
    //    virtual void setRefreshFromBlockHeight(uint64_t refresh_from_block_height) = 0;
    external fun getIntegratedAddress(paymentId: String?): String?
    external fun getSecretViewKey(): String
    external fun getSecretSpendKey(): String

    fun store(): Boolean {
        return store("")
    }

    //TODO virtual void setTrustedDaemon(bool arg) = 0;
    //TODO virtual bool trustedDaemon() const = 0;
    @Synchronized
    external fun store(path: String?): Boolean
    fun close(): Boolean {
        disposePendingTransaction()
        return WalletManager.instance?.close(this) == true
    }

    external fun getFilename(): String

    //    virtual std::string keysFilename() const = 0;
    fun init(upperTransactionSizeLimit: Long): Boolean {
        var daemonAddress = WalletManager.instance?.getDaemonAddress()
        var daemonUsername = WalletManager.instance?.daemonUsername
        var daemonPassword = WalletManager.instance?.daemonPassword
        var proxyAddress = WalletManager.instance?.proxy
        var message = "init("
        if (daemonAddress != null) {
            message = "${message}\n$daemonAddress"
        } else {
            Timber.tag(TAG).i("")
            message = "${message}\ndaemon_address == null"
            daemonAddress = ""
        }
        message = "${message}\nupper_transaction_size_limit = 0 (probably)"
        if (daemonUsername != null) {
            Timber.tag(TAG).i(daemonUsername)
        } else {
            message = "${message}\ndaemon_username == null"
            daemonUsername = ""
        }
        if (daemonPassword != null) {
            Timber.tag(TAG).i(daemonPassword)
        } else {
            message = "${message}daemon_password == null"
            daemonPassword = ""
        }
        if (proxyAddress != null) {
            message = "${message}\nproxy : $proxyAddress"
        } else {
            message = "${message}\nproxy_address = null"
            proxyAddress = ""
        }
        Timber.tag(TAG).i("${message}\n);")

        isInitialized = initJ(
            daemonAddress, upperTransactionSizeLimit,
            daemonUsername, daemonPassword,
            proxyAddress
        )
        return isInitialized
    }

    private external fun initJ(
        daemonAddress: String, upperTransactionSizeLimit: Long,
        daemonUsername: String, daemonPassword: String, proxyAddress: String
    ): Boolean

    external fun getRestoreHeight(): Long
    external fun setRestoreHeight(height: Long): Boolean

    private val connectionStatus: ConnectionStatus
        get() {
            val s = getConnectionStatusJ()
            return ConnectionStatus.values()[s]
        }

    private external fun getConnectionStatusJ(): Int

    external fun setTrustedDaemon(trusted: Boolean): Boolean

    fun setProxy(address: String?): Boolean {
        return setProxyJ(address)
    }

    private external fun setProxyJ(address: String?): Boolean
    val balance: Long
        get() = getBalance(accountIndex)

    private external fun getBalance(accountIndex: Int): Long

    external fun viewOnlyBalance(): Long

    external fun getBalanceAll(): Long

    val unlockedBalance: Long
        get() = getUnlockedBalance(accountIndex)

    external fun getUnlockedBalanceAll(): Long

    external fun getUnlockedBalance(accountIndex: Int): Long
    external fun isWatchOnly(): Boolean

    external fun getBlockChainHeight(): Long
    external fun getApproximateBlockChainHeight(): Long

    external fun getDaemonBlockChainHeight(): Long
    external fun getDaemonBlockChainTargetHeight(): Long

    fun setSynchronized() {
        isSynchronized = true
    }

    // Native wallet2 owns the authoritative synchronization state.
    val nativeSynchronized: Boolean
        get() = isSynchronizedJ()

    private external fun isSynchronizedJ(): Boolean

    external fun startRefresh()
    external fun pauseRefresh()
    external fun refresh(): Boolean
    external fun refreshAsync()
    private external fun rescanBlockchainAsyncJ(): Boolean
    fun rescanBlockchainAsync(): Boolean {
        isSynchronized = false
        val started = rescanBlockchainAsyncJ()
        if (started) {
            // pauseRefresh() is used by the custom-height reset path before
            // requesting the rescan. Native rescan only queues the operation;
            // the refresh worker must be enabled again to execute it.
            startRefresh()
        }
        return started
    }

    //TODO virtual void setAutoRefreshInterval(int millis) = 0;
    //TODO virtual int autoRefreshInterval() const = 0;
    private fun disposePendingTransaction() {
        if (pendingTransaction != null) {
            disposeTransaction(pendingTransaction)
            pendingTransaction = null
            unsignedTransaction = null
        }
    }

    fun createSweepTransaction(
        dstAddr: String,
        priority: PendingTransaction.Priority,
        keyImages: ArrayList<String>
    ): PendingTransaction? {
        disposePendingTransaction()
        val _priority = priority.ordinal
        val txHandle = createSweepTransaction(dstAddr, "", 0, _priority, accountIndex, keyImages)
        pendingTransaction = PendingTransaction(txHandle)
        unsignedTransaction = null
        return pendingTransaction
    }

    external fun createTransactionJ(
        dstAddr: String, paymentId: String,
        amount: Long, mixinCount: Int,
        priority: Int, accountIndex: Int, keyImages: ArrayList<String>
    ): Long

    external fun signAndExportJ(inputFile: String?, outputFile: String?): String?

    @Throws(Exception::class)
    fun createTransaction(
        dst_addr: String?,
    	amount: Long,
    	sweepAll: Boolean = false,
    	mixin_count: Int = 0,
    	priority: PendingTransaction.Priority = PendingTransaction.Priority.Priority_Default,
    	selectedUtxos: List<CoinsInfo> = arrayListOf()
    ): PendingTransaction {
    	disposePendingTransaction()
        val priority: Int = priority.ordinal
    	val preferredInputs = selectedUtxos.map { it.key }.toCollection(ArrayList())
    	if (selectedUtxos.isNotEmpty() && !sweepAll) {
            checkSelectedAmounts(preferredInputs, amount, false)
    	}
    	val txHandle =
            if (sweepAll) createSweepTransaction(
            	dst_addr!!, "", mixin_count, priority,
            	accountIndex, preferredInputs
            ) else createTransactionJ(
            	dst_addr!!, "", amount, mixin_count, priority,
             	accountIndex, preferredInputs
            )
    	pendingTransaction = PendingTransaction(txHandle)
    	unsignedTransaction = null
    	return pendingTransaction!!
    }

    fun send(pendingTransaction: PendingTransaction): Boolean {
        return pendingTransaction.commit("", overwrite = true)
    }


    @Throws(java.lang.Exception::class)
    fun getUtxos(): List<CoinsInfo> {
        return coins?.all ?: listOf()
    }


    @Throws(java.lang.Exception::class)
    private fun checkSelectedAmounts(selectedUtxos: List<String>, amount: Long, sendAll: Boolean) {
        if (!sendAll) {
            var amountSelected: Long = 0
            for (coinsInfo in getUtxos()) {
                if (selectedUtxos.contains(coinsInfo.key)) {
                    amountSelected += coinsInfo.amount
                }
            }
            if (amountSelected <= amount) {
                Timber.tag("Wallet").e("insufficient wallet balance- Available/Selected: $amountSelected, amount: $amount")
                throw java.lang.Exception("insufficient wallet balance")
            }
        }
    }
    private external fun createSweepTransaction(
        dstAddr: String, paymentId: String,
        mixinCount: Int,
        priority: Int, accountIndex: Int, keyImages: ArrayList<String>
    ): Long

    fun createSweepUnmixableTransaction(): PendingTransaction? {
        disposePendingTransaction()
        val txHandle = createSweepUnmixableTransactionJ()
        pendingTransaction = PendingTransaction(txHandle)
        return pendingTransaction
    }

    private external fun createSweepUnmixableTransactionJ(): Long
    private external fun disposeTransaction(pendingTransaction: PendingTransaction?)
    private external fun getHistoryJ(): Long
    private external fun getCoinsJ(): Long


    external fun exportOutputs(filename: String?, all: Boolean): Boolean
    external fun importOutputs(filename: String?): String?
    external fun exportKeyImages(filename: String?, all: Boolean): Boolean
    external fun hasUnknownKeyImages(): Boolean
    external fun importKeyImages(filename: String?): Boolean
    fun loadUnsignedTransaction(inputFile: String?): UnsignedTransaction {
        val unsignedTx: Long = loadUnsignedTx(inputFile)
        unsignedTransaction = UnsignedTransaction(unsignedTx)
        pendingTransaction = null
        return unsignedTransaction!!
    }

    external fun submitTransaction(filename: String?): String?

    private external fun loadUnsignedTx(inputFile: String?): Long

    //virtual TransactionHistory * history() const = 0;
    fun refreshHistory() {
        // Always create the native transaction history before refreshing it.
        // The old implementation silently did nothing while history was null,
        // which left every observer except screens that had already initialized
        // history with an empty transaction list.
        val txHistory = history ?: TransactionHistory(getHistoryJ(), accountIndex).also {
            history = it
        }
        txHistory.refreshWithNotes(this)
    }

    external fun stopBackgroundSync(password: String?): Boolean

    external fun startBackgroundSync(): Boolean

    fun refreshCoins() {
        if (isSynchronized) {
            Timber.tag("Wallet").d("Coin Refreshed: %s", coins?.getCount())
            coins?.refresh()
        }
    }

    private external fun setListenerJ(listener: WalletListener?): Long
    fun setListener(listener: WalletListener?) {
        listenerHandle = setListenerJ(listener)
    }

    external fun getDefaultMixin(): Int
    external fun setDefaultMixin(mixin: Int)
    external fun setUserNote(txid: String?, note: String?): Boolean
    external fun getUserNote(txid: String?): String?
    external fun getTxKey(txid: String?): String?

    @JvmOverloads
    external fun addAccount(label: String? = NEW_ACCOUNT_NAME)
    var accountLabel: String?
        get() = getAccountLabel(accountIndex)
        //virtual std::string signMessage(const std::string &message) = 0;
        set(label) {
            setAccountLabel(accountIndex, label)
        }

    private fun getAccountLabel(accountIndex: Int): String {
        var label = getSubaddressLabel(accountIndex, 0)
        if (label == NEW_ACCOUNT_NAME) {
            val address = getAddress(accountIndex)
            val len = address.length
            label = address.substring(0, 6) +
                    "\u2026" + address.substring(len - 6, len)
        }
        return label
    }

    fun getSubaddressLabel(addressIndex: Int): String {
        return getSubaddressLabel(accountIndex, addressIndex)
    }

    private external fun getSubaddressLabel(accountIndex: Int, addressIndex: Int): String
    private fun setAccountLabel(accountIndex: Int, label: String?) {
        setSubaddressLabel(accountIndex, 0, label)
    }

    fun setSubaddressLabel(addressIndex: Int, label: String?) {
        setSubaddressLabel(accountIndex, addressIndex, label)
        refreshHistory()
    }

    private external fun setSubaddressLabel(accountIndex: Int, addressIndex: Int, label: String?)
    external fun getNumAccounts(): Int
    val numSubAddresses: Int
        get() = getNumSubaddresses(accountIndex)

    private external fun getNumSubaddresses(accountIndex: Int): Int

    private fun getNewSubaddress(accountIndex: Int): String {
        val timeStamp = SimpleDateFormat("yyyy-MM-dd-HH:mm:ss", Locale.US).format(Date())
        addSubaddress(accountIndex, timeStamp)
        val subaddress = getLastSubaddress(accountIndex)
        Timber.tag("Wallet").i("${getNumSubaddresses(accountIndex) - 1} : ${subaddress}")
        return subaddress
    }

    external fun addSubaddress(accountIndex: Int, label: String?)
    private fun getLastSubaddress(accountIndex: Int): String {
        return getSubaddress(accountIndex, getNumSubaddresses(accountIndex) - 1)
    }

    val deviceType: Device
        get() {
            val device = getDeviceTypeJ()
            return Device.values()[device + 1] // mapping is monero+1=android
        }

    private external fun getDeviceTypeJ(): Int

    fun validateAddress(addressField: String): Boolean {
        return WalletManager.instance?.networkType?.value?.let {
            isAddressValid(
                addressField,
                it
            )
        } == true
    }

    enum class Device(val accountLookahead: Int, val subaddressLookahead: Int) {
        Device_Undefined(0, 0), Device_Software(50, 200), Device_Ledger(5, 20)

    }

    enum class StatusEnum {
        Status_Ok, Status_Error, Status_Critical
    }

    enum class ConnectionStatus {
        ConnectionStatus_Disconnected, ConnectionStatus_Connected, ConnectionStatus_WrongVersion
    }

    class Status internal constructor(status: Int, val errorString: String) {
        val status: StatusEnum
        var connectionStatus: ConnectionStatus? = null // optional

        init {
            this.status = StatusEnum.entries.toTypedArray()[status]
        }

        val isOk: Boolean
            get() = (status == StatusEnum.Status_Ok
                    && (connectionStatus == null || connectionStatus == ConnectionStatus.ConnectionStatus_Connected))

        override fun toString(): String {
            return "Wallet.Status: $status/$errorString/$connectionStatus"
        }
    }

    companion object {
        const val SWEEP_ALL = Long.MAX_VALUE
        private const val NEW_ACCOUNT_NAME = "Untitled account" // src/wallet/wallet2.cpp:941

        init {
            System.loadLibrary("anonero")
        }

        @JvmStatic
        external fun getDisplayAmount(amount: Long): String

        @JvmStatic
        external fun getAmountFromString(amount: String?): Long

        @JvmStatic
        external fun getAmountFromDouble(amount: Double): Long

        @JvmStatic
        external fun generatePaymentId(): String

        @JvmStatic
        external fun isPaymentIdValid(payment_id: String): Boolean

        @JvmStatic
        external fun isAddressValid(address: String?, networkType: Int): Boolean

        @JvmStatic
        external fun getPaymentIdFromAddress(address: String?, networkType: Int): String?

        @JvmStatic
        external fun getMaximumAllowedAmount(): Long
    }
}
