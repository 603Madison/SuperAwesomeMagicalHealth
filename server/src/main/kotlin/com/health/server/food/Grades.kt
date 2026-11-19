package com.health.server.food

import com.health.api.Food
import com.health.api.FoodGrade
import com.health.api.MicroAim
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import kotlin.math.roundToInt

/**
 * What a grade weighs a food against: the person's day's [target] (calories, protein, fibre,
 * sodium, vitamins and the rest, from their profile and goals), the [conditions] they've picked
 * (catalog ids), and which way their weight goal goes ([weeklyChangeKg]).
 */
data class GradeGoals(
    val target: NutritionTarget? = null,
    val conditions: Set<String> = emptySet(),
    val weeklyChangeKg: Double = 0.0,
) {
    val diabetes get() = "type-1-diabetes" in conditions || "type-2-diabetes" in conditions
    val bloodPressure get() = "hypertension" in conditions
    val heart get() = "high-cholesterol" in conditions
    val losing get() = weeklyChangeKg < 0 || "lose-weight" in conditions
    val gaining get() = !losing && (weeklyChangeKg > 0 || "gain-weight" in conditions)
    val muscle get() = "build-muscle" in conditions
}

/**
 * Food grades, A to F: how well a food fits one person's goals. Pure.
 *
 * The base is Nutri-Score's nutrient profile (its 2023 thresholds for general foods), per 100 g:
 * points for energy, sugars, saturated fat and salt, less points for fibre and protein (protein
 * counts while the first four come to under 11, as in Nutri-Score, or always with a goal to build
 * muscle). Fruit, vegetable and legume content isn't known for most foods, so it isn't counted.
 * Without a serving's weight, the food is judged per 150 kcal and energy density isn't counted.
 *
 * Then the person's goals weigh in: their conditions make the matching points count half as much
 * again (diabetes: sugars; high blood pressure: salt; heart health: saturated fat), a weight goal
 * makes energy count more (losing) or less (gaining), and a serving that brings a good share of
 * the day's protein, fibre or vitamins counts in its favour, one that brings a large share of the
 * day's sodium, sugar, saturated fat, carbs (with diabetes) or calories (losing) against it.
 *
 * Letters follow Nutri-Score's bands for the final score: A up to 0, B up to 2, C up to 10,
 * D up to 18, F above. Reasons are short and neutral: what the food has, never what to do.
 */
object Grades {
    private val ENERGY_KJ = doubleArrayOf(335.0, 670.0, 1005.0, 1340.0, 1675.0, 2010.0, 2345.0, 2680.0, 3015.0, 3350.0)
    private val SUGARS = doubleArrayOf(3.4, 6.8, 10.0, 14.0, 17.0, 20.0, 24.0, 27.0, 31.0, 34.0, 37.0, 41.0, 44.0, 48.0, 51.0)
    private val SATURATED_FAT = DoubleArray(10) { it + 1.0 }
    private val SALT = DoubleArray(20) { (it + 1) * 0.2 }
    private val FIBRE = doubleArrayOf(3.0, 4.1, 5.2, 6.3, 7.4)
    private val PROTEIN = doubleArrayOf(2.4, 4.8, 7.2, 9.6, 12.0, 14.0, 17.0)

    /** Calories a food without a known weight is judged per (as if it were 100 g). */
    const val ASSUMED_KCAL = 150.0

    /** A point for each threshold [value] is above; none when it isn't known. */
    fun points(value: Double?, thresholds: DoubleArray): Int = value?.let { v -> thresholds.count { v > it + 1e-9 } } ?: 0

    /** Nutri-Score's points for [per100] (a food's nutrients per 100 g); energy only when [energyKnown]. */
    data class Profile(val energy: Int, val sugars: Int, val saturatedFat: Int, val salt: Int, val fibre: Int, val protein: Int)

