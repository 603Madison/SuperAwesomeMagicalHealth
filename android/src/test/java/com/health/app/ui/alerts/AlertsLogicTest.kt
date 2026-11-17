package com.health.app.ui.alerts

import com.health.api.AlertFeedback
import com.health.api.AlertKind
import com.health.api.AlertLevel
import com.health.api.AlertSettings
import com.health.api.AlertWatch
import com.health.api.HealthAlert
import com.health.app.ui.modules.FeatureScreens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Health alerts' words, order and the phone's own notifications, without a screen. */
class AlertsLogicTest {
    init {
        Locale.setDefault(Locale.US)
    }

    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 11, 17)
    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    /** Newer Javas put a narrow no-break space before AM and PM. */
    private fun String.plain() = replace('\u202F', ' ').replace('\u00A0', ' ')

    private fun alert(
        id: String,
        level: AlertLevel = AlertLevel.WATCH,
        createdAt: Long = at(today, 8),
        seenAt: Long? = null,
        dismissedAt: Long? = null,
        feedback: AlertFeedback? = null,
        start: Long = createdAt,
        end: Long? = null,
    ) = HealthAlert(
        id, AlertKind.HIGH_HEART_RATE, level, "Your heart rate stayed high at rest", "It was 128–151 bpm for 14 min. Worth a look.", "Rest.",
        start = start, end = end, createdAt = createdAt, seenAt = seenAt, dismissedAt = dismissedAt, feedback = feedback,
    )

    @Test
    fun `each level in plain words`() {
        assertEquals("Could need care now", Alerts.levelText(AlertLevel.URGENT))
        assertEquals("Worth a doctor's look", Alerts.levelText(AlertLevel.DOCTOR))
        assertEquals("Keep an eye on", Alerts.levelText(AlertLevel.WATCH))
    }

    @Test
    fun `newest first, but one that could need care now stays on top until it's put away`() {
        val urgent = alert("u", AlertLevel.URGENT, createdAt = at(today, 2))
        val newer = alert("w", AlertLevel.WATCH, createdAt = at(today, 9))
        val doctor = alert("d", AlertLevel.DOCTOR, createdAt = at(today, 5))
        val older = alert("o", AlertLevel.WATCH, createdAt = at(today.minusDays(3), 9))
        assertEquals(listOf("u", "w", "d", "o"), Alerts.newestFirst(listOf(older, doctor, newer, urgent)).map { it.id })
        // Put away, it takes its place by date.
        val away = urgent.copy(dismissedAt = at(today, 10))
        assertEquals(listOf("w", "d", "u", "o"), Alerts.newestFirst(listOf(older, doctor, newer, away)).map { it.id })
    }

    @Test
    fun `unseen ones are those not seen and not put away`() {
        val list = listOf(alert("a"), alert("b", seenAt = 1), alert("c", dismissedAt = 1))
        assertEquals(setOf("a"), Alerts.unseenIds(list))
    }

    @Test
    fun `put away, with or without a reason`() {
        assertEquals("Put away: I was active", Alerts.awayText(alert("a", dismissedAt = 1, feedback = AlertFeedback.ACTIVE)))
        assertEquals("Put away: I'm getting it checked", Alerts.awayText(alert("a", dismissedAt = 1, feedback = AlertFeedback.DOCTOR)))
        assertEquals("Put away", Alerts.awayText(alert("a", dismissedAt = 1)))
        val changed = alert("b", dismissedAt = 5)
        assertEquals(listOf("a", "b", "c"), Alerts.replace(listOf(alert("a"), alert("b"), alert("c")), changed).map { it.id })
        assertEquals(5L, Alerts.replace(listOf(alert("a"), alert("b")), changed)[1].dismissedAt)
    }

    @Test
    fun `which devices it came from`() {
        assertNull(Alerts.devicesText(emptyList()))
        assertNull(Alerts.devicesText(listOf(" ")))
        assertEquals("From Polar H10", Alerts.devicesText(listOf("Polar H10")))
        assertEquals("From Polar H10 and Colmi R02", Alerts.devicesText(listOf("Polar H10", "Colmi R02")))
        assertEquals("From Polar H10, Colmi R02 and Pebble", Alerts.devicesText(listOf("Polar H10", "Colmi R02", "Pebble")))
    }

    @Test
    fun `when it happened`() {
        assertEquals("Today, 2:10 AM", Alerts.whenText(alert("a", start = at(today, 2, 10)), zone, today).plain())
        assertEquals("Today, 2:10 AM – 2:48 AM", Alerts.whenText(alert("a", start = at(today, 2, 10), end = at(today, 2, 48)), zone, today).plain())
        assertEquals(
            "Yesterday 11:40 PM – Today 12:25 AM",
            Alerts.whenText(alert("a", start = at(today.minusDays(1), 23, 40), end = at(today, 0, 25)), zone, today).plain(),
        )
        assertEquals("Sat, 14 Nov – Today", Alerts.whenText(alert("a", start = at(today.minusDays(3), 7), end = at(today, 7)), zone, today).plain())
    }

    @Test
    fun `what it's watching, and the empty list`() {
        assertEquals(Alerts.WatchState.ON, Alerts.watchState(AlertWatch("Blood oxygen", "From Colmi R02", true)))
        assertEquals(Alerts.WatchState.OFF, Alerts.watchState(AlertWatch("Heart rhythm", "Turned off", false)))
        assertEquals(Alerts.WatchState.NOT_YET, Alerts.watchState(AlertWatch("Heart rhythm", "Needs a chest strap: a ring or watch can't tell", false)))
        val watching = listOf(AlertWatch("Blood oxygen", "From Colmi R02", true))
        assertTrue(Alerts.emptyText(AlertSettings(enabled = false), watching).startsWith("Health alerts are off"))
        assertTrue(Alerts.emptyText(AlertSettings(), emptyList()).startsWith("There's nothing to watch yet"))
        assertEquals("No alerts in the last 30 days. Here's what it's watching:", Alerts.emptyText(AlertSettings(), watching))
    }

    @Test
    fun `settings move a step at a time within the server's limits`() {
        val high = Alerts.HIGH_HEART_RATE
        assertEquals(125, high.step(120, 1))
        assertEquals(115, high.step(120, -1))
        assertEquals(120, high.step(117, 1))
        assertEquals(115, high.step(117, -1))
        assertEquals(180, high.step(180, 1))
        assertEquals(100, high.step(100, -1))
        assertEquals(41, Alerts.LOW_HEART_RATE.step(40, 1))
        assertEquals(85, Alerts.LOW_OXYGEN.step(85, -1))
        assertEquals("100–180 bpm", high.text)
        assertEquals("85–94%", Alerts.LOW_OXYGEN.text)
        // The same limits as the server keeps.
        val clamped = AlertSettings(highHeartRate = 999, lowHeartRate = 1, lowOxygen = 99).clamped()
        assertEquals(high.max, clamped.highHeartRate)
        assertEquals(Alerts.LOW_HEART_RATE.min, clamped.lowHeartRate)
        assertEquals(Alerts.LOW_OXYGEN.max, clamped.lowOxygen)
    }

    @Test
    fun `each alert is told once at each level, unless it's seen or put away, or notifications are off`() {
        val urgent = alert("u", AlertLevel.URGENT, createdAt = at(today, 2))
        val watch = alert("w", AlertLevel.WATCH, createdAt = at(today, 9))
        val seen = alert("s", seenAt = 1)
        val away = alert("a", dismissedAt = 1)
        val list = listOf(watch, urgent, seen, away)
        assertEquals(listOf("u", "w"), Alerts.toTell(list, AlertSettings(), emptySet()).map { it.id })
        assertEquals(listOf("w"), Alerts.toTell(list, AlertSettings(), setOf("u:URGENT")).map { it.id })
        // More serious than when it was told: told again.
        val worse = watch.copy(level = AlertLevel.DOCTOR)
        assertEquals(listOf("w"), Alerts.toTell(listOf(worse), AlertSettings(), setOf("w:WATCH")).map { it.id })
        assertTrue(Alerts.toTell(list, AlertSettings(notify = false), emptySet()).isEmpty())
        assertTrue(Alerts.toTell(list, AlertSettings(enabled = false), emptySet()).isEmpty())
        assertEquals("u:URGENT", Alerts.noticeKey(urgent))
    }

    @Test
    fun `the told keys keep the newest, once each`() {
        assertEquals(listOf("a:WATCH", "b:WATCH", "c:URGENT"), Alerts.rememberTold(listOf("a:WATCH", "b:WATCH"), listOf("c:URGENT")))
        assertEquals(listOf("b:WATCH", "a:WATCH"), Alerts.rememberTold(listOf("a:WATCH", "b:WATCH"), listOf("a:WATCH")))
        val many = (1..250).map { "x$it:WATCH" }
        val kept = Alerts.rememberTold(many, listOf("new:URGENT"))
        assertEquals(Alerts.TOLD_KEPT, kept.size)
        assertEquals("new:URGENT", kept.last())
        assertEquals("x52:WATCH", kept.first())
    }

    @Test
    fun `a notification's words`() {
        assertEquals("It was 128–151 bpm for 14 min.", Alerts.firstSentence("It was 128–151 bpm for 14 min. Worth a look."))
        assertEquals("Temperature was up 0.4 °C.", Alerts.firstSentence("Temperature was up 0.4 °C. Rest."))
        assertEquals("No full stop here", Alerts.firstSentence(" No full stop here "))
        assertEquals("Is it racing?", Alerts.firstSentence("Is it racing? Sit down."))
        assertEquals("An urgent health alert", Alerts.privateTitle(AlertLevel.URGENT))
        assertEquals("A health alert", Alerts.privateTitle(AlertLevel.DOCTOR))
        assertEquals("A health alert", Alerts.privateTitle(AlertLevel.WATCH))
        assertEquals("1 new health alert", Alerts.countText(1))
        assertEquals("3 new health alerts", Alerts.countText(3))
    }

    @Test
    fun `the bell's count follows what was fetched, and keeps older unseen ones a short fetch didn't cover`() {
        val since = at(today.minusDays(2), 8)
        val known = mapOf("old" to at(today.minusDays(5), 8), "recent" to at(today, 1))
        val fetched = listOf(alert("new", createdAt = at(today, 9)), alert("seen", seenAt = 1))
        // "recent" was in the two days fetched and isn't in the answer any more (seen elsewhere): gone.
        assertEquals(setOf("old", "new"), Alerts.mergeUnseen(known, fetched, since).keys)
        assertTrue(Alerts.mergeUnseen(known, fetched, since, enabled = false).isEmpty())
        // A whole page's worth replaces it all.
        assertEquals(setOf("new"), Alerts.mergeUnseen(known, fetched, Long.MIN_VALUE).keys)
    }

    @Test
    fun `the page's arrangement is saved under stable ids`() {
        assertEquals("android.alerts", FeatureScreens.ALERTS)
        assertTrue(FeatureScreens.ALERTS in FeatureScreens.ALL)
        assertEquals(listOf("alerts", "watching", "settings"), Alerts.SECTIONS.map { it.id })
    }
}
