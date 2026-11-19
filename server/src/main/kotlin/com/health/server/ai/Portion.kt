package com.health.server.ai

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.health.api.PortionFood
import kotlin.math.roundToInt

/**
 * "How much did I have?" (POST /api/ai/portion): a photo of what someone's eating (and its package,
 * if they like) with the food they're adding, and the assistant judges how many servings that is.
 * The prompt, the answer's shape (structured output, its schema made from [Reading]) and tidying
 * it. The call itself is [AiService.portion]; the photos go through the same pipeline as
 * [FoodPhoto] and are never kept.
 */
object Portion {
    /** A number of servings and a sentence: a small answer. */
    const val MAX_TOKENS = 1_500L

    /** The most servings it may say: a family-size bag of something small. */
    const val MAX_SERVINGS = 50.0

    const val COULD_NOT = "Couldn't tell from that one; try another angle, or set the servings yourself"

    val SYSTEM_PROMPT = """
        You judge how much of a known food someone has in a photo, for someone logging what they ate in their own health app.
        You're told the food, its serving (a label, and grams when known), and maybe the whole package's size or how many
        servings a recipe makes. They'll check and change your answer before it's logged.

        - Answer with a number of servings of that serving: 1.5 means one and a half servings. Use the plate, bowl, cutlery,
          hands and packaging for scale. A second photo, if there is one, shows the package.
        - For a recipe, a serving is one share of the whole recipe: use how many it makes to judge.
        - If the person adds a note (like "half eaten" or "I had all of it"), take it into account.
        - Give one short sentence, under 20 words, on how you judged it, like "Looks like about half the bag".
        - Describe the food neutrally. Never judge the food, the amount or the person, and give no advice.
        - If there's no food in the photo or you can't tell how much, answer 0 servings and say so in a few words.
    """.trimIndent()

    /** The words that go with the photos: the food, its serving, the package or recipe, and the person's note. */
    fun request(food: PortionFood, hasPackage: Boolean, note: String?): String = buildString {
        append("How many servings of this are in the photo")
        append(if (hasPackage) " (the second photo is the package)?" else "?")
        append("\nFood: ").append(food.name.trim().take(160))
        food.brand?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" (").append(it.take(120)).append(")") }
        append("\nOne serving: ").append(food.serving.trim().take(80).ifEmpty { "1 serving" })
        grams(food.servingGrams)?.let { append(" = ").append(it).append(" g") }
        grams(food.packageGrams)?.let { append("\nThe whole package: ").append(it).append(" g") }
        food.recipeServings?.takeIf { it.isFinite() && it > 0 && it < 1000 }?.let { append("\nThe whole recipe makes ").append(number(it)).append(" servings") }
        note?.let { append("\nTheir note: \"").append(it).append("\"") }
    }

    private fun grams(v: Double?): String? = v?.takeIf { it.isFinite() && it > 0 && it < 100_000 }?.let(::number)

    private fun number(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else ((v * 10).roundToInt() / 10.0).toString()

    @JsonClassDescription("How much of the food is in the photo")
    class Reading(
        @field:JsonPropertyDescription("How many servings, like 1.5; 0 if there's no food or it can't be told")
        val servings: Double = 0.0,
        @field:JsonPropertyDescription("One short sentence on how it was judged")
        val note: String = "",
    )

    /** The servings, sane: above nothing, at most [MAX_SERVINGS], to two places; null if it couldn't tell. */
    fun servings(reading: Reading): Double? = reading.servings
        .takeIf { it.isFinite() && it >= 0.01 }
        ?.coerceAtMost(MAX_SERVINGS)
        ?.let { (it * 100).roundToInt() / 100.0 }
        ?.coerceAtLeast(0.05)

    /** The sentence, tidy: one line, not too long. */
    fun note(reading: Reading): String = reading.note.trim().replace(Regex("\\s+"), " ").take(200)
}
