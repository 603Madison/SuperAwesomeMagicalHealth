package com.health.app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.health.api.AlertLevel
import com.health.api.HealthAlert
import com.health.api.HealthAlerts
import com.health.app.MainActivity
import com.health.app.R
import com.health.app.ui.alerts.Alerts

/**
 * Health alerts as the phone's own notifications, with no push service: after each sync the app
 * asks the server for the last two days' alerts, and tells the person of each one it hasn't told
 * of yet, at its level (one that gets more serious is told again). Which ones it has told of is
 * kept here. One that could need care now is loud and stays until it's swiped away; the rest are
 * ordinary. A locked screen shows only "A health alert".
 */
class AlertNotices(private val context: Context) {
    private val prefs = context.getSharedPreferences("health-alerts", Context.MODE_PRIVATE)

    /** The alerts told of, oldest first ([Alerts.noticeKey]). */
    fun told(): List<String> = prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    /** Tells the person of each of [result]'s alerts not told of yet ([canNotify]: the app may show notifications). Returns how many. */
    fun tell(result: HealthAlerts, canNotify: Boolean): Int {
        if (!canNotify) return 0
        val due = Alerts.toTell(result.alerts, result.settings, told().toSet())
        if (due.isEmpty()) return 0
        channels()
        // Oldest first, so the newest is on top.
        due.reversed().forEach(::show)
        prefs.edit { putString(KEY, Alerts.rememberTold(told(), due.map(Alerts::noticeKey)).joinToString("\n")) }
        return due.size
    }

    /** Takes [alert]'s notification away (it was put away, or seen). */
    fun cancel(alert: HealthAlert) = NotificationManagerCompat.from(context).cancel(id(alert))

    /** Takes away the notifications of [alerts] that aren't urgent (they've been seen; an urgent one stays until swiped). */
    fun seen(alerts: List<HealthAlert>) = alerts.filter { it.level != AlertLevel.URGENT }.forEach(::cancel)

    /** Forgets what was told (signing out). */
    fun clear() = prefs.edit { remove(KEY) }

    private fun show(alert: HealthAlert) {
        val urgent = alert.level == AlertLevel.URGENT
        val channel = if (urgent) CHANNEL_URGENT else CHANNEL
        val open = PendingIntent.getActivity(
            context, id(alert),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, "alerts"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = Alerts.firstSentence(alert.body)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setWhen(alert.createdAt)
            .setShowWhen(true)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // Opening the app doesn't take an urgent one away: it stays until it's swiped away.
            .setAutoCancel(!urgent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(Alerts.privateTitle(alert.level)).setContentText("Open the app to see it").build(),
            )
            .setContentIntent(open)
            .build()
        @Suppress("MissingPermission") // the caller checked (canNotify)
        NotificationManagerCompat.from(context).notify(id(alert), notification)
    }

    private fun channels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Health alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When your readings look out of the ordinary for you: worth keeping an eye on, or a doctor's look."
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_URGENT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_URGENT, "Urgent health alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When a reading could need care now."
                },
            )
        }
    }

    companion object {
        private const val KEY = "told"
        const val CHANNEL = "health-alerts"
        const val CHANNEL_URGENT = "health-alerts-urgent"

        /** One notification an alert, replaced when it gets more serious. */
        fun id(alert: HealthAlert): Int = ("health-alert:" + alert.id).hashCode()
    }
}
