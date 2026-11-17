package com.health.app.ui.alerts

import com.health.api.AlertLevel
import com.health.api.AlertSettings
import com.health.api.AlertWatch
import com.health.api.HealthAlert
import com.health.app.ui.Format
import com.health.app.ui.components.ArrangeItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Health alerts (GET /api/alerts), the parts that don't need a screen: each level's words, the
 * order they're listed in, when one happened, what putting one away says, what it's watching, the
 * settings kept within the server's limits, and which ones the phone still has to tell the person
 * about. As the web's lib/alerts.ts, so both apps say the same.
 */
object Alerts {
    /** How far back the page looks. */
    const val DAYS = 30

    /** How far back the phone looks after each sync, for alerts to tell the person about. */
    const val RECENT_DAYS = 2

    /** The page's sections in the default order (android.alerts). Their ids are saved: never rename one. */
    val SECTIONS = listOf(
        ArrangeItem("alerts", "Alerts"),
        ArrangeItem("watching", "What it's watching"),
        ArrangeItem("settings", "Settings"),
    )

    /** How soon to act, in the words the badge uses. */
    fun levelText(level: AlertLevel): String = when (level) {
        AlertLevel.URGENT -> "Could need care now"
        AlertLevel.DOCTOR -> "Worth a doctor's look"
        AlertLevel.WATCH -> "Keep an eye on"
    }

    /**
     * Newest first, as the server sends them, except that one that could need care now (and isn't
     * put away) comes before the rest: a newer, milder one never pushes it down.
     */
    fun newestFirst(alerts: List<HealthAlert>): List<HealthAlert> =
        alerts.sortedWith(compareBy<HealthAlert> { if (it.level == AlertLevel.URGENT && it.dismissedAt == null) 0 else 1 }.thenByDescending { it.createdAt })

    /** Put away by the person: it stays in the list, quieter. */
    fun isAway(a: HealthAlert): Boolean = a.dismissedAt != null

    /** Not seen yet, and not put away: "New" on this visit, and what Today's bell counts. */
    fun isUnseen(a: HealthAlert): Boolean = a.seenAt == null && a.dismissedAt == null

    fun unseenIds(alerts: List<HealthAlert>): Set<String> = alerts.filter(::isUnseen).map { it.id }.toSet()

    /** The list once [changed] (put away, say) is in it in place of its old self. */
    fun replace(alerts: List<HealthAlert>, changed: HealthAlert): List<HealthAlert> = alerts.map { if (it.id == changed.id) changed else it }

    /** What a put-away alert says about it: "Put away: I was active", or just "Put away". */
    fun awayText(a: HealthAlert): String = a.feedback?.let { "Put away: ${it.label}" } ?: "Put away"

    /** "From Polar H10 and Colmi R02"; null without devices. */
    fun devicesText(devices: List<String>): String? {
        val list = devices.filter { it.isNotBlank() }
        if (list.isEmpty()) return null
        val named = if (list.size == 1) list[0] else "${list.dropLast(1).joinToString(", ")} and ${list.last()}"
        return "From $named"
    }

    private const val LONG_MS = 20 * 3_600_000L

    /**
     * When it happened: "Today, 2:10 AM" (a moment), "Today, 2:10 AM – 2:48 AM" (a spell), across
     * midnight "Yesterday 11:40 PM – Today 12:25 AM", and over days "Sat, 14 Nov – Today".
     */
    fun whenText(a: HealthAlert, zone: ZoneId, today: LocalDate): String {
        fun day(ms: Long) = Format.day(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate(), today)
        fun time(ms: Long) = Format.timeOfDay(ms, zone)
        val startDay = day(a.start)
        val end = a.end
        if (end == null || end <= a.start) return "$startDay, ${time(a.start)}"
        val endDay = day(end)
        if (end - a.start >= LONG_MS) return if (startDay == endDay) startDay else "$startDay – $endDay"
        if (endDay == startDay) return "$startDay, ${time(a.start)} – ${time(end)}"
        return "$startDay ${time(a.start)} – $endDay ${time(end)}"
    }

