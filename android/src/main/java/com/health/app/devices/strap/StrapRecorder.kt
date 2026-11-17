package com.health.app.devices.strap

import android.content.Context
import android.util.Log
import com.health.app.attempt
import com.health.app.devices.ble.BleException
import com.health.app.devices.ble.GattConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the strap is doing right now, for the Devices page and the notification. */
data class StrapLive(
    val connected: Boolean = false,
    /** The latest heart rate, while the strap is touching skin. */
    val bpm: Int? = null,
    /** False when the strap says it isn't touching skin. */
    val touching: Boolean = true,
    /** Why it isn't connected, in words for people ("Bluetooth is off"), when there's a reason. */
    val problem: String? = null,
)

/**
 * Records the paired strap: its own Bluetooth connection (beside the ring's, which comes and goes
 * with each sync), each measurement handed to [StrapStore], which keeps the readings. When the
 * strap goes (out of reach, taken off, its battery), it connects again by itself, waiting a little
 * longer each time, and what it gathered is kept. While recording, what's waiting is sent with
 * [upload] about every 15 minutes, and once more when it stops. [StrapService] keeps the app
 * running meanwhile, screen off and all.
 */
class StrapRecorder(
    private val context: Context,
    private val store: StrapStore,
    private val scope: CoroutineScope,
    private val upload: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _live = MutableStateFlow(StrapLive())
    val live: StateFlow<StrapLive> = _live.asStateFlow()

    private var job: Job? = null

    /** Whether it's recording in this run of the app (the store may remember a recording the app was closed during). */
    val running: Boolean get() = job?.isActive == true

    /** Starts recording the paired strap, or carries on with the recording the app was closed during. False without a strap. */
    @Synchronized
    fun start(): Boolean {
        if (job?.isActive == true) return true
        val recording = store.start(clock()) ?: return false
        job = scope.launch { record(recording.address) }
        return true
    }

    /** Stops recording: what it gathered is kept and (unless not [send]: signing out) sent. */
    @Synchronized
    fun stop(send: Boolean = true) {
        job?.cancel()
        job = null
        val recorded = store.recording.value != null
        store.stop()
        _live.value = StrapLive()
        if (send && recorded) scope.launch { attempt { upload() }.onFailure { Log.w(TAG, "Couldn't send the strap's readings", it) } }
    }

    private suspend fun record(address: String) = coroutineScope {
        launch {
            while (true) {
                delay(TICK_MS)
                store.tick(clock())
            }
        }
        launch {
            while (true) {
                delay(UPLOAD_MS)
                attempt { upload() }.onFailure { Log.w(TAG, "Couldn't send the strap's readings", it) }
            }
        }
        var wait = FIRST_RETRY_MS
        while (true) {
            try {
                val gatt = GattConnection.connect(context, address, noun = "strap")
                try {
                    _live.value = StrapLive(connected = true)
                    StrapProtocol.battery(attempt { gatt.read(StrapProtocol.BATTERY_SERVICE, StrapProtocol.BATTERY_LEVEL) }.getOrNull())?.let(store::setBattery)
                    if (!gatt.hasService(StrapProtocol.HEART_RATE_SERVICE)) throw BleException("That doesn't seem to be a heart rate strap")
                    gatt.enableNotifications(StrapProtocol.HEART_RATE_SERVICE, StrapProtocol.HEART_RATE_MEASUREMENT)
                    while (true) {
                        // Null when the strap has been quiet a while, or has gone (the channel closes): connect again.
                        val n = withTimeoutOrNull(QUIET_MS) { gatt.notifications.receiveCatching().getOrNull() } ?: break
                        if (n.characteristic != StrapProtocol.HEART_RATE_MEASUREMENT) continue
                        val m = StrapProtocol.measurement(n.value) ?: continue
                        // Heard from: the next time it goes, try again soon.
                        wait = FIRST_RETRY_MS
                        store.record(m, clock())
                        _live.value = StrapLive(connected = true, bpm = m.bpm.takeIf { m.worn && it > 0 }, touching = m.worn)
                    }
                } finally {
                    gatt.close()
                }
                _live.value = StrapLive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The strap's connection ended: ${e.javaClass.simpleName}")
                _live.value = StrapLive(problem = (e as? BleException)?.message)
            }
            store.gap()
            delay(wait)
            wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    companion object {
        private const val TAG = "StrapRecorder"

        /** How often finished minutes and windows are let out when the strap is quiet. */
        private const val TICK_MS = 60_000L

        /** How often what's waiting is sent while recording. */
        const val UPLOAD_MS = 15 * 60_000L

        /** A strap sends about every second: this long without a word, it has gone. */
        private const val QUIET_MS = 30_000L

        /** Waits between tries to connect again: from this, doubling, up to [MAX_RETRY_MS]. */
        private const val FIRST_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 60_000L
    }
}
