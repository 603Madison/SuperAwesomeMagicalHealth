package com.health.app.ui.today

import com.health.api.FieldLayout
import com.health.api.Fields
import com.health.api.Module
import com.health.api.QuickAction
import com.health.api.QuickAction.CHECK_IN
import com.health.api.QuickAction.COFFEE
import com.health.api.QuickAction.DRINK
import com.health.api.QuickAction.FOOD
import com.health.api.QuickAction.MEDITATE
import com.health.api.QuickAction.SLEEP
import com.health.api.QuickAction.WATER
import com.health.api.QuickAction.WEIGHT
import com.health.api.QuickAction.WORKOUT
import com.health.api.ScreenLayout
import com.health.api.TodaySection
import com.health.app.devices.healthconnect.ExportKind
import com.health.app.devices.healthconnect.HealthConnectExportMapping
import com.health.app.ui.components.mergeOrder
import com.health.app.ui.food.FoodSections
import com.health.app.ui.settings.ExportState
import com.health.app.ui.settings.WaterSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Today's sections and the + sheet's grid, in the person's order, and water turned off. */
class TodayLayoutTest {
    @Test
    fun `today is the scores, Right now, Your day and For you, by their saved ids`() {
        assertEquals(listOf(TodaySection.SCORES, TodaySection.UP_NEXT, TodaySection.DAY, TodaySection.INSIGHTS), TodayLayout.sections(Module.DEFAULT))
        // The scores need a ring's or watch's features; the rest are everyone's.
        assertEquals(listOf(TodaySection.UP_NEXT, TodaySection.DAY, TodaySection.INSIGHTS), TodayLayout.sections(setOf(Module.MIND)))
        assertEquals(listOf(TodaySection.SCORES, TodaySection.UP_NEXT, TodaySection.DAY, TodaySection.INSIGHTS), TodayLayout.sections(Module.entries.toSet()))
        // Up next is called Right now; the old cards' ids stay in the enum, saved, but aren't on Today.
        assertEquals("Right now", TodayLayout.label(TodaySection.UP_NEXT))
        assertEquals("Your day", TodayLayout.label(TodaySection.DAY))
        assertEquals("For you", TodayLayout.label(TodaySection.INSIGHTS))
        listOf(TodaySection.QUICK, TodaySection.ALSO, TodaySection.FOOD, TodaySection.MEDS, TodaySection.SLEEP).forEach { assertFalse(TodayLayout.applies(it, Module.entries.toSet())) }
    }

    @Test
    fun `the person's order comes first, hidden sections go, and old cards' ids are ignored`() {
        // Saved by the older app, with its cards: theirs are left out, ours keep the order given.
        val layout = ScreenLayout(listOf("FOOD", "INSIGHTS", "QUICK", "SCORES", "MIND"), hidden = setOf("UP_NEXT", "HEART"))
        val shown = TodayLayout.visible(Module.DEFAULT, layout)
        // Your day, new since that order was saved, goes after Right now, where it is by default.
        assertEquals(listOf(TodaySection.INSIGHTS, TodaySection.SCORES, TodaySection.DAY), shown)
        val hidden = TodayLayout.visible(Module.DEFAULT, ScreenLayout(hidden = setOf("DAY")))
        assertEquals(listOf(TodaySection.SCORES, TodaySection.UP_NEXT, TodaySection.INSIGHTS), hidden)
    }

    @Test
    fun `the + sheet's grid is every way to log for the features on, in the person's order`() {
        val available = TodayLayout.quickActions(Module.DEFAULT)
        assertEquals(listOf(FOOD, WATER, WEIGHT, COFFEE, DRINK, WORKOUT, SLEEP, CHECK_IN, MEDITATE), available)
        assertEquals(available, TodayLayout.grid(available, null))
        // Arranged: theirs first; what they hid from the old Quick log is still there (everything tracked shows).
        val arranged = TodayLayout.grid(available, ScreenLayout(listOf("CHECK_IN", "FOOD"), hidden = setOf("WATER", "COFFEE")))
        // (Meditate, newer than the saved order, keeps its place after Check in.)
        assertEquals(listOf(CHECK_IN, MEDITATE, FOOD), arranged.take(3))
        assertEquals(available.toSet(), arranged.toSet())
    }

    @Test
    fun `ways to log follow the features, and Measurements their fields`() {
        val everything = TodayLayout.quickActions(Module.entries.toSet(), water = true, measurements = true)
        assertEquals(QuickAction.entries.toList(), everything)
        assertFalse(QuickAction.MEASUREMENTS in TodayLayout.quickActions(Module.entries.toSet(), measurements = false))
        assertFalse(QuickAction.MOOD in TodayLayout.quickActions(Module.DEFAULT))
        // Check in and Meditate are everyone's.
        assertEquals(listOf(CHECK_IN, MEDITATE), TodayLayout.quickActions(emptySet()))
    }

