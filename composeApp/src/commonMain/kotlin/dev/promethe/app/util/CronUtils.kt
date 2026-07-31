package dev.promethe.app.util

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Multiplatform cron utilities for the UI layer.
 * Provides next-run computation without depending on java.time.
 */
object CronUtils {
    /**
     * Compute the next [count] run times for a cron expression, starting from [fromInstant].
     * Returns formatted date strings like "11 juin 2026 à 9h".
     */
    fun computeNextRuns(
        cron: String,
        count: Int = 3,
        fromInstant: Instant = Instant.fromEpochMilliseconds(kotlin.time.Clock.System.now().toEpochMilliseconds()),
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<String> {
        val results = mutableListOf<String>()
        var cursor = fromInstant.plus(1, DateTimeUnit.MINUTE, timeZone)
        // Truncate to minute boundary
        val cursorDt = cursor.toLocalDateTime(timeZone)
        cursor = LocalDateTime(cursorDt.year, cursorDt.month, cursorDt.dayOfMonth, cursorDt.hour, cursorDt.minute, 0, 0)
            .toInstant(timeZone)

        val maxScans = 525_600 // ~1 year in minutes
        var scanned = 0

        while (results.size < count && scanned < maxScans) {
            val dt = cursor.toLocalDateTime(timeZone)
            if (matchesCron(cron, dt)) {
                results.add(formatDateTime(dt))
            }
            cursor = cursor.plus(1, DateTimeUnit.MINUTE, timeZone)
            scanned++
        }
        return results
    }

    /**
     * Check if a 5-field cron expression matches the given LocalDateTime.
     */
    fun matchesCron(
        expression: String,
        dt: LocalDateTime,
    ): Boolean {
        val parts = expression.trim().split(Regex("\\s+"))
        if (parts.size != 5) return false

        val minute = dt.minute
        val hour = dt.hour
        val dayOfMonth = dt.dayOfMonth
        val month = dt.monthNumber
        val dayOfWeek = dt.dayOfWeek.isoDayNumber % 7 // 0=Sunday

        return matchesField(parts[0], minute, 0, 59) &&
            matchesField(parts[1], hour, 0, 23) &&
            matchesField(parts[2], dayOfMonth, 1, 31) &&
            matchesField(parts[3], month, 1, 12) &&
            matchesField(parts[4], dayOfWeek, 0, 6)
    }

    private fun matchesField(
        field: String,
        value: Int,
        min: Int,
        max: Int,
    ): Boolean {
        if (field == "*") return true

        return field.split(",").any { part ->
            when {
                part.contains("/") -> {
                    val (range, stepStr) = part.split("/", limit = 2)
                    val step = stepStr.toIntOrNull() ?: return@any false
                    if (step <= 0) return@any false
                    val (start, end) = if (range == "*") {
                        min to max
                    } else if (range.contains("-")) {
                        val (s, e) = range.split("-", limit = 2)
                        (s.toIntOrNull() ?: min) to (e.toIntOrNull() ?: max)
                    } else {
                        val s = range.toIntOrNull() ?: return@any false
                        s to max
                    }
                    value in start..end && (value - start) % step == 0
                }

                part.contains("-") -> {
                    val (s, e) = part.split("-", limit = 2)
                    val start = s.toIntOrNull() ?: return@any false
                    val end = e.toIntOrNull() ?: return@any false
                    value in start..end
                }

                else -> {
                    part.toIntOrNull() == value
                }
            }
        }
    }

    // Locale-neutral English month abbreviations — this is a non-composable
    // util, so it cannot resolve localized resources; screens needing fully
    // localized dates should format at the composable level.
    private val MONTH_NAMES = listOf(
        "Jan",
        "Feb",
        "Mar",
        "Apr",
        "May",
        "Jun",
        "Jul",
        "Aug",
        "Sep",
        "Oct",
        "Nov",
        "Dec",
    )

    private fun formatDateTime(dt: LocalDateTime): String {
        val monthName = MONTH_NAMES[dt.monthNumber - 1]
        return "${dt.dayOfMonth} $monthName ${dt.year}, ${dt.hour}:${dt.minute.toString().padStart(2, '0')}"
    }
}
