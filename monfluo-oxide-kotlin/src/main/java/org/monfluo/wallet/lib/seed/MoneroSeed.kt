package org.monfluo.wallet.lib.seed

class MoneroSeed private constructor(
    mnemonic: String,
    language: MoneroSeedLanguage,
) : Seed<MoneroSeedLanguage>(mnemonic, language) {
    override val type: SeedType = SeedType.MONERO

    companion object : SeedFactory<MoneroSeedLanguage, MoneroSeed> {
        @JvmStatic
        external override fun generateSeed(language: MoneroSeedLanguage): MoneroSeed

        @JvmStatic
        external override fun fromMnemonic(text: String): MoneroSeed?
    }
}
