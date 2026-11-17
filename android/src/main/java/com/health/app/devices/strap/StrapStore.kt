package com.health.app.devices.strap

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.health.api.ApiJson
import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.Sample
import com.health.app.attempt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** The heart rate strap chosen on this phone. */
@Serializable
data class PairedStrap(val address: String, val name: String, val battery: Int? = null)

/** A recording under way: of which strap, since when. */
@Serializable
data class StrapRecording(val address: String, val since: Long)

/** A reading waiting for the server, numbered in the order it was made ([seq] is the strap's sync cursor). */
@Serializable
data class PendingSample(val seq: Long, val address: String, val sample: Sample)

/** What the phone keeps while recording, and until the server has it. */
@Serializable
data class StrapPending(
    val nextSeq: Long = 1,
    val samples: List<PendingSample> = emptyList(),
    /** Each strap the waiting readings came from, as the server is told about it. */
    val devices: Map<String, DeviceInfo> = emptyMap(),
    val aggregate: StrapAggregate = StrapAggregate(),
    val recording: StrapRecording? = null,
)

/**
 * The strap the person chose (in the app's settings), and what a recording has gathered: kept in a
 * file, so readings survive the app being closed or the phone restarting, until they're with the
 * server (or in the outbox). Each reading is numbered; the strap's sync cursor is the last number
 * sent, and readings up to it are let go the next time it's asked for what's waiting.
 */
class StrapStore(context: Context, private val file: File = File(context.filesDir, "heart-rate-strap.json")) {
    private val prefs = context.getSharedPreferences("heart-rate-strap", Context.MODE_PRIVATE)

    private val _strap = MutableStateFlow(readStrap())
    val strap: StateFlow<PairedStrap?> = _strap.asStateFlow()

    private var pending: StrapPending = read()
    private val aggregator = StrapAggregator(pending.aggregate)

    private val _recording = MutableStateFlow(pending.recording)

    /** The recording under way, or null. */
    val recording: StateFlow<StrapRecording?> = _recording.asStateFlow()

    @Synchronized
    fun pair(address: String, name: String) {
        val strap = PairedStrap(address, name.ifBlank { DEFAULT_NAME })
        prefs.edit { putString(KEY_STRAP, ApiJson.encodeToString(PairedStrap.serializer(), strap)) }
        _strap.value = strap
    }

    /** Forgets the strap; what it recorded still goes to the server. */
    @Synchronized
    fun forget() {
        prefs.edit { remove(KEY_STRAP) }
        _strap.value = null
    }

    @Synchronized
    fun setBattery(level: Int) {
        val strap = _strap.value ?: return
        if (strap.battery == level) return
        val next = strap.copy(battery = level)
        prefs.edit { putString(KEY_STRAP, ApiJson.encodeToString(PairedStrap.serializer(), next)) }
        _strap.value = next
        pending.devices[strap.address]?.let { pending = pending.copy(devices = pending.devices + (strap.address to it.copy(battery = level))) }
    }

    /** Starts recording the paired strap at [now]; a recording already under way carries on. */
    @Synchronized
    fun start(now: Long): StrapRecording? {
        pending.recording?.let { return it }
        val strap = _strap.value ?: return null
        val recording = StrapRecording(strap.address, now)
        pending = pending.copy(recording = recording, aggregate = StrapAggregate())
        aggregator.state = StrapAggregate()
        save()
        _recording.value = recording
        return recording
    }

    /** A measurement from the strap being recorded, at [at]: finished readings are kept (and saved). */
    @Synchronized
    fun record(m: StrapProtocol.Measurement, at: Long) {
        val recording = pending.recording ?: return
        keep(recording.address, aggregator.add(m, at))
    }

    /** Lets readings whose minute or window is over by [now] out, when the strap has gone quiet. */
    @Synchronized
    fun tick(now: Long) {
        val recording = pending.recording ?: return
        keep(recording.address, aggregator.close(now), always = true)
    }

    /** The connection dropped. */
    @Synchronized
    fun gap() {
        if (pending.recording == null) return
        aggregator.gap()
        pending = pending.copy(aggregate = aggregator.state)
        save()
    }

    /** Stops recording: the last minute and window are kept too. */
    @Synchronized
    fun stop() {
        val recording = pending.recording ?: return
        val last = aggregator.finish()
        pending = pending.copy(recording = null)
        keep(recording.address, last, always = true)
        _recording.value = null
    }

    /** The readings waiting after [sent] (the strap's sync cursor), oldest first; those up to it are let go. */
    @Synchronized
    fun waiting(sent: Long?): List<PendingSample> {
        if (sent != null && pending.samples.any { it.seq <= sent }) {
            val left = pending.samples.filter { it.seq > sent }
            val addresses = left.map { it.address }.toSet() + listOfNotNull(pending.recording?.address)
            pending = pending.copy(samples = left, devices = pending.devices.filterKeys { it in addresses })
            save()
        }
        return pending.samples
    }

    /** The strap [address] as the server is told about it. */
    @Synchronized
    fun device(address: String): DeviceInfo = pending.devices[address] ?: deviceInfo(address)

    /** Forgets everything: the strap, any recording, and what's waiting (signing out). */
    @Synchronized
    fun clear() {
        prefs.edit { clear() }
        pending = StrapPending(nextSeq = pending.nextSeq)
        aggregator.state = StrapAggregate()
        save()
        _strap.value = null
        _recording.value = null
    }

    private fun keep(address: String, samples: List<Sample>, always: Boolean = false) {
        if (samples.isEmpty() && !always) {
            pending = pending.copy(aggregate = aggregator.state)
            return
        }
        var seq = pending.nextSeq
        val added = samples.map { PendingSample(seq++, address, it) }
        val all = (pending.samples + added).takeLast(MAX_WAITING)
        val devices = if (address in pending.devices) pending.devices else pending.devices + (address to deviceInfo(address))
        pending = pending.copy(nextSeq = seq, samples = all, devices = devices, aggregate = aggregator.state)
        save()
    }

    private fun deviceInfo(address: String): DeviceInfo {
        val strap = _strap.value?.takeIf { it.address == address }
        val name = strap?.name ?: DEFAULT_NAME
        return DeviceInfo(DeviceKind.HEART_RATE_STRAP, address, name, model = name, battery = strap?.battery)
    }

    /** Written whole to a file beside it, then moved over it: a crash mid-write can't leave half a file. */
    private fun save() {
        attempt {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(ApiJson.encodeToString(StrapPending.serializer(), pending))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Couldn't keep the strap's readings", it) }
    }

    private fun read(): StrapPending =
        attempt { file.takeIf { it.exists() }?.readText()?.let { ApiJson.decodeFromString(StrapPending.serializer(), it) } }.getOrNull() ?: StrapPending()

    private fun readStrap(): PairedStrap? =
        prefs.getString(KEY_STRAP, null)?.let { attempt { ApiJson.decodeFromString(PairedStrap.serializer(), it) }.getOrNull() }

    companion object {
        private const val TAG = "StrapStore"
        private const val KEY_STRAP = "strap"
        const val DEFAULT_NAME = "Heart rate strap"

        /** At most this many readings wait (about six weeks of nights): the oldest go first. */
        const val MAX_WAITING = 30_000
    }
}
