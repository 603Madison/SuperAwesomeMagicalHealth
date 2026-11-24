package com.health.server.insights

import com.health.api.EnergyProfile
import com.health.api.NutritionTarget
import com.health.api.Profile
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** Energy burned at rest, and what to eat for the person's goals. */
object Energy {
    /** Kilocalories a kilogram of body weight holds, near enough, for planning a change. */
    private const val KCAL_PER_KG = 7700.0

    /**
     * Resting energy a day (Mifflin–St Jeor), or null without height, birth year and weight. The
     * formula's constant differs for female and male bodies; "average" takes the middle.
     */
    fun restingCalories(profile: Profile, weightKg: Double?, date: LocalDate): Double? {
        val height = profile.heightCm ?: return null
        val born = profile.birthYear ?: return null
        val weight = weightKg ?: return null
        val age = (date.year - born).coerceIn(10, 110)
        val constant = when (profile.energyProfile) {
            EnergyProfile.FEMALE -> -161.0
            EnergyProfile.MALE -> 5.0
            EnergyProfile.AVERAGE -> -78.0
        }
        return 10 * weight + 6.25 * height - 5 * age + constant
    }

    /**
     * The day's targets: the person's own, or worked out from what they burn (resting energy, a
     * little more for digesting and pottering about, and their devices' active calories, averaged
     * over recent days) and the weight change they're aiming for.
     */
    fun target(profile: Profile, weightKg: Double?, resting: Double?, averageActive: Double?): NutritionTarget {
        val goals = profile.goals
        val own = goals.calories
        val (calories, basis) = when {
            own != null -> own to "Your goal"
            resting != null -> {
                val active = averageActive ?: (resting * 0.25)
                val maintain = resting * 1.1 + active
                val change = goals.weeklyChangeKg * KCAL_PER_KG / 7
                val target = ((maintain + change) / 10).roundToInt() * 10
                val floor = 1200
                // Every part named, so the sum can be checked: resting, the 10% for digesting and
                // everyday moving, and active; then the change for the goal, in the person's units.
                val everyday = resting * 0.1
                val activeText = if (averageActive != null) "%,d active (your average)".format(active.roundToInt()) else "%,d for moving about".format(active.roundToInt())
                val head = "%,d resting + %,d digesting and everyday moving + %s ≈ %,d kcal keeps your weight".format(
                    resting.roundToInt(), everyday.roundToInt(), activeText, (maintain / 10).roundToInt() * 10,
                )
                val amount = weekly(abs(goals.weeklyChangeKg), profile.units)
                val tail = when {
                    change < -1 -> ". Less %,d to lose %s a week: %,d".format(abs(change).roundToInt(), amount, maxOf(target, floor))
                    change > 1 -> ". Plus %,d to gain %s a week: %,d".format(change.roundToInt(), amount, maxOf(target, floor))
                    else -> ""
                }
                maxOf(target, floor) to (head + tail + if (target < floor) ". Kept to 1,200 kcal at least." else "")
            }
            else -> 2000 to "A general 2,000 kcal. Add your height, birth year and a weight for your own estimate."
        }
        val protein = goals.protein ?: weightKg?.let { (it * 1.4).roundToInt() } ?: (calories * 0.2 / 4).roundToInt()
        val fat = goals.fat ?: (calories * 0.3 / 9).roundToInt()
        val carbs = goals.carbs ?: ((calories - protein * 4 - fat * 9) / 4.0).roundToInt().coerceAtLeast(0)
        return NutritionTarget(calories, protein, carbs, fat, goals.waterMl, basis)
    }

    /** A weekly change in the person's units: "0.25 kg", "0.55 lb". */
    private fun weekly(kg: Double, units: com.health.api.Units): String =
        if (units == com.health.api.Units.IMPERIAL) "${trim(kg * 2.20462)} lb" else "${trim(kg)} kg"

    private fun trim(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

}
