package com.health.app.reminders

import android.content.Context
import androidx.core.content.edit
import com.health.api.ApiJson
import com.health.api.DoseLog
import com.health.api.Medication
import com.health.api.MedsDay
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * The medications as last loaded, kept on the phone so reminders still go off offline and after a
 * restart, with the injection or patch site each is due to use next.
 */
class MedsCache(context: Context) {
    private val prefs = context.getSharedPreferences("medications", Context.MODE_PRIVATE)

    fun medications(): List<Medication> = prefs.getString(MEDS, null)
        ?.let { runCatching { ApiJson.decodeFromString(ListSerializer(Medication.serializer()), it) }.getOrNull() }
        .orEmpty()

    fun save(meds: List<Medication>) = prefs.edit { putString(MEDS, ApiJson.encodeToString(ListSerializer(Medication.serializer()), meds)) }

    /** Remembers each medication's next site from a day's doses. */
    fun saveSites(day: MedsDay) = prefs.edit {
        day.due.forEach { d -> d.nextSite?.let { putString(SITE + d.medicationId, it) } }
    }

    fun nextSite(medicationId: String): String? = prefs.getString(SITE + medicationId, null)

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val MEDS = "meds"
        const val SITE = "site."
    }
}

/** Doses marked from a notification while the server couldn't be reached: sent next time it can. */
class DoseOutbox(private val dir: File) {
    @Synchronized
    fun add(dose: DoseLog) {
        dir.mkdirs()
        // Named by time, then a counter, so they sort in the order they were taken.
        val stamp = System.currentTimeMillis()
        var n = 0
        while (File(dir, name(stamp, n)).exists()) n++
        File(dir, name(stamp, n)).writeText(ApiJson.encodeToString(DoseLog.serializer(), dose))
    }

    private fun name(stamp: Long, n: Int) = "%013d-%04d.json".format(stamp, n)

    fun size(): Int = files().size

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    /** Sends what's waiting, oldest first; stops at the first failure. Returns how many were sent. */
    suspend fun flush(send: suspend (String) -> Unit): Int {
        var sent = 0
        for (file in files()) {
            try {
                send(file.readText())
            } catch (e: Exception) {
                break
            }
            file.delete()
            sent++
        }
        return sent
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
