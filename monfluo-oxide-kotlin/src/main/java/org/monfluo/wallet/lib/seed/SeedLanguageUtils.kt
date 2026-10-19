package org.monfluo.wallet.lib.seed

interface SeedLanguageUtils<L : SeedLanguage> {
    companion object {
        fun getUtils(seedType: SeedType): SeedLanguageUtils<*> =
            when (seedType) {
                SeedType.MONERO -> MoneroSeedLanguage.Companion
                SeedType.POLYSEED -> PolyseedLanguage.Companion
            }
    }

    fun getWords(language: L): Set<String>

    // fun getLanguage(word: String): L?
}
