package com.health.app.ui.modules

import com.health.api.Awareness
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceProgress
import com.health.api.ContinenceSettings
import com.health.api.CustomField
import com.health.api.CustomFieldType
import com.health.api.CycleSettings
import com.health.api.CyclePhase
import com.health.api.Fields
import com.health.api.Fullness
import com.health.api.LeakContents
import com.health.api.LeakSize
import com.health.api.ProgressMetric
import com.health.api.ProgressWeek
import com.health.api.Supply
import com.health.api.SupplyCategory
import com.health.api.SupplyKind
import com.health.api.SupplyStatus
import com.health.api.Verdict
import com.health.app.ui.modules.continence.ContinenceText
import com.health.app.ui.modules.continence.ProgressText
import com.health.app.ui.modules.continence.SupplyArea
import com.health.app.ui.modules.continence.finish
import com.health.app.ui.modules.continence.quickButtonsFor
import com.health.app.ui.modules.continence.quickEntry
import com.health.app.ui.modules.cycle.CycleText
import com.health.app.ui.modules.cycle.CycleWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deeper bladder and bowel diary, supplies, progress and the cycle's wording, without a phone. */
class ContinenceLogicTest {
    private val pad = Supply("Night pads", SupplyKind.PAD, absorbency = "Maxi", dryWeightG = 22.0, onHand = 12, id = "s1")
    private val brief = Supply("Briefs", SupplyKind.BRIEF, onHand = 3, id = "s2")
    private val catheter = Supply("Catheters", SupplyKind.CATHETER, onHand = 40, id = "s3")
    private val tampon = Supply("Tampons", SupplyKind.TAMPON, onHand = 10, id = "s4")
    private val supplies = listOf(pad, brief, catheter, tampon)

    @Test
    fun `the form offers the core kinds always, and the rest by their fields`() {
        val core = ContinenceText.kinds(emptySet())
        assertEquals(listOf(ContinenceKind.URINATION, ContinenceKind.LEAK, ContinenceKind.BOWEL, ContinenceKind.PAD_CHANGE, ContinenceKind.BEDWETTING), core)
        val all = ContinenceText.kinds(setOf(Fields.CONTINENCE_BOWEL_ACCIDENTS, Fields.CONTINENCE_CATHETER, Fields.CONTINENCE_PELVIC_FLOOR))
        assertEquals(ContinenceKind.entries.toList(), all)
        // An entry's own kind always shows, so editing it never loses it.
        assertTrue(ContinenceKind.CATHETER in ContinenceText.kinds(emptySet(), ContinenceKind.CATHETER))
    }

    @Test
    fun `products fit the kind of entry`() {
        assertEquals(listOf(pad, brief), ContinenceText.suppliesFor(ContinenceKind.LEAK, supplies))
        assertEquals(listOf(pad, brief), ContinenceText.suppliesFor(ContinenceKind.PAD_CHANGE, supplies))
        assertEquals(listOf(catheter), ContinenceText.suppliesFor(ContinenceKind.CATHETER, supplies))
        assertEquals("Night pads (Maxi)", ContinenceText.supplyName(pad))
    }

    @Test
    fun `the loss is the wet weight less the dry`() {
        assertEquals(58.5, ContinenceText.loss(80.5, pad)!!, 0.001)
        assertEquals(0.0, ContinenceText.loss(10.0, pad)!!, 0.001)
        assertNull(ContinenceText.loss(80.0, brief))
        assertNull(ContinenceText.loss(null, pad))
    }

    @Test
    fun `an accident reads in plain words`() {
        val e = ContinenceEntry(
            0, ContinenceKind.BOWEL_LEAK, contents = LeakContents.STOOL, leak = LeakSize.SMALL, urgency = 3,
            activity = listOf("walking"), awareness = Awareness.FELT_IT, warningSeconds = 30, supplyId = "s2", fullness = Fullness.WET,
            lossG = 40.0, changedClothes = true, skin = 2, location = "out", tags = listOf("holiday"),
        )
        assertEquals(
            "Stool · urgency couldn't wait · Small · walking · felt it coming · 30 s warning · Briefs · wet · 40 g lost · clothes changed · skin sore · out · #holiday",
            ContinenceText.detail(e, supplies),
        )
        assertEquals("", ContinenceText.detail(ContinenceEntry(0, ContinenceKind.URINATION)))
        assertEquals("2 × Night pads", ContinenceText.detail(ContinenceEntry(0, ContinenceKind.PAD_CHANGE, supplyId = "s1", count = 2), supplies))
    }

