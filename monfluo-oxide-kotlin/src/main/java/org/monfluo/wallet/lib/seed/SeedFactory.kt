package org.monfluo.wallet.lib.seed

interface SeedFactory<L : SeedLanguage, S : Seed<L>> {
    companion object {
        fun getFactory(seedType: SeedType): SeedFactory<*, *> =
            when (seedType) {
                SeedType.MONERO -> MoneroSeed.Companion
                SeedType.POLYSEED -> Polyseed.Companion
            }
    }

    fun generateSeed(language: L): S

    fun fromMnemonic(text: String): S?
}
