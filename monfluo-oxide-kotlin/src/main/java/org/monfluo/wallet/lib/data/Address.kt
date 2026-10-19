package org.monfluo.wallet.lib.data

class Address private constructor(
    val value: String,
    val network: Network,
) {
    companion object {
        @JvmStatic
        external fun fromString(addressValue: String): Address?
    }
}
