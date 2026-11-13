package com.health.app.ui.modules.continence

import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceProgress
import com.health.api.Fields
import com.health.api.Fullness
import com.health.api.LeakContents
import com.health.api.ProgressMetric
import com.health.api.Supply
import com.health.api.SupplyCategory
import com.health.api.SupplyKind
import com.health.api.SupplyStatus
import com.health.api.Verdict
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The bladder and bowel diary's words and choices, in plain Kotlin so they're tested without a
 * phone: which kinds and fields a form offers, how an entry reads in a list, the signs of an
 * infection and what to say about them, supplies, and progress put into words.
 */
object ContinenceText {
    /** What the server says when signs of a urine infection are logged: kept word for word, and always shown. */
    const val INFECTION_NUDGE =
        "Burning, cloudy or strong-smelling urine, or going much more often, are worth telling a doctor or nurse about soon, and today if you have a fever or back pain."

    val UTI_SIGNS = listOf("burning", "cloudy", "strong smell", "blood", "more often", "fever", "back pain", "feeling unwell")

    val ACTIVITIES = listOf(
        "standing up", "walking", "coughing", "sneezing", "laughing", "lifting", "exercise", "bending", "sitting", "asleep",
        "on the way to the toilet", "getting out of the car",
    )

    val LOCATIONS = listOf("home", "work", "out", "in bed", "travelling", "at the gym")

    /** Seconds of warning, from "none" to "a few minutes", as quick choices. */
    val WARNINGS = listOf(0 to "None", 5 to "A few seconds", 30 to "Under a minute", 90 to "A minute or two", 300 to "Several minutes")

    val SKIN = listOf("Fine", "A little sore", "Sore", "Very sore")

    /** The kinds a form offers: the core ones always, the rest when their field is on (and any entry's own kind). */
    fun kinds(fields: Set<String>, current: ContinenceKind? = null): List<ContinenceKind> = ContinenceKind.entries.filter { k ->
        k == current || when (k) {
            ContinenceKind.BOWEL_LEAK -> Fields.CONTINENCE_BOWEL_ACCIDENTS in fields
            ContinenceKind.CATHETER -> Fields.CONTINENCE_CATHETER in fields
            ContinenceKind.PELVIC_FLOOR -> Fields.CONTINENCE_PELVIC_FLOOR in fields
            else -> true
        }
    }

    /** A leak of some kind: what the accident fields are for. */
    fun isAccident(kind: ContinenceKind) = kind == ContinenceKind.LEAK || kind == ContinenceKind.BOWEL_LEAK || kind == ContinenceKind.BEDWETTING

    /** Kinds where a product is worn or used, so the supply and fullness fields fit. */
    fun usesProduct(kind: ContinenceKind) = isAccident(kind) || kind == ContinenceKind.PAD_CHANGE

    /** What leaked, for a kind: stool or wind for bowel accidents, any for the rest. */
    fun contentsFor(kind: ContinenceKind): List<LeakContents> =
        if (kind == ContinenceKind.BOWEL_LEAK) listOf(LeakContents.STOOL, LeakContents.WIND, LeakContents.BOTH) else LeakContents.entries

    /** The supplies that fit a kind of entry: catheters for a catheter, worn products otherwise. */
    fun suppliesFor(kind: ContinenceKind, supplies: List<Supply>): List<Supply> = supplies.filter { s ->
        s.active && s.id != null && when (kind) {
            ContinenceKind.CATHETER -> s.kind == SupplyKind.CATHETER
            else -> s.kind.category == SupplyCategory.CONTINENCE && s.kind != SupplyKind.CATHETER
        }
    }

    /** "Overnight pads (Super)": a supply as a picker shows it. */
    fun supplyName(s: Supply): String = s.name + (s.absorbency?.let { " ($it)" } ?: "")

    /** The weight lost, as the server will work it out: wet weight less the product's dry weight. */
    fun loss(padWeightG: Double?, supply: Supply?): Double? {
        val wet = padWeightG ?: return null
        val dry = supply?.dryWeightG ?: return null
        return ((wet - dry).coerceAtLeast(0.0) * 10).roundToInt() / 10.0
    }

    fun fullness(level: Int): Fullness? = Fullness.entries.firstOrNull { it.level == level }

    /** Seconds of warning in words. */
    fun warning(seconds: Int): String = when {
        seconds <= 0 -> "no warning"
        seconds < 60 -> "$seconds s warning"
        seconds % 60 == 0 -> "${seconds / 60} min warning"
        else -> "${seconds / 60} min ${seconds % 60} s warning"
    }

    /** The nearest of [WARNINGS] to a value, for showing an existing entry's choice. */
    fun nearestWarning(seconds: Int?): Int? = seconds?.let { s -> WARNINGS.minByOrNull { abs(it.first - s) }?.first }

    /** Whether any of [entries] logged signs of a urine infection. */
    fun infectionSigns(entries: List<ContinenceEntry>): Boolean = entries.any { it.utiSigns.isNotEmpty() }

