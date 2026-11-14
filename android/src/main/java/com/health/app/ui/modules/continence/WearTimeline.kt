package com.health.app.ui.modules.continence

import com.health.api.ContinenceDiary
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceWear
import com.health.api.Fullness
import com.health.api.LeakContents
import com.health.app.ui.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The diary's Today, grouped (GET /api/continence/diary): each product worn as one row with the
 * accidents while it was on beneath it, and everything else on its own; with an older server, each
 * of today's entries on its own, as before. In plain Kotlin so it's tested without a phone.
 */
object WearTimeline {
    /** One row of Today, newest first. */
    sealed interface Row {
        val at: Long
    }

    /** A product worn: its name, how it was when it came off, and its accidents beneath (oldest first). */
    data class Worn(val wear: ContinenceWear, override val at: Long) : Row {
        /** It's what's on now (shown here only when the Wearing now card is hidden). */
        val onNow: Boolean get() = wear.current

        /** What a tap on its name opens: the change that took it off, or else the one that put it on. */
        val opens: ContinenceEntry? get() = wear.off ?: wear.on

        /**
         * The change that put it on, when that took nothing off: no other row has it, so it gets a
         * quiet line of its own ("Put on at 7:48 PM"). A change that also took something off heads
         * the row before.
         */
        val putOnAlone: ContinenceEntry? get() = wear.on?.takeIf { it.supplyId == null && it.kind == ContinenceKind.PAD_CHANGE }
    }

    /** An entry on its own: a toilet trip, a bowel movement, an accident with nothing on… */
    data class Single(val entry: ContinenceEntry) : Row {
        override val at: Long get() = entry.at
    }

    /** Whether [diary] is today's, so it drives the rows. */
    fun grouped(diary: ContinenceDiary?, today: LocalDate): Boolean = diary != null && diary.date == today.toString()

    /**
     * Today's rows, newest first. From the grouped [diary] when it's today's: each wear with its
     * accidents oldest first, and what's on now left out when the Wearing now card above shows it
     * ([currentAbove]). Without it, each of [todays] entries on its own.
     */
    fun rows(diary: ContinenceDiary?, today: LocalDate, todays: List<ContinenceEntry>, currentAbove: Boolean): List<Row> {
        if (diary == null || !grouped(diary, today)) return todays.sortedByDescending { it.at }.map { Single(it) }
        return diary.items.sortedByDescending { it.at }.mapNotNull { item ->
            val wear = item.wear
            val entry = item.entry
            when {
                wear != null -> if (wear.current && currentAbove) null else Worn(wear.copy(accidents = wear.accidents.sortedBy { it.at }), item.at)
                entry != null -> Single(entry)
                else -> null
            }
        }
    }

    /** A worn product's name. */
    fun title(w: ContinenceWear): String = w.name ?: "A product"

    /**
     * The line under a worn product's name: "Worn 3 h 12 min · then Day pants", "Worn 7 h · leaked
     * through · then nothing on · skin a little sore" (with whatever else the change noted); "On at
     * 7:48 PM" when taking it off isn't in the diary; "Since 7:48 PM · 2 h 10 min" while it's on.
     * [clock] says a time the person's way.
     */
    fun subtitle(w: ContinenceWear, clock: (Long) -> String): String {
        val off = w.off
        return when {
            w.current -> listOfNotNull(w.start?.let { "Since ${clock(it)}" }, w.minutes?.let { WearText.wornFor(it) }).joinToString(" · ")
            off == null -> w.start?.let { "On at ${clock(it)}" } ?: "Taking it off isn't in the diary"
            else -> listOfNotNull(
                w.minutes?.let { "worn ${WearText.wornFor(it)}" },
                "leaked through".takeIf { WearText.marksLeak(w) },
                if (off.nothingOn) "then nothing on" else w.nextName?.let { "then $it" },
                // The rest of what the change noted: skin, clothes or bedding changed, grams lost…
                ContinenceText.detail(off.copy(supplyId = null, count = null, fullness = null, leakedThrough = false, night = false)).ifEmpty { null },
            ).joinToString(" · ").ifEmpty { "Taken off" }.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * How full a worn product shows, and the word beside it: as it came off; while it's on, as it is
     * so far ("Dry so far" with nothing logged). None when taking it off isn't in the diary.
     */
    fun fullness(w: ContinenceWear): Pair<Fullness, String>? = when {
        w.current -> WearText.shownFullness(w)?.let { f -> f to (WearText.fullnessWord(w) ?: f.label) }
        w.off == null -> null
        else -> w.fullness?.let { it to it.label }
    }

    /** An accident beneath its product, in short: "Small · cough, lifting", "Stool · leaked through". */
    fun accidentDetail(a: ContinenceEntry): String = ContinenceText.detail(
        ContinenceEntry(
            a.at, a.kind,
            contents = a.contents.takeIf { it != LeakContents.URINE || a.kind == ContinenceKind.BOWEL_LEAK },
            leak = a.leak, triggers = a.triggers, activity = a.activity,
            leakedThrough = a.leakedThrough || a.fullness == Fullness.LEAKED,
        ),
    )

    /** The day of a time in a list, when it isn't today: "Yesterday", "Wed". */
    fun dayOf(at: Long, zone: ZoneId, today: LocalDate): String? = when (val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()) {
        today -> null
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("EEE").format(date)
    }

    /** A time in a list: "11:40 PM" today, "Yesterday 11:40 PM", "Wed 11:40 PM". */
    fun stamp(at: Long, zone: ZoneId, today: LocalDate): String =
        listOfNotNull(dayOf(at, zone, today), Format.timeOfDay(at, zone)).joinToString(" ")

    /** The quiet last line of a wear put on with nothing taken off: "Put on at 7:48 PM", "Put on yesterday at 11:20 PM". */
    fun putOnLine(at: Long, zone: ZoneId, today: LocalDate): String {
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val time = Format.timeOfDay(at, zone)
        return when (date) {
            today -> "Put on at $time"
            today.minusDays(1) -> "Put on yesterday at $time"
            else -> "Put on ${DateTimeFormatter.ofPattern("EEEE").format(date)} at $time"
        }
    }

    /** A change that took something off; putting something on alone isn't one. */
    fun isChange(e: ContinenceEntry): Boolean = e.kind == ContinenceKind.PAD_CHANGE && e.supplyId != null

    /** Today's counts, at the top of its card. */
    data class Counts(val trips: Int, val leaks: Int, val bowel: Int, val bowelLeaks: Int, val changes: Int, val catheters: Int)

    fun counts(entries: List<ContinenceEntry>): Counts = Counts(
        trips = entries.count { it.kind == ContinenceKind.URINATION },
        leaks = entries.count { it.kind == ContinenceKind.LEAK || it.kind == ContinenceKind.BEDWETTING },
        bowel = entries.count { it.kind == ContinenceKind.BOWEL },
        bowelLeaks = entries.count { it.kind == ContinenceKind.BOWEL_LEAK },
        changes = entries.count(::isChange),
        catheters = entries.count { it.kind == ContinenceKind.CATHETER },
    )

    /** The quieter counts under the three big ones: "1 bowel accident · 2 changes". */
    fun moreCounts(c: Counts): String = listOfNotNull(
        c.bowelLeaks.takeIf { it > 0 }?.let { "$it ${if (it == 1) "bowel accident" else "bowel accidents"}" },
        c.changes.takeIf { it > 0 }?.let { "$it ${if (it == 1) "change" else "changes"}" },
        c.catheters.takeIf { it > 0 }?.let { "$it ${if (it == 1) "catheter" else "catheters"}" },
    ).joinToString(" · ")
}
