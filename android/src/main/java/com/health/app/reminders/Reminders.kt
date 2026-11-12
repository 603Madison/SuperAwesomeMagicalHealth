package com.health.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.health.api.MedForm
import com.health.app.MainActivity
import com.health.app.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Medication reminders: one alarm for the next moment a dose is due (exact where Android allows
 * it, else within ten minutes), a notification for each dose due then with Taken and Snooze, and
 * the next alarm set when one goes off. Works from [MedsCache], so it needs no network.
 */
class Reminders(private val context: Context, private val cache: MedsCache, private val zone: () -> ZoneId) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Whether reminders can go off at the minute (Android 12 and later ask the person first). */
    fun canBeExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    /** Whether the app may show notifications (Android 13 and later ask the person first). */
    fun canNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Sets the alarm for the next dose due, replacing any set before. */
    fun reschedule(now: Long = System.currentTimeMillis()) {
        val intent = fireIntent(0)
        alarms.cancel(intent)
        val (at, _) = ReminderPlanner.next(cache.medications(), now, zone()) ?: return
        set(at, fireIntent(at))
    }

    fun cancelAll() {
        alarms.cancel(fireIntent(0))
        NotificationManagerCompat.from(context).cancelAll()
    }

    /** Shows a notification for each of [doses]. */
    fun notify(doses: List<PlannedDose>) {
        if (doses.isEmpty() || !canNotify()) return
        ensureChannel()
        val manager = NotificationManagerCompat.from(context)
        doses.forEach { dose ->
            val time = DateTimeFormatter.ofPattern("H:mm").format(Instant.ofEpochMilli(dose.at).atZone(zone()))
            val site = cache.nextSite(dose.medicationId)?.takeIf { dose.form == MedForm.INJECTION || dose.form == MedForm.PATCH }
            val text = listOfNotNull(dose.dose, "due at $time", site?.let { "next site: $it" }).joinToString(" · ")
            val open = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Time for ${dose.name}")
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, "Taken", actionIntent(ReminderReceiver.ACTION_TAKEN, dose))
                .addAction(0, "Snooze 10 min", actionIntent(ReminderReceiver.ACTION_SNOOZE, dose))
                .build()
            @Suppress("MissingPermission") // checked in canNotify()
            manager.notify(notificationId(dose), notification)
        }
    }

    /** Takes the notification away and shows it again in [minutes]. */
    fun snooze(dose: PlannedDose, minutes: Int = 10, now: Long = System.currentTimeMillis()) {
        dismiss(dose)
        set(now + minutes * 60_000L, doseIntent(ReminderReceiver.ACTION_SNOOZED, dose))
    }

    fun dismiss(dose: PlannedDose) = NotificationManagerCompat.from(context).cancel(notificationId(dose))

    private fun set(at: Long, intent: PendingIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, intent)
        }
    }

    private fun fireIntent(at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_FIRE,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra(ReminderReceiver.EXTRA_AT, at),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String, dose: PlannedDose): PendingIntent = doseIntent(action, dose)

    private fun doseIntent(action: String, dose: PlannedDose): PendingIntent = PendingIntent.getBroadcast(
        context, (action + notificationId(dose)).hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(action).apply { ReminderReceiver.put(this, dose) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Medication reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When a dose is due, with Taken and Snooze."
            },
        )
    }

    companion object {
        const val CHANNEL = "medication-reminders"
        private const val REQUEST_FIRE = 4100

        fun notificationId(dose: PlannedDose): Int = (dose.medicationId + dose.at).hashCode()
    }
}