    /** "urgency strong · 250 ml", "Small · cough, sneeze · Pad, wet", "type 4". */
    fun detail(e: ContinenceEntry, supplies: List<Supply> = emptyList()): String = listOfNotNull(
        e.contents?.label?.takeIf { e.kind != ContinenceKind.URINATION },
        e.volumeMl?.let { "$it ml" },
        e.urgency?.takeIf { it > 0 }?.let { "urgency ${URGENCY[it.coerceIn(0, 3)].lowercase()}" },
        e.leak?.label,
        (e.triggers + e.activity).distinct().takeIf { it.isNotEmpty() }?.joinToString(", "),
        e.awareness?.label?.lowercase(),
        e.reachedToilet?.label?.lowercase()?.takeIf { e.kind != ContinenceKind.URINATION || e.reachedToilet != com.health.api.ReachedToilet.YES },
        e.warningSeconds?.let { warning(it) },
        e.bristol?.let { "type $it" },
        e.reps?.let { "$it reps" + (e.holdSeconds?.let { s -> " of ${s}s" } ?: "") },
        e.supplyId?.let { id -> supplies.firstOrNull { it.id == id }?.name }?.let { name -> if ((e.count ?: 1) > 1) "${e.count} × $name" else name },
        e.count?.takeIf { e.supplyId == null && it > 1 }?.let { "$it products" },
        e.fullness?.label?.lowercase(),
        e.lossG?.let { "${fmt(it)} g lost" },
        "leaked through".takeIf { e.leakedThrough && e.fullness != Fullness.LEAKED },
        "clothes changed".takeIf { e.changedClothes },
        "bedding changed".takeIf { e.changedBedding },
        e.skin?.takeIf { it > 0 }?.let { "skin ${SKIN[it.coerceIn(0, 3)].lowercase()}" },
        e.utiSigns.takeIf { it.isNotEmpty() }?.joinToString(", "),
        e.location,
        "at night".takeIf { e.night },
        "pain".takeIf { e.pain },
        "blood".takeIf { e.blood },
        e.tags.takeIf { it.isNotEmpty() }?.joinToString(", ") { "#$it" },
    ).joinToString(" · ")

    /** A number as people read it: "2", "0.6", "1.25". */
    fun fmt(v: Double): String = when {
        v == v.roundToInt().toDouble() -> v.roundToInt().toString()
        abs(v) < 1 && (v * 100).roundToInt() % 10 != 0 -> String.format(java.util.Locale.ROOT, "%.2f", v)
        else -> String.format(java.util.Locale.ROOT, "%.1f", v)
    }

    /** Low supplies to show on Today and in the diary: the continence ones (and wipes, creams). */
    fun lowFor(statuses: List<SupplyStatus>, categories: Set<SupplyCategory>): List<SupplyStatus> =
        statuses.filter { it.low && it.supply.kind.category in categories }

    /** "12 left · about 1.5 a day · 8 days", for a supply's row. */
    fun stock(s: SupplyStatus): String = listOfNotNull(
        if (s.supply.reusable) "reusable" else "${s.supply.onHand} left",
        s.perDay?.let { "about ${fmt(it)} a day" },
        s.daysLeft?.takeIf { !s.supply.reusable }?.let { "$it ${if (it == 1) "day" else "days"} left" },
    ).joinToString(" · ")
}

/** Progress put into words. */
object ProgressText {
    /** Whether there's enough in the diary for any verdict at all. */
    fun enough(p: ContinenceProgress): Boolean = p.verdict != Verdict.NOT_ENOUGH

    /** "0.6 a day", "12 g a day", "2.1": a metric's value with its unit. */
    fun value(v: Double?, metric: ProgressMetric): String {
        if (v == null) return "–"
        val unit = metric.unit.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        return ContinenceText.fmt(v) + unit
    }

    /** "1.2 → 0.6": before and after, for a row. */
    fun change(metric: ProgressMetric): String = "${value(metric.before, metric)} → ${value(metric.after, metric)}"

    /** What a metric's change means, in words a screen reader says too. */
    fun verdictWords(v: Verdict): String = when (v) {
        Verdict.IMPROVING -> "better"
        Verdict.STEADY -> "about the same"
        Verdict.WORSENING -> "harder"
        Verdict.NOT_ENOUGH -> "not enough yet"
    }

    /** Weeks with something logged, for the chart. */
    fun loggedWeeks(p: ContinenceProgress) = p.weeks.count { it.days > 0 }

    /** Said when there's not enough to tell: how much more would do it. */
    fun notEnough(p: ContinenceProgress): String {
        val logged = loggedWeeks(p)
        return when {
            logged == 0 -> "Nothing in the diary yet. Log for a few days, and this will start to show how things are going."
            else -> "Not enough to tell yet: this compares your last ${p.recentWeeks} ${if (p.recentWeeks == 1) "week" else "weeks"} with the ones before, and needs at least five days logged in each. Keep going."
        }
    }
}
