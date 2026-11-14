package com.health.app.ui.modules

import com.health.api.ContinenceDay
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceSettings
import com.health.api.ContinenceWear
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

    // What's on now, with how it is so far: night briefs, damp after a leak.
    private val leak = ContinenceEntry(at(1, 10), ContinenceKind.LEAK, supplyId = "n", fullness = Fullness.DAMP, id = "a1")
    private val wear = ContinenceWear("n", "Night briefs", SupplyKind.BRIEF, start = at(23, 20, today.minusDays(1)), current = true, fullness = Fullness.DAMP, accidents = listOf(leak))
    private val onNow = ContinenceWearing("n", "Night briefs", SupplyKind.BRIEF, at(23, 20, today.minusDays(1)), wear = wear)

    @Test
    fun `the Change sheet starts from how what's on is so far`() {
        val now = at(6, 30)
        val d = WearText.changeDraft(onNow, ContinenceSettings(), all, emptyList(), now)
        assertEquals("n", d.supplyId)
        assertEquals(Fullness.DAMP, d.fullness)
        assertFalse(d.leakedThrough)
        // It leaked through in an accident: so it starts.
        assertTrue(WearText.changeDraft(onNow.copy(wear = wear.copy(leakedThrough = true)), ContinenceSettings(), all, emptyList(), now).leakedThrough)
        val over = WearText.changeDraft(onNow.copy(wear = wear.copy(fullness = Fullness.LEAKED)), ContinenceSettings(), all, emptyList(), now)
        assertEquals(Fullness.LEAKED, over.fullness)
        assertTrue(over.leakedThrough)
        // Dry so far, or nothing known about it: not noted.
        assertNull(WearText.changeDraft(onNow.copy(wear = wear.copy(fullness = null, accidents = emptyList())), ContinenceSettings(), all, emptyList(), now).fullness)
        assertNull(WearText.changeDraft(onNow.copy(wear = null), ContinenceSettings(), all, emptyList(), now).fullness)
        // A wear of something else never sets how this was.
        assertNull(WearText.changeDraft(onNow.copy(wear = wear.copy(supplyId = "d")), ContinenceSettings(), all, emptyList(), now).fullness)
        // "2 accidents while you wore it".
        assertEquals("1 accident while you wore it", WearText.whileWorn(wear))
        assertEquals("2 accidents while you wore it", WearText.whileWorn(wear.copy(accidents = listOf(leak, leak.copy(at = at(3))))))
        assertNull(WearText.whileWorn(wear.copy(accidents = emptyList())))
        assertNull(WearText.whileWorn(null))
    }

    @Test
    fun `a new accident is in what's on, as full as it is so far`() {
        val new = ContinenceEntry(at(5), ContinenceKind.LEAK)
        val draft = WearText.entryDraft(new, onNow, usual = "d")
        assertEquals("n", draft.supplyId)
        assertEquals(Fullness.DAMP, draft.fullness)
        assertTrue(WearText.inWhatsOn(draft, onNow))
        // A wet night and a bowel accident too; a fullness already picked stays.
        assertEquals("n", WearText.entryDraft(new.copy(kind = ContinenceKind.BEDWETTING), onNow, "d").supplyId)
        assertEquals(Fullness.FULL, WearText.entryDraft(new.copy(kind = ContinenceKind.BOWEL_LEAK, fullness = Fullness.FULL), onNow, "d").fullness)
        // At "leaked through" so far, it starts leaked through.
        assertTrue(WearText.entryDraft(new, onNow.copy(wear = wear.copy(fullness = Fullness.LEAKED)), "d").leakedThrough)
        // Picked something else ("Not this"): no longer in what's on.
        assertFalse(WearText.inWhatsOn(draft.copy(supplyId = "d"), onNow))
        assertFalse(WearText.inWhatsOn(draft.copy(supplyId = null), onNow))
        // Editing an accident keeps its own product, and isn't "now".
        val saved = ContinenceEntry(at(1, 10), ContinenceKind.LEAK, supplyId = "d", id = "x")
        assertEquals(saved, WearText.entryDraft(saved, onNow, "d"))
        assertFalse(WearText.inWhatsOn(saved.copy(supplyId = "n"), onNow))
        // Nothing on: in nothing. Nothing known: in the usual, as before.
        assertNull(WearText.entryDraft(new, ContinenceWearing(since = 1, nothingOn = true), "d").supplyId)
        assertEquals("d", WearText.entryDraft(new, ContinenceWearing(), "d").supplyId)
        assertEquals("d", WearText.entryDraft(new, null, "d").supplyId)
        assertNull(WearText.entryDraft(new, null, "d").fullness)
        // Kinds without a product have none; a catheter keeps its own.
        assertNull(WearText.entryDraft(new.copy(kind = ContinenceKind.URINATION, supplyId = "n"), onNow, "d").supplyId)
        assertEquals("c", WearText.entryDraft(new.copy(kind = ContinenceKind.CATHETER, supplyId = "c"), onNow, "d").supplyId)
    }

    @Test
    fun `save and change takes off what the accident was in, as it left it`() {
        val now = at(5, 2)
        val accident = ContinenceEntry(at(5), ContinenceKind.LEAK, supplyId = "n", fullness = Fullness.SOAKED, leakedThrough = true)
        val c = WearText.changeAfter(accident, onNow, ContinenceSettings(defaultSupplyId = "d"), all, emptyList(), now)
        assertEquals(ContinenceKind.PAD_CHANGE, c.kind)
        assertEquals(now, c.at)
        assertEquals("n", c.supplyId)
        assertEquals("n", c.putOnSupplyId)
        assertEquals(Fullness.SOAKED, c.fullness)
        assertTrue(c.leakedThrough)
        // Not noted in the accident: as it was before.
        assertEquals(Fullness.DAMP, WearText.changeAfter(accident.copy(fullness = null, leakedThrough = false), onNow, null, all, emptyList(), now).fullness)
        // Logged as a while ago: the change is never before it.
        assertEquals(accident.at, WearText.changeAfter(accident, onNow, null, all, emptyList(), accident.at - 60_000).at)
        // In something put away since: it still comes off, and the usual goes on.
        val old = WearText.changeAfter(accident.copy(supplyId = "o"), onNow, ContinenceSettings(defaultSupplyId = "d"), all, emptyList(), now)
        assertEquals("o", old.supplyId)
        assertEquals("d", old.putOnSupplyId)
        // In nothing: nothing comes off, and how it was isn't asked.
        val none = WearText.changeAfter(accident.copy(supplyId = null), onNow, ContinenceSettings(defaultSupplyId = "d"), all, emptyList(), now)
        assertNull(none.supplyId)
        assertNull(none.fullness)
        assertFalse(none.leakedThrough)
        // The accident just saved counts in what comes off, once.
        assertEquals(2, WearText.withAccident(onNow, accident)?.wear?.accidents?.size)
        assertEquals(1, WearText.withAccident(onNow, accident.copy(at = leak.at))?.wear?.accidents?.size)
        assertEquals(1, WearText.withAccident(onNow, accident.copy(supplyId = "d"))?.wear?.accidents?.size)
        assertEquals(onNow, WearText.withAccident(onNow, null))
    }

    @Test
    fun `what's on reads how it is`() {
        assertEquals("Damp · 1 accident", WearText.stateLine(wear))
        assertEquals("Dry so far", WearText.stateLine(wear.copy(fullness = null, accidents = emptyList())))
        assertEquals("Wet · leaked through · 2 accidents", WearText.stateLine(wear.copy(fullness = Fullness.WET, leakedThrough = true, accidents = listOf(leak, leak))))
        assertEquals("Leaked through · 1 accident", WearText.stateLine(wear.copy(fullness = Fullness.LEAKED, leakedThrough = true)))
        assertEquals("1 accident", WearText.stateLine(wear.copy(fullness = null)))
        assertNull(WearText.stateLine(null))
        assertEquals(Fullness.DRY, WearText.shownFullness(wear.copy(fullness = null, accidents = emptyList())))
        assertNull(WearText.shownFullness(wear.copy(fullness = null)))
        assertTrue(WearText.marksLeak(wear.copy(leakedThrough = true)))
        assertFalse(WearText.marksLeak(wear.copy(leakedThrough = true, fullness = Fullness.LEAKED)))
    }

    @Test
    fun `a one-tap accident is in what's on, and the quick buttons leave Change to the card above`() {
        val statuses = listOf(com.health.api.SupplyStatus(day))
        assertEquals("n", quickEntry(ContinenceKind.LEAK, ContinenceSettings(), statuses, 5, onNow).supplyId)
        assertEquals("n", quickEntry(ContinenceKind.BEDWETTING, ContinenceSettings(), statuses, 5, onNow).supplyId)
        assertNull(quickEntry(ContinenceKind.LEAK, ContinenceSettings(), statuses, 5, null).supplyId)
        assertNull(quickEntry(ContinenceKind.URINATION, ContinenceSettings(), statuses, 5, onNow).supplyId)
        val change = quickEntry(ContinenceKind.PAD_CHANGE, ContinenceSettings(defaultSupplyId = "d"), statuses, 5, onNow)
        assertNull(change.supplyId)
        assertEquals("d", change.putOnSupplyId)
        val kinds = listOf(ContinenceKind.URINATION, ContinenceKind.LEAK, ContinenceKind.BOWEL, ContinenceKind.PAD_CHANGE)
        assertEquals(listOf(ContinenceKind.URINATION, ContinenceKind.LEAK, ContinenceKind.BOWEL), WearText.quickKinds(kinds, changeAbove = true))
        assertEquals(kinds, WearText.quickKinds(kinds, changeAbove = false))
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