    @Test
    fun `with water off, the + sheet has no water`() {
        val available = TodayLayout.quickActions(Module.DEFAULT, water = false)
        assertFalse(WATER in available)
        // A saved order naming water just skips it.
        val arranged = TodayLayout.grid(available, ScreenLayout(listOf("WATER", "COFFEE", "FOOD")))
        assertEquals(COFFEE, arranged.first())
        assertFalse(WATER in arranged)
        // And the Food tab has no water section.
        assertFalse(FoodSections.items(water = false).any { it.id == FoodSections.WATER })
        assertTrue(FoodSections.items(water = true).any { it.id == FoodSections.WATER })
    }

    @Test
    fun `the water switch keeps every other field choice`() {
        val layout = FieldLayout(
            effective = Fields.DEFAULTS - Fields.CYCLE_BBT + Fields.CONTINENCE_ACTIVITY,
            show = setOf(Fields.CONTINENCE_ACTIVITY),
            hide = setOf(Fields.CYCLE_BBT),
        )
        assertTrue(WaterSetting.isOn(layout))
        val off = WaterSetting.choices(layout, on = false)
        assertEquals(setOf(Fields.CONTINENCE_ACTIVITY), off.show)
        assertEquals(setOf(Fields.CYCLE_BBT, Fields.NUTRITION_WATER), off.hide)
        val hidden = layout.copy(effective = layout.effective - Fields.NUTRITION_WATER, hide = off.hide)
        assertFalse(WaterSetting.isOn(hidden))
        val on = WaterSetting.choices(hidden, on = true)
        assertEquals(setOf(Fields.CONTINENCE_ACTIVITY), on.show)
        assertEquals(setOf(Fields.CYCLE_BBT), on.hide)
        // Before the fields load, water shows (it's on by default).
        assertTrue(WaterSetting.isOn(null))
    }

    @Test
    fun `with water off, the phone neither offers nor writes hydration to Health Connect`() {
        val chosen = setOf(ExportKind.WEIGHT, ExportKind.HYDRATION)
        assertEquals(chosen, HealthConnectExportMapping.writable(chosen, Fields.DEFAULTS))
        assertEquals(setOf(ExportKind.WEIGHT), HealthConnectExportMapping.writable(chosen, Fields.DEFAULTS - Fields.NUTRITION_WATER))
        // Not known: not written this time.
        assertEquals(setOf(ExportKind.WEIGHT), HealthConnectExportMapping.writable(chosen, null))
        assertFalse(ExportKind.HYDRATION in ExportState(water = false).kinds)
        assertEquals(ExportKind.entries.toList(), ExportState(water = true).kinds)
    }

    @Test
    fun `arranging keeps the places of sections this phone doesn't offer`() {
        // The web's sidebar order, then the phone moves Cycle above Mind and adds Habits.
        val saved = listOf("TODAY", "TRENDS", "MIND", "CYCLE", "REPORTS")
        assertEquals(listOf("TODAY", "TRENDS", "CYCLE", "MIND", "REPORTS", "HABITS"), mergeOrder(saved, listOf("CYCLE", "MIND", "HABITS")))
        // A quick action for a feature that's off keeps its place, so turning it back on restores it.
        assertEquals(listOf("WATER", "MOOD", "FOOD"), mergeOrder(listOf("FOOD", "MOOD", "WATER"), listOf("WATER", "FOOD")))
        // Nothing saved yet: just the new order.
        assertEquals(listOf("B", "A"), mergeOrder(emptyList(), listOf("B", "A")))
        // A deleted habit's section is dropped, not kept for ever; other kinds of section keep their place.
        assertEquals(
            listOf("habit.b", "summary", "habit.a"),
            mergeOrder(listOf("habit.a", "habit.gone", "summary", "habit.b"), listOf("habit.b", "summary", "habit.a")),
        )
        assertEquals(listOf("lab.x", "level.old", "today"), mergeOrder(listOf("lab.x", "level.old", "today"), listOf("lab.x", "today")))
    }

    @Test
    fun `settings and food keep stable section ids`() {
        assertEquals(listOf("ideas", "totals", "breakfast", "lunch", "dinner", "snack", "water", "drinks"), FoodSections.items(water = true).map { it.id })
        // Ideas are for today only.
        assertEquals(listOf("totals", "breakfast", "lunch", "dinner", "snack", "water", "drinks"), FoodSections.items(water = true, ideas = false).map { it.id })
        // Without amounts of food (eating disorder recovery), no totals.
        assertEquals(listOf("ideas", "breakfast", "lunch", "dinner", "snack", "water", "drinks"), FoodSections.items(water = true, numbers = false).map { it.id })
        assertEquals("android.food", FoodSections.KEY)
    }
}
