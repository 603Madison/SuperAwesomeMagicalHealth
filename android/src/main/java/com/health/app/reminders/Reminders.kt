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
import com.health.api.Module
import com.health.app.MainActivity
import com.health.app.R
import com.health.app.attempt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reminders: one alarm for the next moment anything is due (exact where Android allows it, else as
 * near as the phone's idle mode lets), a notification for each reminder due then (and, if the alarm
 * came late, for what came due meanwhile), and the next alarm set when one goes off. Doses come
 * with Taken and Snooze, and again while they aren't marked; refills, habits and appointments have
 * their own channels. On a locked screen, names only show when the person says so. Works from
 * [MedsCache] and [ReminderCache], so it needs no network.
 */
class Reminders(
    private val context: Context,
    private val cache: MedsCache,
    private val zone: () -> ZoneId,
    private val extra: ReminderCache? = null,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Whether reminders can go off at the minute (Android 12 and later ask the person first). */
    fun canBeExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    /** Whether the app may show notifications (Android 13 and later ask the person first). */
    fun canNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** What reminders are planned from, as kept on the phone. */
    fun inputs(): ReminderInputs {
        val meds = cache.medications()
        return ReminderInputs(
            meds = meds,
            marked = extra?.marked().orEmpty(),
            prefs = extra?.prefs() ?: com.health.api.ReminderPrefs(),
            // Before the features are known, medications kept here still remind.
            modules = extra?.modules()?.takeIf { it.isNotEmpty() } ?: if (meds.isNotEmpty()) setOf(Module.MEDICATIONS) else emptySet(),
            habits = extra?.habits().orEmpty(),
            appointments = extra?.appointments().orEmpty(),
            bladder = extra?.bladder(),
            changeDueAt = extra?.changeDue()?.first,
            wornSince = extra?.changeDue()?.second,
        )
    }

    /** Sets the alarm for the next reminder due, replacing any set before. */
    fun reschedule(now: Long = System.currentTimeMillis()) {
        alarms.cancel(fireIntent(0))
        val (at, _) = CarePlanner.next(inputs(), now, zone()) ?: return
        set(at, fireIntent(at))
    }

    /**
     * Takes the reminders away: the next alarm, snoozed doses' alarms, and the reminders showing.
     * Only these: the focus timer, routines, practice and the digest are left as they are.
     */
    fun cancelAll() {
        alarms.cancel(fireIntent(0))
        extra?.snoozed()?.forEach { key -> doseOfKey(key)?.let { alarms.cancel(doseIntent(ReminderReceiver.ACTION_SNOOZED, it)) } }
        extra?.clearSnoozed()
        val manager = context.getSystemService(NotificationManager::class.java)
        val ours = CHANNELS.map { it.id }.toSet()
        attempt { manager.activeNotifications }.getOrNull().orEmpty()
            .filter { it.notification.channelId in ours }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    /**
     * Shows the reminders due when an alarm set for [at] goes off at [now]; if Android held it
     * back, what came due since reminders last went off too (within two hours).
     */
    fun fire(at: Long, now: Long = System.currentTimeMillis()) {
        show(CarePlanner.catchUp(inputs(), extra?.lastFired(), at, now, zone()))
        extra?.saveLastFired(maxOf(at, now))
    }

    /** Shows a notification for each of [doses] (a snoozed one coming back). */
    fun notify(doses: List<PlannedDose>) = show(
        doses.map { d ->
            val time = DateTimeFormatter.ofPattern("H:mm").format(Instant.ofEpochMilli(d.at).atZone(zone()))
            PlannedAlarm(
                at = d.at, kind = AlarmKind.DOSE, key = "dose:" + CarePlanner.doseKey(d.medicationId, d.at),
                title = "Time for ${d.name}", body = listOfNotNull(d.dose, "due at $time").joinToString(" · "),
                privateTitle = "Time for your $time medications", privateBody = "Open the app to see what's due.", dose = d,
            )
        },
    )

    /** Shows each of [alarms] in its own notification. */
    fun show(alarms: List<PlannedAlarm>) {
        if (alarms.isEmpty() || !canNotify()) return
        ensureChannels()
        val showNames = extra?.prefs()?.showNames ?: false
        val manager = NotificationManagerCompat.from(context)
        alarms.forEach { alarm ->
            val dose = alarm.dose
            val site = dose?.let { d -> cache.nextSite(d.medicationId)?.takeIf { d.form == MedForm.INJECTION || d.form == MedForm.PATCH } }
            val body = listOfNotNull(alarm.body, site?.let { "next site: $it" }).joinToString(" · ")
            val channel = channelOf(alarm.kind)
            val builder = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(alarm.title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(if (alarm.kind == AlarmKind.APPOINTMENT) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_REMINDER)
                .setPriority(if (alarm.kind == AlarmKind.DOSE || alarm.kind == AlarmKind.DOSE_AGAIN || alarm.kind == AlarmKind.APPOINTMENT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(openIntent(alarm))
                .setAutoCancel(true)
                .setSilent(alarm.silent)
            if (showNames) {
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            } else {
                // A locked screen shows only the version without names.
                builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
                    NotificationCompat.Builder(context, channel)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(alarm.privateTitle)
                        .setContentText(alarm.privateBody)
                        .build(),
                )
            }
            if (dose != null) {
                builder.addAction(0, "Taken", doseIntent(ReminderReceiver.ACTION_TAKEN, dose))
                    .addAction(0, "Snooze 10 min", doseIntent(ReminderReceiver.ACTION_SNOOZE, dose))
            }
            @Suppress("MissingPermission") // checked in canNotify()
            manager.notify(notificationId(alarm), builder.build())
        }
    }

    /** Takes the notification away and shows it again in [minutes]. */
    fun snooze(dose: PlannedDose, minutes: Int = 10, now: Long = System.currentTimeMillis()) {
        dismiss(dose)
        extra?.snooze(CarePlanner.doseKey(dose.medicationId, dose.at))
        set(now + minutes * 60_000L, doseIntent(ReminderReceiver.ACTION_SNOOZED, dose))
    }

    /** A snoozed dose's time is up: it shows again, unless it's been marked meanwhile or its medication no longer reminds. */
    fun snoozeOver(dose: PlannedDose, signedIn: Boolean) {
        extra?.unsnooze(CarePlanner.doseKey(dose.medicationId, dose.at))
        if (signedIn && CarePlanner.stillDue(dose, inputs())) notify(listOf(dose))
    }

    fun dismiss(dose: PlannedDose) = NotificationManagerCompat.from(context).cancel(notificationId(dose))

    /** A dose marked (from a notification): no more reminders for it. */
    fun marked(dose: PlannedDose) {
        val key = CarePlanner.doseKey(dose.medicationId, dose.at)
        extra?.mark(key)
        extra?.unsnooze(key)
        alarms.cancel(doseIntent(ReminderReceiver.ACTION_SNOOZED, dose))
        dismiss(dose)
        reschedule()
    }

    private fun set(at: Long, intent: PendingIntent) = alarms.setReminder(at, intent)

    private fun openIntent(alarm: PlannedAlarm): PendingIntent = PendingIntent.getActivity(
        context, alarm.kind.ordinal + 1,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, routeOf(alarm.kind)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun fireIntent(at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_FIRE,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra(ReminderReceiver.EXTRA_AT, at),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun doseIntent(action: String, dose: PlannedDose): PendingIntent = PendingIntent.getBroadcast(
        context, (action + notificationId(dose)).hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(action).apply { ReminderReceiver.put(this, dose) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        CHANNELS.forEach { (id, name, importance, about) ->
            if (manager.getNotificationChannel(id) == null) {
                manager.createNotificationChannel(NotificationChannel(id, name, importance).apply { description = about })
            }
        }
    }

    private data class Channel(val id: String, val name: String, val importance: Int, val about: String)

    companion object {
        const val CHANNEL = "medication-reminders"
        const val CHANNEL_REFILLS = "medication-refills"
        const val CHANNEL_HABITS = "habit-reminders"
        const val CHANNEL_APPOINTMENTS = "appointment-reminders"
        const val CHANNEL_BLADDER = "bladder-training"
        const val CHANNEL_CHANGE = "change-reminders"
        private const val REQUEST_FIRE = 4100

        private val CHANNELS = listOf(
            Channel(CHANNEL, "Medication reminders", NotificationManager.IMPORTANCE_HIGH, "When a dose is due, with Taken and Snooze, and again if it isn't marked."),
            Channel(CHANNEL_REFILLS, "Refills", NotificationManager.IMPORTANCE_DEFAULT, "When a medication is running low."),
            Channel(CHANNEL_HABITS, "Habits", NotificationManager.IMPORTANCE_DEFAULT, "A nudge when a habit isn't ticked by its time."),
            Channel(CHANNEL_APPOINTMENTS, "Appointments", NotificationManager.IMPORTANCE_HIGH, "Before each appointment."),
            Channel(CHANNEL_BLADDER, "Bladder training", NotificationManager.IMPORTANCE_DEFAULT, "Planned toilet trips while you're training your bladder."),
            Channel(CHANNEL_CHANGE, "Change reminders", NotificationManager.IMPORTANCE_DEFAULT, "A discreet nudge to check or change, after the hours you chose."),
        )

        fun channelOf(kind: AlarmKind): String = when (kind) {
            AlarmKind.DOSE, AlarmKind.DOSE_AGAIN -> CHANNEL
            AlarmKind.REFILL -> CHANNEL_REFILLS
            AlarmKind.HABIT -> CHANNEL_HABITS
            AlarmKind.APPOINTMENT -> CHANNEL_APPOINTMENTS
            AlarmKind.BLADDER -> CHANNEL_BLADDER
            AlarmKind.CHANGE -> CHANNEL_CHANGE
        }

        /** Where tapping a reminder goes in the app. */
        fun routeOf(kind: AlarmKind): String = when (kind) {
            AlarmKind.DOSE, AlarmKind.DOSE_AGAIN, AlarmKind.REFILL -> "meds"
            AlarmKind.HABIT -> "today"
            AlarmKind.APPOINTMENT -> "appointments"
            AlarmKind.BLADDER, AlarmKind.CHANGE -> "continence"
        }

        fun notificationId(dose: PlannedDose): Int = (dose.medicationId + dose.at).hashCode()

        /** A dose from its [CarePlanner.doseKey], enough to find its notification and alarms again. */
        fun doseOfKey(key: String): PlannedDose? {
            val at = key.substringAfterLast('@', "").toLongOrNull() ?: return null
            return PlannedDose(key.substringBeforeLast('@'), "", null, MedForm.OTHER, at)
        }

        /** A dose and its reminders again share one notification, so each replaces the last. */
        fun notificationId(alarm: PlannedAlarm): Int = alarm.dose?.let(::notificationId) ?: alarm.key.hashCode()
    }
}