    fun profile(per100: Nutrients, energyKnown: Boolean = true) = Profile(
        energy = if (energyKnown) points(per100.calories * 4.184, ENERGY_KJ) else 0,
        sugars = points(per100.sugar, SUGARS),
        saturatedFat = points(per100.saturatedFat, SATURATED_FAT),
        salt = points(per100.sodiumMg?.let { it * 2.5 / 1000 }, SALT),
        fibre = points(per100.fiber, FIBRE),
        protein = points(per100.protein, PROTEIN),
    )

    /** The letter for a final score. */
    fun letter(score: Int): String = when {
        score <= 0 -> "A"
        score <= 2 -> "B"
        score <= 10 -> "C"
        score <= 18 -> "D"
        else -> "F"
    }

    private data class Reason(val key: String, val text: String, val weight: Double)

    /** [food]'s grade for someone with [goals]; null when there's too little to go on (no calories or macros). */
    fun grade(food: Food, goals: GradeGoals = GradeGoals()): FoodGrade? {
        val n = food.nutrients
        val grams = food.servingGrams?.takeIf { it.isFinite() && it > 0 }
        val per100 = when {
            grams != null -> n * (100.0 / grams)
            n.calories > 0 -> n * (ASSUMED_KCAL / n.calories)
            else -> return null
        }
        if (per100.calories <= 0 && per100.protein + per100.carbs + per100.fat <= 0) return null
        val p = profile(per100, energyKnown = grams != null)
        val reasons = mutableListOf<Reason>()
        fun weighed(points: Int, factor: Double) = (points * factor).roundToInt()

        // ---- The profile, weighed by the person's conditions and weight goal.
        val energy = weighed(p.energy, if (goals.losing) 1.5 else if (goals.gaining) 0.5 else 1.0)
        val sugars = weighed(p.sugars, if (goals.diabetes) 1.5 else 1.0)
        val saturated = weighed(p.saturatedFat, if (goals.heart) 1.5 else 1.0)
        val salt = weighed(p.salt, if (goals.bloodPressure) 1.5 else 1.0)
        val negative = energy + sugars + saturated + salt
        val protein = if (negative < 11 || goals.muscle) weighed(p.protein, if (goals.muscle) 1.5 else 1.0) else 0
        var score = negative - p.fibre - protein

        if (p.energy >= 7) {
            reasons += if (goals.gaining) Reason("energy", "Energy-dense, which suits your goal", 2.0) else Reason("energy", "Energy-dense", energy.toDouble())
        }
        if (p.sugars >= 5) reasons += Reason("sugar", if (goals.diabetes) "High in sugars for your blood sugar goal" else "High in sugars", sugars + 0.5)
        else if (goals.diabetes && per100.sugar != null && p.sugars == 0) reasons += Reason("sugar", "Low in sugars, which suits your blood sugar goal", 2.5)
        if (p.saturatedFat >= 5) reasons += Reason("satfat", if (goals.heart) "High in saturated fat for your heart goal" else "High in saturated fat", saturated + 0.5)
        else if (goals.heart && per100.saturatedFat != null && p.saturatedFat <= 1) reasons += Reason("satfat", "Low in saturated fat, which suits your heart goal", 2.5)
        if (p.salt >= 7) reasons += Reason("sodium", if (goals.bloodPressure) "High in sodium for your blood pressure goal" else "High in sodium", salt + 0.5)
        else if (goals.bloodPressure && per100.sodiumMg != null && p.salt <= 1) reasons += Reason("sodium", "Low in sodium, good for your blood pressure goal", 2.5)
        if (per100.fiber != null && p.fibre == 0) reasons += Reason("fibre", "Lower in fibre", 1.5)
        else if (p.fibre >= 3) reasons += Reason("fibre", "Good fibre", p.fibre.toDouble())
        if (p.protein >= 5 && protein > 0) reasons += Reason("protein", "Good protein", protein.toDouble())

        // ---- One serving against the day's targets.
        val t = goals.target
        if (t != null) {
            fun share(amount: Double?, target: Double?) = if (amount == null || target == null || target <= 0) 0.0 else amount / target
            if (share(n.protein, t.protein.toDouble()) >= 0.2) {
                score -= 2
                reasons += Reason("protein", "Good protein for your goal", 3.5)
            }
            if (share(n.fiber, t.fiberG) >= 0.15) {
                score -= 1
                reasons += Reason("fibre", "Good fibre for your day", 3.0)
            }
            if (share(n.sodiumMg, t.sodiumMg) >= 0.3) {
                score += 2
                reasons += Reason("sodium", if (goals.bloodPressure) "High in sodium for your blood pressure goal" else "Much of a day's sodium in one serving", 4.0)
            }
            if (share(n.sugar, t.sugarG) >= 0.4) {
                score += 2
                reasons += Reason("sugar", if (goals.diabetes) "High in sugars for your blood sugar goal" else "Much of a day's sugar in one serving", 4.0)
            }
            if (share(n.saturatedFat, t.saturatedFatG) >= 0.35) {
                score += 2
                reasons += Reason("satfat", if (goals.heart) "High in saturated fat for your heart goal" else "Much of a day's saturated fat in one serving", 4.0)
            }
            if (goals.diabetes && share(n.carbs, t.carbs.toDouble()) >= 0.25) {
                score += 2
                reasons += Reason("carbs", "Many carbs per serving for your blood sugar goal", 3.8)
            }
            if (goals.losing && share(n.calories, t.calories.toDouble()) >= 0.35) {
                score += 2
                reasons += Reason("calories", "Over a third of your day's calories", 3.6)
            }
            // Vitamins and minerals the person aims for: a fifth of the day's from one serving.
            val good = t.micros.filter { it.micro.aim == MicroAim.AT_LEAST && it.amount > 0 }
                .mapNotNull { m -> n.micros[m.micro]?.let { m.micro to it / m.amount } }
                .filter { it.second >= 0.2 }
                .sortedByDescending { it.second }
            if (good.isNotEmpty()) {
                score -= minOf(good.size, 2)
                val names = good.take(2).map { plain(it.first.label.lowercase()) }
                reasons += Reason("micros", "Good source of ${names.joinToString(" and ")}", 2.0 + good.size.coerceAtMost(2) * 0.5)
            }
            val limits = t.micros.filter { it.micro.aim == MicroAim.AT_MOST && it.amount > 0 }
                .mapNotNull { m -> n.micros[m.micro]?.let { m.micro to it / m.amount } }
                .filter { it.second >= 0.3 }
            if (limits.isNotEmpty()) {
                score += 1
                val name = plain(limits.maxBy { it.second }.first.label.lowercase())
                reasons += Reason("limits", if (goals.heart) "High in $name for your heart goal" else "High in $name", 2.2)
            }
        }

        // Sugars, saturated fat and sodium all unknown: never better than B, and it says so.
        val unknown = n.sugar == null && n.saturatedFat == null && n.sodiumMg == null
        var letter = letter(score)
        if (unknown) {
            if (letter == "A") letter = "B"
            reasons += Reason("unknown", "Sugars, fat and sodium aren't known", 5.0)
        }
        // One reason per nutrient: the one about the person's own goal where there is one, ranked by the larger weight.
        val chosen = reasons.groupBy { it.key }
            .map { (_, same) -> same.maxBy { (if ("your" in it.text) 1000.0 else 0.0) + it.weight }.copy(weight = same.maxOf { it.weight }) }
            .sortedByDescending { it.weight }.take(3).map { it.text }
        return FoodGrade(letter, chosen.ifEmpty { listOf(if (letter <= "B") "Fits your goals well" else "Graded on its nutrients per 100 g") })
    }

    /** "vitamin c" → "vitamin C", "thiamin (b1)" → "thiamin (B1)": names as they're written. */
    private fun plain(name: String): String = name
        .replace(Regex("""\bvitamin ([a-z])(\d*)\b""")) { "vitamin ${it.groupValues[1].uppercase()}${it.groupValues[2]}" }
        .replace(Regex("""\(b(\d+)\)""")) { "(B${it.groupValues[1]})" }
}
