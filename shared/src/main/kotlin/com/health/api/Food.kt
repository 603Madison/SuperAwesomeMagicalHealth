package com.health.api

import kotlinx.serialization.Serializable

@Serializable
enum class Meal(val label: String) { BREAKFAST("Breakfast"), LUNCH("Lunch"), DINNER("Dinner"), SNACK("Snacks") }

/** What's in an amount of food. Grams, except where named otherwise. */
@Serializable
data class Nutrients(
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double? = null,
    val sugar: Double? = null,
    val saturatedFat: Double? = null,
    val sodiumMg: Double? = null,
) {
    operator fun plus(other: Nutrients) = Nutrients(
        calories = calories + other.calories,
        protein = protein + other.protein,
        carbs = carbs + other.carbs,
        fat = fat + other.fat,
        fiber = add(fiber, other.fiber),
        sugar = add(sugar, other.sugar),
        saturatedFat = add(saturatedFat, other.saturatedFat),
        sodiumMg = add(sodiumMg, other.sodiumMg),
    )

    operator fun times(factor: Double) = Nutrients(
        calories = calories * factor,
        protein = protein * factor,
        carbs = carbs * factor,
        fat = fat * factor,
        fiber = fiber?.times(factor),
        sugar = sugar?.times(factor),
        saturatedFat = saturatedFat?.times(factor),
        sodiumMg = sodiumMg?.times(factor),
    )

    private fun add(a: Double?, b: Double?): Double? = if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
}

@Serializable
enum class FoodSource { CUSTOM, OPEN_FOOD_FACTS }

/** A food that can be logged: [nutrients] are for one [serving] (which weighs [servingGrams], if known). */
@Serializable
data class Food(
    val name: String,
    val nutrients: Nutrients,
    val serving: String = "1 serving",
    val servingGrams: Double? = null,
    val brand: String? = null,
    val barcode: String? = null,
    val source: FoodSource = FoodSource.CUSTOM,
    /** The person's own foods and ones found online that they've logged have an id. */
    val id: String? = null,
    /** How many times the person has logged it. */
    val uses: Int = 0,
)

/** One thing eaten: [servings] of a food, with the nutrients that amount holds. */
@Serializable
data class FoodEntry(
    val date: String,
    val meal: Meal,
    val name: String,
    val nutrients: Nutrients,
    val servings: Double = 1.0,
    val serving: String = "1 serving",
    val brand: String? = null,
    val foodId: String? = null,
    val id: String? = null,
    val loggedAt: Long? = null,
)

/**
 * Logging something eaten. With [food] (a search result, or one of the person's foods) the
 * server works out the nutrients for [servings]; without, [nutrients] are taken as given (a quick
 * add of calories, or a photo's estimate, with its [serving] like "about 180 g").
 */
@Serializable
data class FoodEntryInput(
    val date: String,
    val meal: Meal,
    val servings: Double = 1.0,
    val food: Food? = null,
    val name: String? = null,
    val nutrients: Nutrients? = null,
    val serving: String? = null,
)

@Serializable
data class FoodEntryChange(val meal: Meal? = null, val servings: Double? = null, val date: String? = null)

@Serializable
data class WaterEntry(val date: String, val ml: Int, val id: String? = null, val at: Long? = null)

/** What the person should aim for in a day, and how that was worked out. */
@Serializable
data class NutritionTarget(
    val calories: Int,
    val protein: Int,
    val carbs: Int,
    val fat: Int,
    val waterMl: Int,
    /** "Your goal", or how the calories were estimated. */
    val basis: String,
)

@Serializable
data class FoodDay(
    val date: String,
    val entries: List<FoodEntry>,
    val water: List<WaterEntry>,
    val totals: Nutrients,
    val waterMl: Int,
    val target: NutritionTarget,
    /** Calories burned so far that day (resting plus active), where known. */
    val burned: Double? = null,
)

@Serializable
data class FoodSearch(val mine: List<Food>, val online: List<Food>, val onlineError: String? = null)

/**
 * Something the person has eaten before, offered for [meal]: one food, or a whole meal they've had
 * (then [items] holds each part). [items] are ready to log, one `POST /api/food/entries` each
 * ([FoodEntry.toInput]); [nutrients] are their total. [why] is in plain words and never names an
 * amount ("You often have this at dinner"). [lastEaten] is a date.
 */
@Serializable
data class FoodSuggestion(
    val name: String,
    val brand: String? = null,
    val serving: String,
    val servings: Double,
    val nutrients: Nutrients,
    val meal: Meal,
    val items: List<FoodEntry>,
    val why: String,
    val timesEaten: Int,
    val lastEaten: String? = null,
)

/**
 * Ideas for [meal] from what the person has eaten before (`GET /api/food/suggestions`), best
 * first; [gap] is what's still to eat towards the day's targets (protein, calories), when that
 * counts. With [showNumbers] false (eating disorder recovery), the apps show no amounts at all.
 */
@Serializable
data class FoodSuggestions(
    val meal: Meal,
    val gap: Nutrients? = null,
    val items: List<FoodSuggestion>,
    val showNumbers: Boolean = true,
)

/** Logging this entry again, on [date] at [meal]: the person's food when it was one, else its name and nutrients. */
fun FoodEntry.toInput(date: String, meal: Meal): FoodEntryInput {
    val each = if (servings > 0) nutrients * (1.0 / servings) else nutrients
    return if (foodId != null) {
        FoodEntryInput(date, meal, servings, food = Food(name = name, nutrients = each, serving = serving, brand = brand, id = foodId))
    } else {
        FoodEntryInput(date, meal, servings, name = name, nutrients = each)
    }
}
