package com.health.app.ui.modules.continence

import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceSettings
import com.health.api.ContinenceWear
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
     * The Change sheet's starting point: [now], taking off what's on, as full as it is so far (and
     * leaked through if it has), and putting the same on again (or, with nothing known, the usual
     * or the last used).
     */
    fun changeDraft(wearing: ContinenceWearing?, settings: ContinenceSettings?, supplies: List<Supply>, recent: List<String>, now: Long): ContinenceEntry {
        val ids = wearable(supplies).mapNotNull { it.id }.toSet()
        val off = wearing?.supplyId?.takeIf { it in ids }
        val usual = settings?.defaultSupplyId?.takeIf { it in ids }
        val on = off ?: usual ?: recent.firstOrNull { it in ids }
        val wear = wearing?.wear?.takeIf { off != null && it.supplyId == off }
        return ContinenceEntry(
            now, ContinenceKind.PAD_CHANGE, supplyId = off, putOnSupplyId = on,
            fullness = wear?.fullness, leakedThrough = wear?.let { it.leakedThrough || it.fullness == Fullness.LEAKED } ?: false,
        )
    }

    /**
     * The Change sheet after "Save and change": taking off what [accident] was in, as it left it
     * (or, not noted there, as it was before), and putting the same on again (or the usual, or the
     * last used). Never before the accident, so the accident stays with what came off.
     */
    fun changeAfter(accident: ContinenceEntry, wearing: ContinenceWearing?, settings: ContinenceSettings?, supplies: List<Supply>, recent: List<String>, now: Long): ContinenceEntry {
        val ids = wearable(supplies).mapNotNull { it.id }.toSet()
        val off = accident.supplyId
        val on = off?.takeIf { it in ids } ?: settings?.defaultSupplyId?.takeIf { it in ids } ?: recent.firstOrNull { it in ids }
        val wear = wearing?.wear?.takeIf { off != null && it.supplyId == off }
        val fullness = (accident.fullness ?: wear?.fullness).takeIf { off != null }
        val through = off != null && (accident.leakedThrough || fullness == Fullness.LEAKED || wear?.leakedThrough == true)
        return ContinenceEntry(maxOf(now, accident.at), ContinenceKind.PAD_CHANGE, supplyId = off, putOnSupplyId = on, fullness = fullness, leakedThrough = through)
    }

    /** Whether a new accident [e] is in what's on now: its sheet says "In your …" and "How full it is now". */
    fun inWhatsOn(e: ContinenceEntry, wearing: ContinenceWearing?): Boolean =
        e.id == null && ContinenceText.isAccident(e.kind) && wearing?.supplyId != null && e.supplyId == wearing.supplyId

    /**
     * A new entry's product. An accident is in what's on now, as full as it is so far (so the person
     * only nudges it up); with nothing on, in nothing; with nothing known, in the usual ([usual]).
     * Kinds without a product have none (a catheter keeps its own); an entry being edited keeps its own.
     */
    fun entryDraft(e: ContinenceEntry, wearing: ContinenceWearing?, usual: String?): ContinenceEntry {
        if (e.id != null) return e
        if (!ContinenceText.usesProduct(e.kind)) return if (e.kind == ContinenceKind.CATHETER) e else e.copy(supplyId = null)
        val on = wearing?.supplyId
        return when {
            ContinenceText.isAccident(e.kind) && on != null -> {
                val fullness = e.fullness ?: wearing.wear?.takeIf { it.supplyId == on }?.fullness
                e.copy(supplyId = on, fullness = fullness, leakedThrough = e.leakedThrough || fullness == Fullness.LEAKED)
            }
            ContinenceText.isAccident(e.kind) && wearing?.nothingOn == true -> e.copy(supplyId = null)
            else -> e.copy(supplyId = e.supplyId ?: usual)
        }
    }

    /** What a one-tap accident is in: what's on now (nothing for other kinds). */
    fun quickSupply(kind: ContinenceKind, wearing: ContinenceWearing?): String? = wearing?.supplyId?.takeIf { ContinenceText.isAccident(kind) }

    /**
     * What's on, counting [accident] just logged in it (for the Change sheet that follows "Save and
     * change", until the diary reloads with it: then it's there once, by its time and kind).
     */
    fun withAccident(wearing: ContinenceWearing?, accident: ContinenceEntry?): ContinenceWearing? {
        val wear = wearing?.wear ?: return wearing
        if (accident == null || accident.supplyId != wear.supplyId || wear.accidents.any { it.at == accident.at && it.kind == accident.kind }) return wearing
        return wearing.copy(wear = wear.copy(accidents = (wear.accidents + accident).sortedBy { it.at }))
    }

    /** "1 accident", "3 accidents". */
    fun accidents(n: Int): String = if (n == 1) "1 accident" else "$n accidents"

    /** "2 accidents while you wore it": the Change sheet's reminder; null with none. */
    fun whileWorn(wear: ContinenceWear?): String? = wear?.accidents?.size?.takeIf { it > 0 }?.let { "${accidents(it)} while you wore it" }

    /** How full what's on is, as its gauge shows it: as noted, or dry with nothing logged since it went on (null with accidents but no fullness noted). */
    fun shownFullness(wear: ContinenceWear): Fullness? = wear.fullness ?: Fullness.DRY.takeIf { wear.accidents.isEmpty() }

    /** The gauge's word: "Wet", or "Dry so far" with nothing logged since it went on. */
    fun fullnessWord(wear: ContinenceWear): String? = wear.fullness?.label ?: "Dry so far".takeIf { wear.accidents.isEmpty() }

    /** Whether to mark "Leaked through" beside the gauge (at that level the gauge says it already). */
    fun marksLeak(wear: ContinenceWear): Boolean = wear.leakedThrough && wear.fullness != Fullness.LEAKED

    /** What's on, and how it is, in a line: "Wet · 2 accidents", "Dry so far", "Damp · leaked through · 1 accident". */
    fun stateLine(wear: ContinenceWear?): String? {
        if (wear == null) return null
        val n = wear.accidents.size
        return listOfNotNull(fullnessWord(wear), "leaked through".takeIf { marksLeak(wear) }, accidents(n).takeIf { n > 0 })
            .joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
    }

    /** The diary's quick buttons: without Change while the Wearing now card above has its own. */
    fun quickKinds(kinds: List<ContinenceKind>, changeAbove: Boolean): List<ContinenceKind> =
        if (changeAbove) kinds - ContinenceKind.PAD_CHANGE else kinds

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