    @Test
    fun `signs of an infection are spotted, and the nudge is the server's`() {
        assertFalse(ContinenceText.infectionSigns(listOf(ContinenceEntry(0, ContinenceKind.URINATION))))
        assertTrue(ContinenceText.infectionSigns(listOf(ContinenceEntry(0, ContinenceKind.URINATION, utiSigns = listOf("burning")))))
        assertTrue(ContinenceText.INFECTION_NUDGE.contains("doctor or nurse"))
    }

    @Test
    fun `warnings are said and matched to the nearest choice`() {
        assertEquals("no warning", ContinenceText.warning(0))
        assertEquals("45 s warning", ContinenceText.warning(45))
        assertEquals("2 min warning", ContinenceText.warning(120))
        assertEquals("1 min 30 s warning", ContinenceText.warning(90))
        assertEquals(30, ContinenceText.nearestWarning(40))
        assertNull(ContinenceText.nearestWarning(null))
    }

    @Test
    fun `saving a form drops what doesn't fit its kind`() {
        val e = ContinenceEntry(0, ContinenceKind.URINATION, bristol = 4, fullness = Fullness.WET, supplyId = "s1", contents = LeakContents.URINE)
        val done = finish(e, volume = "320", weight = "90", reps = "10", hold = "5")
        assertEquals(320, done.volumeMl)
        assertNull(done.bristol)
        assertNull(done.fullness)
        assertNull(done.supplyId)
        assertNull(done.padWeightG)
        assertNull(done.reps)
        assertNull(done.contents)
        val change = finish(ContinenceEntry(0, ContinenceKind.PAD_CHANGE, supplyId = "s1", count = 2, fullness = Fullness.SOAKED), "", "120", "", "")
        assertEquals(120.0, change.padWeightG!!, 0.0)
        assertEquals("s1", change.supplyId)
        assertEquals(2, change.count)
        assertNull(finish(ContinenceEntry(0, ContinenceKind.URINATION), "5000", "", "", "").volumeMl)
        val set = finish(ContinenceEntry(0, ContinenceKind.PELVIC_FLOOR), "", "", "12", "6")
        assertEquals(12, set.reps)
        assertEquals(6, set.holdSeconds)
    }

    @Test
    fun `a quick change uses the usual product`() {
        val settings = ContinenceSettings(defaultSupplyId = "s1")
        val statuses = supplies.map { SupplyStatus(it) }
        assertEquals("s1", quickEntry(ContinenceKind.PAD_CHANGE, settings, statuses, 5).supplyId)
        assertNull(quickEntry(ContinenceKind.URINATION, settings, statuses, 5).supplyId)
        assertNull(quickEntry(ContinenceKind.PAD_CHANGE, ContinenceSettings(defaultSupplyId = "gone"), statuses, 5).supplyId)
    }

    @Test
    fun `quick buttons follow the person's choice and their fields`() {
        val s = ContinenceSettings(quickButtons = listOf(ContinenceKind.CATHETER, ContinenceKind.URINATION))
        assertEquals(listOf(ContinenceKind.URINATION), quickButtonsFor(s, emptySet()))
        assertEquals(listOf(ContinenceKind.CATHETER, ContinenceKind.URINATION), quickButtonsFor(s, setOf(Fields.CONTINENCE_CATHETER)))
        assertEquals(4, quickButtonsFor(ContinenceSettings(quickButtons = emptyList()), emptySet()).size)
    }

    @Test
    fun `low supplies and stock read plainly, and go to the right place`() {
        val low = SupplyStatus(brief, perDay = 1.5, daysLeft = 2, low = true)
        val lowTampons = SupplyStatus(tampon, low = true)
        assertEquals("3 left · about 1.5 a day · 2 days left", ContinenceText.stock(low))
        assertEquals("reusable", ContinenceText.stock(SupplyStatus(Supply("Cup", SupplyKind.CUP, reusable = true))))
        assertEquals(listOf(low), ContinenceText.lowFor(listOf(low, lowTampons, SupplyStatus(pad)), setOf(SupplyCategory.CONTINENCE)))
        assertEquals(SupplyArea.CYCLE, SupplyArea.forLow(listOf(lowTampons)))
        assertEquals(SupplyArea.CONTINENCE, SupplyArea.forLow(listOf(lowTampons, low)))
        assertTrue(SupplyArea.CYCLE.kinds.all { it.category == SupplyCategory.PERIOD })
        assertTrue(SupplyKind.BRIEF in SupplyArea.CONTINENCE.kinds && SupplyKind.WIPES in SupplyArea.CONTINENCE.kinds)
    }

