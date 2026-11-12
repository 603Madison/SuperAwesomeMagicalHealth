package com.health.app.devices.gadgetbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.health.app.sync.Collected
import com.health.app.sync.Conduit
import com.health.app.sync.SyncStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gadgetbridge's exported database. Gadgetbridge talks to Pebbles (the new Core Devices ones
 * too), Colmi rings, many Garmins and more; set it to export its database automatically, pick
 * that file here once, and each sync reads what's new in it.
 */
class GadgetbridgeConduit(
    private val context: Context,
    private val store: SyncStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : Conduit {
    override val id = ID
    override val title = "Gadgetbridge"

    override suspend fun ready(): Boolean = store.gadgetbridgeUri.value != null

    override suspend fun collect(since: Long?): Collected = withContext(Dispatchers.IO) {
        val uri = store.gadgetbridgeUri.value?.toUri() ?: return@withContext Collected(emptyList(), clock())
        val copy = File(context.cacheDir, "gadgetbridge.db")
        try {
            val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                ?: throw IllegalStateException("Can't open the export file any more; choose it again")
            input.use { inp -> copy.outputStream().use { inp.copyTo(it) } }
            val start = since?.let { it - OVERLAP_MS } ?: (clock() - FIRST_SYNC_MS)
            val result = runCatching { GadgetbridgeReader(copy).read(start) }.getOrElse {
                throw IllegalStateException("That file isn't a Gadgetbridge database (${it.message})")
            }
            val note = when {
                result.tables == 0 -> "No health data found in that file"
                result.batches.isEmpty() -> "Nothing new since the last export"
                else -> null
            }
            Collected(result.batches, maxOf(since ?: 0L, result.latest ?: since ?: start), note)
        } finally {
            copy.delete()
            File(copy.path + "-journal").delete()
        }
    }

    /** Remembers the export file the person picked (keeping permission to read it). */
    fun choose(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        store.setGadgetbridgeUri(uri.toString())
        store.setCursor(ID, null)
        store.clearStatus(ID)
    }

    fun remove() {
        store.gadgetbridgeUri.value?.let { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        store.setGadgetbridgeUri(null)
        store.setCursor(ID, null)
        store.clearStatus(ID)
    }

    /** The chosen file's name, for showing. */
    fun fileName(): String? {
        val uri = store.gadgetbridgeUri.value?.toUri() ?: return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    companion object {
        const val ID = "gadgetbridge"
        private const val OVERLAP_MS = 6 * 3_600_000L
        private const val FIRST_SYNC_MS = 30 * 86_400_000L
    }
}
