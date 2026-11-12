package com.health.app.reminders

import android.content.Context
import androidx.core.content.edit
import com.health.api.ApiJson
import com.health.api.Appointment
import com.health.api.MedsDay
import com.health.api.Module
import com.health.api.ReminderPrefs
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * What reminders need besides the medications ([MedsCache]): how the person likes them, which
 * features are on, doses already marked, today's habits and the appointments ahead. Kept on the
 * phone so reminders still go off offline and after a restart.
 */
class ReminderCache(context: Context) {
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun prefs(): ReminderPrefs = read(PREFS) { ApiJson.decodeFromString(ReminderPrefs.serializer(), it) } ?: ReminderPrefs()

    fun modules(): Set<Module> = read(MODULES) { ApiJson.decodeFromString(SetSerializer(Module.serializer()), it) } ?: emptySet()

    fun marked(): Set<String> = prefs.getStringSet(MARKED, emptySet()).orEmpty()

    fun habits(): List<HabitReminder> = read(HABITS) {
        ApiJson.decodeFromString(ListSerializer(ListSerializer(String.serializer())), it).mapNotNull { h ->
            if (h.size == 5) HabitReminder(h[0], h[1], h[2], h[3], h[4] == "1") else null
        }
    }.orEmpty()

    fun appointments(): List<Appointment> = read(APPOINTMENTS) { ApiJson.decodeFromString(ListSerializer(Appointment.serializer()), it) }.orEmpty()

    fun savePrefs(value: ReminderPrefs, modules: Set<Module>) = prefs.edit {
        putString(PREFS, ApiJson.encodeToString(ReminderPrefs.serializer(), value))
        putString(MODULES, ApiJson.encodeToString(SetSerializer(Module.serializer()), modules))
    }

    /** Remembers which of a day's doses are taken or skipped; keeps a fortnight's worth. */
    fun saveMarked(day: MedsDay, now: Long = System.currentTimeMillis()) {
        val fresh = day.due.filter { it.log != null }.map { CarePlanner.doseKey(it.medicationId, it.scheduledFor) }
        val unmarked = day.due.filter { it.log == null }.map { CarePlanner.doseKey(it.medicationId, it.scheduledFor) }.toSet()
        keepMarked((marked() - unmarked) + fresh, now)
    }

    /** A dose marked from a notification. */
    fun mark(key: String, now: Long = System.currentTimeMillis()) = keepMarked(marked() + key, now)

    private fun keepMarked(keys: Set<String>, now: Long) {
        val cutoff = now - 14L * 24 * 3600 * 1000
        prefs.edit { putStringSet(MARKED, keys.filter { (it.substringAfter('@').toLongOrNull() ?: 0) >= cutoff }.toSet()) }
    }

    fun saveHabits(habits: List<HabitReminder>) = prefs.edit {
        putString(HABITS, ApiJson.encodeToString(ListSerializer(ListSerializer(String.serializer())), habits.map { listOf(it.id, it.name, it.time, it.date, if (it.done) "1" else "0") }))
    }

    fun saveAppointments(list: List<Appointment>) = prefs.edit { putString(APPOINTMENTS, ApiJson.encodeToString(ListSerializer(Appointment.serializer()), list)) }

    fun clear() = prefs.edit { clear() }

    private fun <T> read(key: String, decode: (String) -> T): T? = prefs.getString(key, null)?.let { runCatching { decode(it) }.getOrNull() }

    private companion object {
        const val PREFS = "prefs"
        const val MODULES = "modules"
        const val MARKED = "marked"
        const val HABITS = "habits"
        const val APPOINTMENTS = "appointments"
    }
}
