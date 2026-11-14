package com.health.app.reminders

import android.content.Context
import androidx.core.content.edit
import com.health.api.ApiJson
import com.health.api.DoseLog
import com.health.api.Medication
import com.health.api.MedsDay
import com.health.app.attempt
import com.health.app.sync.AccountFolders
import com.health.app.sync.SyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * The medications as last loaded, kept on the phone so reminders still go off offline and after a
 * restart, with the injection or patch site each is due to use next.
 */
class MedsCache(context: Context) {
    private val prefs = context.getSharedPreferences("medications", Context.MODE_PRIVATE)

    fun medications(): List<Medication> = prefs.getString(MEDS, null)
        ?.let { attempt { ApiJson.decodeFromString(ListSerializer(Medication.serializer()), it) }.getOrNull() }
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

/**
 * Doses marked from a notification while the server couldn't be reached: kept under the account
 * that marked them and sent, to that account only, next time it can be reached.
 */
class DoseOutbox(dir: File) {
    private val folders = AccountFolders(dir)

    @Synchronized
    fun add(account: String, dose: DoseLog) {
        val folder = folders.of(account).apply { mkdirs() }
        // Named by time, then a counter, so they sort in the order they were taken.
        val stamp = System.currentTimeMillis()
        var n = 0
        while (File(folder, name(stamp, n)).exists()) n++
        File(folder, name(stamp, n)).writeText(ApiJson.encodeToString(DoseLog.serializer(), dose))
    }

    private fun name(stamp: Long, n: Int) = "%013d-%04d.json".format(stamp, n)

    /** How many doses [account] has waiting (none when signed out). */
    fun size(account: String?): Int = account?.let { folders.files(it).size } ?: 0

    /** Moves doses kept by an older version of the app under [account] (whoever is signed in). */
    fun adopt(account: String) = folders.adopt(account)

    /**
     * Sends [account]'s doses, oldest first. One the server refuses as wrong ([isRefused]: a dose
     * for a medication that's gone, say) is dropped, so it can't hold up the rest; any other
     * failure stops until next time. Returns how many were sent.
     */
    suspend fun flush(account: String, send: suspend (String) -> Unit, isRefused: (Exception) -> Boolean = ::refused): Int {
        var sent = 0
        for (file in folders.files(account)) {
            val json = attempt { file.readText() }.getOrNull()
            if (json == null) {
                file.delete()
                continue
            }
            try {
                send(json)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (!isRefused(e)) break
                file.delete()
                continue
            }
            file.delete()
            sent++
        }
        return sent
    }

    /** Forgets what [account] has waiting (signing out when the person asks). */
    fun clear(account: String) = folders.clear(account)

    companion object {
        /**
         * Whether the server refused a dose as wrong, in its own words: a bad dose, or one for a
         * medication that's gone. Only these are dropped; anything else waits to be sent again.
         */
        fun refused(e: Exception): Boolean = SyncManager.isRefused(e, setOf(400, 404, 422))
    }
}
