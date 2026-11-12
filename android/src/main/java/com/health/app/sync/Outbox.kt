package com.health.app.sync

import com.health.api.ApiJson
import com.health.api.IngestBatch
import java.io.File

/**
 * Batches the server couldn't take yet (it was down, the phone was offline), kept on disk and
 * sent again, oldest first, at the next sync. Only the newest [limit] are kept.
 */
class Outbox(private val dir: File, private val limit: Int = 500) {
    init {
        dir.mkdirs()
    }

    @Synchronized
    fun add(batch: IngestBatch) {
        dir.mkdirs()
        val name = "${System.currentTimeMillis()}-${System.nanoTime() % 1_000_000}.json"
        File(dir, name).writeText(ApiJson.encodeToString(IngestBatch.serializer(), batch))
        val files = files()
        if (files.size > limit) files.take(files.size - limit).forEach { it.delete() }
    }

    @Synchronized
    fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    val size: Int get() = files().size

    /**
     * Sends what's waiting with [send], oldest first; a batch goes once it's accepted. Stops at the
     * first one that fails for a reason that may pass (offline, the server down); one the server
     * refuses as wrong is dropped so it can't block the rest. Returns how many were sent.
     */
    suspend fun flush(send: suspend (String) -> Unit, isTemporary: (Exception) -> Boolean): Int {
        var sent = 0
        for (file in files()) {
            val json = runCatching { file.readText() }.getOrNull()
            if (json == null) {
                file.delete()
                continue
            }
            try {
                send(json)
                file.delete()
                sent++
            } catch (e: Exception) {
                if (isTemporary(e)) break
                file.delete()
            }
        }
        return sent
    }

    fun clear() {
        files().forEach { it.delete() }
    }
}