    /** Where one thing it can watch stands: on, off (the person turned it off), or not yet (it needs a device, or is learning their usual). */
    enum class WatchState(val text: String) { ON("Watching"), OFF("Off"), NOT_YET("Not yet") }

    fun watchState(w: AlertWatch): WatchState = when {
        w.watching -> WatchState.ON
        w.how.startsWith("turned off", ignoreCase = true) -> WatchState.OFF
        else -> WatchState.NOT_YET
    }

    /** The empty list's words: alerts off, nothing to watch yet, or a quiet spell (then what it's watching follows). */
    fun emptyText(settings: AlertSettings, watching: List<AlertWatch>, days: Int = DAYS): String = when {
        !settings.enabled -> "Health alerts are off, so nothing is being watched. Turn them on below to get a heads-up."
        watching.none { it.watching } -> "There's nothing to watch yet: a device that measures heart rate or blood oxygen brings it in."
        else -> "No alerts in the last $days days. Here's what it's watching:"
    }

    // ---- Settings ----

    /** A number the person can set: within what the server keeps (AlertSettings.clamped), a step at a time. */
    data class Range(val min: Int, val max: Int, val step: Int, val unit: String) {
        fun clamp(value: Int): Int = value.coerceIn(min, max)

        /** One step up (1) or down (−1), landing on the step's own numbers (115, 120…). */
        fun step(value: Int, direction: Int): Int {
            val on = if (direction > 0) Math.floorDiv(value, step) * step + step else -Math.floorDiv(-value, step) * step - step
            return clamp(on)
        }

        /** "100–180 bpm", "85–94%". */
        val text: String get() = if (unit == "%") "$min–$max%" else "$min–$max $unit"
    }

    val HIGH_HEART_RATE = Range(100, 180, 5, "bpm")
    val LOW_HEART_RATE = Range(30, 60, 1, "bpm")
    val LOW_OXYGEN = Range(85, 94, 1, "%")

    // ---- Telling the person (notifications) and Today's bell ----

    /** What the phone remembers having told: each alert at each level once ("id:URGENT"), so one that gets more serious is told again. */
    fun noticeKey(a: HealthAlert): String = "${a.id}:${a.level.name}"

    /**
     * The alerts to tell the person about now: with alerts and notifications on, those not put
     * away or seen yet, at a level this phone hasn't told of before ([told]).
     */
    fun toTell(alerts: List<HealthAlert>, settings: AlertSettings, told: Set<String>): List<HealthAlert> {
        if (!settings.enabled || !settings.notify) return emptyList()
        return newestFirst(alerts).filter { isUnseen(it) && noticeKey(it) !in told }
    }

    /** At most this many told keys are kept: plenty for 30 days of alerts. */
    const val TOLD_KEPT = 200

    /** [told] (oldest first) with [added] at the end, the oldest let go beyond [max]. */
    fun rememberTold(told: List<String>, added: List<String>, max: Int = TOLD_KEPT): List<String> =
        (told.filterNot { it in added } + added.distinct()).takeLast(max)

    /** A notification's line: the body's first sentence. */
    fun firstSentence(body: String): String {
        val text = body.trim()
        val end = Regex("""[.!?](\s|$)""").find(text)?.range?.first ?: return text
        return text.substring(0, end + 1)
    }

    /** What a locked screen shows instead of the alert. */
    fun privateTitle(level: AlertLevel): String = if (level == AlertLevel.URGENT) "An urgent health alert" else "A health alert"

    /** "1 new health alert", "3 new health alerts": the bell's words. */
    fun countText(count: Int): String = if (count == 1) "1 new health alert" else "$count new health alerts"

    /**
     * The unseen alerts the phone knows of (id to when made), once [fetched] (the alerts made since
     * [since]) is in: what it knew from before [since] stays, the rest is what was fetched. With
     * alerts off, none.
     */
    fun mergeUnseen(known: Map<String, Long>, fetched: List<HealthAlert>, since: Long, enabled: Boolean = true): Map<String, Long> {
        if (!enabled) return emptyMap()
        return known.filterValues { it < since } + fetched.filter(::isUnseen).associate { it.id to it.createdAt }
    }
}
