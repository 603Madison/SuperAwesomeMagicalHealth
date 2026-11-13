package com.health.app.ui.today

import com.health.api.FieldLayout
import com.health.api.Fields
import com.health.api.Layouts
import com.health.api.Module
import com.health.api.NavPage
import com.health.api.QuickAction
import com.health.api.QuickAction.CHECK_IN
import com.health.api.QuickAction.COFFEE
import com.health.api.QuickAction.DRINK
import com.health.api.QuickAction.FOOD
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
import com.health.app.ui.modules.hubRows
import com.health.app.ui.settings.ExportState
import com.health.app.ui.settings.SettingsSections
import com.health.app.ui.settings.WaterSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of Today's cards and quick actions show, what's under "More", and water turned off. */
class TodayLayoutTest {
    @Test
    fun `today has a section for each feature that's on, in the shared default order`() {
        assertEquals(
            listOf(
                TodaySection.SCORES, TodaySection.INSIGHTS, TodaySection.QUICK, TodaySection.DRINKS, TodaySection.ACTIVITY, TodaySection.SLEEP,
                TodaySection.HEART, TodaySection.STRESS, TodaySection.FOOD, TodaySection.WATER, TodaySection.WORKOUTS, TodaySection.BODY,
            ),
            TodayLayout.sections(Module.DEFAULT),
        )
        val more = TodayLayout.sections(Module.DEFAULT + setOf(Module.MEDICATIONS, Module.CYCLE, Module.HABITS))
        assertTrue(listOf(TodaySection.MEDS, TodaySection.CYCLE, TodaySection.SUPPLIES, TodaySection.LIFE_STAGE, TodaySection.HABITS).all { it in more })
        // Every section knows whether it applies, and the order is always the enum's.
        val all = TodayLayout.sections(Module.entries.toSet())
        assertEquals(TodaySection.entries.toList(), all)
    }

    @Test
    fun `with only Mind on, its energy budget is called Energy`() {
        assertTrue(TodaySection.NEURO in TodayLayout.sections(setOf(Module.MIND)))
        assertEquals("Energy", TodayLayout.label(TodaySection.NEURO, setOf(Module.MIND)))
        assertEquals("Autism & ADHD", TodayLayout.label(TodaySection.NEURO, setOf(Module.MIND, Module.NEURO)))
        // Quick actions and insights are there for everyone; scores only with activity or sleep.
        assertEquals(listOf(TodaySection.INSIGHTS, TodaySection.QUICK, TodaySection.MIND, TodaySection.NEURO), TodayLayout.sections(setOf(Module.MIND)))
    }

    @Test
    fun `the person's order comes first, hidden sections go, and new ones go where they'd be`() {
        val layout = ScreenLayout(listOf("FOOD", "QUICK", "SCORES", "MIND"), hidden = setOf("INSIGHTS", "HEART"))
        val shown = TodayLayout.visible(Module.DEFAULT, water = true, layout)
        assertEquals(listOf(TodaySection.FOOD, TodaySection.WATER, TodaySection.WORKOUTS, TodaySection.BODY, TodaySection.QUICK, TodaySection.DRINKS), shown.take(6))
        assertFalse(TodaySection.INSIGHTS in shown)
        assertFalse(TodaySection.HEART in shown)
        assertEquals(TodayLayout.sections(Module.DEFAULT).size - 2, shown.size)
    }

    @Test
    fun `the first six quick actions show, and the rest are under More`() {
        val available = TodayLayout.quickActions(Module.DEFAULT)
        assertEquals(listOf(FOOD, WATER, WEIGHT, COFFEE, DRINK, WORKOUT, SLEEP, CHECK_IN), available)
        val row = TodayLayout.quickRow(available, null)
        assertEquals(QuickAction.SHOWN, row.shown.size)
        assertEquals(listOf(FOOD, WATER, WEIGHT, COFFEE, DRINK, WORKOUT), row.shown)
        assertEquals(listOf(SLEEP, CHECK_IN), row.more)
        assertEquals(setOf("SLEEP", "CHECK_IN"), TodayLayout.defaultUnderMore(available))
        // Fewer than six: all show, and nothing's under More.
        val few = TodayLayout.quickRow(TodayLayout.quickActions(setOf(Module.SLEEP)), null)
        assertEquals(listOf(SLEEP, CHECK_IN), few.shown)
        assertTrue(few.more.isEmpty())
    }

