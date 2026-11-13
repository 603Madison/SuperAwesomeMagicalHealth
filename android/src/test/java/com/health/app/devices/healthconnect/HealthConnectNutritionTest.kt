package com.health.app.devices.healthconnect

import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.health.api.FoodEntry
import com.health.api.Meal
import com.health.api.Micronutrient
import com.health.api.Nutrients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class HealthConnectNutritionTest {
    private val zone = ZoneId.of("Europe/London")
    private val at = Instant.parse("2026-11-13T08:00:00Z")

    private val cereal = Nutrients(
        calories = 140.0, protein = 4.0, carbs = 27.0, fat = 1.0, fiber = 6.0, sugar = 7.0, saturatedFat = 0.3, sodiumMg = 190.0,
        micros = mapOf(
            Micronutrient.IRON to 5.6, Micronutrient.CALCIUM to 40.0, Micronutrient.VITAMIN_D to 1.7, Micronutrient.FOLATE to 80.0,
            Micronutrient.VITAMIN_B12 to 0.8, Micronutrient.CHOLESTEROL to 0.0, Micronutrient.MONO_FAT to 0.4, Micronutrient.OMEGA_3 to 0.1,
        ),
    )

    private fun record(n: Nutrients) = HealthConnectNutrition.record(n, at, ZoneOffset.UTC, at.plusSeconds(60), ZoneOffset.UTC, Metadata.manualEntry(), "Bran flakes", MealType.MEAL_TYPE_BREAKFAST)

    @Test
    fun `food goes to Health Connect with its vitamins and minerals, each in its unit`() {
        val r = record(cereal)
        assertEquals(5.6, r.iron!!.inMilligrams, 1e-6)
        assertEquals(0.0056, r.iron!!.inGrams, 1e-9)
        assertEquals(40.0, r.calcium!!.inMilligrams, 1e-6)
        assertEquals(1.7, r.vitaminD!!.inMicrograms, 1e-6)
        assertEquals(80.0, r.folate!!.inMicrograms, 1e-6)
        assertEquals(0.8, r.vitaminB12!!.inMicrograms, 1e-6)
        assertEquals(0.4, r.monounsaturatedFat!!.inGrams, 1e-9)
        assertEquals(0.0, r.cholesterol!!.inMilligrams, 1e-9) // a real zero stays a zero
        assertEquals(6.0, r.dietaryFiber!!.inGrams, 1e-9)
        assertEquals(190.0, r.sodium!!.inMilligrams, 1e-6)
        // What wasn't known isn't written as 0.
        assertNull(r.vitaminC)
        assertNull(r.zinc)
        assertNull(r.potassium)
    }

    @Test
    fun `and comes back the same, with what was missing still missing`() {
        val back = HealthConnectNutrition.nutrients(record(cereal))
        assertEquals(140.0, back.calories, 1e-6)
        assertEquals(6.0, back.fiber!!, 1e-9)
        assertEquals(190.0, back.sodiumMg!!, 1e-6)
        listOf(Micronutrient.IRON, Micronutrient.CALCIUM, Micronutrient.VITAMIN_D, Micronutrient.FOLATE, Micronutrient.VITAMIN_B12, Micronutrient.MONO_FAT).forEach { m ->
            assertEquals(m.name, cereal.micros[m]!!, back.micros[m]!!, 1e-6)
        }
        // Health Connect has no omega-3: it doesn't travel.
        assertFalse(Micronutrient.OMEGA_3 in back.micros)
        assertFalse(Micronutrient.OMEGA_3 in HealthConnectNutrition.CARRIED)
        assertNull(back.micros[Micronutrient.VITAMIN_C])
        assertEquals(Micronutrient.entries.size - 1, HealthConnectNutrition.CARRIED.size)
    }

    @Test
    fun `a record from another app (say MyFitnessPal) reads into the app's units`() {
        val r = NutritionRecord(
            startTime = at, startZoneOffset = null, endTime = at.plusSeconds(60), endZoneOffset = null, metadata = Metadata.manualEntry(),
            energy = androidx.health.connect.client.units.Energy.kilocalories(250.0),
            potassium = androidx.health.connect.client.units.Mass.grams(0.42),
            selenium = androidx.health.connect.client.units.Mass.milligrams(0.03),
            vitaminA = androidx.health.connect.client.units.Mass.micrograms(150.0),
            transFat = androidx.health.connect.client.units.Mass.grams(0.2),
        )
        val n = HealthConnectNutrition.nutrients(r)
        assertEquals(420.0, n.micros[Micronutrient.POTASSIUM]!!, 1e-6)
        assertEquals(30.0, n.micros[Micronutrient.SELENIUM]!!, 1e-6)
        assertEquals(150.0, n.micros[Micronutrient.VITAMIN_A]!!, 1e-6)
        assertEquals(0.2, n.micros[Micronutrient.TRANS_FAT]!!, 1e-9)
        assertNull(n.fiber)
        assertEquals(4, n.micros.size)
    }

    @Test
    fun `too much for Health Connect is kept to its limit rather than failing the write`() {
        val r = record(Nutrients(100.0, micros = mapOf(Micronutrient.IODINE to 900_000_000.0)))
        assertEquals(100.0, r.iodine!!.inGrams, 1e-6)
    }

    @Test
    fun `the export writes an entry's micros, and a change to them writes it again`() {
        val entry = FoodEntry("2026-11-13", Meal.BREAKFAST, "Bran flakes", cereal, id = "e1", loggedAt = at.toEpochMilli())
        val out = HealthConnectExportMapping.food(entry, zone)!!
        val r = out.record(3) as NutritionRecord
        assertEquals(5.6, r.iron!!.inMilligrams, 1e-6)
        assertEquals("samh:food:e1", r.metadata.clientRecordId)
        assertEquals(MealType.MEAL_TYPE_BREAKFAST, r.mealType)
        val more = HealthConnectExportMapping.food(entry.copy(nutrients = cereal.copy(micros = cereal.micros + (Micronutrient.ZINC to 1.2))), zone)!!
        assertTrue(out.fingerprint != more.fingerprint)
    }
}
