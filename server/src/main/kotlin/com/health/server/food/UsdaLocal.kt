package com.health.server.food

import com.health.api.ApiJson
import com.health.api.Food
import com.health.server.Services
import com.health.server.SettingsStore
import com.health.server.db.Database
import com.health.server.util.OutboundDns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Admin › Food search's view of the copy kept on this server (`GET /api/admin/usda-local`). */
@Serializable
data class UsdaLocalStatus(
    /** Whether there's a copy to search. */
    val ready: Boolean,
    /** Whether a download or an update is under way (the copy in use keeps working meanwhile). */
    val working: Boolean = false,
    /** What it's doing now: "Downloading packaged foods". */
    val phase: String? = null,
    /** How far that is, 0 to 1, where known. */
    val progress: Double? = null,
    /** The copy in use: its release (the newest date among its files), whether it has packaged foods, how many foods, and its size on disk. */
    val release: String? = null,
    val packaged: Boolean = false,
    val foods: Int = 0,
    val bytes: Long = 0,
    val lastUpdated: Long? = null,
    val lastChecked: Long? = null,
    val nextCheck: Long? = null,
    /** A newer release found by the last check, by its date. */
    val newer: String? = null,
    /** Why the last download or check failed, in plain words, and when. */
    val error: String? = null,
    val errorAt: Long? = null,
    /** Roughly how much each choice takes: to download, and on disk, in bytes. */
    val everydayDownload: Long = EVERYDAY_DOWNLOAD,
    val everydayDisk: Long = EVERYDAY_DISK,
    val allDownload: Long = ALL_DOWNLOAD,
    val allDisk: Long = ALL_DISK,
) {
    companion object {
        // Measured with the October 2026 release (UsdaLocalLiveTest): 18 MB to download and 3.5 MB on
        // disk for 13,589 everyday foods; 214 MB and 105 MB for 441,676 foods with packaged ones. Disk
        // rounded up, as the free-space check leans on it and releases grow.
        const val EVERYDAY_DOWNLOAD = 18_000_000L
        const val EVERYDAY_DISK = 5_000_000L
        const val ALL_DOWNLOAD = 214_000_000L
        const val ALL_DISK = 120_000_000L
    }
}

/**
 * USDA FoodData Central kept on this server (Admin › Food search, "Keep USDA's food list on this
 * server"): no request leaves the server for a search or a scan, and no key is needed. The admin
 * downloads it (everyday foods, about 18 MB, 4 MB kept; or with packaged foods, about 214 MB, 105 MB kept) from USDA's
 * public-domain downloads, found on [pageUrl] by their names (which carry their release dates). Each
 * file is downloaded to a temporary file in the data folder, read as it streams (never whole into
 * memory) into a new `usda-foods.db` beside the one in use, and swapped in only once it's complete.
 * A check each month looks for a newer release; with automatic updates on, it downloads it too.
 * The downloads carry nothing about anyone; they go through the outbound client (checked
 * addresses, redirects only to USDA's own https address).
 */
