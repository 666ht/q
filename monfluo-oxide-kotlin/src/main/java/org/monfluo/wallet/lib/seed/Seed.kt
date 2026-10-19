package org.monfluo.wallet.lib.seed

abstract class Seed<L : SeedLanguage> internal constructor(
    val mnemonic: String,
    val language: L,
) {
    abstract val type: SeedType
}
