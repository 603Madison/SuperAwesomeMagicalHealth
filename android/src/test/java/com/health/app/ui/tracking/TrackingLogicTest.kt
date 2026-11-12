package com.health.app.ui.tracking

import com.health.api.BpCategory
import com.health.api.Drink
import com.health.api.DrinkKind
import com.health.api.GlucoseUnit
import com.health.api.Habit
import com.health.api.Units
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TrackingLogicTest {
    init {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `a drink's caffeine and alcohol come from its kind and size`() {
        assertEquals(96.0 to 0.0, drinkContents(DrinkKind.COFFEE, 240))
        assertEquals(0.0 to 14.0, drinkContents(DrinkKind.BEER, 355)) // a standard drink
        assertEquals(0.0 to 14.2, drinkContents(DrinkKind.WINE, 150))
        assertEquals(63.0 to 0.0, drinkContents(DrinkKind.ESPRESSO, 30))
        val quick = quickDrink(DrinkKind.TEA, at = 1L)
        assertEquals(240, quick.volumeMl)
        assertEquals(48.0, quick.caffeineMg, 0.0)
    }

    @Test
    fun `drinks read plainly`() {
        assertEquals("1 drink", standardDrinksText(1.0))
        assertEquals("1.5 drinks", standardDrinksText(1.5))
        assertEquals("240 ml · 96 mg caffeine", drinkDetail(Drink(0, DrinkKind.COFFEE, 240, caffeineMg = 96.0), Units.METRIC))
        assertEquals("150 ml · 1 drink", drinkDetail(Drink(0, DrinkKind.WINE, 150, alcoholG = 14.2), Units.METRIC))
    }

    @Test
    fun `blood pressure falls in the heart association's categories, as the server has them`() {
        assertEquals(BpCategory.NORMAL, bpCategory(116, 76))
        assertEquals(BpCategory.ELEVATED, bpCategory(124, 78))
        assertEquals(BpCategory.STAGE_1, bpCategory(128, 84))
        assertEquals(BpCategory.STAGE_2, bpCategory(142, 88))
        assertEquals(BpCategory.CRISIS, bpCategory(185, 100))
        assertEquals(BpCategory.LOW, bpCategory(88, 58))
    }

    @Test
    fun `glucose shows in either unit and is kept in mmol per litre`() {
        assertEquals("5.4", Glucose.number(5.4, GlucoseUnit.MMOL_L))
        assertEquals("97", Glucose.number(5.4, GlucoseUnit.MG_DL))
        assertEquals("97 mg/dL", Glucose.text(5.4, GlucoseUnit.MG_DL))
        assertEquals(5.4, Glucose.toMmol(97.3, GlucoseUnit.MG_DL), 0.01)
        assertEquals(5.4, Glucose.toMmol(5.4, GlucoseUnit.MMOL_L), 0.0)
    }

    @Test
    fun `a habit's aim reads as people say it`() {
        assertEquals("Every day", aimText(Habit("Stretch")))
        assertEquals("5 days a week", aimText(Habit("Walk", timesPerWeek = 5)))
        assertEquals("Every day, 3 times", aimText(Habit("Water", perDay = 3)))
    }

    @Test
    fun `every habit icon the web offers has one here`() {
        listOf(
            "sparkles", "dumbbell", "footprints", "droplet", "moon", "bed-double", "book-open", "brain", "heart", "leaf", "sun",
            "coffee", "pill", "wind", "music", "pen-line", "apple", "smile", "wine-off", "list-checks",
        ).forEach { assert(it in HabitIcons.all) { "No icon for $it" } }
    }
}
