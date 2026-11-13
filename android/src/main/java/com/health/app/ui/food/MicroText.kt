package com.health.app.ui.food

import com.health.api.ExtraNutrient
import com.health.api.FoodEntry
import com.health.api.MicroAim
import com.health.api.MicroGroup
import com.health.api.Micronutrient
import com.health.api.NutrientTargetSetting
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import com.health.api.TrendDay
import java.math.RoundingMode
import java.text.NumberFormat

/*
 * Vitamins, minerals, fibre and the finer fats as the Food tab, foods, Trends and the targets
 * show them: amounts against targets in words, "No data" where nothing carried one, and how
 * complete a day is. Pure, so it can be tested (the web's lib/micros.ts says the same).
 */

/** How the Food tab groups nutrients: fibre and sugar, then fats, minerals and vitamins. */
enum class NutrientGroup(val label: String) { FIBER_SUGAR("Fibre and sugar"), FATS("Fats"), MINERALS("Minerals"), VITAMINS("Vitamins") }

/** One nutrient: one of [Nutrients]' own ([extra]) or a [micro]. [key] is its name in either enum. */
data class NutrientInfo(val key: String, val label: String, val unit: String, val group: NutrientGroup, val aim: MicroAim, val extra: ExtraNutrient? = null, val micro: Micronutrient? = null)

/** A nutrient's day: its amount against its target, the bar's fill, and the words. */
data class MicroRow(
    val info: NutrientInfo,
    val amount: Double?,
    val target: Double?,
    val state: State,
    /** How far towards the target or limit, 0–1; null without one. */
    val fraction: Float?,
    val text: String,
    val note: String? = null,
) {
    enum class State { NONE, PLAIN, UNDER_TARGET, MET, UNDER_LIMIT, OVER_LIMIT }
}

object MicroText {
    private fun extra(e: ExtraNutrient, group: NutrientGroup) = NutrientInfo(e.name, e.label, e.unit, group, e.aim, extra = e)
    private fun micro(m: Micronutrient) = NutrientInfo(
        m.name, m.label, m.unit,
        when (m.group) { MicroGroup.FATS -> NutrientGroup.FATS; MicroGroup.MINERALS -> NutrientGroup.MINERALS; MicroGroup.VITAMINS -> NutrientGroup.VITAMINS },
        m.aim, micro = m,
    )

    /** Every nutrient, in the order they show: each group in turn, saturated fat with the fats and sodium with the minerals. */
    val ALL: List<NutrientInfo> = buildList {
        add(extra(ExtraNutrient.FIBER, NutrientGroup.FIBER_SUGAR))
        add(extra(ExtraNutrient.SUGAR, NutrientGroup.FIBER_SUGAR))
        add(extra(ExtraNutrient.SATURATED_FAT, NutrientGroup.FATS))
        Micronutrient.entries.filter { it.group == MicroGroup.FATS }.forEach { add(micro(it)) }
        add(extra(ExtraNutrient.SODIUM, NutrientGroup.MINERALS))
        Micronutrient.entries.filter { it.group == MicroGroup.MINERALS }.forEach { add(micro(it)) }
        Micronutrient.entries.filter { it.group == MicroGroup.VITAMINS }.forEach { add(micro(it)) }
    }

    /** The few the Food tab shows before "Show all". */
    val MAIN_FEW: List<String> = listOf("FIBER", "SODIUM", "IRON", "CALCIUM", "VITAMIN_D", "POTASSIUM")

    fun info(key: String): NutrientInfo = ALL.first { it.key == key }

    /** How much of it [n] holds, or null where that isn't known (never 0 for unknown). */
    fun amount(n: Nutrients?, info: NutrientInfo): Double? = when {
        n == null -> null
        info.extra != null -> info.extra.of(n)
        else -> n.micros[info.micro]
    }

    /** A day's intake for a chart: null on days nothing carried it. */
    fun amount(d: TrendDay, info: NutrientInfo): Double? = when (info.extra) {
        ExtraNutrient.FIBER -> d.fiber
        ExtraNutrient.SUGAR -> d.sugar
        ExtraNutrient.SATURATED_FAT -> d.saturatedFat
        ExtraNutrient.SODIUM -> d.sodiumMg
        null -> d.micros[info.micro]
    }

    fun target(t: NutritionTarget?, info: NutrientInfo): Double? = when {
        t == null -> null
        info.extra != null -> info.extra.target(t)
        else -> t.micros.firstOrNull { it.micro == info.micro }?.amount
    }

    private fun upper(t: NutritionTarget?, info: NutrientInfo): Double? = t?.micros?.firstOrNull { it.micro == info.micro && info.micro != null }?.upper

