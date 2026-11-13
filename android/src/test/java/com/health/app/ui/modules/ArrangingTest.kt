package com.health.app.ui.modules

import com.health.api.DrinkKind
import com.health.api.Fields
import com.health.api.Habit
import com.health.api.HabitSource
import com.health.api.HabitStatus
import com.health.api.LabResult
import com.health.api.LabSeries
import com.health.api.Layouts
import com.health.api.LifeStage
import com.health.api.Module
import com.health.api.ScreenLayout
import com.health.api.Supply
import com.health.api.SupplyKind
import com.health.api.SupplyStatus
import com.health.app.ui.care.APPOINTMENT_SECTIONS
import com.health.app.ui.care.REPORT_SECTIONS
import com.health.app.ui.care.SHARING_SECTIONS
import com.health.app.ui.care.hormoneSections
import com.health.app.ui.care.lifeStageSections
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.modules.body.measurementSections
import com.health.app.ui.modules.continence.PROGRESS_SECTIONS
import com.health.app.ui.modules.continence.SupplyArea
import com.health.app.ui.modules.continence.continenceSections
import com.health.app.ui.modules.continence.supplySections
import com.health.app.ui.modules.cycle.cycleSections
import com.health.app.ui.modules.meds.MEDS_TODAY_SECTIONS
import com.health.app.ui.modules.meds.labSections
import com.health.app.ui.modules.mind.mindSections
import com.health.app.ui.modules.neuro.NEURO_TOOLS
import com.health.app.ui.modules.neuro.neuroSections
import com.health.app.ui.modules.photos.photoSections
import com.health.app.ui.modules.transition.transitionSections
import com.health.app.ui.tracking.VITALS_SECTIONS
import com.health.app.ui.tracking.drinkKinds
import com.health.app.ui.tracking.drinksIntro
import com.health.app.ui.tracking.habitSections
import com.health.app.ui.tracking.habitSources
import com.health.app.ui.tracking.quickDrinks
import com.health.app.ui.tracking.symptomsSections
import com.health.app.ui.trends.trendsSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The feature screens' sections (what the Arrange sheet offers) and leaving water out. */
class ArrangingTest {
    private val everyField = Fields.ALL.keys
    private val all = Module.entries.toSet()
    private val idPattern = Regex("^[A-Za-z0-9._-]{1,${Layouts.MAX_ID}}$")

    private fun ids(items: List<ArrangeItem>) = items.map { it.id }

    /** What the server accepts, without repeats, and a name for each. */
    private fun assertValid(screen: String, items: List<ArrangeItem>) {
        assertTrue("$screen has sections", items.isNotEmpty())
        assertEquals("$screen's ids are unique", items.size, items.map { it.id }.toSet().size)
        assertTrue("$screen has at most ${Layouts.MAX_SECTIONS}", items.size <= Layouts.MAX_SECTIONS)
        items.forEach {
            assertTrue("$screen: ${it.id}", idPattern.matches(it.id))
            assertTrue("$screen: ${it.id} has a name", it.label.isNotBlank())
        }
    }

    @Test
    fun keysAreAndroidsOwnAndStable() {
        assertEquals(FeatureScreens.ALL.size, FeatureScreens.ALL.toSet().size)
        FeatureScreens.ALL.forEach {
            assertTrue(it, it.startsWith("android."))
            assertTrue(it, idPattern.matches(it))
        }
        // Saved on the server: these must never change.
        assertEquals("android.trends", FeatureScreens.TRENDS)
        assertEquals("android.mind", FeatureScreens.MIND)
        assertEquals("android.continence", FeatureScreens.CONTINENCE)
        assertEquals("android.meds.today", FeatureScreens.MEDS_TODAY)
    }

    @Test
    fun everyScreenOffersValidSections() {
        assertValid("trends", trendsSections(all))
        assertValid("mind", mindSections(energy = true, neuro = true))
        assertValid("neuro", neuroSections(everyField))
        assertValid("cycle", cycleSections(everyField, lifeStageSections(LifeStage.PREGNANT)))
        assertValid("continence", continenceSections(everyField))
        assertValid("progress", PROGRESS_SECTIONS)
        assertValid("meds.today", MEDS_TODAY_SECTIONS)
        assertValid("vitals", VITALS_SECTIONS)
        assertValid("symptoms", symptomsSections())
        assertValid("hormones", hormoneSections(emptyList()))
        assertValid("appointments", APPOINTMENT_SECTIONS)
        assertValid("reports", REPORT_SECTIONS)
        assertValid("sharing", SHARING_SECTIONS)
        assertValid("transition", transitionSections(everyField, all))
        assertValid("photos", photoSections())
        assertValid("measurements", measurementSections())
    }

