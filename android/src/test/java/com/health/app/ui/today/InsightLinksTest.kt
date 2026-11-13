package com.health.app.ui.today

import com.health.api.AppPage
import com.health.api.Insight
import com.health.api.Meal
import com.health.api.Module
import com.health.api.TodaySection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where each insight on Today opens. */
class InsightLinksTest {
    private val all = Module.entries.toSet()
    private val doses = Insight("info", "2 doses still to take", "Progesterone, Estradiol", "pill", Module.MEDICATIONS, link = AppPage.MEDS, shownBy = TodaySection.MEDS)

    @Test
    fun `an insight opens its link, or else its feature's page`() {
        assertEquals("meds", InsightLinks.of(doses, all)?.route)
        assertEquals("food", InsightLinks.of(Insight("info", "Protein 46 g under target", "", "egg", link = AppPage.FOOD), all)?.route)
        // No link: the feature's own page.
        assertEquals("cycle", InsightLinks.of(Insight("info", "Period likely tomorrow", "", "calendar", Module.CYCLE), all)?.route)
        assertEquals("habits", InsightLinks.of(Insight("info", "1 habit left today", "", "sparkles", Module.HABITS), all)?.route)
        // Neither: about nothing in particular, so not a link.
        assertNull(InsightLinks.of(Insight("info", "Learning your baseline", ""), all))
    }

    @Test
    fun `every page has somewhere to go on the phone`() {
        val routes = AppPage.entries.associateWith { InsightLinks.target(it, all).route }
        assertEquals(null, routes[AppPage.TODAY])
        assertEquals("trends", routes[AppPage.SLEEP])
        assertEquals("trends", routes[AppPage.ACTIVITY])
        assertEquals("trends", routes[AppPage.TRENDS])
        assertEquals("trends", routes[AppPage.BODY])
        assertEquals("measurements", InsightLinks.target(AppPage.BODY, all, measurements = true).route)
        assertEquals("continence", routes[AppPage.CONTINENCE])
        assertEquals("supplies/continence", routes[AppPage.SUPPLIES])
        assertEquals("supplies/cycle", InsightLinks.target(AppPage.SUPPLIES, setOf(Module.CYCLE)).route)
        assertEquals("neuro", routes[AppPage.NEURO])
        // Autism & ADHD's energy lives in Mind when only Mind is on.
        assertEquals("mind", InsightLinks.target(AppPage.NEURO, setOf(Module.MIND)).route)
        assertEquals("devices", routes[AppPage.DEVICES])
        AppPage.entries.filter { it != AppPage.TODAY }.forEach { assertEquals(it.name, true, routes[it] != null) }
    }

    @Test
    fun `the row reads as its title and the page it opens`() {
        assertEquals("2 doses still to take. Open Medications", InsightLinks.label(doses, InsightLinks.of(doses, all)!!))
        val sleep = Insight("info", "1h 5m less sleep than usual", "", "moon", link = AppPage.SLEEP)
        assertEquals("1h 5m less sleep than usual. Open Trends", InsightLinks.label(sleep, InsightLinks.of(sleep, all)!!))
        val ready = Insight("warn", "Take it easy today", "", "battery-low", link = AppPage.TODAY)
        assertEquals("Take it easy today. Open your scores", InsightLinks.label(ready, InsightLinks.of(ready, all)!!))
    }

    @Test
    fun `viewing a share opens only what's shared, and never Devices`() {
        val shared = setOf(Module.SLEEP, Module.NUTRITION)
        assertNull(InsightLinks.of(doses, shared, readOnly = true))
        assertNull(InsightLinks.of(Insight("info", "Welcome!", "", "sparkles", link = AppPage.DEVICES), shared, readOnly = true))
        assertEquals("devices", InsightLinks.of(Insight("info", "Welcome!", "", "sparkles", link = AppPage.DEVICES), shared)?.route)
        assertEquals("trends", InsightLinks.of(Insight("info", "HRV is lower than usual", "", "activity", link = AppPage.TRENDS), shared, readOnly = true)?.route)
    }

    @Test
    fun `the day's doses aren't repeated while Medications shows them`() {
        val sleep = Insight("info", "Short on sleep", "", "moon", link = AppPage.SLEEP)
        assertEquals(listOf(sleep), InsightLinks.shown(listOf(doses, sleep), Module.DEFAULT + Module.MEDICATIONS, setOf(TodaySection.MEDS)))
        assertEquals(listOf(doses, sleep), InsightLinks.shown(listOf(doses, sleep), Module.DEFAULT + Module.MEDICATIONS, emptySet()))
        // A feature that's off shows nothing of its own.
        assertEquals(listOf(sleep), InsightLinks.shown(listOf(doses, sleep), Module.DEFAULT, emptySet()))
        assertEquals("snacks", InsightLinks.mealWord(Meal.SNACK))
        assertEquals("dinner", InsightLinks.mealWord(Meal.DINNER))
    }
}
