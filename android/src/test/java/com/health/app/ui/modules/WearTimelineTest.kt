package com.health.app.ui.modules

import com.health.api.ContinenceDiary
import com.health.api.ContinenceDiaryItem
import com.health.api.ContinenceEntry
import com.health.api.ContinenceKind
import com.health.api.ContinenceWear
import com.health.api.Fullness
import com.health.api.LeakContents
import com.health.api.LeakSize
import com.health.api.SupplyKind
import com.health.app.ui.Format
import com.health.app.ui.modules.continence.WearTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** The diary's Today, grouped: each product worn with its accidents beneath, and the rest on its own. */
class WearTimelineTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val today: LocalDate = LocalDate.of(2026, 11, 13)
    private fun at(h: Int, m: Int = 0, day: LocalDate = today): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val clock = { t: Long -> Format.timeOfDay(t, zone) }
    private fun String.plain() = replace(' ', ' ').replace(' ', ' ')

    // Overnight: briefs put on last night (nothing taken off), a wet night and a leak, changed for day pants this morning.
    private val putOn = ContinenceEntry(at(19, 48, today.minusDays(1)), ContinenceKind.PAD_CHANGE, putOnSupplyId = "m", id = "p1")
    private val wetNight = ContinenceEntry(at(2, 30), ContinenceKind.BEDWETTING, supplyId = "m", fullness = Fullness.WET, id = "a1")
    private val evening = ContinenceEntry(at(22, 5, today.minusDays(1)), ContinenceKind.LEAK, supplyId = "m", leak = LeakSize.SMALL, triggers = listOf("cough"), fullness = Fullness.DAMP, id = "a0")
    private val morning = ContinenceEntry(at(7, 0), ContinenceKind.PAD_CHANGE, supplyId = "m", putOnSupplyId = "d", fullness = Fullness.FULL, wornMinutes = 672, id = "c1")
    private val overnight = ContinenceWear(
        "m", "Megamax Airlock", SupplyKind.BRIEF, on = putOn, off = morning, start = putOn.at, end = morning.at, minutes = 672,
        fullness = Fullness.FULL, accidents = listOf(wetNight, evening), nextName = "Day pants",
    )
    // Day pants since 7:00, with a leak at 9:40.
    private val leak = ContinenceEntry(at(9, 40), ContinenceKind.LEAK, supplyId = "d", leak = LeakSize.SMALL, triggers = listOf("cough", "lifting"), fullness = Fullness.DAMP, id = "a2")
    private val current = ContinenceWear("d", "Day pants", SupplyKind.PULL_UP, on = morning, start = morning.at, current = true, minutes = 630, fullness = Fullness.DAMP, accidents = listOf(leak))
    private val trip = ContinenceEntry(at(8, 15), ContinenceKind.URINATION, volumeMl = 300, id = "t1")
    private val bowel = ContinenceEntry(at(12, 0), ContinenceKind.BOWEL, bristol = 4, id = "b1")
    private val now = at(17, 30)
    private val diary = ContinenceDiary(
        today.toString(),
        listOf(
            ContinenceDiaryItem(now, wear = current),
            ContinenceDiaryItem(bowel.at, entry = bowel),
            ContinenceDiaryItem(trip.at, entry = trip),
            ContinenceDiaryItem(morning.at, wear = overnight),
        ),
    )
    private val todays = listOf(wetNight, morning, trip, leak, bowel)

    @Test
    fun `each product worn is one row with its accidents oldest first, and what's on now is left to the card above`() {
        val rows = WearTimeline.rows(diary, today, todays, currentAbove = true)
        assertEquals(listOf(bowel.at, trip.at, morning.at), rows.map { it.at })
        assertEquals(bowel, (rows[0] as WearTimeline.Single).entry)
        val worn = rows[2] as WearTimeline.Worn
        assertEquals(listOf("a0", "a1"), worn.wear.accidents.map { it.id })
        assertFalse(worn.onNow)
        // Its name opens the change that took it off; putting it on took nothing off, so it has a line of its own.
        assertEquals(morning, worn.opens)
        assertEquals(putOn, worn.putOnAlone)
    }

    @Test
    fun `with the Wearing now card hidden, what's on is a row marked on now`() {
        val rows = WearTimeline.rows(diary, today, todays, currentAbove = false)
        val first = rows.first() as WearTimeline.Worn
        assertTrue(first.onNow)
        assertEquals(morning, first.opens)
        // Put on by a change that took the briefs off: that change heads the briefs' row instead.
        assertNull(first.putOnAlone)
        assertEquals(4, rows.size)
    }

    @Test
    fun `without today's grouped diary, each of today's entries is a row of its own, newest first`() {
        val flat = WearTimeline.rows(null, today, todays, currentAbove = true)
        assertEquals(listOf(bowel, leak, trip, morning, wetNight), flat.map { (it as WearTimeline.Single).entry })
        assertFalse(WearTimeline.grouped(diary.copy(date = today.minusDays(1).toString()), today))
        assertEquals(5, WearTimeline.rows(diary.copy(date = today.minusDays(1).toString()), today, todays, currentAbove = true).size)
        assertTrue(WearTimeline.grouped(diary, today))
    }

    @Test
    fun `a worn product reads as how long, then what went on`() {
        assertEquals("Megamax Airlock", WearTimeline.title(overnight))
        assertEquals("A product", WearTimeline.title(overnight.copy(name = null)))
        assertEquals("Worn 11 h 12 min · then Day pants", WearTimeline.subtitle(overnight, clock))
        assertEquals(
            "Worn 11 h 12 min · leaked through · then nothing on",
            WearTimeline.subtitle(overnight.copy(leakedThrough = true, nextName = null, off = morning.copy(putOnSupplyId = null, nothingOn = true)), clock),
        )
        // At "leaked through", the gauge says it already.
        assertEquals("Worn 11 h 12 min · then Day pants", WearTimeline.subtitle(overnight.copy(leakedThrough = true, fullness = Fullness.LEAKED), clock))
        assertEquals("Taken off", WearTimeline.subtitle(overnight.copy(minutes = null, nextName = null), clock))
        // Whatever else the change noted follows.
        assertEquals(
            "Worn 11 h 12 min · then Day pants · bedding changed · skin a little sore",
            WearTimeline.subtitle(overnight.copy(off = morning.copy(skin = 1, changedBedding = true, night = true)), clock),
        )
        // Taking it off isn't in the diary: just when it went on.
        assertEquals("On at 7:48 PM", WearTimeline.subtitle(overnight.copy(off = null, end = null), clock).plain())
        // On now: since when, and for how long.
        assertEquals("Since 7:00 AM · 10 h 30 min", WearTimeline.subtitle(current, clock).plain())
    }

    @Test
    fun `how full a worn product shows`() {
        assertEquals(Fullness.FULL to "Full", WearTimeline.fullness(overnight))
        assertNull(WearTimeline.fullness(overnight.copy(fullness = null)))
        // Taking it off isn't in the diary: how it was isn't known.
        assertNull(WearTimeline.fullness(overnight.copy(off = null)))
        assertEquals(Fullness.DAMP to "Damp", WearTimeline.fullness(current))
        assertEquals(Fullness.DRY to "Dry so far", WearTimeline.fullness(current.copy(fullness = null, accidents = emptyList())))
        assertNull(WearTimeline.fullness(current.copy(fullness = null)))
    }

    @Test
    fun `an accident beneath its product is short`() {
        assertEquals("Small · cough, lifting", WearTimeline.accidentDetail(leak))
        assertEquals("", WearTimeline.accidentDetail(wetNight))
        assertEquals("Stool · leaked through", WearTimeline.accidentDetail(ContinenceEntry(1, ContinenceKind.BOWEL_LEAK, contents = LeakContents.STOOL, fullness = Fullness.LEAKED, supplyId = "m")))
        assertEquals("Urine and stool · Medium", WearTimeline.accidentDetail(leak.copy(contents = LeakContents.BOTH, leak = LeakSize.MEDIUM, triggers = emptyList())))
        // A leak of urine says nothing new.
        assertEquals("Small", WearTimeline.accidentDetail(leak.copy(contents = LeakContents.URINE, triggers = emptyList())))
    }

    @Test
    fun `times say the day when it isn't today`() {
        assertEquals("9:40 AM", WearTimeline.stamp(leak.at, zone, today).plain())
        assertEquals("Yesterday 10:05 PM", WearTimeline.stamp(evening.at, zone, today).plain())
        assertEquals("Wed 10:05 PM", WearTimeline.stamp(at(22, 5, today.minusDays(2)), zone, today).plain())
        assertNull(WearTimeline.dayOf(leak.at, zone, today))
        assertEquals("Yesterday", WearTimeline.dayOf(evening.at, zone, today))
        assertEquals("Put on at 7:00 AM", WearTimeline.putOnLine(morning.at, zone, today).plain())
        assertEquals("Put on yesterday at 7:48 PM", WearTimeline.putOnLine(putOn.at, zone, today).plain())
        assertEquals("Put on Wednesday at 10:05 PM", WearTimeline.putOnLine(at(22, 5, today.minusDays(2)), zone, today).plain())
    }

    @Test
    fun `changes count only what came off, not putting something on alone`() {
        val entries = todays + putOn.copy(at = at(18)) + ContinenceEntry(at(19), ContinenceKind.PAD_CHANGE, supplyId = "d", nothingOn = true) +
            ContinenceEntry(at(20), ContinenceKind.BOWEL_LEAK) + ContinenceEntry(at(21), ContinenceKind.CATHETER)
        val counts = WearTimeline.counts(entries)
        assertEquals(WearTimeline.Counts(trips = 1, leaks = 2, bowel = 1, bowelLeaks = 1, changes = 2, catheters = 1), counts)
        assertTrue(WearTimeline.isChange(morning))
        assertFalse(WearTimeline.isChange(putOn))
        assertFalse(WearTimeline.isChange(leak))
        assertEquals("1 bowel accident · 2 changes · 1 catheter", WearTimeline.moreCounts(counts))
        assertEquals("", WearTimeline.moreCounts(WearTimeline.counts(listOf(putOn))))
    }
}
