package com.health.server.care

import com.health.server.modules.EntryKind
import com.health.server.modules.EntryStore
import kotlinx.serialization.KSerializer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Settings-like entries kept once per person (sealed like any entry), on a fixed day. */
internal object Singletons {
    private val DAY: LocalDate = LocalDate.of(1970, 1, 1)

    suspend fun <T> get(entries: EntryStore, userId: String, kind: EntryKind, serializer: KSerializer<T>): T? =
        entries.list(userId, kind, DAY, DAY, serializer).lastOrNull()?.value

    suspend fun <T> put(entries: EntryStore, userId: String, kind: EntryKind, value: T, serializer: KSerializer<T>) {
        entries.deleteDay(userId, kind, DAY)
        entries.put(userId, kind, null, DAY, 0L, value, serializer)
    }
}

private val SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val LONG = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

/** "3 Nov". */
internal fun shortDate(date: String?): String = date?.let { runCatching { LocalDate.parse(it).format(SHORT) }.getOrNull() } ?: "–"

/** "3 November 2026". */
internal fun longDate(date: String?): String = date?.let { runCatching { LocalDate.parse(it).format(LONG) }.getOrNull() } ?: "–"

/** A number without needless decimals: 4, 4.5, 0.25. */
internal fun num(value: Double): String {
    if (abs(value - value.roundToLong()) < 1e-9) return value.roundToLong().toString()
    return "%.2f".format(Locale.ENGLISH, value).trimEnd('0').trimEnd('.')
}

internal fun plural(n: Int, one: String, many: String = "${one}s") = "$n ${if (n == 1) one else many}"
