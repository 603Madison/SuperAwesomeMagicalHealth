package com.health.server.modules

import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.Fullness
import com.health.api.ProductStats
import com.health.api.Supply
import com.health.api.SupplyKind
import com.health.server.util.Days
import kotlin.math.roundToInt

/**
 * What's worn when, from the diary's changes: pure, so it can be tested on its own.
 *
 * A change ([ContinenceKind.PAD_CHANGE]) takes off [ContinenceEntry.supplyId] and puts on
 * [ContinenceEntry.putOnSupplyId], or nothing ([ContinenceEntry.nothingOn]). Changes from before
 * what went on was recorded put on a fresh one of what they took off, so older diaries pair up too.
 */
object Wear {
    /** A wear is only paired, and what's on only known, within this long: past it, the diary has a gap. */
    const val MAX_MINUTES = 24 * 60

    /** At least this many wears before a product has a usual wear time. */
    const val ENOUGH_WEARS = 3

    /** What a change put on. */
    fun putOn(e: ContinenceEntry): String? = when {
        e.kind != ContinenceKind.PAD_CHANGE -> null
        e.putOnSupplyId != null -> e.putOnSupplyId
        e.nothingOn -> null
        else -> e.supplyId
    }

    /** Whether a change says what's on after it: something went on, or everything came off. */
    fun settles(e: ContinenceEntry): Boolean = e.kind == ContinenceKind.PAD_CHANGE && (putOn(e) != null || e.nothingOn)

    /** A change that took something off ([off]), and the change that put it on ([on]) if that's in the diary and within a day. */
    data class Removal(val off: ContinenceEntry, val on: ContinenceEntry?) {
        val minutes: Int? get() = on?.let { ((off.at - it.at) / 60_000).toInt() }
    }

    /** Each change in [list] (any order) that took something off, earliest first, paired with the one that put it on. */
    fun removals(list: List<ContinenceEntry>): List<Removal> = pairs(list).map { (i, on) -> Removal(list[i], on) }

    /** [list] (in its order) with each change's [ContinenceEntry.wornMinutes] set from its pair. */
    fun withWorn(list: List<ContinenceEntry>): List<ContinenceEntry> {
        val worn = pairs(list).associate { (i, on) -> i to on?.let { ((list[i].at - it.at) / 60_000).toInt() } }
        return list.mapIndexed { i, e -> if (e.kind == ContinenceKind.PAD_CHANGE) e.copy(wornMinutes = worn[i]) else e }
    }

    private fun pairs(list: List<ContinenceEntry>): List<Pair<Int, ContinenceEntry?>> {
        val out = mutableListOf<Pair<Int, ContinenceEntry?>>()
        var current: ContinenceEntry? = null
        for (i in list.indices.sortedBy { list[it].at }) {
            val e = list[i]
            if (e.kind != ContinenceKind.PAD_CHANGE) continue
            if (e.supplyId != null) {
                val on = current?.takeIf { putOn(it) == e.supplyId && e.at - it.at in 0..MAX_MINUTES * 60_000L }
                out += i to on
            }
            if (settles(e)) current = e
        }
        return out
    }

    /** The kinds of entry that are accidents: they belong to whatever was on at the time. */
    val ACCIDENTS = setOf(ContinenceKind.LEAK, ContinenceKind.BOWEL_LEAK, ContinenceKind.BEDWETTING)

    /** An accident logged this soon before the product it names went on still belongs to it (logged first, put on just after). */
    const val GRACE_MS = 10 * 60_000L

    /**
     * One product's time on, before names are added: [start] to [end] (either null when the diary
     * doesn't say), [current] when it's what's on now. Its accidents are the ones from [from]
     * (exclusive) to [to] (inclusive).
     */
    data class Span(
        val supplyId: String,
        val on: ContinenceEntry?,
        val off: ContinenceEntry?,
        val start: Long?,
        val end: Long?,
        val current: Boolean,
        val from: Long,
        val to: Long,
        val accidents: List<ContinenceEntry> = emptyList(),
    ) {
        /** How full it was when it came off, or, until then, as the latest accident left it. */
        val fullness: Fullness? get() = off?.fullness ?: accidents.lastOrNull { it.fullness != null }?.fullness
        val leakedThrough: Boolean
            get() = (off?.let { it.leakedThrough || it.fullness == Fullness.LEAKED } ?: false) ||
                accidents.any { it.leakedThrough || it.fullness == Fullness.LEAKED }

        /** How long it was (or has been, up to [now]) worn. */
        fun minutes(now: Long): Int? = when {
            start == null -> off?.wornMinutes
            end != null -> ((end - start) / 60_000).toInt()
            current -> ((now - start) / 60_000).toInt().coerceAtLeast(0)
            else -> null
        }

        /** The time it's listed at: when it came off, now while it's on, or when it went on. */
        fun listedAt(now: Long): Long = end ?: if (current) now else start ?: to

        /** Whether it was on at any time from [dayFrom] (inclusive) to [dayTo] (exclusive). */
        fun overlaps(dayFrom: Long, dayTo: Long): Boolean = (start ?: to) < dayTo && to >= dayFrom
    }

