package org.monfluo.wallet.lib.seed

sealed interface SeedLanguage {
    fun getWords(): List<String>
}