    @Test
    fun `arranged quick actions follow the person, with those hidden under More`() {
        val available = TodayLayout.quickActions(Module.DEFAULT)
        val row = TodayLayout.quickRow(available, ScreenLayout(listOf("CHECK_IN", "FOOD"), hidden = setOf("WATER", "COFFEE")))
        assertEquals(listOf(CHECK_IN, FOOD, WEIGHT, DRINK, WORKOUT, SLEEP), row.shown)
        assertEquals(listOf(WATER, COFFEE), row.more)
        // Once arranged, what isn't hidden shows: the person chose it.
        val all = TodayLayout.quickRow(available, ScreenLayout(listOf("SLEEP", "FOOD")))
        assertEquals(available.size, all.shown.size)
        assertEquals(SLEEP, all.shown.first())
        assertTrue(all.more.isEmpty())
    }

    @Test
    fun `quick actions follow the features, and Measurements their fields`() {
        val everything = TodayLayout.quickActions(Module.entries.toSet(), water = true, measurements = true)
        assertEquals(QuickAction.entries.toList(), everything)
        assertFalse(QuickAction.MEASUREMENTS in TodayLayout.quickActions(Module.entries.toSet(), measurements = false))
        assertFalse(QuickAction.MOOD in TodayLayout.quickActions(Module.DEFAULT))
        // Check in is everyone's.
        assertEquals(listOf(CHECK_IN), TodayLayout.quickActions(emptySet()))
    }

    @Test
    fun `with water off, Today has no water card and no water quick action`() {
        assertFalse(TodaySection.WATER in TodayLayout.sections(Module.DEFAULT, water = false))
        assertTrue(TodaySection.DRINKS in TodayLayout.sections(Module.DEFAULT, water = false))
        val available = TodayLayout.quickActions(Module.DEFAULT, water = false)
        assertFalse(WATER in available)
        // The next one moves up from under More.
        val row = TodayLayout.quickRow(available, null)
        assertEquals(listOf(FOOD, WEIGHT, COFFEE, DRINK, WORKOUT, SLEEP), row.shown)
        assertEquals(listOf(CHECK_IN), row.more)
        // A saved order naming water just skips it.
        val arranged = TodayLayout.quickRow(available, ScreenLayout(listOf("WATER", "COFFEE", "FOOD")))
        assertEquals(COFFEE, arranged.shown.first())
        assertFalse(WATER in arranged.shown + arranged.more)
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
    }

    @Test
    fun `the Health tab lists the features that are on, then devices and conditions, by shared ids`() {
        val rows = hubRows(setOf(Module.ACTIVITY, Module.BODY, Module.MIND, Module.CYCLE), showDevices = true, showMeasurements = true)
        assertEquals(listOf("MIND", "CYCLE", "BODY", NavPage.DEVICES.name, NavPage.CONDITIONS.name), rows.map { it.id })
        val shown = Layouts.visible(rows, { it.id }, ScreenLayout(listOf("CYCLE"), hidden = setOf("MIND")))
        assertEquals(listOf("CYCLE", "BODY", NavPage.DEVICES.name, NavPage.CONDITIONS.name), shown.map { it.id })
    }

    @Test
    fun `settings and food keep stable section ids`() {
        assertEquals(listOf("you", "features", "goals", "reminders", "connections", "appearance", "account"), SettingsSections.ITEMS.map { it.id })
        assertEquals("android.settings", SettingsSections.KEY)
        assertEquals(listOf("totals", "breakfast", "lunch", "dinner", "snack", "water", "drinks"), FoodSections.items(water = true).map { it.id })
        assertEquals("android.food", FoodSections.KEY)
    }
}
