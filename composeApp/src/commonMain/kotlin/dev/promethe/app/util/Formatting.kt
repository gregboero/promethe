package dev.promethe.app.util

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Format epoch millis as "HH:mm" in the system's local time zone.
 */
fun formatTime(epochMillis: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMillis)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.hour.pad()}:${local.minute.pad()}"
}

/**
 * Format epoch millis as "HH:mm:ss" in the system's local time zone.
 */
fun formatTimeWithSeconds(epochMillis: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMillis)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.hour.pad()}:${local.minute.pad()}:${local.second.pad()}"
}

/**
 * Format epoch millis as "MMM d, yyyy  HH:mm" in the system's local time zone.
 */
fun formatDateTime(epochMillis: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMillis)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val monthName =
        local.month.name
            .take(3)
            .lowercase()
            .replaceFirstChar { it.uppercaseChar() }
    return "$monthName ${local.dayOfMonth}, ${local.year}  ${local.hour.pad()}:${local.minute.pad()}"
}

/**
 * Format a Double with a fixed number of decimal places.
 * Replaces JVM-only `"%.Xf".format(value)`.
 */
fun Double.fmt(decimals: Int): String {
    if (decimals <= 0) return this.toLong().toString()
    val factor = pow10(decimals)
    val rounded = kotlin.math.round(this * factor) / factor
    val parts = rounded.toString().split(".")
    val intPart = parts[0]
    val fracPart = if (parts.size > 1) parts[1] else ""
    return "$intPart.${fracPart.padEnd(decimals, '0').take(decimals)}"
}

/**
 * Format a Float with a fixed number of decimal places.
 */
fun Float.fmt(decimals: Int): String = this.toDouble().fmt(decimals)

/**
 * Format a Long with thousands separators (comma-grouped).
 * Replaces JVM-only `"%,d".format(value)`.
 */
fun Long.fmtGrouped(): String {
    val s = this.toString()
    val negative = s.startsWith("-")
    val digits = if (negative) s.substring(1) else s
    val result =
        buildString {
            digits.reversed().forEachIndexed { i, c ->
                if (i > 0 && i % 3 == 0) append(',')
                append(c)
            }
        }.reversed()
    return if (negative) "-$result" else result
}

/**
 * Format an Int with thousands separators (comma-grouped).
 */
fun Int.fmtGrouped(): String = this.toLong().fmtGrouped()

private fun Int.pad(): String = this.toString().padStart(2, '0')

private fun pow10(n: Int): Double {
    var result = 1.0
    repeat(n) { result *= 10.0 }
    return result
}
