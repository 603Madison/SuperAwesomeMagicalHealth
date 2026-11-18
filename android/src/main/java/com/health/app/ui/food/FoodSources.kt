package com.health.app.ui.food

import com.health.api.Food
import com.health.api.FoodSource

/** Where a food came from, in words, and a search's foods from the food databases under headings (USDA first, as the server sends them). */
object FoodSources {
    /** The heading when the food databases found nothing, or couldn't be asked. */
    const val NONE_TITLE = "Food databases"

    /** "USDA", "Open Food Facts", "Mealie"; null for the person's own. */
    fun name(source: FoodSource): String? = when (source) {
        FoodSource.USDA -> "USDA"
        FoodSource.OPEN_FOOD_FACTS -> "Open Food Facts"
        FoodSource.MEALIE -> "Mealie"
        FoodSource.CUSTOM -> null
    }

    private fun title(source: FoodSource): String = if (source == FoodSource.USDA) "USDA FoodData Central" else name(source) ?: NONE_TITLE

    /** [online] in runs of the same source, each with its heading, in the order they came. */
    fun groups(online: List<Food>): List<Pair<String, List<Food>>> {
        val out = mutableListOf<Pair<String, MutableList<Food>>>()
        for (food in online) {
            val title = title(food.source)
            if (out.lastOrNull()?.first == title) out.last().second += food else out += title to mutableListOf(food)
        }
        return out
    }

    /** Offered for a barcode neither database knows. */
    fun unknownBarcode(code: String) = "Barcode $code isn't in the food databases yet. Add it as a food of your own? You'll find it by name or barcode next time."
}
