package com.health.server.insights

import com.health.api.AlertFeedback
import com.health.api.AlertLevel
import com.health.api.AlertSettings
import com.health.api.AlertWatch
import com.health.api.ApiJson
import com.health.api.AppPage
import com.health.api.CyclePhase
import com.health.api.HealthAlert
import com.health.api.HealthAlerts
import com.health.api.Insight
import com.health.api.Metric
import com.health.api.Module
import com.health.api.Profile
import com.health.api.SuggestedQuestion
import com.health.api.WebhookEvent
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.platform.FeatureHooks
import com.health.server.platform.Reminder
import com.health.server.platform.json
import com.health.server.util.Random
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Health alerts: the person's latest readings checked against [AlertRules] (at most once a minute,
 * whenever the apps ask or the scheduler goes round), each spell or night kept once (by its key)
 * and brought up to date as it goes on. What they say is sealed. A new alert, or one that becomes
 * more serious, is sent once by notification and webhook (see [reminders]); the phone also shows
 * those it hasn't yet. Never shared, never in the digest.
 */
class AlertService(private val services: Services) : FeatureHooks {
    override val module: Module? = null
    private val log = LoggerFactory.getLogger("samh.alerts")
    private val checked = ConcurrentHashMap<String, Long>()
    private val db get() = services.db
    private val clock get() = services.clock

    suspend fun settings(userId: String): AlertSettings = services.userSettings.get(userId, KEY, AlertSettings.serializer()) ?: AlertSettings()

    suspend fun putSettings(userId: String, input: AlertSettings): AlertSettings {
        val saved = services.userSettings.put(userId, KEY, input.clamped(), AlertSettings.serializer())
        checked.remove(userId)
        return saved
    }

