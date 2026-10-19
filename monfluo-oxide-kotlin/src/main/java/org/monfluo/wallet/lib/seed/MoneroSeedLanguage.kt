package org.monfluo.wallet.lib.seed

import java.util.EnumMap

enum class MoneroSeedLanguage : SeedLanguage {
    Chinese,
    English,
    Dutch,
    French,
    Spanish,
    German,
    Italian,
    Portuguese,
    Japanese,
    Russian,
    Esperanto,
    Lojban,
    DeprecatedEnglish,
    ;

    companion object : SeedLanguageUtils<MoneroSeedLanguage> {
        private val languageToWords: Map<MoneroSeedLanguage, Set<String>> = EnumMap(entries.associateWith { it.getWords().toSet() })

        override fun getWords(language: MoneroSeedLanguage): Set<String> = languageToWords[language]!!
    }

    external override fun getWords(): List<String>
}
