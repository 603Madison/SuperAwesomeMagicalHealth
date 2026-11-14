package com.health.server.platform

import com.health.api.User
import com.health.server.Services
import com.health.server.util.Days

/** What the AI connector can say about notifications and webhooks, for troubleshooting. */
object PlatformMcp {
    suspend fun deliveries(services: Services, user: User, limit: Int): String {
        val platform = services.platform
        val hooks = platform.webhooks.list(user.id)
        if (hooks.isEmpty()) return "No webhooks yet. They're added in Settings › Integrations."
        val zone = Days.zoneOf(services.profiles.get(user.id).timezone)
        val names = hooks.associate { it.id to it.name }
        return buildString {
            appendLine("Webhooks:")
            hooks.forEach { h ->
                appendLine("- ${h.name} → ${h.url} (${if (h.enabled) "on" else "off"}; ${if (h.includeDetails) "with names" else "times and counts only"}): ${h.events.joinToString(", ") { it.key }}")
            }
            val calls = platform.webhooks.deliveries(user.id, limit)
            appendLine()
            if (calls.isEmpty()) appendLine("No calls yet.") else {
                appendLine("Latest calls (newest first):")
                calls.forEach { d ->
                    appendLine("- ${Say.dateTime(d.at, zone)} ${names[d.webhookId] ?: "?"} ${d.event.key}: ${if (d.ok) "OK" else "failed"}${d.status?.let { " (HTTP $it)" } ?: ""}${d.error?.let { " $it" } ?: ""}")
                }
            }
        }.trimEnd()
    }

    suspend fun status(services: Services, user: User): String {
        val platform = services.platform
        val profile = services.profiles.get(user.id)
        val zone = Days.zoneOf(profile.timezone)
        val browsers = platform.push.list(user.id)
        val failures = platform.push.failures(user.id)
        val hooks = platform.webhooks.list(user.id)
        val now = services.clock()
        val upcoming = platform.scheduler.collect(user.id, profile, now, now + 24 * 3_600_000L).distinctBy { it.key }.sortedBy { it.at }
        val digest = platform.digests.latest(user.id)
        val prefs = profile.reminders
        return buildString {
            appendLine("Reminder round: " + (platform.scheduler.lastTick?.let { "last ran ${Say.dateTime(it, zone)}, sent ${platform.scheduler.lastSent}" } ?: "hasn't run since the server started"))
            appendLine("Browsers with notifications: ${browsers.size}")
            browsers.forEach { b ->
                appendLine("- ${b.name}: since ${Say.dateTime(b.createdAt, zone)}, last delivered ${b.lastOkAt?.let { Say.dateTime(it, zone) } ?: "never"}, failures in a row ${failures[b.id] ?: 0}")
            }
            appendLine("Webhooks: ${hooks.size} (${hooks.count { it.enabled }} on)" + (hooks.mapNotNull { it.lastDelivery }.maxByOrNull { it.at }?.let { "; last call ${Say.dateTime(it.at, zone)} ${if (it.ok) "OK" else "failed: ${it.error ?: it.status}"}" } ?: ""))
            appendLine(
                "Settings: remind again after ${prefs.remindAgainMinutes} min (${prefs.remindAgainTimes}×), missed after ${prefs.missedAfterMinutes} min, " +
                    "refills at ${prefs.refillDays} days left, quiet ${prefs.quietFrom ?: "–"}–${prefs.quietTo ?: "–"}, names in reminders ${if (prefs.showNames) "shown" else "hidden"}",
            )
            appendLine("Next 24 hours: ${upcoming.size} reminders" + upcoming.take(10).joinToString("") { "\n- ${Say.dateTime(it.at, zone)} ${it.event.key}: ${it.privateTitle}" })
            appendLine("Email: " + if (services.settings.get().smtp.ready) "set up" else "not set up on this server")
            appendLine("Weekly digest: " + (if (profile.digest.enabled) "on" else "off") + (digest?.let { ", latest for ${it.weekStart} to ${it.weekEnd}: ${it.headline}" } ?: ", none yet"))
        }.trimEnd()
    }
}
