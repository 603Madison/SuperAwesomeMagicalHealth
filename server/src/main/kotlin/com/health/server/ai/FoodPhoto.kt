package com.health.server.ai

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.health.api.EstimateConfidence
import com.health.api.Food
import com.health.api.FoodPhotoItem
import com.health.api.Meal
import com.health.api.MyConditions
import com.health.api.Nutrients
import kotlin.math.roundToInt

/**
 * Food photo estimates (POST /api/ai/food-photo): the prompt, the shape Claude answers in
 * (structured output, its schema made from [Reading]), tidying what comes back, and finding the
 * person's own foods with a similar name. The call itself is [AiService.foodPhoto].
 */
object FoodPhoto {
    /** The photo's long side as it's sent: plenty to tell foods apart, and quick to send. */
    const val SIDE = 1024

    /** The output budget: a plate's worth of items, with room to think. */
    const val MAX_TOKENS = 4_000L

    private const val MAX_ITEMS = 20

    /**
     * Eating-disorder recovery (the catalog's condition): the apps show foods and portions without
     * calorie or macro numbers, since numbers about food can make recovery harder.
     */
    const val RECOVERY_CONDITION = "eating-disorder"

    fun hidesNumbers(conditions: MyConditions): Boolean = conditions.conditions.any { it.id == RECOVERY_CONDITION }

    const val COULD_NOT = "Couldn't make that out; try another angle or add it by hand"
    const val NOTHING_SEEN = "Couldn't see any food or drink in that one; try another angle or add it by hand"

    val SYSTEM_PROMPT = """
        You estimate what's in a photo of food, for someone logging what they ate in their own health app. They'll check and
        change your estimate before it's logged.

        - List each food and drink you can see as its own item: a sandwich is one item; a plate of chicken, rice and salad is
          three. Use plain names people would search for, like "Spaghetti bolognese" or "Flat white".
        - Estimate each portion as it will be eaten: grams for food, millilitres for drinks. Use the plate, bowl, cutlery,
          hands and packaging for scale.
        - Estimate calories (kcal), protein, carbs, fat, fibre, sugar and saturated fat (all in grams except calories) and
          sodium (milligrams) for that whole portion, from typical values for the food.
        - Give your confidence (LOW, MEDIUM or HIGH), and one short line on what you assumed, like "assumed cooked weight",
          "dressing not visible" or "assumed semi-skimmed milk".
        - If the person adds a note (like "half eaten" or "large plate"), take it into account.
        - Describe food neutrally. Never judge the food, the amount or the person, and give no advice.
        - If there's no food or drink in the photo, or you can't make it out, return no items and say so in a few words in
          notes. Otherwise leave notes empty unless something matters, like part of the plate being out of view.
    """.trimIndent()

    /** The words that go with the photo: which meal, and the person's note. */
    fun request(meal: Meal?, note: String?): String = buildString {
        append("What's in this photo?")
        meal?.let { append(" It's their ${it.label.lowercase().removeSuffix("s")}.") }
        note?.let { append(" Their note: \"$it\"") }
    }

    // ---- What Claude answers with (the structured output's schema comes from these classes)

    @JsonClassDescription("What's in the photo")
    class Reading(
        @field:JsonPropertyDescription("Each food and drink in the photo, one item each; none if there's no food or it can't be made out")
        val items: List<Item> = emptyList(),
        @field:JsonPropertyDescription("A few words for the person if something matters, or an empty string")
        val notes: String = "",
    )

    enum class PortionUnit { GRAMS, MILLILITRES }

    enum class Sure { LOW, MEDIUM, HIGH }

