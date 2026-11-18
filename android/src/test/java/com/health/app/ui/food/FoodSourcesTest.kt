package com.health.app.ui.food

import com.health.api.Food
import com.health.api.FoodSource
import com.health.api.Nutrients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a food came from, and a search's foods under headings. */
class FoodSourcesTest {
    private fun food(name: String, source: FoodSource) = Food(name, Nutrients(100.0), source = source)

    @Test
    fun `each source has a plain name, and the person's own foods none`() {
        assertEquals("USDA", FoodSources.name(FoodSource.USDA))
        assertEquals("Open Food Facts", FoodSources.name(FoodSource.OPEN_FOOD_FACTS))
        assertEquals("Mealie", FoodSources.name(FoodSource.MEALIE))
        assertNull(FoodSources.name(FoodSource.CUSTOM))
    }

    @Test
    fun `results are grouped under where they came from, in order`() {
        val groups = FoodSources.groups(listOf(food("Banana, raw", FoodSource.USDA), food("Greek Yogurt", FoodSource.USDA), food("Skyr", FoodSource.OPEN_FOOD_FACTS)))
        assertEquals(listOf("USDA FoodData Central" to listOf("Banana, raw", "Greek Yogurt"), "Open Food Facts" to listOf("Skyr")), groups.map { (t, f) -> t to f.map { it.name } })
        assertEquals(emptyList<Pair<String, List<Food>>>(), FoodSources.groups(emptyList()))
    }
}
