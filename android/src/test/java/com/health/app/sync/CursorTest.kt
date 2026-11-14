package com.health.app.sync

import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.IngestBatch
import com.health.api.IngestResult
import com.health.api.Metric
import com.health.api.Sample
import com.health.app.devices.colmi.ColmiConduit
import com.health.app.devices.healthconnect.HealthConnectCursors
import com.health.app.net.ServerException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files

/** A data source never moves past readings it couldn't read: they're asked for again next time. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class CursorTest {
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test
    fun `each kind of Health Connect record reads from where it got to`() {
        val now = 100 * day
        // The first time, a month back.
        assertEquals(now - 30 * day, HealthConnectCursors.from(part = null, since = null, now = now))
        // Then from the last sync, a little before it (readings can arrive late)…
        assertEquals(now - day - 6 * hour, HealthConnectCursors.from(part = null, since = now - day, now = now))
        // …unless the kind has its own place, which wins.
        assertEquals(now - 3 * day - 6 * hour, HealthConnectCursors.from(part = now - 3 * day, since = now - day, now = now))
    }

    @Test
    fun `a kind that couldn't be read starts from the same place next time`() {
        val first = 100 * day
        val from = HealthConnectCursors.from(part = null, since = first - day, now = first)
        val failed = HealthConnectCursors.after(read = false, from = from, now = first)
        // A day later, the kind that failed still starts where it did; one that was read moves on.
        val next = first + day
        assertEquals(from, HealthConnectCursors.from(part = failed, since = first, now = next))
        assertEquals(first - 6 * hour, HealthConnectCursors.from(part = HealthConnectCursors.after(read = true, from = from, now = first), since = first, now = next))
        // Never read before, and failing the first time: a month back again.
        val never = HealthConnectCursors.from(null, null, first)
        assertEquals(never, HealthConnectCursors.from(HealthConnectCursors.after(false, never, first), first, next))
    }

    @Test
    fun `a ring reading that failed keeps the ring's cursor where it was`() {
        assertEquals(500L, ColmiConduit.cursorAfter(since = 100L, now = 500L, readFailed = false))
        assertEquals(100L, ColmiConduit.cursorAfter(since = 100L, now = 500L, readFailed = true))
        assertNull(ColmiConduit.cursorAfter(since = null, now = 500L, readFailed = true))
    }

    private class PartsConduit(override val id: String, val cursor: Long?, val parts: Map<String, Long>) : Conduit {
        override val title = id
        override suspend fun ready() = true
        override suspend fun collect(since: Long?) =
            Collected(listOf(IngestBatch(DeviceInfo(DeviceKind.HEALTH_CONNECT, "app", "App"), samples = listOf(Sample(Metric.STEPS, 1, value = 1.0)))), cursor, parts = parts)
    }

    private fun store() = SyncStore(RuntimeEnvironment.getApplication()).also { it.clear() }

    private fun manager(store: SyncStore, conduit: Conduit, upload: suspend (IngestBatch) -> IngestResult) =
        SyncManager(listOf(conduit), store, Outbox(Files.createTempDirectory("outbox").toFile()), upload = upload, uploadJson = { error("unused") }, account = { "aaaa1111" })

    @Test
    fun `where each kind got to is kept with the cursor, once the data is in`() = runBlocking {
        val store = store()
        manager(store, PartsConduit("hc", 42, mapOf("steps" to 42L, "sleep" to 7L))) { IngestResult("d", 1, 0, 0) }.sync()
        assertEquals(42L, store.cursor("hc"))
        assertEquals(42L, store.partCursor("hc", "steps"))
        assertEquals(7L, store.partCursor("hc", "sleep"))
        assertNull(store.partCursor("hc", "weight"))
    }

    @Test
    fun `nothing moves when the server refuses the data`() = runBlocking {
        val store = store()
        manager(store, PartsConduit("hc", 42, mapOf("steps" to 42L))) { throw ServerException(400, "That request isn't right", fromServer = true) }.sync()
        assertNull(store.cursor("hc"))
        assertNull(store.partCursor("hc", "steps"))
    }

    @Test
    fun `a source can start again from the beginning`() = runBlocking {
        val store = store()
        store.setCursor("ring", 5L)
        manager(store, PartsConduit("ring", null, emptyMap())) { IngestResult("d", 1, 0, 0) }.sync()
        assertNull(store.cursor("ring"))
    }
}