    /** Looks at the latest readings, keeps what's worth a heads-up, and returns the alerts that are new or more serious. */
    suspend fun check(userId: String, force: Boolean = false): List<HealthAlert> {
        val now = clock()
        if (!force && (checked[userId] ?: Long.MIN_VALUE) > now - CHECK_EVERY_MS) return emptyList()
        checked[userId] = now
        val settings = settings(userId)
        if (!settings.enabled) return emptyList()
        val zone = services.profiles.days(userId)
        val profile = services.profiles.get(userId)
        val records = services.records
        val samples = records.samples(userId, now - LOOKBACK_MS - HOUR, now + MINUTE, listOf(Metric.HEART_RATE, Metric.STEPS, Metric.IRREGULAR_RHYTHM)) +
            records.samples(userId, now - 8 * DAY, now + MINUTE, listOf(Metric.SPO2))
        val today = zone.today(now)
        val luteal = Module.CYCLE in profile.modules && try {
            services.modules.cycle.today(userId, today).phase == CyclePhase.LUTEAL
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        val input = AlertRules.Input(
            now = now,
            samples = samples,
            sleep = records.sleep(userId, now - 8 * DAY, now + MINUTE),
            workouts = records.workouts(userId, now - LOOKBACK_MS - HOUR, now + MINUTE).map { it.start..it.end },
            devices = records.deviceRefs(userId),
            days = services.days.stats(userId, today.minusDays(35), today, zone),
            luteal = luteal,
            settings = settings,
            zone = zone,
            lookbackMs = LOOKBACK_MS,
        )
        return AlertRules.detect(input).mapNotNull { keep(userId, it, now) }
    }

    private class Stored(val id: String, val level: AlertLevel, val createdAt: Long, val seenAt: Long?, val dismissedAt: Long?, val data: String)

    private fun context(userId: String, id: String) = "alert:$userId:$id"

    private fun open(userId: String, s: Stored): HealthAlert? = try {
        ApiJson.decodeFromString(HealthAlert.serializer(), services.box.open(s.data, context(userId, s.id)))
            .copy(id = s.id, level = s.level, createdAt = s.createdAt, seenAt = s.seenAt, dismissedAt = s.dismissedAt)
    } catch (e: Exception) {
        log.warn("An alert couldn't be opened: {}", e.javaClass.simpleName)
        null
    }

    private fun seal(userId: String, alert: HealthAlert) = services.box.seal(ApiJson.encodeToString(HealthAlert.serializer(), alert), context(userId, alert.id))

    private suspend fun stored(userId: String, where: String, vararg args: Any?): List<Stored> = db.tx {
        query("SELECT id, level, created_at, seen_at, dismissed_at, data FROM health_alerts WHERE user_id = ? AND $where ORDER BY created_at DESC", userId, *args) {
            Stored(it.string("id"), AlertLevel.valueOf(it.string("level")), it.long("created_at"), it.longOrNull("seen_at"), it.longOrNull("dismissed_at"), it.string("data"))
        }
    }

    /** Keeps [c]: a new alert, or the one with its key brought up to date. Returns it when it's new or more serious than before. */
    private suspend fun keep(userId: String, c: AlertRules.Candidate, now: Long): HealthAlert? {
        val old = stored(userId, "key = ?", c.key).firstOrNull()?.let { s -> open(userId, s)?.let { s to it } }
        if (old == null) {
            val alert = HealthAlert(Random.id(), c.kind, c.level, c.title, c.body, c.advice, c.evidence, c.start, c.end, c.devices, now)
            val inserted = db.tx {
                update(
                    "INSERT OR IGNORE INTO health_alerts (id, user_id, key, kind, level, start_at, created_at, updated_at, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    alert.id, userId, c.key, c.kind.name, c.level.name, c.start, now, now, seal(userId, alert),
                )
            }
            return alert.takeIf { inserted == 1 }
        }
        val (row, was) = old
        // Never less serious than it was: a spell that eases still happened.
        val level = if (severity(c.level) > severity(was.level)) c.level else was.level
        val worse = level != was.level
        val now2 = was.copy(
            level = level, title = c.title, body = c.body, advice = c.advice, evidence = c.evidence, start = c.start, end = c.end, devices = c.devices,
            seenAt = if (worse) null else was.seenAt, dismissedAt = if (worse) null else was.dismissedAt, feedback = if (worse) null else was.feedback,
        )
        if (now2 == was) return null
        db.tx {
            update(
                "UPDATE health_alerts SET level = ?, start_at = ?, seen_at = ?, dismissed_at = ?, data = ? WHERE id = ? AND user_id = ?",
                level.name, c.start, now2.seenAt, now2.dismissedAt, seal(userId, now2), row.id, userId,
            )
            // More serious: it's told again (a notification goes out for each level once).
            if (worse) update("UPDATE health_alerts SET updated_at = ? WHERE id = ?", now, row.id)
        }
        return now2.takeIf { worse }
    }

    /** The last [days] of alerts, newest first, after a fresh look. */
    suspend fun list(userId: String, days: Int = 30): HealthAlerts {
        check(userId)
        val settings = settings(userId)
        val since = clock() - days.coerceIn(1, 400) * DAY
        val alerts = stored(userId, "created_at >= ?", since).mapNotNull { open(userId, it) }
        return HealthAlerts(alerts, settings, watching(userId, settings), LIMITS, checked[userId])
    }

    /** Not put away, from the last day and a half: for Today. */
    suspend fun active(userId: String): List<HealthAlert> {
        check(userId)
        if (!settings(userId).enabled) return emptyList()
        return stored(userId, "dismissed_at IS NULL AND updated_at >= ?", clock() - 36 * HOUR).mapNotNull { open(userId, it) }
    }

    /** Today's insights for [active] alerts, most serious first. */
    suspend fun insights(userId: String): List<Insight> = active(userId).sortedBy { it.level.ordinal }.map { a ->
        Insight(
            tone = if (a.level == AlertLevel.URGENT) "alert" else "warn",
            title = a.title,
            body = a.body.substringBefore(". ").let { if (it.endsWith(".")) it else "$it." },
            icon = when (a.kind) {
                com.health.api.AlertKind.LOW_OXYGEN, com.health.api.AlertKind.OXYGEN_DIPS -> "wind"
                com.health.api.AlertKind.GETTING_SICK -> "thermometer"
                else -> "heart-pulse"
            },
            link = AppPage.ALERTS,
        )
    }

    suspend fun seen(userId: String, id: String?) {
        val now = clock()
        val changed = db.tx {
            if (id == null) update("UPDATE health_alerts SET seen_at = ? WHERE user_id = ? AND seen_at IS NULL", now, userId)
            else update("UPDATE health_alerts SET seen_at = COALESCE(seen_at, ?) WHERE user_id = ? AND id = ?", now, userId, id)
        }
        if (id != null && changed == 0) throw ApiError.notFound("No such alert")
    }

    suspend fun dismiss(userId: String, id: String, feedback: AlertFeedback?): HealthAlert {
        val row = stored(userId, "id = ?", id).firstOrNull() ?: throw ApiError.notFound("No such alert")
        val now = clock()
        val alert = (open(userId, row) ?: throw ApiError.notFound("No such alert")).copy(feedback = feedback, dismissedAt = now, seenAt = row.seenAt ?: now)
        db.tx { update("UPDATE health_alerts SET dismissed_at = ?, seen_at = ?, data = ? WHERE id = ? AND user_id = ?", now, alert.seenAt, seal(userId, alert), id, userId) }
        return alert
    }

    /** What the alerts can watch with the person's devices, said plainly. */
    private suspend fun watching(userId: String, settings: AlertSettings): List<AlertWatch> {
        val now = clock()
        val refs = services.records.deviceRefs(userId)
        val recent = services.records.samples(userId, now - 7 * DAY, now + MINUTE, listOf(Metric.HEART_RATE, Metric.SPO2, Metric.IRREGULAR_RHYTHM, Metric.RESTING_HEART_RATE, Metric.HRV))
        fun from(metric: Metric) = recent.filter { it.metric == metric }.map { refs[it.deviceId]?.name ?: it.deviceName }.distinct()
        val heart = from(Metric.HEART_RATE)
        val oxygen = from(Metric.SPO2)
        val rhythm = from(Metric.IRREGULAR_RHYTHM)
        val straps = Combine.straps(refs).mapNotNull { refs[it]?.name }
        val zone = services.profiles.days(userId)
        val today = zone.today(now)
        val nights = services.days.stats(userId, today.minusDays(28), today, zone).count { it.restingHr != null || it.hrv != null }
        fun list(names: List<String>) = names.joinToString(", ")
        return listOf(
            AlertWatch("Heart rate at rest, high and low", if (heart.isEmpty()) "Needs a device that measures heart rate" else "From ${list(heart)}", heart.isNotEmpty()),
            AlertWatch(
                "Heart rhythm",
                when {
                    !settings.rhythm -> "Turned off"
                    rhythm.isNotEmpty() -> "From ${list(rhythm)}, while it records"
                    straps.isNotEmpty() -> "From ${list(straps)}, while it records"
                    else -> "Needs a chest strap: a ring or watch can't tell"
                },
                settings.rhythm && (rhythm.isNotEmpty() || straps.isNotEmpty()),
            ),
            AlertWatch("Blood oxygen", if (oxygen.isEmpty()) "Needs a device that measures it" else "From ${list(oxygen)}", oxygen.isNotEmpty()),
            AlertWatch(
                "Signs of getting ill",
                when {
                    !settings.illness -> "Turned off"
                    nights < AlertRules.BASELINE_MIN -> "Learning your usual: ${AlertRules.BASELINE_MIN - nights} more nights"
                    else -> "Resting heart rate, HRV, temperature and breathing against your usual"
                },
                settings.illness && nights >= AlertRules.BASELINE_MIN,
            ),
        )
    }

    // ---- Notifications and appointments ----

    override suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> {
        check(userId)
        val settings = settings(userId)
        if (!settings.enabled) return emptyList()
        val due = db.tx {
            query("SELECT id, level, created_at, seen_at, dismissed_at, data, updated_at FROM health_alerts WHERE user_id = ? AND dismissed_at IS NULL AND updated_at >= ? AND updated_at < ?", userId, from, to) {
                Stored(it.string("id"), AlertLevel.valueOf(it.string("level")), it.long("updated_at"), it.longOrNull("seen_at"), it.longOrNull("dismissed_at"), it.string("data"))
            }
        }
        return due.mapNotNull { s ->
            val a = open(userId, s) ?: return@mapNotNull null
            Reminder(
                key = "alert:${a.id}:${a.level.name}",
                at = s.createdAt,
                event = WebhookEvent.HEALTH_ALERT,
                title = a.title,
                body = a.body.substringBefore(". ").let { if (it.endsWith(".")) it else "$it." },
                privateTitle = if (a.level == AlertLevel.URGENT) "An urgent health alert" else "A health alert",
                privateBody = "Open the app to see it",
                url = "/alerts",
                data = json("level" to a.level.name.lowercase()),
                detailedData = json("level" to a.level.name.lowercase(), "kind" to a.kind.name.lowercase(), "title" to a.title),
                push = settings.notify,
            )
        }
    }

    override suspend fun questions(userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
        val zone = services.profiles.days(userId)
        val worth = setOf(AlertLevel.URGENT, AlertLevel.DOCTOR)
        val explained = setOf(AlertFeedback.ACTIVE, AlertFeedback.EXPECTED, AlertFeedback.DEVICE)
        return stored(userId, "created_at >= ? AND created_at < ?", zone.start(since), zone.end(to)).mapNotNull { open(userId, it) }
            .filter { it.level in worth && it.feedback !in explained }
            .distinctBy { it.kind }
            .map { a -> SuggestedQuestion("My ${a.kind.label.lowercase()} on ${zone.dateOf(a.start)}: what could cause it, and should it be checked?", a.body) }
    }

    companion object {
        const val KEY = "alerts.settings"
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR
        const val CHECK_EVERY_MS = MINUTE
        const val LOOKBACK_MS = 36 * HOUR

        /** Most serious highest. */
        fun severity(level: AlertLevel) = AlertLevel.entries.size - level.ordinal

        const val LIMITS = "These are a heads-up from consumer devices, not a diagnosis, and they can't catch everything: a ring or watch can't tell your heart's rhythm, " +
            "and a quiet day here doesn't mean all is well. If you feel unwell, trust that over any reading, and if it could be an emergency, call your local emergency number."
    }
}
