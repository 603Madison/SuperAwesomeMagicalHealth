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
    /** Vitamins, minerals and the rest, where known, each in its [Micronutrient.unit]. */
    val micros: Map<Micronutrient, Double> = emptyMap(),
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
        micros = (micros.keys + other.micros.keys).associateWith { (micros[it] ?: 0.0) + (other.micros[it] ?: 0.0) },
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
        micros = micros.mapValues { it.value * factor },
    )

    private fun add(a: Double?, b: Double?): Double? = if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
}

/** Whether more is better (up to a point) or less is: for a target's bar and wording. */
@Serializable
enum class MicroAim { AT_LEAST, AT_MOST }

@Serializable
enum class MicroGroup(val label: String) { FATS("Fats"), MINERALS("Minerals"), VITAMINS("Vitamins") }

/**
 * The vitamins, minerals and finer fats a food can carry (alongside [Nutrients]' fibre, sugar,
 * saturated fat and sodium). [offKey] is Open Food Facts' per-100 g key, whose values are in
 * grams, so [fromGrams] turns them into [unit].
 */
@Serializable
enum class Micronutrient(val label: String, val unit: String, val group: MicroGroup, val offKey: String, val fromGrams: Double, val aim: MicroAim = MicroAim.AT_LEAST) {
    TRANS_FAT("Trans fat", "g", MicroGroup.FATS, "trans-fat_100g", 1.0, MicroAim.AT_MOST),
    MONO_FAT("Monounsaturated fat", "g", MicroGroup.FATS, "monounsaturated-fat_100g", 1.0),
    POLY_FAT("Polyunsaturated fat", "g", MicroGroup.FATS, "polyunsaturated-fat_100g", 1.0),
    OMEGA_3("Omega-3", "g", MicroGroup.FATS, "omega-3-fat_100g", 1.0),
    CHOLESTEROL("Cholesterol", "mg", MicroGroup.FATS, "cholesterol_100g", 1000.0, MicroAim.AT_MOST),
    POTASSIUM("Potassium", "mg", MicroGroup.MINERALS, "potassium_100g", 1000.0),
    CALCIUM("Calcium", "mg", MicroGroup.MINERALS, "calcium_100g", 1000.0),
    IRON("Iron", "mg", MicroGroup.MINERALS, "iron_100g", 1000.0),
    MAGNESIUM("Magnesium", "mg", MicroGroup.MINERALS, "magnesium_100g", 1000.0),
    ZINC("Zinc", "mg", MicroGroup.MINERALS, "zinc_100g", 1000.0),
    PHOSPHORUS("Phosphorus", "mg", MicroGroup.MINERALS, "phosphorus_100g", 1000.0),
    SELENIUM("Selenium", "µg", MicroGroup.MINERALS, "selenium_100g", 1_000_000.0),
    IODINE("Iodine", "µg", MicroGroup.MINERALS, "iodine_100g", 1_000_000.0),
    VITAMIN_A("Vitamin A", "µg", MicroGroup.VITAMINS, "vitamin-a_100g", 1_000_000.0),
    VITAMIN_C("Vitamin C", "mg", MicroGroup.VITAMINS, "vitamin-c_100g", 1000.0),
    VITAMIN_D("Vitamin D", "µg", MicroGroup.VITAMINS, "vitamin-d_100g", 1_000_000.0),
    VITAMIN_E("Vitamin E", "mg", MicroGroup.VITAMINS, "vitamin-e_100g", 1000.0),
    VITAMIN_K("Vitamin K", "µg", MicroGroup.VITAMINS, "vitamin-k_100g", 1_000_000.0),
    THIAMIN("Thiamin (B1)", "mg", MicroGroup.VITAMINS, "vitamin-b1_100g", 1000.0),
    RIBOFLAVIN("Riboflavin (B2)", "mg", MicroGroup.VITAMINS, "vitamin-b2_100g", 1000.0),
    NIACIN("Niacin (B3)", "mg", MicroGroup.VITAMINS, "vitamin-pp_100g", 1000.0),
    VITAMIN_B6("Vitamin B6", "mg", MicroGroup.VITAMINS, "vitamin-b6_100g", 1000.0),
    FOLATE("Folate", "µg", MicroGroup.VITAMINS, "vitamin-b9_100g", 1_000_000.0),
    VITAMIN_B12("Vitamin B12", "µg", MicroGroup.VITAMINS, "vitamin-b12_100g", 1_000_000.0),
}

/**
 * A daily target for one nutrient: [amount] to reach (AT_LEAST) or stay under (AT_MOST), and an
 * upper limit where one is set. From adult reference intakes for the person's chosen
 * [EnergyProfile] (never inferred), unless they've set their own ([custom]).
 */
@Serializable
data class MicroTarget(val micro: Micronutrient, val amount: Double, val upper: Double? = null, val custom: Boolean = false)

/**
 * Fibre, sugar, saturated fat and sodium: the parts of [Nutrients] with fields of their own, each
 * with a target on [NutritionTarget] (fibre to reach, the rest to stay under).
 */
@Serializable
enum class ExtraNutrient(val label: String, val unit: String, val aim: MicroAim) {
    FIBER("Fibre", "g", MicroAim.AT_LEAST),
    SUGAR("Sugar", "g", MicroAim.AT_MOST),
    SATURATED_FAT("Saturated fat", "g", MicroAim.AT_MOST),
    SODIUM("Sodium", "mg", MicroAim.AT_MOST),
    ;

    /** How much of it [n] holds, where known. */
    fun of(n: Nutrients): Double? = when (this) {
        FIBER -> n.fiber
        SUGAR -> n.sugar
        SATURATED_FAT -> n.saturatedFat
        SODIUM -> n.sodiumMg
    }

    /** The day's target for it, where there is one. */
    fun target(t: NutritionTarget): Double? = when (this) {
        FIBER -> t.fiberG
        SUGAR -> t.sugarG
        SATURATED_FAT -> t.saturatedFatG
        SODIUM -> t.sodiumMg
    }
}

/**
 * One nutrient's target in the settings (`GET /api/food/targets`): [key] is an [ExtraNutrient] or
 * [Micronutrient] name. [reference] is the reference intake for the person's chosen
 * [EnergyProfile] (none for a few, like monounsaturated fat), [upper] the upper limit where there
 * is one, and [own] the person's own target, if they've set one.
 */
@Serializable
data class NutrientTargetSetting(val key: String, val reference: Double? = null, val upper: Double? = null, val own: Double? = null)

/** Every nutrient's target, with where the reference intakes come from: [basis] ("Adult reference intake, female values") and [source]. */
@Serializable
data class NutrientTargets(val items: List<NutrientTargetSetting>, val basis: String, val source: String)

/** `PUT /api/food/targets`: the person's own targets by key; a nutrient left out goes back to its reference intake. */
@Serializable
data class NutrientTargetsChange(val own: Map<String, Double> = emptyMap())

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
    /** Fibre, sodium, vitamins and minerals: a day's targets (see [MicroTarget]). */
    val micros: List<MicroTarget> = emptyList(),
    /** Fibre to reach, and sugar, saturated fat and sodium to stay under (see [ExtraNutrient]), where there's a target. */
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    val saturatedFatG: Double? = null,
    val sodiumMg: Double? = null,
    /** Where those targets come from, in a few words: "Adult reference intake, female values". */
    val microBasis: String? = null,
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
