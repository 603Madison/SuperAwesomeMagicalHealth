package com.health.app.sync

import org.robolectric.RuntimeEnvironment
import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.IngestBatch
import com.health.api.IngestResult
import com.health.api.Metric
import com.health.api.Sample
import com.health.app.net.ServerException
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

    private val me = "aaaa1111"
    private val someoneElse = "bbbb2222"

    @Test
    fun `big batches go up in parts, and the cursor moves on`() = runBlocking {
        val store = store()
        val sent = ArrayList<Int>()
        val conduit = FakeConduit("ring", listOf(batch(12_000)))
        val manager = SyncManager(listOf(conduit), store, outbox(), upload = { sent += it.samples.size; IngestResult("d", it.samples.size, 0, 0) }, uploadJson = { error("unused") }, account = { me })
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
            account = { me },
        )
        manager.sync()
        assertEquals(1, outbox.size(me))
        assertEquals(1, store.statuses.value.getValue("ring").queued)
        assertTrue(store.statuses.value.getValue("ring").ok)

        online = true
        manager.sync()
        assertEquals(0, outbox.size(me))
        assertEquals(1, received.size)
        assertTrue(received.single().contains("AA:BB"))
    }

    @Test
    fun `a source that fails is reported, and the others still sync`() = runBlocking {
        val store = store()
        val good = FakeConduit("good", listOf(batch(1)))
        val bad = FakeConduit("bad", emptyList(), IllegalStateException("Bluetooth is off"))
        val manager = SyncManager(listOf(bad, good), store, outbox(), upload = { IngestResult("d", 1, 0, 0) }, uploadJson = { error("unused") }, account = { me })
        val result = manager.sync()
        assertEquals("bad: Bluetooth is off", result)
        assertFalse(store.statuses.value.getValue("bad").ok)
        assertTrue(store.statuses.value.getValue("good").ok)
        assertEquals(null, store.cursor("bad"))
    }

    @Test
    fun `a batch the server refuses is dropped from the outbox, not retried forever`() = runBlocking {
        val outbox = outbox()
        outbox.add(me, batch(1))
        outbox.add(me, batch(2))
        val sent = outbox.flush(me, { if (it.contains("\"start\":1000")) throw ServerException(400, "That request isn't right", fromServer = true) }, SyncManager::isTemporary)
        assertEquals(1, sent)
        assertEquals(0, outbox.size(me))
    }

    @Test
    fun `only the server's own refusal drops a batch`() {
        // The server, in its own words: the batch itself is wrong.
        assertFalse(SyncManager.isTemporary(ServerException(400, "That request isn't right", fromServer = true)))
        assertFalse(SyncManager.isTemporary(ServerException(422, "Too many samples", fromServer = true)))
        // Offline, the server down or busy, its sign-in no longer good: try again later.
        listOf(0, 401, 403, 408, 429, 500, 502, 503).forEach { assertTrue("$it", SyncManager.isTemporary(ServerException(it, "x", fromServer = it != 0))) }
        // Something in between: a proxy's 404, 403 or 413, a redirect, a captive portal's page.
        listOf(301, 302, 307, 400, 403, 404, 413, 422).forEach { assertTrue("$it", SyncManager.isTemporary(ServerException(it, "The server answered HTTP $it"))) }
        // A 200 that isn't the server's answer (a captive portal's sign-in page).
        assertTrue(SyncManager.isTemporary(ServerException(200, "The server's answer didn't make sense")))
        // Something unexpected isn't a reason to throw data away either.
        assertTrue(SyncManager.isTemporary(IllegalStateException("odd")))
    }

    @Test
    fun `a captive portal's page keeps the batch waiting, and the rest behind it`() = runBlocking {
        val outbox = outbox()
        outbox.add(me, batch(1))
        outbox.add(me, batch(2))
        val sent = outbox.flush(me, { throw ServerException(200, "The server's answer didn't make sense") }, SyncManager::isTemporary)
        assertEquals(0, sent)
        assertEquals(2, outbox.size(me))
    }

    @Test
    fun `batches wait for their own account, and only go to it`() = runBlocking {
        val outbox = outbox()
        outbox.add(me, batch(1))
        val received = ArrayList<String>()
        assertEquals(0, outbox.flush(someoneElse, { received += it }, SyncManager::isTemporary))
        assertTrue(received.isEmpty())
        assertEquals(1, outbox.size(me))
        assertEquals(0, outbox.size(someoneElse))
        assertEquals(0, outbox.size(null))
        assertEquals(1, outbox.flush(me, { received += it }, SyncManager::isTemporary))
        assertEquals(1, received.size)
    }

    @Test
    fun `batches kept by an older version go to whoever is signed in`() = runBlocking {
        val dir = Files.createTempDirectory("outbox").toFile()
        java.io.File(dir, "1700000000000-1.json").writeText(com.health.api.ApiJson.encodeToString(IngestBatch.serializer(), batch(1)))
        val outbox = Outbox(dir)
        outbox.adopt(me)
        assertEquals(1, outbox.size(me))
        assertEquals(0, outbox.size(someoneElse))
    }

    @Test
    fun `account keys tell accounts apart, and don't change`() {
        val a = com.health.app.data.Accounts.key("https://health.example.com", "u1")
        assertEquals(a, com.health.app.data.Accounts.key("https://Health.example.com/", "u1"))
        assertFalse(a == com.health.app.data.Accounts.key("https://health.example.com", "u2"))
        assertFalse(a == com.health.app.data.Accounts.key("https://other.example.com", "u1"))
        assertTrue(a.matches(Regex("[0-9a-f]{24}")))
    }

    @Test
    fun `chunking keeps sleep and workouts with the first part`() {
        val b = batch(7).copy(sleep = listOf(com.health.api.SleepSession(0, 1)))
        val parts = b.chunked(3)
        assertEquals(listOf(3, 3, 1), parts.map { it.samples.size })
        assertEquals(listOf(1, 0, 0), parts.map { it.sleep.size })
    }
}