    @Test
    fun trendsHasAChartForEachFeatureThatsOn() {
        assertEquals(
            listOf(
                "scores", "mood", "doses", "period", "trips", "leaks", "sleep", "steps", "heart", "hrv", "calories", "water",
                "bp", "glucose", "stress", "caffeine", "alcohol", "symptoms", "habits", "weight", "workouts",
            ),
            ids(trendsSections(all)),
        )
        assertEquals(listOf("scores", "sleep", "steps", "heart", "hrv", "calories", "water", "stress", "caffeine", "alcohol", "weight", "workouts"), ids(trendsSections(Module.DEFAULT)))
        assertEquals(listOf("mood"), ids(trendsSections(setOf(Module.MIND))))
    }

    @Test
    fun trendsInThePersonsOrder() {
        val saved = ScreenLayout(order = listOf("weight", "sleep"), hidden = setOf("scores"))
        val shown = Layouts.visible(trendsSections(Module.DEFAULT), { it.id }, saved).map { it.id }
        // Charts the saved order doesn't name follow the one before them by default (workouts after weight).
        assertEquals(listOf("weight", "workouts", "sleep", "steps", "heart", "hrv", "calories", "water", "stress", "caffeine", "alcohol"), shown)
    }

    @Test
    fun waterOffLeavesWaterOut() {
        assertFalse("water" in ids(trendsSections(all, water = false)))
        assertTrue("caffeine" in ids(trendsSections(all, water = false)))
        assertTrue("alcohol" in ids(trendsSections(all, water = false)))

        assertFalse(DrinkKind.WATER in quickDrinks(water = false))
        assertEquals(listOf(DrinkKind.COFFEE, DrinkKind.TEA, DrinkKind.BEER, DrinkKind.WINE), quickDrinks(water = false))
        assertTrue(DrinkKind.WATER in quickDrinks(water = true))
        assertFalse(DrinkKind.WATER in drinkKinds(water = false))
        // A drink logged as water before keeps its kind.
        assertTrue(DrinkKind.WATER in drinkKinds(water = false, current = DrinkKind.WATER))
        assertFalse(drinksIntro(water = false).contains("water", ignoreCase = true))
        assertTrue(drinksIntro(water = true).contains("water"))

        assertFalse(HabitSource.WATER_GOAL in habitSources(water = false))
        assertTrue(HabitSource.WATER_GOAL in habitSources(water = false, current = HabitSource.WATER_GOAL))
        assertEquals(HabitSource.entries.size - 1, habitSources(water = false, current = HabitSource.MANUAL).size)
        assertEquals(HabitSource.entries, habitSources(water = true))
    }

    @Test
    fun mindFollowsWhatsOn() {
        assertEquals(listOf("feeling", "mood", "insights", "tools", "journal", "checkins"), ids(mindSections()))
        assertEquals(listOf("feeling", "mood", "insights", "tools", "energy", "neuro", "journal", "checkins"), ids(mindSections(energy = true, neuro = true)))
        // Viewing someone's share: nothing to log.
        assertFalse("feeling" in ids(mindSections(readOnly = true)))
    }

    @Test
    fun autismAndAdhdOffersOnlyTheToolsThatAreOn() {
        val few = setOf(Fields.NEURO_CARDS, Fields.NEURO_ABOUT_ME, Fields.NEURO_CALM)
        assertEquals(listOf("quick", "energy", "insights", "cards", "about", "calm", "more"), ids(neuroSections(few)))
        val every = NEURO_TOOLS.map { it.first }.toSet()
        assertFalse("more" in ids(neuroSections(every)))
        assertTrue("overload" in ids(neuroSections(every)))
        // Read-only: no calm mode or more tools to turn on, and no big buttons without cards.
        assertEquals(listOf("lookafter", "energy", "overload", "insights"), ids(neuroSections(setOf(Fields.NEURO_OVERLOAD), readOnly = true)))
    }

