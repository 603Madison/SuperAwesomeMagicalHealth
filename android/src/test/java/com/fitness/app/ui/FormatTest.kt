package com.fitness.app.ui

import com.fitness.api.Meal
import com.fitness.api.Units
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

class FormatTest {
    init {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `weights in kilograms and pounds`() {
        assertEquals("68.0 kg", Format.weight(68.0, Units.METRIC))
        assertEquals("149.9 lb", Format.weight(68.0, Units.IMPERIAL))
        assertEquals(68.0, Format.weightToKg(Format.weightValue(68.0, Units.IMPERIAL), Units.IMPERIAL), 1e-9)
    }

    @Test
    fun `distances in kilometres and miles`() {
        assertEquals("5.20 km", Format.distance(5200.0, Units.METRIC))
        assertEquals("12.5 km", Format.distance(12_500.0, Units.METRIC))
        assertEquals("3.11 mi", Format.distance(5000.0, Units.IMPERIAL))
        assertEquals(1609.344, Format.distanceToMetres(1.0, Units.IMPERIAL), 1e-9)
    }

    @Test
    fun `heights in centimetres and feet and inches`() {
        assertEquals("170 cm", Format.height(170.0, Units.METRIC))
        assertEquals("5′7″", Format.height(170.0, Units.IMPERIAL))
        assertEquals(170.18, Format.heightToCm(5, 7.0), 1e-6)
    }

    @Test
    fun `water, temperature differences and durations`() {
        assertEquals("750 ml", Format.water(750, Units.METRIC))
        assertEquals("2.0 L", Format.water(2000, Units.METRIC))
        assertEquals("68 fl oz", Format.water(2000, Units.IMPERIAL))
        assertEquals("+0.3 °C", Format.temperatureDelta(0.3, Units.METRIC))
        assertEquals("−0.9 °F", Format.temperatureDelta(-0.5, Units.IMPERIAL))
        assertEquals("±0.0 °C", Format.temperatureDelta(0.01, Units.METRIC))
        assertEquals("7h 32m", Format.minutes(452))
        assertEquals("8h", Format.minutes(480))
        assertEquals("45m", Format.minutes(45))
    }

    @Test
    fun `days are named as people say them`() {
        val today = LocalDate.of(2026, 11, 12)
        assertEquals("Today", Format.day(today, today))
        assertEquals("Yesterday", Format.day(today.minusDays(1), today))
        assertEquals("Mon, 9 Nov", Format.day(LocalDate.of(2026, 11, 9), today))
        assertEquals("9 Nov 2025", Format.day(LocalDate.of(2025, 11, 9), today))
        assertEquals("Thursday, 12 November", Format.longDay(today))
    }

    @Test
    fun `how long ago`() {
        val now = 1_000_000_000L
        assertEquals("just now", Format.ago(now - 10_000, now))
        assertEquals("5 min ago", Format.ago(now - 5 * 60_000, now))
        assertEquals("3 h ago", Format.ago(now - 3 * 3_600_000, now))
        assertEquals("4 days ago", Format.ago(now - 4 * 86_400_000L, now))
    }

    @Test
    fun `numbers typed either way`() {
        assertEquals(72.5, Format.parse("72,5")!!, 1e-9)
        assertEquals(72.5, Format.parse(" 72.5 ")!!, 1e-9)
        assertEquals(null, Format.parse("lots"))
        assertEquals("1.5", Format.compact(1.5))
        assertEquals("2", Format.compact(2.0))
        assertEquals("1,234", Format.number(1234.4))
    }

    @Test
    fun `the meal it probably is`() {
        assertEquals(Meal.BREAKFAST, mealNow(LocalTime.of(7, 30)))
        assertEquals(Meal.LUNCH, mealNow(LocalTime.of(12, 15)))
        assertEquals(Meal.SNACK, mealNow(LocalTime.of(15, 45)))
        assertEquals(Meal.DINNER, mealNow(LocalTime.of(19, 0)))
    }
}
