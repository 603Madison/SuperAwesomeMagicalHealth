package com.health.app.ui.modules.continence

import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceSettings
import com.health.api.ContinenceWearing
import com.health.api.Fields
import com.health.api.Fullness
import com.health.api.ProductStats
import com.health.api.Supply
import com.health.api.SupplyCategory
import com.health.api.SupplyKind
import kotlin.math.roundToInt

/**
 * What's being worn, and a change as one step, in plain Kotlin so it's tested without a phone:
 * the products a change offers and in what order, the Change sheet's starting point, and how a
 * change, a wear and a product's pattern read. Mirrors the web's lib/continence.ts.
 */
object WearText {
    /** The kinds of supply that are worn: what the Change sheet and "What are you wearing?" offer. */
    val WORN_KINDS = SupplyKind.entries.filter { it.category == SupplyCategory.CONTINENCE && it != SupplyKind.CATHETER } + SupplyKind.OTHER

    /** A product's kind in a word or two: "Brief", "Pull-up". */
    fun kindWord(kind: SupplyKind?): String? = kind?.takeIf { it in WORN_KINDS && it != SupplyKind.OTHER }?.label?.substringBefore(" / ")?.substringBefore(" (")

    /** The active supplies that are worn. */
    fun wearable(supplies: List<Supply>): List<Supply> = supplies.filter { it.active && it.id != null && it.kind in WORN_KINDS }

    /** Whether the diary keeps track of products for this person: the fields, or something already on. */
    fun tracksProducts(fields: Set<String>, wearing: ContinenceWearing?): Boolean =
        Fields.CONTINENCE_PROTECTION in fields || Fields.CONTINENCE_SUPPLIES in fields || wearing?.since != null

