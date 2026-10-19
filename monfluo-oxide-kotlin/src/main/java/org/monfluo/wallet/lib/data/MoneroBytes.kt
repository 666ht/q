package org.monfluo.wallet.lib.data

// 32 bytes, displayed as hex
// used e.g. for transaction and block hashes, as private/public keys, etc.
data class MoneroBytes(
    val hash: ByteArray,
) {
    override fun toString(): String = hash.toHexString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MoneroBytes

        if (!hash.contentEquals(other.hash)) return false

        return true
    }

    override fun hashCode(): Int = hash.contentHashCode()
}