    /**
     * Every product's time on in [list] (any order), earliest first, each with the accidents while it
     * was on. A wear runs from the change that put it on to the change that took it off. When the
     * next change takes off something else, or puts something on without taking anything off, the
     * wear ends there unrecorded ([Span.off] null); past [MAX_MINUTES] with no change, the diary has
     * a gap and its end isn't known. A change that takes off something whose putting on isn't in the
     * diary gathers the accidents since the change before (within a day).
     *
     * An accident belongs to the wear it happened in, and never to a product other than the one it
     * names. At a change's moment it belongs to what came off; one logged up to [GRACE_MS] before the
     * product it names went on belongs to that product.
     */
    fun spans(list: List<ContinenceEntry>, now: Long): List<Span> {
        val sorted = list.sortedBy { it.at }
        val max = MAX_MINUTES * 60_000L
        val out = mutableListOf<Span>()
        var current: ContinenceEntry? = null
        var previous: ContinenceEntry? = null
        // What's on ends here without its removal in the diary (or, past a day, at an unknown time).
        fun close(at: Long) {
            val on = current ?: return
            val id = putOn(on) ?: return
            val end = at.takeIf { it - on.at <= max }
            out += Span(id, on, null, on.at, end, current = false, from = on.at, to = end ?: (on.at + max))
        }
        for (e in sorted) {
            if (e.kind != ContinenceKind.PAD_CHANGE) continue
            val off = e.supplyId
            if (off != null) {
                val on = current?.takeIf { putOn(it) == off && e.at - it.at in 0..max }
                if (on == null) close(e.at)
                val from = on?.at ?: previous?.at?.takeIf { e.at - it in 0..max } ?: (e.at - max)
                out += Span(off, on, e, on?.at, e.at, current = false, from = from, to = e.at)
                current = null
            } else if (settles(e)) close(e.at).also { current = null }
            if (settles(e)) current = e
            previous = e
        }
        current?.let { on ->
            val id = putOn(on) ?: return@let
            if (now - on.at <= max) out += Span(id, on, null, on.at, null, current = true, from = on.at, to = maxOf(now, on.at) + 5 * 60_000L)
            else out += Span(id, on, null, on.at, null, current = false, from = on.at, to = on.at + max)
        }
        // Each accident to the wear it happened in: inside one before at its edge, then the latest to start.
        val byWear = mutableMapOf<Int, MutableList<ContinenceEntry>>()
        for (a in sorted.filter { it.kind in ACCIDENTS }) {
            val named = a.supplyId
            val best = out.indices.filter { i -> out[i].let { s -> named == null || named == s.supplyId } }.mapNotNull { i ->
                val s = out[i]
                when {
                    a.at > s.from && a.at <= s.to -> i to 0
                    named != null && a.at in (s.from - GRACE_MS)..s.to -> i to 1
                    else -> null
                }
            }.minWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenByDescending { out[it.first].from })?.first ?: continue
            byWear.getOrPut(best) { mutableListOf() } += a
        }
        return out.mapIndexed { i, s -> byWear[i]?.let { s.copy(accidents = it) } ?: s }
    }

    /** The latest change up to [now] that settles what's on, if it's recent enough to go on. */
    fun latest(list: List<ContinenceEntry>, now: Long): ContinenceEntry? =
        list.filter { settles(it) && it.at <= now + 5 * 60_000L }.maxByOrNull { it.at }?.takeIf { now - it.at <= MAX_MINUTES * 60_000L }

    /** How long [supplyId] is usually worn: the middle of its paired wears in [list], with enough of them. */
    fun typical(list: List<ContinenceEntry>, supplyId: String): Int? {
        val worn = removals(list).filter { it.off.supplyId == supplyId }.mapNotNull { it.minutes }.sorted()
        if (worn.size < ENOUGH_WEARS) return null
        val mid = worn.size / 2
        return if (worn.size % 2 == 1) worn[mid] else ((worn[mid - 1] + worn[mid]) / 2.0).roundToInt()
    }

    /**
     * When a change reminder is due: [hours] after [since]. If that's in the night ([nightFrom] to
     * [nightTo], minutes of the day) and the person would rather not hear at night, the end of that night.
     */
    fun dueAt(since: Long, hours: Int, days: Days, nightFrom: Int, nightTo: Int, atNight: Boolean): Long {
        val due = since + hours * 3_600_000L
        if (atNight || !inNight(days.minuteOfDay(due), nightFrom, nightTo)) return due
        val date = days.dateOf(due)
        // By the clock, so the night ends at the right time on the days the clocks change.
        val until = java.time.LocalTime.of(nightTo / 60 % 24, nightTo % 60)
        val end = days.at(date, until)
        return if (end > due) end else days.at(date.plusDays(1), until)
    }

    fun inNight(minute: Int, nightFrom: Int, nightTo: Int): Boolean =
        if (nightFrom > nightTo) minute >= nightFrom || minute < nightTo else minute in nightFrom until nightTo

    /**
     * How each product goes, from the changes in [list] that took it off at or after [since] (the
     * entries before are there to pair with). Products no longer in [supplies] are left out. A wear
     * is a night one when the middle of it is in the night ([night]), or, not knowing when it went
     * on, when it came off in the night.
     */
    fun stats(list: List<ContinenceEntry>, supplies: Map<String, Supply>, since: Long = Long.MIN_VALUE, night: (Long) -> Boolean = { false }): List<ProductStats> =
        removals(list).filter { it.off.at >= since }.groupBy { it.off.supplyId!! }.mapNotNull { (id, rs) ->
            val supply = supplies[id] ?: return@mapNotNull null
            val worn = rs.mapNotNull { it.minutes }
            val fullness = rs.mapNotNull { it.off.fullness }.groupingBy { it }.eachCount()
                .maxWithOrNull(compareBy<Map.Entry<Fullness, Int>> { it.value }.thenBy { it.key.level })?.key
            val through = rs.count { it.off.leakedThrough || it.off.fullness == Fullness.LEAKED }
            val night = rs.count { r -> r.on?.let { night((it.at + r.off.at) / 2) } ?: r.off.night }
            ProductStats(
                supplyId = id,
                name = supply.name,
                changes = rs.size,
                avgWornMinutes = worn.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                typicalFullness = fullness,
                leakedThroughPct = (through * 100.0 / rs.size).roundToInt(),
                nightShare = (night * 100.0 / rs.size).roundToInt() / 100.0,
                leakedThrough = through,
                kind = supply.kind,
            )
        }.sortedWith(compareByDescending<ProductStats> { it.changes }.thenBy { it.name })

    /** A product's kind in a word or two: "brief", "pull-up", "bed pad". */
    fun kindWord(kind: SupplyKind): String = kind.label.substringBefore(" / ").substringBefore(" (").lowercase()

    /** "7 h 10 min", "45 min", "6 h". */
    fun say(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    /** A usual wear time, roughly: to the quarter hour under two hours, the half hour after. */
    fun rough(minutes: Int): Int {
        val step = if (minutes < 120) 15 else 30
        return ((minutes / step.toDouble()).roundToInt() * step).coerceAtLeast(step)
    }

    /** "Night briefs: usually 7 h, often full, leaked through 1 in 12." */
    fun describe(p: ProductStats): String = listOfNotNull(
        p.avgWornMinutes?.let { "usually ${say(rough(it))}" },
        p.typicalFullness?.let { "often ${it.label.lowercase()}" },
        if (p.leakedThrough > 0) "leaked through ${p.leakedThrough} in ${p.changes}" else if (p.changes >= ENOUGH_WEARS) "never leaked through" else null,
        p.nightShare.takeIf { it >= 0.5 }?.let { "mostly at night" },
    ).joinToString(", ").ifEmpty { "${p.changes} ${if (p.changes == 1) "change" else "changes"}" }.let { "${p.name}: $it." }
}
