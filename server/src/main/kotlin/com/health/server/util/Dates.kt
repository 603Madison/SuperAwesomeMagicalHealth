package com.health.server.util

import com.health.server.ApiError
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** A person's days: where they begin and end in their time zone. */
class Days(val zone: ZoneId) {
    fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** When [date] begins. */
    fun start(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** When [date] ends (the next day begins). */
    fun end(date: LocalDate): Long = start(date.plusDays(1))

    /** The hour of the day (0–23) at [ms]. */
    fun hourOf(ms: Long): Int = Instant.ofEpochMilli(ms).atZone(zone).hour

    /** Minutes after midnight at [ms], as a clock shows them. */
    fun minuteOfDay(ms: Long): Int = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour * 60 + it.minute }

    fun today(now: Long): LocalDate = dateOf(now)

    /** "YYYY-MM-DD", or "today" / "yesterday". */
    fun parse(text: String, now: Long): LocalDate = when (text) {
        "today" -> today(now)
        "yesterday" -> today(now).minusDays(1)
        else -> parseDate(text)
    }

    companion object {
        fun zoneOf(id: String?): ZoneId = id?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    }
}

fun parseDate(text: String): LocalDate = try {
    require(text.length == 10)
    LocalDate.parse(text)
} catch (e: IllegalArgumentException) {
    throw ApiError.badRequest("Dates look like 2026-01-31")
} catch (e: DateTimeParseException) {
    throw ApiError.badRequest("Dates look like 2026-01-31")
}
