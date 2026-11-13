package com.health.app.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.health.api.ApiJson
import com.health.api.Routine
import com.health.api.RoutineRun
import com.health.app.MainActivity
import com.health.app.R
import com.health.app.container
import com.health.app.net.ServerApi
import com.health.app.net.routineRuns
import com.health.app.net.routines
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * Routine reminders (Autism & ADHD), as the medication reminders work: the routines with a
 * reminder time are kept on the phone, one alarm is set for the next one due, and when it goes off
 * a gentle notification shows (unless that routine's already done today) and the next is set.
 * Kept apart from the other reminders so each can change on its own.
 */

/** A routine with a reminder, as kept on the phone. [days] are 1 (Monday) to 7; empty means every day. */
@Serializable
data class RoutineAlarm(val id: String, val name: String, val time: String, val days: List<Int> = emptyList(), val steps: Int = 0, val minutes: Int = 0)

/** When routines remind: pure, so it can be tested. */
object RoutinePlanner {
    fun alarmsOf(routines: List<Routine>): List<RoutineAlarm> = routines.filter { it.active && it.reminder != null && it.id != null }.mapNotNull { r ->
        val time = runCatching { LocalTime.parse(r.reminder) }.getOrNull() ?: return@mapNotNull null
        RoutineAlarm(r.id!!, r.name, time.toString(), r.days, r.steps.size, r.steps.mapNotNull { it.minutes }.sum())
    }

    /** Routines finished on each day ("2026-11-12" to ids), from the runs: every step done. */
    fun doneOf(routines: List<Routine>, runs: List<RoutineRun>): Map<String, Set<String>> = runs.filter { run ->
        val steps = routines.firstOrNull { it.id == run.routineId }?.steps?.size ?: return@filter false
        steps > 0 && run.done.size >= steps
    }.groupBy { it.date }.mapValues { (_, list) -> list.map { it.routineId }.toSet() }

    private fun on(alarm: RoutineAlarm, date: LocalDate) = alarm.days.isEmpty() || date.dayOfWeek.value in alarm.days

    /** The next moment after [now] any routine reminds, with the routines due then; null when none in the next eight days. */
    fun next(alarms: List<RoutineAlarm>, done: Map<String, Set<String>>, now: Long, zone: ZoneId): Pair<Long, List<RoutineAlarm>>? {
        if (alarms.isEmpty()) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (offset in 0..8) {
            val date = today.plusDays(offset.toLong())
            val due = alarms.filter { a -> on(a, date) && a.id !in done[date.toString()].orEmpty() }
                .groupBy { date.atTime(LocalTime.parse(it.time)).atZone(zone).toInstant().toEpochMilli() }
                .filterKeys { it > now }
            due.keys.minOrNull()?.let { at -> return at to due.getValue(at) }
        }
        return null
    }

    /** The routines due at [at] that aren't done that day. */
    fun dueAt(alarms: List<RoutineAlarm>, done: Map<String, Set<String>>, at: Long, zone: ZoneId): List<RoutineAlarm> {
        val time = Instant.ofEpochMilli(at).atZone(zone)
        val date = time.toLocalDate()
        return alarms.filter { a ->
            on(a, date) && a.id !in done[date.toString()].orEmpty() &&
                kotlin.math.abs(date.atTime(LocalTime.parse(a.time)).atZone(zone).toInstant().toEpochMilli() - at) < 60_000
        }
    }

    /** "3 steps, about 20 minutes. One step at a time." */
    fun body(a: RoutineAlarm): String =
        "${a.steps} ${if (a.steps == 1) "step" else "steps"}${if (a.minutes > 0) ", about ${a.minutes} minutes" else ""}. One step at a time."
}

/** The routines and what's done, kept on the phone; and the alarm for the next reminder. */
class RoutineReminders(private val context: Context) {
    private val prefs = context.getSharedPreferences("routine-reminders", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun alarms(): List<RoutineAlarm> = prefs.getString(ALARMS, null)?.let {
        runCatching { ApiJson.decodeFromString(ListSerializer(RoutineAlarm.serializer()), it) }.getOrNull()
    }.orEmpty()

    fun done(): Map<String, Set<String>> = prefs.all.keys.filter { it.startsWith(DONE) }.associate { key ->
        key.removePrefix(DONE) to prefs.getStringSet(key, emptySet()).orEmpty().toSet()
    }

    /** Keeps [routines] and the last week's [runs], and sets the next alarm. */
    fun update(routines: List<Routine>, runs: List<RoutineRun>, zone: ZoneId, now: Long = System.currentTimeMillis()) {
        val keep = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(8)
        val done = RoutinePlanner.doneOf(routines, runs)
        prefs.edit {
            putString(ALARMS, ApiJson.encodeToString(ListSerializer(RoutineAlarm.serializer()), RoutinePlanner.alarmsOf(routines)))
            prefs.all.keys.filter { it.startsWith(DONE) }.forEach { remove(it) }
            done.filterKeys { runCatching { !LocalDate.parse(it).isBefore(keep) }.getOrDefault(false) }.forEach { (date, ids) -> putStringSet(DONE + date, ids) }
        }
        reschedule(zone, now)
    }

    /** Fetches the routines and the last week's runs, and keeps them. */
    suspend fun refresh(api: ServerApi, zone: ZoneId) = update(api.routines(), api.routineRuns(8), zone)

    fun reschedule(zone: ZoneId, now: Long = System.currentTimeMillis()) {
        alarms.cancel(fireIntent(0))
        val (at, _) = RoutinePlanner.next(alarms(), done(), now, zone) ?: return
        val intent = fireIntent(at)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, intent)
        }
    }

    /** Forgets everything (signing out, or Autism & ADHD turned off). */
    fun clear() {
        alarms.cancel(fireIntent(0))
        prefs.edit { clear() }
    }

    /** Shows the routines due at [at]. */
    fun fire(at: Long, zone: ZoneId, showNames: Boolean, canNotify: Boolean) {
        val due = RoutinePlanner.dueAt(alarms(), done(), at, zone)
        if (due.isEmpty() || !canNotify) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Routines", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "A gentle nudge when a routine's time comes." },
            )
        }
        due.forEach { a ->
            val open = PendingIntent.getActivity(
                context, a.id.hashCode(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN, "neuro/routines"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Time for: ${a.name}")
                .setContentText(RoutinePlanner.body(a))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
            if (showNames) {
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            } else {
                builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("A routine reminder").setContentText("One of your routines is ready when you are.").build(),
                )
            }
            @Suppress("MissingPermission") // the caller checked
            NotificationManagerCompat.from(context).notify(("routine:" + a.id).hashCode(), builder.build())
        }
    }

    private fun fireIntent(at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST,
        Intent(context, RoutineReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL = "routine-reminders"
        const val ACTION_FIRE = "com.health.app.reminders.ROUTINE"
        const val EXTRA_AT = "at"
        private const val REQUEST = 4200
        private const val ALARMS = "alarms"
        private const val DONE = "done."
    }
}

/** Where routine reminders' alarms land; a restart, an update or a new time zone sets the alarm again. */
class RoutineReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        val routines = RoutineReminders(context)
        when (intent.action) {
            RoutineReminders.ACTION_FIRE -> {
                val at = intent.getLongExtra(RoutineReminders.EXTRA_AT, 0)
                if (at > 0) routines.fire(at, c.zone(), c.reminderCache.prefs().showNames, c.reminders.canNotify())
                routines.reschedule(c.zone())
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED ->
                routines.reschedule(c.zone())
        }
    }
}
