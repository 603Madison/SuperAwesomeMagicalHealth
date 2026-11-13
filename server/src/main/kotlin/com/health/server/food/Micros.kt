package com.health.server.food

import com.health.api.AppPage
import com.health.api.ExtraNutrient
import com.health.api.FoodEntry
import com.health.api.Insight
import com.health.api.LifeStage
import com.health.api.MicroAim
import com.health.api.MicroGroup
import com.health.api.Micronutrient
import com.health.api.Module
import com.health.api.NutrientTargets
import com.health.api.NutrientTargetsChange
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import com.health.server.ApiError
import com.health.server.care.LifeStageService
import com.health.server.care.LifeStages
import com.health.server.health.ProfileStore
import com.health.server.insights.ReferenceIntakes
import com.health.server.modules.UserSettingsStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate
import java.util.Locale

/**
 * Vitamins, minerals and the finer fats on foods: checking what's sent, the week's one gentle
 * insight, and their names in exports and text. Pure, so it can be tested on its own.
 */
object Micros {
    /** The most one food or entry can sensibly carry, by unit (kelp is rich in iodine, liver in vitamin A). */
    fun maximum(unit: String): Double = when (unit) {
        "g" -> 2_000.0
        "mg" -> 100_000.0
        else -> 1_000_000.0
    }

    /**
     * [micros] that make sense: each finite, not negative and under [maximum]. With [strict] (what
     * a person typed), one that doesn't is refused; otherwise (a food database's) it's left out.
     */
    fun checked(micros: Map<Micronutrient, Double>, strict: Boolean): Map<Micronutrient, Double> = micros.filter { (m, v) ->
        val ok = v.isFinite() && v >= 0 && v <= maximum(m.unit)
        if (!ok && strict) throw ApiError.badRequest("${m.label} doesn't look right: between 0 and ${String.format(Locale.US, "%,.0f", maximum(m.unit))} ${m.unit}")
        ok
    }

    /** A CSV column's name for a nutrient: "iron_mg", "vitamin_d_ug". */
    fun column(m: Micronutrient): String = "${m.name.lowercase()}_${m.unit.replace("µ", "u")}"

    /** "12.5", "0.42", "1,200": enough figures to mean something, few enough to read. */
    fun amount(v: Double): String = when {
        v >= 100 -> String.format(Locale.US, "%,.0f", v)
        v >= 10 -> String.format(Locale.US, "%.1f", v).removeSuffix(".0")
        else -> String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }

