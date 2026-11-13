package com.health.app.ui.food

import com.health.api.ExtraNutrient
import com.health.api.FoodEntry
import com.health.api.Meal
import com.health.api.MicroTarget
import com.health.api.Micronutrient
import com.health.api.NutrientTargetSetting
import com.health.api.Nutrients
import com.health.api.NutritionTarget
import com.health.api.TrendDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MicroTextTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private val target = NutritionTarget(
        2000, 100, 250, 67, 2000, "",
        micros = listOf(MicroTarget(Micronutrient.IRON, 18.0, 45.0), MicroTarget(Micronutrient.CALCIUM, 1000.0, 2500.0), MicroTarget(Micronutrient.VITAMIN_D, 15.0, 100.0, custom = true)),
        fiberG = 25.0, sugarG = 90.0, saturatedFatG = 22.2, sodiumMg = 2300.0,
    )
    private val totals = Nutrients(1500.0, 80.0, 200.0, 50.0, fiber = 12.0, sodiumMg = 1450.0, micros = mapOf(Micronutrient.IRON to 9.5, Micronutrient.VITAMIN_D to 120.0))

    private fun row(key: String, n: Nutrients = totals) = MicroText.row(MicroText.info(key), n, target)

    @Test
    fun `every nutrient is listed once, in groups, with the main few among them`() {
        assertEquals(Micronutrient.entries.size + ExtraNutrient.entries.size, MicroText.ALL.size)
        assertEquals(MicroText.ALL.size, MicroText.ALL.map { it.key }.toSet().size)
        assertEquals(listOf("FIBER", "SODIUM", "IRON", "CALCIUM", "VITAMIN_D", "POTASSIUM"), MicroText.rows(totals, target, all = false).map { it.info.key })
        assertEquals(NutrientGroup.MINERALS, MicroText.info("SODIUM").group)
        assertEquals(NutrientGroup.FATS, MicroText.info("SATURATED_FAT").group)
    }

    @Test
    fun `nothing carried says No data, never 0`() {
        val calcium = row("CALCIUM")
        assertEquals(MicroRow.State.NONE, calcium.state)
        assertEquals("No data", calcium.text)
        assertNull(calcium.amount)
        assertNull(calcium.fraction)
    }

    @Test
    fun `amounts read against targets, and limits as limits`() {
        assertEquals("9.5 of 18 mg", row("IRON").text)
        assertEquals(9.5f / 18f, row("IRON").fraction!!, 1e-4f)
        assertEquals("1,450 mg, under your limit", row("SODIUM").text)
        val over = row("SODIUM", totals.copy(sodiumMg = 2600.0))
        assertEquals(MicroRow.State.OVER_LIMIT, over.state)
        assertEquals("A little over your limit today", over.note)
        val d = row("VITAMIN_D")
        assertEquals(MicroRow.State.MET, d.state)
        assertEquals(1f, d.fraction!!, 0f)
        assertTrue(d.note!!.contains("upper limit"))
        val mono = row("MONO_FAT", totals.copy(micros = mapOf(Micronutrient.MONO_FAT to 14.25)))
        assertEquals(MicroRow.State.PLAIN, mono.state)
        assertEquals("14.3 g", mono.text)
    }

    @Test
    fun `amounts keep enough figures to mean something`() {
        assertEquals("0.63", MicroText.amountText(0.63))
        assertEquals("4.2", MicroText.amountText(4.2))
        assertEquals("135", MicroText.amountText(135.4))
        assertEquals("1,200", MicroText.amountText(1200.0))
        assertEquals("2.4 µg", MicroText.withUnit(2.4, "µg"))
    }

    private fun entry(name: String, n: Nutrients = Nutrients(100.0)) = FoodEntry("2026-11-13", Meal.LUNCH, name, n)

    @Test
    fun `how complete the day is, in words`() {
        val list = listOf(entry("Cereal", Nutrients(100.0, micros = mapOf(Micronutrient.IRON to 4.0))), entry("Toast"), entry("Soup", Nutrients(100.0, micros = mapOf(Micronutrient.POTASSIUM to 300.0))), entry("Tea", Nutrients(2.0, fiber = 0.0)))
        assertEquals("Vitamins and minerals came with 2 of 4 foods today. Only those count.", MicroText.coverage(list, isToday = true))
        assertEquals("None of the foods that day came with vitamins or minerals. Ones found by search or barcode often do.", MicroText.coverage(listOf(entry("Toast")), isToday = false))
        assertNull(MicroText.coverage(emptyList(), true))
    }

    @Test
    fun `what a food carries, and what's typed in`() {
        val n = Nutrients(120.0, fiber = 2.7, micros = mapOf(Micronutrient.IRON to 4.2, Micronutrient.CALCIUM to 135.0, Micronutrient.VITAMIN_B12 to 0.63))
        assertEquals("Fibre 2.7 g · Iron 4.2 mg · Calcium 135 mg · Vitamin B12 0.63 µg", MicroText.carried(n))
        assertEquals("Fibre 2.7 g · Iron 4.2 mg · and 2 more", MicroText.carried(n, 2))
        assertNull(MicroText.carried(Nutrients(95.0)))

        val (typed, problem) = MicroText.typed(Nutrients(200.0), mapOf("FIBER" to "3,5", "IRON" to "2", "CALCIUM" to " ", "SODIUM" to "0"))
        assertNull(problem)
        assertEquals(3.5, typed.fiber!!, 1e-9)
        assertEquals(0.0, typed.sodiumMg!!, 1e-9)
        assertEquals(mapOf(Micronutrient.IRON to 2.0), typed.micros)
        assertNull(typed.sugar)
        assertTrue(MicroText.typed(Nutrients(200.0), mapOf("ZINC" to "-1")).second!!.startsWith("Zinc"))
    }

    @Test
    fun `charts and settings read the same nutrients`() {
        val day = TrendDay("2026-11-13", fiber = 18.0, micros = mapOf(Micronutrient.IRON to 11.0))
        assertEquals(18.0, MicroText.amount(day, MicroText.info("FIBER"))!!, 1e-9)
        assertEquals(11.0, MicroText.amount(day, MicroText.info("IRON"))!!, 1e-9)
        assertNull(MicroText.amount(day, MicroText.info("CALCIUM")))
        assertEquals("Reference 18 mg · upper limit 45 mg", MicroText.referenceText(NutrientTargetSetting("IRON", 18.0, 45.0)))
        assertEquals("Limit 2,300 mg", MicroText.referenceText(NutrientTargetSetting("SODIUM", 2300.0)))
        assertEquals("No reference intake", MicroText.referenceText(NutrientTargetSetting("MONO_FAT")))
    }
}