    class Item(
        @field:JsonPropertyDescription("What it is, in a few plain words")
        val name: String = "",
        @field:JsonPropertyDescription("The portion: grams for food, millilitres for drinks")
        val amount: Double = 0.0,
        @field:JsonPropertyDescription("GRAMS for food, MILLILITRES for drinks")
        val unit: PortionUnit = PortionUnit.GRAMS,
        @field:JsonPropertyDescription("Energy in the whole portion, kcal")
        val calories: Double = 0.0,
        @field:JsonPropertyDescription("Protein in the whole portion, grams")
        val protein: Double = 0.0,
        @field:JsonPropertyDescription("Carbohydrate in the whole portion, grams")
        val carbs: Double = 0.0,
        @field:JsonPropertyDescription("Fat in the whole portion, grams")
        val fat: Double = 0.0,
        @field:JsonPropertyDescription("Fibre in the whole portion, grams")
        val fiber: Double = 0.0,
        // Fibre, sugar, saturated fat and sodium are typical for a food, so a photo can say; vitamins and minerals it can't.
        @field:JsonPropertyDescription("Total sugars in the whole portion, grams")
        val sugar: Double = 0.0,
        @field:JsonPropertyDescription("Saturated fat in the whole portion, grams")
        val saturatedFat: Double = 0.0,
        @field:JsonPropertyDescription("Sodium in the whole portion, milligrams")
        val sodiumMg: Double = 0.0,
        @field:JsonPropertyDescription("How sure the estimate is")
        val confidence: Sure = Sure.MEDIUM,
        @field:JsonPropertyDescription("One short line on what was assumed, like \"assumed cooked weight\"")
        val assumption: String = "",
    )

    /** The items, tidied: named, with a sensible portion and numbers that can be logged. */
    fun items(reading: Reading): List<FoodPhotoItem> = reading.items.mapNotNull { item ->
        val name = item.name.trim().replace(Regex("\\s+"), " ").take(120).ifBlank { return@mapNotNull null }
        val grams = item.amount.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(5_000.0) ?: return@mapNotNull null
        fun n(v: Double, max: Double) = if (v.isFinite()) (v.coerceIn(0.0, max) * 10).roundToInt() / 10.0 else 0.0
        FoodPhotoItem(
            name = name,
            grams = (grams * 10).roundToInt() / 10.0,
            nutrients = Nutrients(
                calories = n(item.calories, 20_000.0),
                protein = n(item.protein, 2_000.0),
                carbs = n(item.carbs, 3_000.0),
                fat = n(item.fat, 2_000.0),
                fiber = n(item.fiber, 500.0),
                sugar = n(item.sugar, 3_000.0),
                saturatedFat = n(item.saturatedFat, 2_000.0),
                sodiumMg = n(item.sodiumMg, 100_000.0),
            ),
            confidence = when (item.confidence) {
                Sure.LOW -> EstimateConfidence.LOW
                Sure.MEDIUM -> EstimateConfidence.MEDIUM
                Sure.HIGH -> EstimateConfidence.HIGH
            },
            assumption = item.assumption.trim().take(160),
            unit = if (item.unit == PortionUnit.MILLILITRES) "ml" else "g",
        )
    }.take(MAX_ITEMS)

    // ---- The person's own foods

    /** Words that say little about which food it is. */
    private val COMMON = setOf(
        "and", "with", "the", "of", "in", "on", "some", "fresh", "homemade", "home", "made", "plain", "serving", "portion",
        "slice", "piece", "bowl", "cup", "glass", "plate", "small", "medium", "large",
    )

    /** A name's telling words: lower case, singular, without the common ones. */
    fun words(name: String): Set<String> = name.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 && it !in COMMON }
        .map { w ->
            when {
                w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
                w.endsWith("oes") && w.length > 4 -> w.dropLast(2)
                w.endsWith("s") && !w.endsWith("ss") && w.length > 3 -> w.dropLast(1)
                else -> w
            }
        }
        .toSet()

    /**
     * How alike two names are, 0 to 1: the share of their telling words they have in common, as
     * long as most of the food's own words are there ("Chicken breast" for "Grilled chicken breast",
     * but not "Banana bread" for "Banana").
     */
    fun similarity(item: Set<String>, food: Set<String>): Double {
        if (item.isEmpty() || food.isEmpty()) return 0.0
        val common = item.intersect(food).size
        if (common == 0 || common.toDouble() / food.size < 0.6) return 0.0
        return common.toDouble() / (item union food).size
    }

    /** The person's food most like [name], if one is alike enough (the most used, between equals). */
    fun match(name: String, foods: List<Food>): Food? {
        val wanted = words(name)
        return foods.asSequence()
            .map { it to similarity(wanted, words(it.name)) }
            .filter { it.second >= 0.5 }
            .maxWithOrNull(compareBy<Pair<Food, Double>>({ it.second }, { it.first.uses }))
            ?.first
    }
}