    /** "4.2", "135", "0.63", "1,200": enough figures to mean something, few enough to read. */
    fun amountText(v: Double): String = NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = when {
            v >= 100 -> 0
            v >= 1 -> 1
            else -> 2
        }
        roundingMode = RoundingMode.HALF_UP
    }.format(v)

    fun withUnit(v: Double, unit: String) = "${amountText(v)} $unit"

    /** One nutrient's row: its amount against its target or limit, in words. */
    fun row(info: NutrientInfo, totals: Nutrients?, target: NutritionTarget?): MicroRow {
        val amount = amount(totals, info)
        val t = target(target, info)?.takeIf { it > 0 }
        if (amount == null) return MicroRow(info, null, t, MicroRow.State.NONE, null, "No data")
        if (t == null) return MicroRow(info, amount, null, MicroRow.State.PLAIN, null, withUnit(amount, info.unit))
        val fraction = (amount / t).coerceIn(0.0, 1.0).toFloat()
        if (info.aim == MicroAim.AT_MOST) {
            return if (amount <= t) MicroRow(info, amount, t, MicroRow.State.UNDER_LIMIT, fraction, "${withUnit(amount, info.unit)}, under your limit")
            else MicroRow(info, amount, t, MicroRow.State.OVER_LIMIT, fraction, "${withUnit(amount, info.unit)} of ${withUnit(t, info.unit)}", "A little over your limit today")
        }
        val note = upper(target, info)?.takeIf { amount > it }?.let { "Above the ${withUnit(it, info.unit)} upper limit: worth a look if it happens often" }
        return MicroRow(info, amount, t, if (amount >= t) MicroRow.State.MET else MicroRow.State.UNDER_TARGET, fraction, "${amountText(amount)} of ${withUnit(t, info.unit)}", note)
    }

    /** The Food tab's rows: the main few, or every nutrient in order. */
    fun rows(totals: Nutrients?, target: NutritionTarget?, all: Boolean): List<MicroRow> =
        (if (all) ALL else MAIN_FEW.map(::info)).map { row(it, totals, target) }

    /** Whether an entry carried any vitamin or mineral (fibre and the like don't count here). */
    fun carriesMicros(e: FoodEntry): Boolean = e.nutrients.micros.isNotEmpty()

    /** "Vitamins and minerals came with 3 of 7 foods today": how complete the day's numbers are; null with nothing logged. */
    fun coverage(entries: List<FoodEntry>, isToday: Boolean): String? {
        if (entries.isEmpty()) return null
        val carrying = entries.count(::carriesMicros)
        val day = if (isToday) "today" else "that day"
        return when (carrying) {
            0 -> "None of the foods $day came with vitamins or minerals. Ones found by search or barcode often do."
            entries.size -> if (entries.size == 1) "Vitamins and minerals came with the one food $day." else "Vitamins and minerals came with all ${entries.size} foods $day."
            else -> "Vitamins and minerals came with $carrying of ${entries.size} foods $day. Only those count."
        }
    }

    /** What a food carries, in a line ("Fibre 2.7 g · Iron 4.2 mg · and 3 more"): the main few first; null with none. */
    fun carried(n: Nutrients, limit: Int = 99): String? {
        val order = MAIN_FEW.map(::info) + ALL.filter { it.key !in MAIN_FEW }
        val parts = order.mapNotNull { info -> amount(n, info)?.let { "${info.label} ${withUnit(it, info.unit)}" } }
        if (parts.isEmpty()) return null
        return if (parts.size > limit) parts.take(limit).joinToString(" · ") + " · and ${parts.size - limit} more" else parts.joinToString(" · ")
    }

    /** Typed "More nutrients" (key to text) added to [base]: blanks stay unknown; an error names the first that isn't 0 or more. */
    fun typed(base: Nutrients, values: Map<String, String>): Pair<Nutrients, String?> {
        var n = base
        val micros = base.micros.toMutableMap()
        for (info in ALL) {
            val text = values[info.key]?.trim()?.replace(',', '.').orEmpty()
            if (text.isEmpty()) continue
            val v = text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return base to "${info.label} needs a number of 0 or more"
            n = when (info.extra) {
                ExtraNutrient.FIBER -> n.copy(fiber = v)
                ExtraNutrient.SUGAR -> n.copy(sugar = v)
                ExtraNutrient.SATURATED_FAT -> n.copy(saturatedFat = v)
                ExtraNutrient.SODIUM -> n.copy(sodiumMg = v)
                null -> n.also { micros[info.micro!!] = v }
            }
        }
        return n.copy(micros = micros) to null
    }

    /** The targets settings' line: the reference (and upper limit), or that there isn't one. */
    fun referenceText(item: NutrientTargetSetting): String {
        val info = info(item.key)
        val reference = item.reference ?: return "No reference intake"
        val head = if (info.aim == MicroAim.AT_MOST) "Limit ${withUnit(reference, info.unit)}" else "Reference ${withUnit(reference, info.unit)}"
        return item.upper?.let { "$head · upper limit ${withUnit(it, info.unit)}" } ?: head
    }

    /** A "More nutrients" field's label, with its unit: "Iron (mg)", "Thiamin B1 (mg)". */
    fun inputLabel(info: NutrientInfo): String = "${info.label.replace("(", "").replace(")", "")} (${info.unit})"

    /** Whether a field's label needs a row of its own. */
    fun isLong(info: NutrientInfo): Boolean = inputLabel(info).length > 16

    /** "More nutrients" fields two to a row, but long labels on their own. */
    fun inputRows(infos: List<NutrientInfo>): List<List<NutrientInfo>> {
        val rows = mutableListOf<List<NutrientInfo>>()
        var waiting: NutrientInfo? = null
        for (info in infos) {
            when {
                isLong(info) -> rows += listOf(info)
                waiting == null -> waiting = info
                else -> { rows += listOf(waiting, info); waiting = null }
            }
        }
        waiting?.let { rows += listOf(it) }
        return rows
    }
}