    @Test
    fun cycleHasTheLifeStagesCards() {
        assertEquals(listOf("today", "calendar", "averages", "temperature", "cycles"), ids(cycleSections(Fields.DEFAULTS)))
        assertEquals(
            listOf("today", "stage", "stage.feeds", "stage.tips", "calendar", "lowsupplies", "averages", "temperature", "cycles", "products"),
            ids(cycleSections(everyField, lifeStageSections(LifeStage.POSTPARTUM))),
        )
        assertEquals(listOf("stage", "stage.pregnancy", "stage.movements", "stage.contractions", "stage.tips"), ids(lifeStageSections(LifeStage.PREGNANT)))
        assertEquals(listOf("stage", "stage.hotflashes", "stage.tips"), ids(lifeStageSections(LifeStage.MENOPAUSE)))
        assertEquals(listOf("stage", "stage.tips"), ids(lifeStageSections(LifeStage.CYCLING)))
        assertTrue(lifeStageSections(null).isEmpty())
    }

    @Test
    fun bladderAndBowelFollowsItsFields() {
        assertEquals(listOf("log", "today", "summary", "progress", "insights", "more"), ids(continenceSections(emptySet())))
        assertEquals(
            listOf("log", "today", "lowsupplies", "summary", "progress", "insights", "training", "pelvicfloor", "more"),
            ids(continenceSections(everyField)),
        )
        val viewing = ids(continenceSections(everyField, readOnly = true))
        assertFalse("log" in viewing)
        assertFalse("pelvicfloor" in viewing)
    }

    @Test
    fun suppliesHabitsAndLabsAreArrangedOneByOne() {
        val pads = SupplyStatus(Supply("Night pads", SupplyKind.entries.first { it.category == SupplyArea.CONTINENCE.categories.first() }, id = "4f1c-a9"))
        val tampons = SupplyStatus(Supply("Tampons", SupplyKind.entries.first { it.category in SupplyArea.CYCLE.categories }, id = "77"))
        assertEquals(listOf("supply.4f1c-a9"), ids(supplySections(listOf(pads, tampons), SupplyArea.CONTINENCE)))
        assertEquals(listOf("supply.77"), ids(supplySections(listOf(pads, tampons), SupplyArea.CYCLE)))
        assertTrue(supplySections(listOf(SupplyStatus(Supply("Not saved yet", pads.supply.kind))), SupplyArea.CONTINENCE).isEmpty())

        val walk = HabitStatus(Habit("A walk", id = "h1"), "2026-11-12", 0, false, 0, 0, 0)
        val unsaved = HabitStatus(Habit("New"), "2026-11-12", 0, false, 0, 0, 0)
        assertEquals(listOf(ArrangeItem("habit.h1", "A walk")), habitSections(listOf(walk, unsaved)))

        val labs = listOf(
            LabResult("Estradiol", 120.0, "pg/mL", 1_000), LabResult("Vitamin D", 30.0, "ng/mL", 3_000),
            LabResult("estradiol", 140.0, "pg/mL", 2_000),
        )
        assertEquals(listOf("lab.vitamin-d", "lab.estradiol"), ids(labSections(labs)))

        assertEquals(listOf("labs", "meds", "levels"), ids(hormoneSections(emptyList())))
        assertEquals(listOf("labs", "meds", "level.estradiol"), ids(hormoneSections(listOf(LabSeries("Estradiol", "pg/mL", results = labs.take(1))))))
    }

    @Test
    fun sectionIdsFromNamesAreSafe() {
        assertEquals("lab.testosterone-free", sectionId("lab", "Testosterone (free)"))
        assertEquals("lab.hba1c", sectionId("lab", "HbA1c"))
        assertEquals("lab.x", sectionId("lab", "  !! "))
        assertTrue(sectionId("lab", "a".repeat(200)).length <= Layouts.MAX_ID)
        assertTrue(idPattern.matches(sectionId("supply", "ünïcode näme")))
    }

    @Test
    fun othersFollowWhatsOn() {
        assertFalse("log" in ids(symptomsSections(readOnly = true)))
        assertFalse("add" in ids(photoSections(readOnly = true)))
        assertEquals(listOf("latest"), ids(measurementSections(readOnly = true)))
        assertEquals(listOf("since", "lately"), ids(transitionSections(emptySet(), emptySet())))
        assertEquals(
            listOf("since", "lately", "milestones", "moments", "changes", "voice", "hair", "measurements", "photos"),
            ids(transitionSections(everyField, all)),
        )
    }
}
