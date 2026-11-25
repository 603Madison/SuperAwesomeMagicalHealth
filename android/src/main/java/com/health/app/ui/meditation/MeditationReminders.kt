package com.health.app.ui.meditation

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.health.api.MeditationSettings
import com.health.app.MainActivity
import com.health.app.R
import com.health.app.attempt
import com.health.app.container
import com.health.app.net.ServerApi
import com.health.app.net.meditationSettings
import com.health.app.reminders.CarePlanner
import com.health.app.reminders.EXACT_ALARMS_CHANGED
import com.health.app.reminders.setReminder
import java.time.ZoneId

/*
 * The nightly "Time to wind down", planned as the other reminders are (reminders/Reminders.kt,
 * RoutineReminders.kt): the settings are kept on the phone, one alarm is set for the next one in
 * the person's time zone, and when it goes off a gentle notification shows (unless a meditation
 * began in the half hour before; silently in the person's quiet hours) and the next is set. It
 * opens the Meditation page.
 *
 * The server sends this reminder too, but only by browser push and webhooks: the phone plans its
 * own alarm and doesn't read the server's reminders, so it isn't a duplicate here.
 */
class MeditationReminders(private val context: Context) {
    private val store = MeditationStore(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Keeps [settings] and sets the next alarm (or none). */
    fun update(settings: MeditationSettings, zone: ZoneId, now: Long = System.currentTimeMillis()) {
        store.saveSettings(settings)
        reschedule(zone, now)
    }

    fun reschedule(zone: ZoneId, now: Long = System.currentTimeMillis()) {
        alarms.cancel(fireIntent(0))
        val settings = store.settings() ?: return
        val at = MeditationReminderPlan.next(settings.reminder, settings.time, now, zone) ?: return
        alarms.setReminder(at, fireIntent(at))
    }

    /** Forgets the reminder (signing out). */
    fun clear() {
        alarms.cancel(fireIntent(0))
    }

    /** Shows the reminder for [at], unless it's off now or a meditation began just before; silent in quiet hours. */
    fun fire(at: Long, canNotify: Boolean, quiet: Boolean) {
        val settings = store.settings()
        if (!canNotify || settings?.reminder != true || !MeditationReminderPlan.shows(at, store.lastStartedAt)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Wind down", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "A gentle nudge to wind down, at the time you chose." },
            )
        }
        val open = PendingIntent.getActivity(
            context, REQUEST,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, ROUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(MeditationReminderPlan.TITLE)
            .setContentText(MeditationReminderPlan.TEXT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Nothing personal in it: the same words on the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(quiet)
            .build()
        @Suppress("MissingPermission") // the caller checked
        NotificationManagerCompat.from(context).notify(NOTIFICATION, notification)
    }

    private fun fireIntent(at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST,
        Intent(context, MeditationReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL = "wind-down-reminders"
        const val ACTION_FIRE = "com.health.app.reminders.WIND_DOWN"
        const val EXTRA_AT = "at"
        /** The Meditation page (MainActivity only opens lowercase routes). */
        const val ROUTE = "meditation"
        private const val REQUEST = 4500
        private const val NOTIFICATION = 4510

        /** Brings the reminder in step with the settings on the server (the person's own; a load that fails keeps what's kept). */
        suspend fun refresh(context: Context, api: ServerApi, zone: ZoneId) {
            val reminders = MeditationReminders(context)
            val settings = attempt { api.meditationSettings() }.getOrNull() ?: return reminders.reschedule(zone)
            reminders.update(settings, zone)
        }

        /** Signing out: no reminder, and nothing meditation kept on the phone. */
        fun forget(context: Context) {
            MeditationReminders(context).clear()
            MeditationStore(context).clear()
        }
    }
}

/** Where the wind-down reminder's alarm lands; a restart, an update, a new time zone or being allowed exact alarms sets it again. */
class MeditationReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        val reminders = MeditationReminders(context)
        when (intent.action) {
            MeditationReminders.ACTION_FIRE -> {
                val at = intent.getLongExtra(MeditationReminders.EXTRA_AT, 0)
                if (at > 0 && c.settings.value.signedIn) {
                    reminders.fire(at, c.reminders.canNotify(), CarePlanner.quiet(c.reminderCache.prefs(), at, c.zone()))
                }
                reminders.reschedule(c.zone())
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, EXACT_ALARMS_CHANGED ->
                if (c.settings.value.signedIn) reminders.reschedule(c.zone())
        }
    }
}
