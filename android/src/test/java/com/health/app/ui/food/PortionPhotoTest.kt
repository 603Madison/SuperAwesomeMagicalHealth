package com.health.app.ui.food

import com.health.api.ApiJson
import com.health.api.Food
import com.health.api.FoodSource
import com.health.api.Nutrients
import com.health.api.PortionEstimate
import com.health.api.PortionFood
import com.health.app.net.portionParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "How much did I have?": what goes with the photo, and how the answer reads. */
class PortionPhotoTest {
    private val crisps = Food(
        "Sea salt crisps", Nutrients(calories = 130.0, protein = 2.0, carbs = 13.0, fat = 8.0), serving = "1 bag (25 g)", servingGrams = 25.0,
        brand = "Crunchy Co", barcode = "5000000000001", source = FoodSource.OPEN_FOOD_FACTS, packageGrams = 150.0,
    )

    @Test
    fun `only the food's words go, never its numbers`() {
        val sent = PortionText.food(crisps, null)
        assertEquals(PortionFood("Sea salt crisps", "1 bag (25 g)", 25.0, "Crunchy Co", packageGrams = 150.0), sent)
        // A Mealie recipe's yield, from the food or from the recipe list.
        assertEquals(6.0, PortionText.food(crisps.copy(recipeServings = 6.0), 4.0).recipeServings)
        assertEquals(4.0, PortionText.food(crisps, 4.0).recipeServings)

        val parts = portionParts(byteArrayOf(1), byteArrayOf(2), sent, "  ")
        assertEquals(listOf("food", "photo", "package"), parts.map { it.name })
        val json = parts.first().text!!
        assertEquals(sent, ApiJson.decodeFromString(PortionFood.serializer(), json))
        assertFalse(json.contains("calories") || json.contains("5000000000001"))
        assertEquals(listOf("food", "note", "photo"), portionParts(byteArrayOf(1), null, sent, "half eaten").map { it.name })
    }

    @Test
    fun `an estimate prefills the servings, and says it's hers to check`() {
        val e = PortionEstimate(1.5, "Looks like a bag and a half.", usedToday = 1)
        assertEquals(1.5, PortionText.servings(e)!!, 0.0)
        assertEquals("0.33", PortionText.text(0.3333))
        assertEquals("2", PortionText.text(2.0))
        assertEquals("About 1.5 × 1 bag (25 g): Looks like a bag and a half. Check it before adding.", PortionText.line(e, "1 bag (25 g)"))
        assertTrue(PortionText.line(e.copy(note = ""), "1 bag").endsWith("1 bag. Check it before adding."))
    }

    @Test
    fun `when it couldn't tell, nothing is prefilled and it says so`() {
        val none = PortionEstimate(0.0, "No food in view", refused = true)
        assertNull(PortionText.servings(none))
        assertEquals("No food in view", PortionText.line(none, "1 bag"))
        assertEquals(PortionText.COULD_NOT, PortionText.line(none.copy(note = ""), "1 bag"))
    }
}
