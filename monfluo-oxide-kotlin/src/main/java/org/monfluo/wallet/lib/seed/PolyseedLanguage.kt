package org.monfluo.wallet.lib.seed

import java.util.EnumMap

enum class PolyseedLanguage : SeedLanguage {
    English,
    Spanish,
    French,
    Italian,
    Japanese,
    Korean,
    Czech,
    Portuguese,
    ChineseSimplified,
    ChineseTraditional,
    ;

    companion object : SeedLanguageUtils<PolyseedLanguage> {
        private val languageToWords: Map<PolyseedLanguage, Set<String>> = EnumMap(entries.associateWith { it.getWords().toSet() })

        override fun getWords(language: PolyseedLanguage): Set<String> = languageToWords[language]!!
    }

    external override fun getWords(): List<String>
}
