package com.health.server.platform

import com.health.api.Profile
import com.health.api.PushMessage
import com.health.api.ReminderPrefs
import com.health.api.User
import com.health.api.Webhook
import com.health.api.WebhookEvent
import com.health.server.Services
import com.health.server.util.Days
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.LocalTime

/**
 * Each minute: everything due for each person (doses, refills, mornings, devices, the digest,
 * and whatever the other features add), sent once each by browser push and webhook.
 */
class Scheduler(private val services: Services) {
    private val log = LoggerFactory.getLogger("samh.scheduler")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var lastTick: Long? = null
        private set

    @Volatile
    var lastSent: Int = 0
        private set

    /** Webhook calls go out in the background (they retry); tests wait for them instead. */
    var webhooksInBackground = true

    fun start(): Job = scope.launch {
        log.info("Reminders and webhooks are on")
        while (isActive) {
            try {
                tick(services.clock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("The reminder round failed: {}", e.message, e)
            }
            val now = services.clock()
            delay((60_000 - now % 60_000).coerceIn(1_000, 60_000))
        }
    }

    fun stop() = scope.cancel()

    suspend fun wasSent(userId: String, key: String): Boolean =
        services.db.tx { one("SELECT 1 FROM reminders_sent WHERE user_id = ? AND key = ?", userId, key) { true } } != null

    private suspend fun markSent(userId: String, key: String, now: Long): Boolean =
        services.db.tx { update("INSERT OR IGNORE INTO reminders_sent (user_id, key, at) VALUES (?, ?, ?)", userId, key, now) } == 1

    /** One round, as of [now]: returns how many reminders went out. */
    suspend fun tick(now: Long): Int {
        var sent = 0
        for (user in services.users.list()) {
            if (user.disabled) continue
            try {
                sent += tickUser(user, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Reminders for {} failed: {}", user.username, e.message, e)
            }
        }
        services.db.tx { update("DELETE FROM reminders_sent WHERE at < ?", now - 14 * 86_400_000L) }
        lastTick = now
        lastSent = sent
        return sent
    }

    private suspend fun tickUser(user: User, now: Long): Int {
        val platform = services.platform
        val profile = services.profiles.get(user.id)
        // The digest is worked out at its time whether or not anything is listening (the apps show it).
        val digest = platform.digests.dueReminder(user, profile, now)
        val browsers = platform.push.count(user.id)
        val hooks = platform.webhooks.enabled(user.id)
        if (browsers == 0 && hooks.isEmpty()) return 0
        val due = (collect(user.id, profile, now - WINDOW_BEFORE, now + WINDOW_AFTER) + listOfNotNull(digest))
            .filter { it.at <= now }.distinctBy { it.key }.sortedBy { it.at }
        var sent = 0
        for (reminder in due) {
            if (!markSent(user.id, reminder.key, now)) continue
            deliver(user, profile, reminder, browsers > 0, hooks, now)
            sent++
        }
        return sent
    }

    /** Everything the person's features have due in [from, to). */
    suspend fun collect(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> =
        services.hooks.filter { it.module == null || it.module in profile.modules }.flatMap { hook ->
            try {
                hook.reminders(userId, profile, from, to)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("A feature's reminders failed: {}", e.message, e)
                emptyList()
            }
        }.filter { r -> r.event.module == null || r.event.module in profile.modules }

    private suspend fun deliver(user: User, profile: Profile, reminder: Reminder, push: Boolean, hooks: List<Webhook>, now: Long) {
        val prefs = profile.reminders
        val zone = Days(Days.zoneOf(profile.timezone))
        if (push && reminder.push && (reminder.event in ALWAYS || !quiet(prefs, zone.minuteOfDay(now)))) {
            runCatching {
                services.platform.push.send(user.id, message(reminder, prefs), urgent = reminder.event == WebhookEvent.DOSE_DUE, ttlSeconds = if (reminder.event == WebhookEvent.DOSE_DUE) 2 * 3600 else 12 * 3600)
            }.onFailure { log.warn("Sending a notification failed: {}", it.message) }
        }
        val targets = hooks.filter { reminder.event in it.events }
        if (targets.isEmpty()) return
        val send: suspend () -> Unit = {
            targets.forEach { hook ->
                val detailed = hook.includeDetails
                val payload = WebhookService.payload(
                    reminder.event, reminder.at, user.username,
                    if (detailed) reminder.title else reminder.privateTitle,
                    if (detailed) reminder.detailedData else reminder.data,
                )
                services.platform.webhooks.deliver(user.id, hook, reminder.event, payload)
            }
        }
        if (webhooksInBackground) scope.launch { send() } else send()
    }

    companion object {
        /** Reminders up to this late still go out (after a restart, say); each goes once. */
        const val WINDOW_BEFORE = 15 * 60_000L
        const val WINDOW_AFTER = 60_000L

        /** Doses are worth a sound even in quiet hours; nothing else is. */
        private val ALWAYS = setOf(WebhookEvent.DOSE_DUE)

        fun quiet(prefs: ReminderPrefs, minuteOfDay: Int): Boolean {
            val from = prefs.quietFrom?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return false
            val to = prefs.quietTo?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return false
            val a = from.hour * 60 + from.minute
            val b = to.hour * 60 + to.minute
            return if (a <= b) minuteOfDay in a until b else minuteOfDay >= a || minuteOfDay < b
        }

        /** What a browser shows: names only if the person said so (a locked screen can be seen). */
        fun message(r: Reminder, prefs: ReminderPrefs): PushMessage = PushMessage(
            title = if (prefs.showNames) r.title else r.privateTitle,
            body = if (prefs.showNames) r.body else r.privateBody,
            tag = r.key.substringBefore(":again"),
            url = r.url,
            actions = r.actions,
            medicationId = r.medicationId,
            scheduledFor = r.scheduledFor,
            at = r.at,
        )
    }
}
