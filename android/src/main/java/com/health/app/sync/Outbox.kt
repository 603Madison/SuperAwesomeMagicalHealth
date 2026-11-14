package com.health.app.sync

import com.health.api.ApiJson
import com.health.api.IngestBatch
import com.health.app.attempt
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Folders of things waiting to be sent, one per account ([com.health.app.data.Settings.account]):
 * what one account recorded can only ever go to that account, even after a sign-out the person
 * didn't ask for (the server saying the sign-in is no longer good) and someone else signing in.
 */
class AccountFolders(private val root: File) {
    /** [account]'s folder (made when needed). */
    fun of(account: String): File {
        require(account.isNotBlank() && account.none { it == '/' || it == '.' }) { "Not an account key" }
        return File(root, account)
    }

    /**
     * Files kept before they were kept per account (an older version of the app kept them loose)
     * belong to whoever is signed in now: they move into [account]'s folder.
     */
    @Synchronized
    fun adopt(account: String) {
        val loose = root.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        if (loose.isEmpty()) return
        val folder = of(account).apply { mkdirs() }
        loose.forEach { file -> if (!file.renameTo(File(folder, file.name))) file.delete() }
    }

    /** [account]'s files waiting, oldest first (their names sort by when they were kept). */
    @Synchronized
    fun files(account: String): List<File> {
        adopt(account)
        return of(account).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
    }

    /** Forgets what [account] has waiting. */
    @Synchronized
    fun clear(account: String) {
        of(account).deleteRecursively()
    }

    /** Forgets everything waiting, every account's. */
    @Synchronized
    fun clearAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }
}

/**
 * Batches the server couldn't take yet (it was down, the phone was offline, a captive portal was in
 * the way), kept on disk under their account and sent again, oldest first, at the next sync. Only
 * the newest [limit] are kept for each account.
 */
class Outbox(private val dir: File, private val limit: Int = 500) {
    private val folders = AccountFolders(dir)

    init {
        dir.mkdirs()
    }

    @Synchronized
    fun add(account: String, batch: IngestBatch) {
        val folder = folders.of(account).apply { mkdirs() }
        folders.adopt(account)
        val name = "${System.currentTimeMillis()}-${System.nanoTime() % 1_000_000}.json"
        File(folder, name).writeText(ApiJson.encodeToString(IngestBatch.serializer(), batch))
        val files = files(account)
        if (files.size > limit) files.take(files.size - limit).forEach { it.delete() }
    }

    @Synchronized
    fun files(account: String): List<File> = folders.files(account)

    /** How many batches [account] has waiting (none when signed out). */
    fun size(account: String?): Int = account?.let { files(it).size } ?: 0

    /** Moves batches kept by an older version of the app under [account] (whoever is signed in). */
    fun adopt(account: String) = folders.adopt(account)

    /**
     * Sends what [account] has waiting with [send], oldest first; a batch goes once it's accepted.
     * Stops at the first one that fails for a reason that may pass ([isTemporary]: offline, the
     * server down, a proxy or portal in the way); one the server itself refuses as wrong is
     * dropped so it can't block the rest. Returns how many were sent.
     */
    suspend fun flush(account: String, send: suspend (String) -> Unit, isTemporary: (Exception) -> Boolean): Int {
        var sent = 0
        for (file in files(account)) {
            val json = attempt { file.readText() }.getOrNull()
            if (json == null) {
                file.delete()
                continue
            }
            try {
                send(json)
                file.delete()
                sent++
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (isTemporary(e)) break
                file.delete()
            }
        }
        return sent
    }

    /** Forgets what [account] has waiting (signing out when the person asks). */
    fun clear(account: String) = folders.clear(account)
}