class UsdaLocal(
    private val dataDir: File,
    private val db: Database,
    private val settings: SettingsStore,
    http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pageUrl: String = PAGE_URL,
    loopback: () -> Boolean = { false },
    /** Free space in the data folder (tests stand in a small disk). */
    private val freeSpace: () -> Long = { dataDir.usableSpace },
) {
    val local = UsdaLocalDb(File(dataDir, FILE))
    private val tmp = File(dataDir, "usda-download")
    private val client: OkHttpClient = http.newBuilder()
        // Only public addresses (loopback and private ones only for tests, as the test server is this machine).
        .dns(OutboundDns(private = loopback, loopback = loopback))
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var working: Pair<String, Double?>? = null

    /** What the last check found and how the last download went, kept in the main database. */
    @Serializable
    data class Saved(
        val lastChecked: Long? = null,
        val latest: Map<String, String> = emptyMap(),
        val lastUpdated: Long? = null,
        val error: String? = null,
        val errorAt: Long? = null,
    )

    /** One release's files, by kind ("foundation_food", "sr_legacy_food", "survey_food", "branded_food"): their names and addresses. */
    data class Release(val files: Map<String, String>, val urls: Map<String, HttpUrl>) {
        /** Its date: the newest among the files asked for. */
        fun date(kinds: Collection<String>): String? = kinds.mapNotNull { files[it]?.let(::dateOf) }.maxOrNull()
    }

    // ---- What's kept

    suspend fun status(): UsdaLocalStatus {
        val saved = saved()
        val meta = withContext(Dispatchers.IO) { local.meta() }
        val installed = installedFiles(meta)
        val newer = newerDate(installed, saved.latest)
        val now = working
        return UsdaLocalStatus(
            ready = meta.isNotEmpty(),
            working = job?.isActive == true,
            phase = now?.first,
            progress = now?.second,
            release = meta["release"],
            packaged = meta["packaged"] == "true",
            foods = meta["foods"]?.toIntOrNull() ?: 0,
            bytes = if (local.file.isFile) local.file.length() else 0,
            lastUpdated = saved.lastUpdated,
            lastChecked = saved.lastChecked,
            nextCheck = if (settings.get().usdaLocal.use) (saved.lastChecked ?: clock()) + CHECK_EVERY_MS else null,
            newer = newer,
            error = saved.error,
            errorAt = saved.errorAt,
        )
    }

    /** Whether there's a copy to search, and whether it has packaged foods. */
    val ready: Boolean get() = local.ready

    suspend fun packaged(): Boolean = withContext(Dispatchers.IO) { local.packaged }

    suspend fun search(text: String, kind: UsdaKind, limit: Int): List<Food> = withContext(Dispatchers.IO) { local.search(text, kind, limit) }

    suspend fun barcode(code: String): Food? = withContext(Dispatchers.IO) { local.barcode(code) }

    // ---- Downloading

    /** Starts downloading the latest release (with packaged foods or not), unless one is under way; false if one was. */
    fun download(packaged: Boolean): Boolean {
        if (job?.isActive == true) return false
        job = scope.launch { update(packaged) }
        return true
    }

    /** Waits for a download under way (tests, and the monthly check). */
    suspend fun awaitDownload() {
        job?.join()
    }

    /** Stops a download under way and removes the copy. */
    suspend fun remove() {
        job?.let { it.cancel(); it.join() }
        withContext(Dispatchers.IO) {
            local.remove()
            tmp.deleteRecursively()
        }
    }

    /**
     * Downloads the latest release and swaps it in; the copy in use keeps working until then, and
     * stays if anything goes wrong (the reason is kept for Admin). True once swapped in.
     */
    suspend fun update(packaged: Boolean): Boolean = running.withLock {
        try {
            withContext(Dispatchers.IO) { build(packaged) }
            save { it.copy(lastUpdated = clock(), error = null, errorAt = null) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = (e as? Refusal)?.message ?: "The download didn't finish (${e.javaClass.simpleName}${e.message?.let { m -> ": ${m.take(120)}" } ?: ""})"
            log.warn("USDA's food list wasn't updated: {}", why)
            save { it.copy(error = why, errorAt = clock()) }
            false
        } finally {
            working = null
            withContext(Dispatchers.IO) { tmp.deleteRecursively() }
        }
    }

    private suspend fun build(packaged: Boolean) {
        working = "Looking for USDA's latest release" to null
        val release = release()
        val kinds = EVERYDAY_KINDS + (if (packaged) listOf(BRANDED) else emptyList())
        val missing = kinds.filter { it !in release.files }
        if (missing.isNotEmpty()) throw Refusal("USDA's download page doesn't list ${missing.joinToString { LABELS.getValue(it) }} just now; try again later")
        save { it.copy(lastChecked = clock(), latest = release.files) }

        // Room for the largest file and the new copy, beside the one in use.
        val sizes = kinds.associateWith { size(release.urls.getValue(it)) }
        val needed = sizes.values.max() + (if (packaged) UsdaLocalStatus.ALL_DISK else UsdaLocalStatus.EVERYDAY_DISK) + MARGIN
        val free = freeSpace()
        if (free < needed) throw Refusal("Not enough free space in the data folder: it needs about ${mb(needed)}, and ${mb(free)} is free")

        tmp.deleteRecursively()
        tmp.mkdirs()
        val next = File(tmp, "$FILE.new")
        UsdaLocalWriter(next).use { writer ->
            for (kind in kinds) {
                val zip = File(tmp, release.files.getValue(kind))
                fetch(release.urls.getValue(kind), zip, "Downloading ${LABELS.getValue(kind)}", sizes.getValue(kind))
                read(zip, kind, writer, "Adding ${LABELS.getValue(kind)}")
                zip.delete()
            }
            if (writer.count == 0) throw Refusal("USDA's files had no foods in them; the copy in use is kept")
            working = "Finishing up" to null
            writer.finish(
                mapOf(
                    "release" to release.date(kinds).orEmpty(),
                    "files" to kinds.joinToString(",") { "$it=${release.files.getValue(it)}" },
                    "packaged" to packaged.toString(),
                    "builtAt" to clock().toString(),
                ),
            )
        }
        currentCoroutineContext().ensureActive()
        local.swap(next)
        log.info("USDA's food list is on this server: release {}, {} foods", release.date(kinds), local.meta()["foods"])
    }

    /** Streams [zip]'s JSON (one big object with one list of foods) into [writer], food by food. */
    private suspend fun read(zip: File, kind: String, writer: UsdaLocalWriter, phase: String) {
        val total = zip.length().coerceAtLeast(1)
        val counted = Counting(zip.inputStream().buffered(1 shl 16))
        ZipInputStream(counted).use { z ->
            val entry = generateSequence { z.nextEntry }.firstOrNull { it.name.endsWith(".json", ignoreCase = true) && !it.isDirectory }
                ?: throw Refusal("USDA's ${LABELS.getValue(kind)} file isn't what it used to be (no JSON in it)")
            val foods = foodsIn(z)
            val usdaKind = if (kind == BRANDED) UsdaKind.PACKAGED else UsdaKind.EVERYDAY
            var n = 0
            for (item in foods) {
                val id = (item["fdcId"] as? JsonPrimitive)?.longOrNull ?: continue
                val type = (item["dataType"] as? JsonPrimitive)?.contentOrNull ?: continue
                UsdaFoods.fromBulk(item)?.let { writer.add(id, usdaKind, type, it) }
                if (++n % 2_000 == 0) {
                    currentCoroutineContext().ensureActive()
                    working = phase to (counted.count.toDouble() / total).coerceAtMost(1.0)
                }
            }
        }
    }

    /** The foods in one of USDA's files, one at a time: the list inside its wrapping object (`{"BrandedFoods": [ … ]}`). */
    @OptIn(ExperimentalSerializationApi::class)
    private fun foodsIn(input: InputStream): Sequence<JsonObject> {
        while (true) {
            val b = input.read()
            if (b < 0) throw Refusal("USDA's file has no list of foods in it")
            if (b == '['.code) break
        }
        return ApiJson.decodeToSequence(SequenceInputStream(ByteArrayInputStream(byteArrayOf('['.code.toByte())), WithoutClosingBrace(input)), JsonObject.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
    }

    /** Downloads [url] to [to], counting toward [phase]. */
    private suspend fun fetch(url: HttpUrl, to: File, phase: String, expected: Long) {
        working = phase to 0.0
        open(url, "GET").use { response ->
            val body = response.body ?: throw IOException("No file in USDA's answer")
            val total = body.contentLength().takeIf { it > 0 } ?: expected
            body.byteStream().use { input ->
                to.outputStream().buffered(1 shl 16).use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    var ticks = 0
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (++ticks % 64 == 0) {
                            currentCoroutineContext().ensureActive()
                            if (total > 0) working = phase to (done.toDouble() / total).coerceAtMost(1.0)
                        }
                    }
                    if (total > 0 && done < total) throw IOException("The download stopped part way ($done of $total bytes)")
                }
            }
        }
    }

    /** A file's size, from its headers. */
    private fun size(url: HttpUrl): Long = open(url, "HEAD").use { it.header("Content-Length")?.toLongOrNull() ?: 0L }

    /**
     * The latest release of each kind, from USDA's download page: the JSON files named
     * `FoodData_Central_<kind>_json_<date>.zip`, the newest date of each kind.
     */
    suspend fun release(): Release = withContext(Dispatchers.IO) {
        val page = pageUrl.toHttpUrl()
        val html = open(page, "GET").use { it.body?.string().orEmpty() }
        val found = LINK.findAll(html).mapNotNull { m ->
            val url = page.resolve(m.groupValues[1]) ?: return@mapNotNull null
            Triple(m.groupValues[3], m.groupValues[2], url)
        }.groupBy { it.first }.mapValues { (_, files) -> files.maxBy { dateOf(it.second) } }
        Release(found.mapValues { it.value.second }, found.mapValues { it.value.third })
    }

    /**
     * [url], following a redirect only to USDA's own address over https (or, in tests, the test
     * server's): never anywhere else, and never from https down to http.
     */
    private fun open(url: HttpUrl, method: String): Response {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val response = client.newCall(
                Request.Builder().url(current).method(method, null)
                    .header("User-Agent", "SuperAwesomeMagicalHealth/${Services.version} (self-hosted server)")
                    .build(),
            ).execute()
            if (!response.isRedirect) {
                if (!response.isSuccessful) {
                    response.close()
                    throw IOException("USDA answered HTTP ${response.code} for ${current.encodedPath}")
                }
                return response
            }
            val next = response.header("Location")?.let { current.resolve(it) }
            response.close()
            if (next == null || next.host != url.host || (next.scheme != "https" && next.scheme != url.scheme)) {
                throw Refusal("USDA's download moved somewhere this server doesn't follow")
            }
            current = next
        }
        throw Refusal("USDA's download kept moving; try again later")
    }

    // ---- The monthly check

    /** Runs [tick] each hour, from start-up. */
    fun start(): Job = scope.launch {
        tmp.deleteRecursively()
        while (isActive) {
            try {
                tick(clock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Checking for USDA's latest food list failed: {}", e.message)
            }
            delay(3_600_000)
        }
    }

    fun stop() = scope.cancel()

    /**
     * Once a month (while the copy on this server is chosen): looks for a newer release, and with
     * automatic updates on, downloads it and swaps it in (the same free-space check first). If that
     * fails, the copy in use stays and the reason shows in Admin; the next check tries again.
     * Returns whether a newer release was swapped in.
     */
    suspend fun tick(now: Long): Boolean {
        val choice = settings.get().usdaLocal
        if (!choice.use || !settings.get().usdaFoods) return false
        val saved = saved()
        if (saved.lastChecked != null && now - saved.lastChecked < CHECK_EVERY_MS) return false
        return check(now, update = choice.autoUpdate)
    }

    /** Looks for a newer release now; with [update], downloads it when there is one. */
    suspend fun check(now: Long, update: Boolean = false): Boolean {
        val release = try {
            release()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            save { it.copy(lastChecked = now, error = "Couldn't reach USDA's download page to check for a newer release", errorAt = now) }
            return false
        }
        save { it.copy(lastChecked = now, latest = release.files) }
        val meta = withContext(Dispatchers.IO) { local.meta() }
        if (!update || meta.isEmpty() || newerDate(installedFiles(meta), release.files) == null || job?.isActive == true) return false
        return update(meta["packaged"] == "true")
    }

    // ---- Kept state

    private suspend fun saved(): Saved = db.tx {
        one("SELECT value FROM settings WHERE key = ?", KEY) { it.string("value") }
    }?.let { runCatching { ApiJson.decodeFromString(Saved.serializer(), it) }.getOrNull() } ?: Saved()

    private suspend fun save(change: (Saved) -> Saved) {
        val next = change(saved())
        db.tx { update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", KEY, ApiJson.encodeToString(Saved.serializer(), next)) }
    }

    /** A reason meant for the admin, as it is. */
    private class Refusal(message: String) : IOException(message)

    /** Counts what's read, for progress. */
    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    /**
     * [input] without its last closing brace (and the space after it): what's left of
     * `{"Foods": [ … ]}` once its start is read is a list on its own. Holds back a few bytes until
     * the end is known.
     */
    private class WithoutClosingBrace(private val input: InputStream) : InputStream() {
        private var held = ByteArray(0)
        private var out = ByteArray(0)
        private var at = 0
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (at >= out.size) {
                if (ended) return -1
                val chunk = ByteArray(1 shl 16)
                val n = input.read(chunk)
                val all = held + (if (n > 0) chunk.copyOf(n) else ByteArray(0))
                if (n < 0) {
                    ended = true
                    var end = all.size
                    while (end > 0 && all[end - 1].toInt().toChar().isWhitespace()) end--
                    if (end > 0 && all[end - 1] == '}'.code.toByte()) end--
                    out = all.copyOf(end)
                    held = ByteArray(0)
                } else {
                    val keep = minOf(KEEP, all.size)
                    out = all.copyOf(all.size - keep)
                    held = all.copyOfRange(all.size - keep, all.size)
                }
                at = 0
            }
            val n = minOf(len, out.size - at)
            System.arraycopy(out, at, b, off, n)
            at += n
            return n
        }

        override fun close() = input.close()

        private companion object {
            const val KEEP = 64
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger("samh.food")

        /** USDA's download page, with its trailing slash (without one, it redirects to plain http). */
        const val PAGE_URL = "https://fdc.nal.usda.gov/download-datasets/"
        const val FILE = "usda-foods.db"
        private const val KEY = "usda.local"
        const val CHECK_EVERY_MS = 30L * 24 * 3600 * 1000
        private const val MARGIN = 64L * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        const val BRANDED = "branded_food"
        val EVERYDAY_KINDS = listOf("foundation_food", "sr_legacy_food", "survey_food")
        private val LABELS = mapOf(
            "foundation_food" to "Foundation foods",
            "sr_legacy_food" to "SR Legacy foods",
            "survey_food" to "survey foods",
            BRANDED to "packaged foods",
        )
        private val LINK = Regex("""href="([^"]*/(FoodData_Central_(foundation_food|sr_legacy_food|survey_food|branded_food)_json_(\d{4}-\d{2}(?:-\d{2})?)\.zip))"""")
        private val DATE = Regex("""_json_(\d{4}-\d{2}(?:-\d{2})?)\.zip$""")

        /** "2026-10-29" from "FoodData_Central_branded_food_json_2026-10-29.zip". */
        fun dateOf(file: String): String = DATE.find(file)?.groupValues?.get(1).orEmpty()

        /** The files the copy in use was made from, by kind. */
        private fun installedFiles(meta: Map<String, String>): Map<String, String> =
            meta["files"].orEmpty().split(',').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

        /** The newest date among [latest]'s files that are newer than what's [installed] (for the kinds installed), or null. */
        fun newerDate(installed: Map<String, String>, latest: Map<String, String>): String? =
            installed.keys.mapNotNull { kind -> latest[kind]?.takeIf { dateOf(it) > dateOf(installed.getValue(kind)) }?.let(::dateOf) }.maxOrNull()

        private fun mb(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
    }
}
