package com.health.app.ui.food

import com.health.api.Food
import com.health.api.FoodGrade
import com.health.api.Nutrients
import com.health.api.ShoppingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShoppingTextTest {
    private val oats = Food("Oats", Nutrients(152.0, 5.3, 27.0, 2.6, fiber = 4.0, sugar = 0.4, saturatedFat = 0.5, sodiumMg = 2.0), "40 g", 40.0, grade = FoodGrade("A", listOf("Good fibre")))
    private val cola = Food("Cola", Nutrients(139.0, carbs = 35.0, sugar = 35.0), "1 can")

    @Test
    fun `key nutrients per serving, with what isn't known as a dash`() {
        val lines = ShoppingText.keyNutrients(cola).toMap()
        assertEquals("139 kcal", lines["Calories"])
        assertEquals("35 g", lines["Sugars"])
        assertEquals("—", lines["Fibre"])
        assertEquals("—", lines["Sodium"])
    }

    @Test
    fun `two side by side, and without numbers only grades and servings`() {
        val lines = ShoppingText.compare(oats, cola)
        assertEquals(Triple("Grade", "A", "—"), lines[0])
        assertEquals(Triple("Sugars", "0.4 g", "35 g"), lines.first { it.first == "Sugars" })
        assertEquals(2, ShoppingText.compare(oats, cola, numbers = false).size)
        assertEquals(listOf(oats, null), ShoppingText.compareWith(listOf(null, null), oats))
        assertEquals(listOf(oats, cola), ShoppingText.compareWith(listOf(oats, null), cola))
        assertEquals(listOf(oats, cola), ShoppingText.compareWith(listOf(oats, oats), cola))
    }

    @Test
    fun `the list shows what's still to buy first`() {
        fun item(id: String, checked: Boolean) = ShoppingItem(id, id, checked = checked)
        val items = listOf(item("a", true), item("b", false), item("c", false))
        assertEquals(listOf("b", "c", "a"), ShoppingText.order(items).map { it.id })
        assertEquals("Clear 1 bought", ShoppingText.clearLabel(items))
        assertNull(ShoppingText.clearLabel(listOf(item("b", false))))
    }
}