    /** "7 h 10 min", "45 min", "7 h". */
    fun wornFor(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return when {
            m < 60 -> "$m min"
            m % 60 == 0 -> "${m / 60} h"
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    /** A usual time, roughly: to the quarter hour under two hours, the half hour after. */
    fun rough(minutes: Int): Int {
        val step = if (minutes < 120) 15 else 30
        return ((minutes / step.toDouble()).roundToInt() * step).coerceAtLeast(step)
    }

    /** "7 h", "45 min": a short time for a row's title, to the nearest hour past one. */
    fun hoursShort(minutes: Int): String = if (minutes < 60) "${minutes.coerceAtLeast(0)} min" else "${(minutes / 60.0).roundToInt()} h"

    /** What a change put on: its put-on, or, for a change from before that was recorded, a fresh one of what it took off. */
    fun putOnOf(e: ContinenceEntry): String? = when {
        e.kind != ContinenceKind.PAD_CHANGE || e.nothingOn -> null
        else -> e.putOnSupplyId ?: e.supplyId
    }

    /** The products put on lately, newest first, each once. */
    fun recentPutOns(entries: List<ContinenceEntry>): List<String> = entries.sortedByDescending { it.at }.mapNotNull { putOnOf(it) }.distinct()

    /** What a change offers to put on: the usual first, then the last used, then the rest as they're listed. */
    fun putOnOrder(supplies: List<Supply>, usualId: String?, recent: List<String> = emptyList()): List<Supply> {
        val list = wearable(supplies)
        fun rank(s: Supply): Int = when {
            s.id == usualId -> -1_000
            s.id in recent -> recent.indexOf(s.id) - 500
            else -> list.indexOf(s)
        }
        return list.sortedBy(::rank)
    }

    /**
     * The Change sheet's starting point: [now], taking off what's on, and putting the same on again
     * (or, with nothing known, the usual or the last used).
     */
    fun changeDraft(wearing: ContinenceWearing?, settings: ContinenceSettings?, supplies: List<Supply>, recent: List<String>, now: Long): ContinenceEntry {
        val ids = wearable(supplies).mapNotNull { it.id }.toSet()
        val off = wearing?.supplyId?.takeIf { it in ids }
        val usual = settings?.defaultSupplyId?.takeIf { it in ids }
        val on = off ?: usual ?: recent.firstOrNull { it in ids }
        return ContinenceEntry(now, ContinenceKind.PAD_CHANGE, supplyId = off, putOnSupplyId = on)
    }

    /** "Put on" alone: what "What are you wearing?" logs. */
    fun putOnEntry(supplyId: String, now: Long): ContinenceEntry = ContinenceEntry(now, ContinenceKind.PAD_CHANGE, putOnSupplyId = supplyId)

    /** An existing change as the sheet edits it: an older change put on a fresh one of what it took off. */
    fun asChange(e: ContinenceEntry): ContinenceEntry = when {
        e.kind != ContinenceKind.PAD_CHANGE -> e.copy(kind = ContinenceKind.PAD_CHANGE)
        e.putOnSupplyId != null || e.nothingOn || e.supplyId == null -> e
        else -> e.copy(putOnSupplyId = e.supplyId)
    }

    /** What the sheet saves: nothing put on when it's "nothing for now". */
    fun finishChange(e: ContinenceEntry): ContinenceEntry = e.copy(
        kind = ContinenceKind.PAD_CHANGE,
        putOnSupplyId = e.putOnSupplyId.takeIf { !e.nothingOn },
        fullness = e.fullness.takeIf { e.supplyId != null },
        padWeightG = e.padWeightG.takeIf { e.supplyId != null },
        note = e.note?.trim()?.ifBlank { null },
    )

    /** "Night briefs → Day pants", "Night briefs → nothing for now", "Put on Day pants"; "Change" with no products named. */
    fun changeTitle(e: ContinenceEntry, supplies: List<Supply>): String {
        fun name(id: String) = supplies.firstOrNull { it.id == id }?.name ?: "A product"
        val off = e.supplyId
        val on = putOnOf(e)
        return when {
            e.nothingOn -> if (off != null) "${name(off)} → nothing for now" else "Took it off, nothing on for now"
            off != null && on != null -> "${name(off)} → ${name(on)}"
            on != null -> "Put on ${name(on)}"
            else -> "Change"
        }
    }

    /** What a change says after its title: "Full", "worn 7 h", "leaked through". */
    fun changeDetail(e: ContinenceEntry): List<String> = listOfNotNull(
        e.fullness?.label,
        e.wornMinutes?.let { "worn ${wornFor(it)}" },
        "leaked through".takeIf { e.leakedThrough && e.fullness != Fullness.LEAKED },
    )

    /** The diary's line for a change: "Night briefs → Day pants · Full · worn 7 h". */
    fun changeLine(e: ContinenceEntry, supplies: List<Supply>): String = (listOf(changeTitle(e, supplies)) + changeDetail(e)).joinToString(" · ")

    /** How long it's been on, as of [now]. */
    fun onFor(w: ContinenceWearing, now: Long): Int = w.since?.let { ((now - it) / 60_000).toInt().coerceAtLeast(0) } ?: 0

    /** "Since 11:20 pm · 7 h 10 min"; [clock] says a time the person's way. */
    fun sinceLine(w: ContinenceWearing, now: Long, clock: (Long) -> String): String =
        w.since?.let { "Since ${clock(it)} · ${wornFor(onFor(w, now))}" }.orEmpty()

    /** "You usually change these after about 6 h": with enough wears to go on. */
    fun usualLine(w: ContinenceWearing): String? =
        w.typicalMinutes?.takeIf { w.supplyId != null }?.let { "You usually change these after about ${wornFor(rough(it))}" }

    /** Whether a change reminder is due now (Up next's "Time to change"). */
    fun changeDue(w: ContinenceWearing?, now: Long): Boolean = w?.supplyId != null && w.changeDueAt != null && w.changeDueAt!! <= now

    /** "Time to change: Night briefs, 7 h": Up next's row ("briefs, 7 h" kept together, so the hours never sit on a line alone). */
    fun dueTitle(w: ContinenceWearing, now: Long): String =
        "Time to change: ${w.name ?: "what you have on"},\u00A0${hoursShort(onFor(w, now)).replace(' ', '\u00A0')}"

    /** "usually 7 h, often full, leaked through 1 in 12": a product's pattern, without its name. */
    fun productPattern(p: ProductStats): String = listOfNotNull(
        p.avgWornMinutes?.let { "usually ${wornFor(rough(it))}" },
        p.typicalFullness?.let { "often ${it.label.lowercase()}" },
        if (p.leakedThrough > 0) "leaked through ${p.leakedThrough} in ${p.changes}" else if (p.changes >= 3) "never leaked through" else null,
        "mostly at night".takeIf { p.nightShare >= 0.5 },
    ).joinToString(", ").ifEmpty { "${p.changes} ${if (p.changes == 1) "change" else "changes"}" }

    /** "Night briefs: usually 7 h, often full, leaked through 1 in 12". */
    fun productLine(p: ProductStats): String = "${p.name}: ${productPattern(p)}"
}
