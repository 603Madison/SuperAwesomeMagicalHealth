package com.health.app.sync

import android.util.Log
import com.health.api.IngestBatch
import com.health.api.IngestResult
import com.health.app.net.ServerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Carries data from each data source to the server: sends anything left in the outbox, then asks
 * each source that's set up for what's new, uploads it in parts, and remembers where it got to.
 * A source that fails doesn't stop the others; batches the server can't take now wait in the
 * outbox.
 */
class SyncManager(
    private val conduits: List<Conduit>,
    private val store: SyncStore,
    private val outbox: Outbox,
    private val upload: suspend (IngestBatch) -> IngestResult,
    private val uploadJson: suspend (String) -> IngestResult,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Told after a sync that brought something in, so screens can refresh. */
    private val onNewData: () -> Unit = {},
) {
    private val mutex = Mutex()

    private val _running = MutableStateFlow<String?>(null)

    /** The source syncing now ("" while the outbox is sent), or null. */
    val running: StateFlow<String?> = _running.asStateFlow()

    fun conduit(id: String): Conduit? = conduits.firstOrNull { it.id == id }

    /** Syncs every source that's set up, or only [onlyId]. Returns a line for people. */
    suspend fun sync(onlyId: String? = null): String = mutex.withLock {
        var total = 0
        val problems = mutableListOf<String>()
        try {
            _running.value = ""
            val flushed = outbox.flush({ uploadJson(it) }, ::isTemporary)
            if (flushed > 0) total++
            for (conduit in conduits) {
                if (onlyId != null && conduit.id != onlyId) continue
                val ready = runCatching { conduit.ready() }.getOrDefault(false)
                if (!ready) continue
                _running.value = conduit.id
                val result = syncOne(conduit)
                if (!result.ok) problems += "${conduit.title}: ${result.message}"
                total += result.samples + result.sleep + result.workouts
            }
        } finally {
            _running.value = null
        }
        if (total > 0) onNewData()
        when {
            problems.isNotEmpty() -> problems.joinToString("\n")
            total > 0 -> "Synced"
            else -> "Up to date"
        }
    }

    private suspend fun syncOne(conduit: Conduit): ConduitStatus {
        val now = clock()
        val status = try {
            val collected = conduit.collect(store.cursor(conduit.id))
            var samples = 0
            var sleep = 0
            var workouts = 0
            var queued = 0
            for (batch in collected.batches.filterNot { it.isEmpty }.flatMap { it.chunked() }) {
                try {
                    val result = upload(batch)
                    samples += result.samples
                    sleep += result.sleep
                    workouts += result.workouts
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isTemporary(e)) throw e
                    outbox.add(batch)
                    queued++
                }
            }
            store.setCursor(conduit.id, collected.cursor)
            ConduitStatus(
                lastSyncAt = now,
                ok = true,
                message = when {
                    queued > 0 -> "Couldn't reach the server; $queued ${if (queued == 1) "batch waits" else "batches wait"} to be sent"
                    else -> collected.note
                },
                samples = samples,
                sleep = sleep,
                workouts = workouts,
                queued = queued,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync of ${conduit.id} failed", e)
            ConduitStatus(lastSyncAt = now, ok = false, message = e.message ?: e.javaClass.simpleName)
        }
        store.setStatus(conduit.id, status)
        return status
    }

    companion object {
        private const val TAG = "SyncManager"

        /** Whether trying again later may work: offline, or the server down or busy. */
        fun isTemporary(e: Exception): Boolean = e is ServerException && (e.code == 0 || e.code == 408 || e.code == 429 || e.code >= 500)
    }
}
