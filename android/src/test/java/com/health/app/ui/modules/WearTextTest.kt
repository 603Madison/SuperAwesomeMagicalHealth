package com.health.app.ui.modules

import com.health.api.ContinenceDay
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceSettings
import com.health.api.ContinenceWearing
import com.health.api.DaySummary
import com.health.api.Fields
import com.health.api.Fullness
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.ProductStats
import com.health.api.Supply
import com.health.api.SupplyKind
import com.health.api.TodaySection
import com.health.app.ui.modules.continence.WearText
import com.health.app.ui.modules.continence.continenceSections
import com.health.app.ui.modules.continence.quickEntry
import com.health.app.ui.today.TodayLayout
import com.health.app.ui.today.TodayPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** What's being worn and a change as one step: the Change sheet's defaults, the order products are offered in, and how it all reads. */
class WearTextTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 13)
    private fun at(h: Int, m: Int = 0, day: LocalDate = today): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun String.plain() = replace(' ', ' ')

    private val night = Supply("Night briefs", SupplyKind.BRIEF, onHand = 20, id = "n")
    private val day = Supply("Day pants", SupplyKind.PULL_UP, onHand = 30, id = "d")
    private val liners = Supply("Liners", SupplyKind.LINER, onHand = 40, id = "l")
    private val wipes = Supply("Wipes", SupplyKind.WIPES, onHand = 2, id = "w")
    private val catheters = Supply("Catheters", SupplyKind.CATHETER, onHand = 30, id = "c")
    private val old = Supply("Old pads", SupplyKind.PAD, active = false, id = "o")
    private val all = listOf(liners, day, night, wipes, catheters, old)

    private fun change(at: Long, off: String? = null, on: String? = null, fullness: Fullness? = null, nothingOn: Boolean = false, worn: Int? = null) =
        ContinenceEntry(at, ContinenceKind.PAD_CHANGE, supplyId = off, putOnSupplyId = on, fullness = fullness, nothingOn = nothingOn, wornMinutes = worn)

    @Test
    fun `only active, worn products are offered`() {
        assertEquals(listOf("Liners", "Day pants", "Night briefs"), WearText.wearable(all).map { it.name })
    }

    @Test
    fun `putting on offers the usual first, then the last used, then the rest as listed`() {
        assertEquals(listOf("n", "d", "l"), WearText.putOnOrder(all, "n", listOf("d")).map { it.id })
        assertEquals(listOf("n", "d", "l"), WearText.putOnOrder(all, null, listOf("n", "d")).map { it.id })
        assertEquals(listOf("l", "d", "n"), WearText.putOnOrder(all, null).map { it.id })
        // The usual, put away, isn't offered.
        assertEquals(listOf("l", "d", "n"), WearText.putOnOrder(all, "o").map { it.id })
    }

    @Test
    fun `recent put-ons are newest first, older changes counting what they took off`() {
        val entries = listOf(
            change(1, on = "n"),
            change(3, off = "n", on = "d"),
            change(2, off = "l"),
            change(4, off = "d", nothingOn = true),
            ContinenceEntry(5, ContinenceKind.LEAK, supplyId = "l"),
        )
        assertEquals(listOf("d", "l", "n"), WearText.recentPutOns(entries))
    }

    @Test
    fun `the Change sheet starts from what's on, putting the same on again`() {
        val now = at(6, 30)
        val wearing = ContinenceWearing("n", "Night briefs", SupplyKind.BRIEF, at(23, 20, today.minusDays(1)))
        val d = WearText.changeDraft(wearing, ContinenceSettings(defaultSupplyId = "d"), all, listOf("l"), now)
        assertEquals(ContinenceKind.PAD_CHANGE, d.kind)
        assertEquals(now, d.at)
        assertEquals("n", d.supplyId)
        assertEquals("n", d.putOnSupplyId)
        assertFalse(d.nothingOn)
        // Nothing known: nothing to take off; the usual, or else the last used, to put on.
        val fresh = WearText.changeDraft(null, ContinenceSettings(defaultSupplyId = "d"), all, listOf("l"), now)
        assertNull(fresh.supplyId)
        assertEquals("d", fresh.putOnSupplyId)
        assertEquals("l", WearText.changeDraft(ContinenceWearing(), ContinenceSettings(), all, listOf("l"), now).putOnSupplyId)
        // Nothing on: nothing to take off.
        assertNull(WearText.changeDraft(ContinenceWearing(since = 1, nothingOn = true), ContinenceSettings(defaultSupplyId = "d"), all, emptyList(), now).supplyId)
        // Something put away since isn't picked.
        assertNull(WearText.changeDraft(ContinenceWearing("o", since = 1), ContinenceSettings(), all, emptyList(), now).supplyId)
        assertEquals(ContinenceEntry(now, ContinenceKind.PAD_CHANGE, putOnSupplyId = "d"), WearText.putOnEntry("d", now))
    }

    @Test
    fun `saving a change puts nothing on with nothing on, and how it was needs something taken off`() {
        val saved = WearText.finishChange(change(1, off = null, on = "d", fullness = Fullness.WET).copy(padWeightG = 80.0, note = "  "))
        assertNull(saved.fullness)
        assertNull(saved.padWeightG)
        assertNull(saved.note)
        assertNull(WearText.finishChange(change(1, off = "n", on = "d", nothingOn = true)).putOnSupplyId)
        assertEquals(Fullness.FULL, WearText.finishChange(change(1, off = "n", on = "d", fullness = Fullness.FULL)).fullness)
        // An older change opens as a change into a fresh one of the same.
        assertEquals("n", WearText.asChange(change(1, off = "n")).putOnSupplyId)
        assertEquals("d", WearText.asChange(change(1, off = "n", on = "d")).putOnSupplyId)
        assertNull(WearText.asChange(change(1, off = "n", nothingOn = true)).putOnSupplyId)
    }

    @Test
    fun `a change reads as what came off and what went on`() {
        assertEquals("Night briefs → Day pants · Full · worn 7 h", WearText.changeLine(change(1, "n", "d", Fullness.FULL, worn = 420), all))
        assertEquals("Night briefs → nothing for now", WearText.changeTitle(change(1, "n", nothingOn = true), all))
        assertEquals("Put on Day pants", WearText.changeTitle(change(1, on = "d"), all))
        assertEquals("Night briefs → Night briefs", WearText.changeTitle(change(1, off = "n"), all))
        assertEquals("Change", WearText.changeTitle(change(1), all))
        assertEquals("A product → Day pants", WearText.changeTitle(change(1, "gone", "d"), all))
        assertEquals(listOf("Soaked", "worn 7 h 10 min", "leaked through"), WearText.changeDetail(change(1, "n", "d", Fullness.SOAKED, worn = 430).copy(leakedThrough = true)))
        assertEquals(listOf("Leaked through"), WearText.changeDetail(change(1, "n", "d", Fullness.LEAKED).copy(leakedThrough = true)))
    }

    @Test
    fun `times and kinds read plainly`() {
        assertEquals("7 h 10 min", WearText.wornFor(430))
        assertEquals("45 min", WearText.wornFor(45))
        assertEquals("6 h", WearText.wornFor(360))
        assertEquals(360, WearText.rough(372))
        assertEquals(105, WearText.rough(100))
        assertEquals("6 h", WearText.hoursShort(375))
        assertEquals("7 h", WearText.hoursShort(395))
        assertEquals("Pull-up", WearText.kindWord(SupplyKind.PULL_UP))
        assertEquals("Brief", WearText.kindWord(SupplyKind.BRIEF))
        assertEquals("Bed pad", WearText.kindWord(SupplyKind.BED_PAD))
        assertNull(WearText.kindWord(SupplyKind.TAMPON))
    }

    @Test
    fun `what's on reads since when, for how long, and the usual`() {
        val now = at(6, 30)
        val w = ContinenceWearing("n", "Night briefs", SupplyKind.BRIEF, at(23, 20, today.minusDays(1)), typicalMinutes = 372)
        assertEquals("Since 11:20 PM · 7 h 10 min", WearText.sinceLine(w, now) { com.health.app.ui.Format.timeOfDay(it, zone) }.plain())
        assertEquals("You usually change these after about 6 h", WearText.usualLine(w))
        assertNull(WearText.usualLine(w.copy(typicalMinutes = null)))
        assertTrue(WearText.changeDue(w.copy(changeDueAt = now - 1), now))
        assertFalse(WearText.changeDue(w.copy(changeDueAt = now + 60_000), now))
        assertFalse(WearText.changeDue(ContinenceWearing(since = 1, nothingOn = true, changeDueAt = 2), now))
        assertFalse(WearText.changeDue(null, now))
        assertEquals("Time to change: Night briefs,\u00A07\u00A0h", WearText.dueTitle(w, now))
    }

    @Test
    fun `a product's pattern in a line`() {
        val p = ProductStats("n", "Night briefs", 12, avgWornMinutes = 425, typicalFullness = Fullness.FULL, leakedThroughPct = 8, nightShare = 1.0, leakedThrough = 1)
        assertEquals("Night briefs: usually 7 h, often full, leaked through 1 in 12, mostly at night", WearText.productLine(p))
        assertEquals(
            "Day pants: usually 5 h, often wet, never leaked through",
            WearText.productLine(p.copy(name = "Day pants", avgWornMinutes = 300, typicalFullness = Fullness.WET, leakedThrough = 0, nightShare = 0.0)),
        )
        assertEquals("Liners: 1 change", WearText.productLine(ProductStats("l", "Liners", 1)))
    }

    @Test
    fun `the diary shows what's worn when products are tracked, and a quick change puts the usual on`() {
        assertFalse(WearText.tracksProducts(Fields.DEFAULTS - Fields.CONTINENCE_PROTECTION - Fields.CONTINENCE_SUPPLIES, null))
        assertTrue(WearText.tracksProducts(Fields.DEFAULTS + Fields.CONTINENCE_PROTECTION, null))
        assertTrue(WearText.tracksProducts(emptySet(), ContinenceWearing(since = 1, nothingOn = true)))
        val ids = continenceSections(Fields.DEFAULTS + Fields.CONTINENCE_SUPPLIES).map { it.id }
        assertEquals("wearing", ids.first())
        assertTrue(ids.indexOf("products") == ids.indexOf("progress") + 1)
        val quick = quickEntry(ContinenceKind.PAD_CHANGE, ContinenceSettings(defaultSupplyId = "d"), listOf(com.health.api.SupplyStatus(day)))
        assertEquals("d", quick.putOnSupplyId)
        assertNull(quick.supplyId)
    }

    @Test
    fun `Up next has the change once it's due, and Today has a card for what's on`() {
        val now = at(17, 30)
        val modules = Module.DEFAULT + Module.CONTINENCE
        val wearing = ContinenceWearing("d", "Day pants", SupplyKind.PULL_UP, at(11, 45), changeDueAt = at(16, 45))
        val summary = DaySummary(date = today.toString(), modules = ModulesToday(continence = ContinenceDay(today.toString(), wearing = wearing)))
        val visible = TodayLayout.sections(modules)
        val items = TodayPlan.upNext(summary, modules, visible, now, zone, isToday = true)
        val change = items.single { it.kind == TodayPlan.Kind.CHANGE }
        assertEquals("Time to change: Day pants, 6 h", change.title.replace('\u00A0', ' '))
        assertEquals("On since 11:45 AM", change.detail.plain())
        assertEquals(at(16, 45), change.at)
        // Not yet due, hidden, or not today: no row.
        assertTrue(TodayPlan.upNext(summary, modules, visible, at(16), zone, isToday = true).none { it.kind == TodayPlan.Kind.CHANGE })
        assertTrue(TodayPlan.upNext(summary, modules, visible - TodaySection.CONTINENCE, now, zone, isToday = true).none { it.kind == TodayPlan.Kind.CHANGE })
        assertTrue(TodayPlan.upNext(summary, modules, visible, now, zone, isToday = false).isEmpty())
        // A card, though nothing else is logged today.
        assertEquals(TodayPlan.Place.CARD, TodayPlan.place(TodaySection.CONTINENCE, TodayPlan.Day(summary, modules, items, now, zone)))
        val nothing = summary.copy(modules = ModulesToday(continence = ContinenceDay(today.toString())))
        assertEquals(TodayPlan.Place.ALSO, TodayPlan.place(TodaySection.CONTINENCE, TodayPlan.Day(nothing, modules, emptyList(), now, zone)))
    }
}
