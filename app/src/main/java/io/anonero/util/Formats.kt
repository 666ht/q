package io.anonero.util

import android.icu.text.CompactDecimalFormat
import io.anonero.AnonConfig
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

object Formats {

    fun convertNumber(number: Number, locale: Locale): String? {
        val compactDecimalFormat =
            CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
        return compactDecimalFormat.format(number)
    }

    fun maskAmount(amount: Long): String {
        val plain = getDisplayAmount(amount)
        return "#".repeat(plain.length)
    }

    fun getDisplayAmount(amount: Long): String {
        return BigDecimal(amount)
            .scaleByPowerOfTen(-AnonConfig.XMR_DECIMALS)
            .setScale(12, RoundingMode.HALF_UP)
            .toPlainString()
    }

    fun formatTransactionTime(timestamp: Long, pattern: String = "dd/MM\nHH:mm"): String {
        val instant =
            Instant.ofEpochSecond(timestamp)
        val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofPattern(pattern)
        return dateTime.format(formatter)
    }

    fun formatLogTime(timestamp: Long): String {
        val instant =
            Instant.ofEpochMilli(timestamp)
        val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofPattern("MMM dd hh:mm")
        return dateTime.format(formatter)
    }

    fun formatFileSize(sizeInBytes: Long): String {
        if (sizeInBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
        val digitGroups = (log10(sizeInBytes.toDouble()) / log10(1024.0)).toInt()
        return String.format(
            Locale.US,
            "%.2f %s",
            sizeInBytes / 1024.0.pow(digitGroups.toDouble()),
            units[digitGroups]
        )
    }
}
