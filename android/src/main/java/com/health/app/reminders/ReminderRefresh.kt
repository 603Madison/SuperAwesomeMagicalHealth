package com.health.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build
import com.health.api.Module
import com.health.app.attempt
import java.time.ZoneId

/**
 * Which features the phone's reminders are brought up to date for. Alarms, a restart and the
 * background sync can all start the app while the server can't be reached, so the profile may not
 * have loaded: then the features last known stand in, and nothing is forgotten, since only the
 * profile itself can say a feature is off. With neither, the reminders kept on the phone carry on
 * as they are ([of] is null).
 */
data class ReminderRefresh(val modules: Set<Module>, val forget: Boolean) {
    /** Whether any feature that reminds is on. */
    val any: Boolean get() = REMINDING.any { it in modules }

    /** Whether what's kept for [module]'s reminders goes: only when the profile says it's off. */
    fun forgets(module: Module): Boolean = forget && module !in modules

    companion object {
        /** The features with reminders on the phone (Bladder and bowel brings bladder training and changes). */
        val REMINDING = setOf(Module.MEDICATIONS, Module.HABITS, Module.APPOINTMENTS, Module.CONTINENCE)

        /** From the profile's features (null until it loads) and the ones kept with the reminders. */
        fun of(profile: Set<Module>?, kept: Set<Module>): ReminderRefresh? = when {
            profile != null -> ReminderRefresh(profile, forget = true)
            kept.isNotEmpty() -> ReminderRefresh(kept, forget = false)
            else -> null
        }

        /**
         * The person's time zone: the profile's; before it loads, the one kept with the reminders
         * (the zone they were planned in, which a traveller's phone may not be in); else the phone's.
         */
        fun zone(profile: String?, kept: String?, phone: ZoneId = ZoneId.systemDefault()): ZoneId =
            listOf(profile, kept).firstNotNullOfOrNull { z -> z?.takeIf { it.isNotBlank() }?.let { attempt { ZoneId.of(it) }.getOrNull() } } ?: phone
    }
}

/**
 * Sets [intent] to go off at [at], waking the phone: at the minute where Android allows it (Android
 * 12 and later ask the person), else as near to it as the phone's idle mode lets, which is minutes,
 * never the hours a window can be held for.
 */
internal fun AlarmManager.setReminder(at: Long, intent: PendingIntent) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms()) {
        setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    } else {
        setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }
}

/**
 * Sent when the person allows (or stops allowing) alarms at the minute, Android 12 and later
 * (AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED): the alarms are set again.
 */
const val EXACT_ALARMS_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
