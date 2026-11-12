package com.fitness.app.sync

import org.robolectric.RuntimeEnvironment
import com.fitness.api.DeviceInfo
import com.fitness.api.DeviceKind
import com.fitness.api.IngestBatch
import com.fitness.api.IngestResult
import com.fitness.api.Metric
import com.fitness.api.Sample
import com.fitness.app.net.ServerException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SyncTest {
    private val device = DeviceInfo(DeviceKind.COLMI_RING, "AA:BB", "R02")

    private fun batch(samples: Int) = IngestBatch(device, samples = (0 until samples).map { Sample(Metric.STEPS, it * 1000L, value = 1.0) })

    private class FakeConduit(override val id: String, val batches: List<IngestBatch>, val fail: Exception? = null) : Conduit {
        override val title = id
        var since: Long? = -1
        override suspend fun ready() = true
        override suspend fun collect(since: Long?): Collected {
            this.since = since
            fail?.let { throw it }
            return Collected(batches, 42)
        }
    }

    private fun store() = SyncStore(RuntimeEnvironment.getApplication()).also { it.clear() }

    private fun outbox() = Outbox(Files.createTempDirectory("outbox").toFile())

    @Test
    fun `big batches go up in parts, and the cursor moves on`() = runBlocking {
        val store = store()
        val sent = ArrayList<Int>()
        val conduit = FakeConduit("ring", listOf(batch(12_000)))
        val manager = SyncManager(listOf(conduit), store, outbox(), upload = { sent += it.samples.size; IngestResult("d", it.samples.size, 0, 0) }, uploadJson = { error("unused") })
        assertEquals("Synced", manager.sync())
        assertEquals(listOf(5_000, 5_000, 2_000), sent)
        assertEquals(42L, store.cursor("ring"))
        assertEquals(12_000, store.statuses.value.getValue("ring").samples)
        assertEquals(null, conduit.since)

        manager.sync()
        assertEquals(42L, conduit.since)
    }

    @Test
    fun `when the server can't be reached, batches wait in the outbox and go next time`() = runBlocking {
        val store = store()
        val outbox = outbox()
        var online = false
        val received = ArrayList<String>()
        val manager = SyncManager(
            listOf(FakeConduit("ring", listOf(batch(3)))), store, outbox,
            upload = { if (!online) throw ServerException(0, "offline") else IngestResult("d", it.samples.size, 0, 0) },
            uploadJson = { received += it; IngestResult("d", 3, 0, 0) },
        )
        manager.sync()
        assertEquals(1, outbox.size)
        assertEquals(1, store.statuses.value.getValue("ring").queued)
        assertTrue(store.statuses.value.getValue("ring").ok)

        online = true
        manager.sync()
        assertEquals(0, outbox.size)
        assertEquals(1, received.size)
        assertTrue(received.single().contains("AA:BB"))
    }

    @Test
    fun `a source that fails is reported, and the others still sync`() = runBlocking {
        val store = store()
        val good = FakeConduit("good", listOf(batch(1)))
        val bad = FakeConduit("bad", emptyList(), IllegalStateException("Bluetooth is off"))
        val manager = SyncManager(listOf(bad, good), store, outbox(), upload = { IngestResult("d", 1, 0, 0) }, uploadJson = { error("unused") })
        val result = manager.sync()
        assertEquals("bad: Bluetooth is off", result)
        assertFalse(store.statuses.value.getValue("bad").ok)
        assertTrue(store.statuses.value.getValue("good").ok)
        assertEquals(null, store.cursor("bad"))
    }

    @Test
    fun `a batch the server refuses is dropped from the outbox, not retried forever`() = runBlocking {
        val outbox = outbox()
        outbox.add(batch(1))
        outbox.add(batch(2))
        val sent = outbox.flush({ if (it.contains("\"start\":1000")) throw ServerException(400, "bad") }, SyncManager::isTemporary)
        assertEquals(1, sent)
        assertEquals(0, outbox.size)
    }

    @Test
    fun `chunking keeps sleep and workouts with the first part`() {
        val b = batch(7).copy(sleep = listOf(com.fitness.api.SleepSession(0, 1)))
        val parts = b.chunked(3)
        assertEquals(listOf(3, 3, 1), parts.map { it.samples.size })
        assertEquals(listOf(1, 0, 0), parts.map { it.sleep.size })
    }
}
