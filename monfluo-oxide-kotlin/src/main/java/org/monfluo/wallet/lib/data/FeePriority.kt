package org.monfluo.wallet.lib.data

enum class FeePriority {
    Unimportant,
    Normal,
    Elevated,
    Priority,
    ;

    companion object {
        fun fromInt(value: Int) = entries.first { it.ordinal == value }
    }
}