    /** The fibre, sugar, saturated fat, sodium and micronutrients [n] carries, as text ("fibre 6 g, iron 4.2 mg"), or null with none. */
    fun text(n: Nutrients): String? {
        val parts = ExtraNutrient.entries.mapNotNull { e -> e.of(n)?.let { "${lower(e.label)} ${amount(it)} ${e.unit}" } } +
            Micronutrient.entries.mapNotNull { m -> n.micros[m]?.let { "${lower(m.label)} ${amount(it)} ${m.unit}" } }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    fun lower(label: String): String = label.replaceFirstChar { it.lowercase() }

    // ---- The week's insight

    /** What a day must have for its total of a nutrient to count: foods, and at least half of them carrying it. */
    private const val CARRIED_SHARE = 0.5

    /** Days of the seven that must count, and be under target, for the insight. */
    const val COVERED_DAYS = 5
    const val UNDER_DAYS = 4

    /** A nutrient the insight may talk about: fibre, or a vitamin or mineral with a target to reach. */
    private sealed interface Subject {
        val label: String
        fun of(n: Nutrients): Double?
        fun target(t: NutritionTarget): Double?

        data class Extra(val e: ExtraNutrient) : Subject {
            override val label get() = e.label
            override fun of(n: Nutrients) = e.of(n)
            override fun target(t: NutritionTarget) = e.target(t)
        }

        data class Micro(val m: Micronutrient) : Subject {
            override val label get() = m.label
            override fun of(n: Nutrients) = n.micros[m]
            override fun target(t: NutritionTarget) = t.micros.firstOrNull { it.micro == m }?.amount
        }
    }

    private val SUBJECTS: List<Subject> = listOf<Subject>(Subject.Extra(ExtraNutrient.FIBER)) +
        Micronutrient.entries.filter { it.aim == MicroAim.AT_LEAST && it.group != MicroGroup.FATS }.map { Subject.Micro(it) }

    /**
     * At most one gentle insight about the week ([days], the seven days it covers): a nutrient that's
     * been under its target on most of them. Only days where at least half the foods carried that
     * nutrient count (a total from a few foods means little), and at least [COVERED_DAYS] of the seven
     * must, so a missing value is never taken for none. The caller leaves it out for people in eating
     * disorder recovery.
     */
    fun weeklyInsight(entries: List<FoodEntry>, days: List<LocalDate>, target: NutritionTarget): Insight? {
        val byDay = entries.groupBy { it.date }
        data class Found(val subject: Subject, val under: Int, val ratio: Double)
        val found = SUBJECTS.mapNotNull { s ->
            val aim = s.target(target)?.takeIf { it > 0 } ?: return@mapNotNull null
            val covered = days.mapNotNull { d ->
                val list = byDay[d.toString()].orEmpty()
                val carrying = list.mapNotNull { s.of(it.nutrients) }
                if (list.isEmpty() || carrying.size < list.size * CARRIED_SHARE) null else carrying.sum()
            }
            if (covered.size < COVERED_DAYS) return@mapNotNull null
            val under = covered.count { it < aim }
            if (under < UNDER_DAYS) null else Found(s, under, covered.average() / aim)
        }
        val worst = found.maxWithOrNull(compareBy<Found>({ it.under }, { -it.ratio })) ?: return null
        val name = worst.subject.label
        return Insight(
            "info", "$name has been under your target most days this week",
            "${ideas(worst.subject)} Only foods that came with ${lower(name)} numbers count.",
            "salad", link = AppPage.FOOD,
        )
    }

    private fun ideas(s: Subject): String = when ((s as? Subject.Micro)?.m) {
        null -> "Beans, lentils, oats, wholegrains, fruit and vegetables all bring fibre."
        Micronutrient.IRON -> "Beans, lentils, tofu, leafy greens, fortified cereals and red meat all bring iron."
        Micronutrient.CALCIUM -> "Dairy or fortified plant milks, tofu, almonds and leafy greens all bring calcium."
        Micronutrient.VITAMIN_D -> "Oily fish, eggs and fortified foods bring some vitamin D; daylight helps too."
        Micronutrient.POTASSIUM -> "Potatoes, beans, bananas, yoghurt and leafy greens all bring potassium."
        Micronutrient.MAGNESIUM -> "Nuts, seeds, beans, wholegrains and leafy greens all bring magnesium."
        Micronutrient.ZINC -> "Beans, nuts, seeds, wholegrains, dairy and meat all bring zinc."
        Micronutrient.FOLATE -> "Leafy greens, beans, lentils and fortified cereals all bring folate."
        Micronutrient.VITAMIN_B12 -> "Dairy, eggs, fish, meat and fortified foods bring vitamin B12."
        Micronutrient.VITAMIN_C -> "Fruit, peppers, broccoli and potatoes all bring vitamin C."
        Micronutrient.VITAMIN_A -> "Carrots, sweet potato, leafy greens, eggs and dairy all bring vitamin A."
        Micronutrient.IODINE -> "Dairy, fish, eggs and iodised salt bring iodine."
        Micronutrient.SELENIUM -> "Brazil nuts, fish, eggs and wholegrains bring selenium."
        else -> "A wider mix of foods usually brings more of it."
    }
}

/** Each person's own nutrient targets (sealed in their settings), and what the reference intakes need to know. */
class NutrientTargetsService(
    private val settings: UserSettingsStore,
    private val profiles: ProfileStore,
    private val lifeStages: LifeStageService,
) {
    private val serializer = MapSerializer(String.serializer(), Double.serializer())

    suspend fun own(userId: String): Map<String, Double> = settings.get(userId, KEY, serializer).orEmpty()

    /** Whether the person is pregnant on [date] (their life stage, with the cycle on): folate, iron and iodine go up. */
    suspend fun pregnant(userId: String, date: LocalDate): Boolean {
        if (Module.CYCLE !in profiles.get(userId).modules) return false
        val info = lifeStages.info(userId)
        if (info.stage != LifeStage.PREGNANT) return false
        val status = LifeStages.pregnancy(info, date) ?: return false
        val due = LocalDate.parse(status.dueDate)
        return !date.isBefore(due.minusDays(280)) && !date.isAfter(due.plusDays(14))
    }

    /** The settings' list, for [date] with its [calories] (saturated and trans fat follow them). */
    suspend fun settings(userId: String, date: LocalDate, calories: Int, pregnancy: Boolean = true): NutrientTargets =
        ReferenceIntakes.settings(profiles.get(userId), date, calories, pregnancy && pregnant(userId, date), own(userId))

    /** Saves the person's own targets: each a nutrient's name, more than nothing and less than [Micros.maximum]. */
    suspend fun save(userId: String, change: NutrientTargetsChange): Map<String, Double> {
        if (change.own.size > 64) throw ApiError.badRequest("That's more targets than there are nutrients")
        val clean = change.own.mapValues { (key, v) ->
            val unit = ReferenceIntakes.unitOf(key) ?: throw ApiError.badRequest("There's no nutrient called $key")
            if (!v.isFinite() || v <= 0 || v > Micros.maximum(unit)) throw ApiError.badRequest("A target is more than 0 and up to ${String.format(Locale.US, "%,.0f", Micros.maximum(unit))} $unit")
            (v * 100).let { Math.round(it) / 100.0 }
        }
        if (clean.isEmpty()) settings.delete(userId, KEY) else settings.put(userId, KEY, clean, serializer)
        return clean
    }

    companion object {
        /** Their own targets' key in [UserSettingsStore]. */
        const val KEY = "nutrition.targets"
    }
}