    private fun metric(before: Double?, after: Double?, unit: String = "", verdict: Verdict = Verdict.IMPROVING) =
        ProgressMetric("leaks", "Leaks a day", before, after, unit, true, verdict)

    @Test
    fun `progress is put into words`() {
        assertEquals("1.2 → 0.6", ProgressText.change(metric(1.2, 0.6)))
        assertEquals("– → 0.25", ProgressText.change(metric(null, 0.25)))
        assertEquals("40 g → 12.5 g", ProgressText.change(metric(40.0, 12.5, "g")))
        assertEquals("better", ProgressText.verdictWords(Verdict.IMPROVING))
        assertEquals("harder", ProgressText.verdictWords(Verdict.WORSENING))
    }

    @Test
    fun `not enough data is said plainly`() {
        val empty = ContinenceProgress(Verdict.NOT_ENOUGH, "", 4, emptyList(), (0 until 8).map { ProgressWeek("2026-09-${10 + it}", 0) })
        assertFalse(ProgressText.enough(empty))
        assertTrue(ProgressText.notEnough(empty).startsWith("Nothing in the diary yet"))
        val some = empty.copy(weeks = empty.weeks.mapIndexed { i, w -> if (i > 5) w.copy(days = 3) else w })
        assertTrue(ProgressText.notEnough(some).contains("five days logged"))
        assertEquals(2, ProgressText.loggedWeeks(some))
        assertTrue(ProgressText.enough(some.copy(verdict = Verdict.STEADY)))
    }

    @Test
    fun `the cycle's wording is plain when chosen, or when the fertile window is hidden`() {
        assertFalse(CycleText.plain(CycleSettings(), Fields.DEFAULTS))
        assertTrue(CycleText.plain(CycleSettings(neutralWording = true), Fields.DEFAULTS))
        assertTrue(CycleText.plain(CycleSettings(), Fields.DEFAULTS - Fields.CYCLE_FERTILITY))
        assertEquals("Mid-cycle", CycleText.phase(CyclePhase.FERTILE, plain = true))
        assertEquals("Fertile window", CycleText.phase(CyclePhase.FERTILE, plain = false))
        assertEquals("Luteal", CycleText.phase(CyclePhase.LUTEAL, plain = true))
        assertEquals(28, CycleText.length("28", 15..90))
        assertNull(CycleText.length("200", 15..90))
        assertTrue(CycleText.validLength("", 15..90))
        assertFalse(CycleText.validLength("10", 15..90))
    }

    @Test
    fun `the cycle offers the person's own symptoms first, less hidden ones`() {
        val list = CycleWords.symptoms(CycleSettings(symptoms = listOf("jaw pain"), hiddenSymptoms = listOf("Hot flashes")))
        assertEquals("jaw pain", list.first())
        assertFalse("hot flashes" in list)
        assertTrue("cramps" in list)
        assertEquals(listOf(tampon), CycleWords.periodSupplies(supplies))
    }

    @Test
    fun `own fields keep their values as text`() {
        assertEquals(listOf("tired", "foggy"), CustomFieldValues.split("tired; foggy;"))
        assertEquals("tired; foggy", CustomFieldValues.join(listOf("tired", " ", "foggy")))
        assertNull(CustomFieldValues.join(emptyList()))
        assertEquals("how-tired-2", CustomFieldValues.keyFor("How tired?", setOf("how-tired")))
        val scale = CustomField("tired", "How tired", CustomFieldType.SCALE, max = 10)
        val yes = CustomField("caf", "Caffeine", CustomFieldType.YES_NO)
        val water = CustomField("water", "Water", CustomFieldType.NUMBER, unit = "ml")
        assertEquals(listOf("How tired: 7 of 10", "Caffeine: Yes", "Water: 500 ml"), CustomFieldValues.lines(listOf(scale, yes, water), mapOf("tired" to "7", "caf" to "yes", "water" to "500")))
    }
}
