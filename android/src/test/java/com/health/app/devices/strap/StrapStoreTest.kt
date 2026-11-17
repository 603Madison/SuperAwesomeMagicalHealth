package com.health.app.devices.strap

import com.health.api.ApiJson
import com.health.api.DeviceKind
import com.health.api.IngestBatch
import com.health.api.IngestResult
import com.health.api.Metric
import com.health.app.devices.strap.StrapProtocol.Contact
import com.health.app.devices.strap.StrapProtocol.Measurement
import com.health.app.net.ServerException
import com.health.app.sync.Outbox
import com.health.app.sync.SyncManager
import com.health.app.sync.SyncStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** A recording's readings kept on the phone until the server has them, through the same sync (and outbox) as the ring's. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class StrapStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val dir: File = Files.createTempDirectory("strap").toFile()
    private val file = File(dir, "strap.json")
    private val t0 = StrapMaths.windowOf(1_795_384_800_000L)
    private val address = "E4:5F:01:AA:20:7C"
    private val me = "aaaa1111"

    private fun store() = StrapStore(context, file)

    private fun syncStore() = SyncStore(context).also { it.clear() }

    /** A minute of beats from [from], one a second, and the tick after it. */
    private fun StrapStore.minute(from: Long, bpm: Int = 60) {
        for (i in 0 until 60) record(Measurement(bpm, Contact.DETECTED, listOf(60_000.0 / bpm)), from + i * 1000L)
        tick(from + 60_000L)
    }

    @Test
    fun `nothing is recorded without a strap, or before Record`() {
        val store = store()
        assertNull(store.start(t0))
        store.pair(address, "Polar H10 A1B2C3D4")
        store.record(Measurement(60, Contact.DETECTED), t0)
        assertTrue(store.waiting(null).isEmpty())
        assertNotNull(store.start(t0))
    }

    @Test
    fun `readings and the unfinished minute survive the app being closed`() {
        val first = store()
        first.pair(address, "Polar H10 A1B2C3D4")
        first.start(t0)
        first.minute(t0)
        // Half of the next minute, kept on the next tick.
        for (i in 1 until 30) first.record(Measurement(70, Contact.DETECTED), t0 + 60_000L + i * 1000L)
        first.tick(t0 + 90_000L)

        val second = store()
        assertEquals(t0, second.recording.value?.since)
        assertEquals("Polar H10 A1B2C3D4", second.strap.value?.name)
        assertEquals(listOf(60.0), second.waiting(null).map { it.sample.value })
        second.stop()
        assertNull(second.recording.value)
        assertEquals(listOf(60.0, 70.0), second.waiting(null).map { it.sample.value })
    }

    @Test
    fun `a sync sends what's waiting as the strap, and lets it go once the server has it`() = runBlocking {
        val store = store()
        store.pair(address, "Polar H10 A1B2C3D4")
        store.start(t0)
        store.setBattery(84)
        store.minute(t0)
        store.minute(t0 + 60_000L, bpm = 55)
        val syncStore = syncStore()
        val sent = ArrayList<IngestBatch>()
        val manager = SyncManager(
            listOf(StrapConduit(store, syncStore)), syncStore, Outbox(File(dir, "outbox")),
            upload = { sent += it; IngestResult("d", it.samples.size, 0, 0) }, uploadJson = { error("unused") }, account = { me },
        )
        assertEquals("Synced", manager.sync(StrapConduit.ID))
        val batch = sent.single()
        assertEquals(DeviceKind.HEART_RATE_STRAP, batch.device.kind)
        assertEquals(address, batch.device.identifier)
        assertEquals("Polar H10 A1B2C3D4", batch.device.name)
        assertEquals(84, batch.device.battery)
        assertEquals(listOf(t0 to 60.0, t0 + 60_000L to 55.0), batch.samples.map { it.start to it.value })
        assertTrue(batch.samples.all { it.metric == Metric.HEART_RATE })

        // Sent: let go, and nothing to send next time.
        assertTrue(store.waiting(syncStore.cursor(StrapConduit.ID)).isEmpty())
        assertEquals("Up to date", manager.sync(StrapConduit.ID))
        assertEquals(1, sent.size)
    }

    @Test
    fun `when the server can't be reached, the readings wait in the outbox`() = runBlocking {
        val store = store()
        store.pair(address, "HRM-Dual")
        store.start(t0)
        store.minute(t0)
        val syncStore = syncStore()
        val outbox = Outbox(File(dir, "outbox"))
        val manager = SyncManager(
            listOf(StrapConduit(store, syncStore)), syncStore, outbox,
            upload = { throw ServerException(0, "offline") }, uploadJson = { error("unused") }, account = { me },
        )
        manager.sync(StrapConduit.ID)
        assertEquals(1, outbox.size(me))
        assertTrue(store.waiting(syncStore.cursor(StrapConduit.ID)).isEmpty())
        val queued = ApiJson.decodeFromString(IngestBatch.serializer(), outbox.files(me).single().readText())
        assertEquals(DeviceKind.HEART_RATE_STRAP, queued.device.kind)
    }

    @Test
    fun `forgetting the strap keeps what it recorded for the server`() = runBlocking {
        val store = store()
        store.pair(address, "TICKR 1A2B")
        store.start(t0)
        store.minute(t0)
        store.stop()
        store.forget()
        assertNull(store.strap.value)
        val syncStore = syncStore()
        val conduit = StrapConduit(store, syncStore)
        assertTrue(conduit.ready())
        val batch = conduit.collect(null).batches.single()
        assertEquals("TICKR 1A2B", batch.device.name)
        assertEquals(address, batch.device.identifier)
    }

    @Test
    fun `signing out hands what's waiting to the account's outbox, or lets it go when asked`() = runBlocking {
        val kept = store()
        kept.pair(address, "Polar H9")
        kept.start(t0)
        kept.minute(t0)
        kept.stop()
        val outbox = Outbox(File(dir, "outbox"))
        StrapConduit(kept, syncStore()).signedOut(me, keep = true, outbox)
        assertEquals(1, outbox.size(me))
        assertNull(kept.strap.value)
        assertTrue(kept.waiting(null).isEmpty())

        val dropped = StrapStore(context, File(dir, "other.json"))
        dropped.pair(address, "Polar H9")
        dropped.start(t0)
        dropped.minute(t0)
        dropped.stop()
        StrapConduit(dropped, syncStore()).signedOut(me, keep = false, outbox)
        assertEquals(1, outbox.size(me))
        assertTrue(dropped.waiting(null).isEmpty())
        assertFalse(StrapConduit(dropped, syncStore()).collect(null).batches.isNotEmpty())
    }
}
