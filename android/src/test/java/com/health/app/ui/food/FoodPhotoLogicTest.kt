package com.health.app.ui.food

import com.health.api.AiConsent
import com.health.api.AiStatus
import com.health.api.EstimateConfidence
import com.health.api.Food
import com.health.api.FoodPhotoEstimate
import com.health.api.FoodPhotoItem
import com.health.api.Meal
import com.health.api.Module
import com.health.api.Nutrients
import com.health.app.net.ServerException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Food photo estimates' sums and words: scaling by grams, the person's own food, what's logged. */
class FoodPhotoLogicTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private val bolognese = Food("Homemade spaghetti bolognese", Nutrients(560.0, 30.0, 70.0, 16.0), "1 bowl", servingGrams = 350.0, id = "f1", uses = 6)
    private val toast = Food("Sourdough toast", Nutrients(120.0, 4.0, 22.0, 1.0), "1 slice", id = "f2")
    private val estimate = FoodPhotoEstimate(
        items = listOf(
            FoodPhotoItem("Spaghetti bolognese", 320.0, Nutrients(480.0, 24.0, 62.0, 14.0, fiber = 5.0), EstimateConfidence.MEDIUM, "assumed cooked weight", match = bolognese),
            FoodPhotoItem("Toast", 40.0, Nutrients(110.0, 3.6, 20.0, 1.2), EstimateConfidence.HIGH, "", match = toast),
            FoodPhotoItem("Orange juice", 250.0, Nutrients(110.0, 1.7, 26.0, 0.5), EstimateConfidence.LOW, "assumed a standard glass", unit = "ml"),
        ),
        usedToday = 3,
    )
    private val rows = FoodPhotoText.rows(estimate)

    @Test
    fun `nutrients follow the grams`() {
        val half = rows[0].copy(amount = "160")
        val n = FoodPhotoText.nutrients(half)
        assertEquals(240.0, n.calories, 0.001)
        assertEquals(12.0, n.protein, 0.001)
        assertEquals(31.0, n.carbs, 0.001)
        assertEquals(7.0, n.fat, 0.001)
        assertEquals(2.5, n.fiber!!, 0.001)
        // As it came, and doubled.
        assertEquals(480.0, FoodPhotoText.nutrients(rows[0]).calories, 0.001)
        assertEquals(960.0, FoodPhotoText.nutrients(rows[0].copy(amount = "640")).calories, 0.001)
        assertEquals(1.5, FoodPhotoText.scaled(estimate.items[0], 1.0).calories, 0.001)
        // A comma works as a decimal point; nothing sensible is nothing.
        assertEquals(110.0 * 1.25, FoodPhotoText.nutrients(rows[2].copy(amount = "312,5")).calories, 0.001)
        assertEquals(0.0, FoodPhotoText.nutrients(rows[0].copy(amount = "")).calories, 0.0)
        assertEquals(0.0, FoodPhotoText.nutrients(rows[0].copy(amount = "-20")).calories, 0.0)
    }

    @Test
    fun `the person's own food stands in, by weight or by servings`() {
        val mine = FoodPhotoText.useMine(rows[0], true)
        assertTrue(mine.useMine)
        assertEquals("320", mine.amount)
        assertEquals("g", FoodPhotoText.unit(mine))
        assertEquals(560.0 * 320 / 350, FoodPhotoText.nutrients(mine).calories, 0.001)
        val input = FoodPhotoText.input(mine, "2026-11-12", Meal.DINNER)
        assertEquals(bolognese, input.food)
        assertEquals(320.0 / 350, input.servings, 0.0001)
        assertNull(input.nutrients)

        // No weight per serving: counted in servings, starting at one.
        val slices = FoodPhotoText.useMine(rows[1], true)
        assertEquals("1", slices.amount)
        assertEquals("× 1 slice", FoodPhotoText.unit(slices))
        assertEquals(240.0, FoodPhotoText.nutrients(slices.copy(amount = "2")).calories, 0.001)
        assertEquals(2.0, FoodPhotoText.input(slices.copy(amount = "2"), "2026-11-12", Meal.BREAKFAST).servings, 0.0)

        // And back to the estimate, at its own amount.
        val back = FoodPhotoText.useMine(slices, false)
        assertFalse(back.useMine)
        assertEquals("40", back.amount)
        assertEquals(110.0, FoodPhotoText.nutrients(back).calories, 0.001)
    }

    @Test
    fun `an estimate is logged as itself, its portion as the serving`() {
        val input = FoodPhotoText.input(rows[2].copy(name = " Fresh orange juice ", amount = "500"), "2026-11-12", Meal.BREAKFAST)
        assertEquals("Fresh orange juice", input.name)
        assertEquals(1.0, input.servings, 0.0)
        assertEquals("about 500 ml", input.serving)
        assertEquals(220.0, input.nutrients!!.calories, 0.001)
        assertNull(input.food)
        assertEquals("about 320 g", FoodPhotoText.input(rows[0], "2026-11-12", Meal.LUNCH).serving)
    }

    @Test
    fun `totals leave out rows without a name or an amount, and count rows added by hand`() {
        val added = FoodPhotoText.added(99).copy(name = "Parmesan", amount = "10", calories = "40")
        val all = rows + added + rows[0].copy(key = 7, name = "  ") + rows[0].copy(key = 8, amount = "0")
        assertEquals(4, FoodPhotoText.ready(all).size)
        assertEquals(480.0 + 110 + 110 + 40, FoodPhotoText.total(all).calories, 0.001)
        assertEquals(40.0, FoodPhotoText.nutrients(added).calories, 0.0)
        assertEquals(0.0, FoodPhotoText.nutrients(added).protein, 0.0)
    }

    @Test
    fun `it's offered with the assistant on, a yes and Food allowed, and never while viewing a share`() {
        val yes = AiConsent(setOf(Module.NUTRITION, Module.SLEEP), acknowledged = true)
        val on = AiStatus(available = true, consent = yes)
        assertEquals(PhotoOffer.READY, FoodPhotoText.offer(on, viewing = false))
        assertNull(FoodPhotoText.offer(on, viewing = true))
        assertNull(FoodPhotoText.offer(on.copy(available = false), viewing = false))
        assertNull(FoodPhotoText.offer(null, viewing = false))
        assertEquals(PhotoOffer.SETTINGS, FoodPhotoText.offer(on.copy(consent = null), viewing = false))
        assertEquals(PhotoOffer.SETTINGS, FoodPhotoText.offer(on.copy(consent = yes.copy(modules = setOf(Module.SLEEP))), viewing = false))
        assertEquals(PhotoOffer.SETTINGS, FoodPhotoText.offer(on.copy(consent = yes.copy(acknowledged = false)), viewing = false))
    }

    @Test
    fun `words are plain`() {
        assertEquals("A fair guess: assumed cooked weight", FoodPhotoText.confidence(estimate.items[0]))
        assertEquals("A close guess", FoodPhotoText.confidence(estimate.items[1]))
        assertEquals("480 kcal · P 24 g · C 62 g · F 14 g", FoodPhotoText.numbers(estimate.items[0].nutrients))
        assertTrue(FoodPhotoText.notice("Anthropic").startsWith("The photo is sent to Anthropic to estimate what's in it."))
        assertEquals("The assistant isn't turned on on this server.", FoodPhotoText.error(ServerException(409, "x")))
        assertEquals("Let the assistant see Food first", FoodPhotoText.error(ServerException(403, "Let the assistant see Food first")))
        assertTrue(FoodPhotoText.error(ServerException(0, "x")).startsWith("Can't reach your server"))
    }
}
