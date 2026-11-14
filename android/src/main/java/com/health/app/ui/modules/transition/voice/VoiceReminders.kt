package com.health.app.ui.modules.transition.voice

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.health.api.ApiJson
import com.health.api.VoicePlan
import com.health.app.MainActivity
import com.health.app.R
import com.health.app.attempt
import com.health.app.container
import com.health.app.net.ServerApi
import com.health.app.net.voicePlan
import com.health.app.reminders.EXACT_ALARMS_CHANGED
import com.health.app.reminders.setReminder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate
import java.time.ZoneId

/*
 * The practice reminder, as the routine reminders work (reminders/RoutineReminders.kt): the plan's
 * time and rest days are kept on the phone, one alarm is set for the next one, and when it goes off
 * a gentle notification shows (unless the person has already practised that day) and the next is
 * set. Its words are discreet everywhere, the lock screen included: never "voice" or "transition".
 *
 * The server also sends this reminder (FeatureHooks.reminders, VOICE_PRACTICE), but only by browser
 * push and webhooks: the phone app plans its own alarms (as for doses, habits, routines and bladder
 * training) and doesn't read the server's reminders, so this one isn't a duplicate on the phone.
 */
class VoiceReminders(private val context: Context) {
    private val prefs = context.getSharedPreferences("voice-reminder", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    private fun time(): String? = prefs.getString(TIME, null)
    private fun restDays(): List<Int> = prefs.getString(REST, null)?.let {
        attempt { ApiJson.decodeFromString(ListSerializer(Int.serializer()), it) }.getOrNull()
    }.orEmpty()

    private fun practisedOn(): LocalDate? = prefs.getString(PRACTISED, null)?.let { attempt { LocalDate.parse(it) }.getOrNull() }

    /** Keeps the plan's reminder time and rest days, and sets the next alarm (or none). */
    fun update(plan: VoicePlan, practisedOn: LocalDate?, zone: ZoneId, now: Long = System.currentTimeMillis()) {
        prefs.edit {
            if (plan.reminder == null) remove(TIME) else putString(TIME, plan.reminder)
            putString(REST, ApiJson.encodeToString(ListSerializer(Int.serializer()), plan.restDays))
            if (practisedOn == null) remove(PRACTISED) else putString(PRACTISED, practisedOn.toString())
        }
        reschedule(zone, now)
    }

    fun reschedule(zone: ZoneId, now: Long = System.currentTimeMillis()) {
        alarms.cancel(fireIntent(0))
        val at = VoiceReminderPlan.next(time(), restDays(), practisedOn(), now, zone) ?: return
        alarms.setReminder(at, fireIntent(at))
    }

    /** Forgets the reminder (signing out, or Transition turned off). */
    fun clear() {
        alarms.cancel(fireIntent(0))
        prefs.edit { clear() }
    }

    /** Shows the reminder for [at], unless that's a rest day or the person has practised already. */
    fun fire(at: Long, zone: ZoneId, canNotify: Boolean) {
        val date = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        if (!canNotify || time() == null || date.dayOfWeek.value in restDays() || date == practisedOn()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Practice", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "A gentle nudge at the time you chose." },
            )
        }
        val open = PendingIntent.getActivity(
            context, REQUEST,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, ROUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(TITLE).setContentText(TEXT).build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(TITLE)
            .setContentText(TEXT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // the caller checked
        NotificationManagerCompat.from(context).notify(NOTIFICATION, notification)
    }

    private fun fireIntent(at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST,
        Intent(context, VoiceReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_AT, at),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL = "practice-reminders"
        const val ACTION_FIRE = "com.health.app.reminders.PRACTICE"
        const val EXTRA_AT = "at"
        /** The trainer's home (MainActivity only opens lowercase routes). */
        const val ROUTE = "transition/voice-training"

        /**
         * Discreet on purpose: nothing on the lock screen (or anywhere) says what it's for. The same
         * words as the server's push for VOICE_PRACTICE.
         */
        const val TITLE = "Time for a little practice"
        const val TEXT = "Ready when you are."

        /** Brings the reminder in step with the plan on the server, or (with Transition off) clears it. */
        suspend fun refresh(context: Context, api: ServerApi, on: Boolean, zone: ZoneId) {
            val reminders = VoiceReminders(context)
            if (!on) return reminders.clear()
            val plan = attempt { api.voicePlan() }.getOrNull() ?: return
            reminders.update(plan, VoiceStore(context).practisedOn, zone)
        }

        /**
         * Signing out: no reminder, and nothing the trainer kept on the phone (recordings
         * included), except sessions waiting to be sent, each under its account; [dropUnsentOf]'s
         * go too (the person asked to sign out of it).
         */
        fun forget(context: Context, dropUnsentOf: String? = null) {
            VoiceReminders(context).clear()
            VoiceStore(context).forget(context, dropUnsentOf)
            VoiceViewModel.forget()
        }

        private const val REQUEST = 4300
        /** Its own: the focus timer's are 4301 and 4302. */
        private const val NOTIFICATION = 4310
        private const val TIME = "time"
        private const val REST = "rest"
        private const val PRACTISED = "practised"
    }
}

/** Where the practice reminder's alarm lands; a restart, an update, a new time zone or being allowed exact alarms sets it again. */
class VoiceReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        val reminders = VoiceReminders(context)
        when (intent.action) {
            VoiceReminders.ACTION_FIRE -> {
                val at = intent.getLongExtra(VoiceReminders.EXTRA_AT, 0)
                if (at > 0 && c.settings.value.signedIn) reminders.fire(at, c.zone(), c.reminders.canNotify())
                reminders.reschedule(c.zone())
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, EXACT_ALARMS_CHANGED ->
                reminders.reschedule(c.zone())
        }
    }
}
