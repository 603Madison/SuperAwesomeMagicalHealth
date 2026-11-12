package com.health.app.devices.vitals

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.edit
import com.health.api.BloodPressure
import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.GlucoseContext
import com.health.api.GlucoseReading
import com.health.api.IngestBatch
import com.health.app.devices.ble.BleException
import com.health.app.devices.ble.GattConnection
import com.health.app.devices.colmi.ColmiConduit
import com.health.app.sync.Collected
import com.health.app.sync.Conduit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

enum class MonitorKind(val label: String, val device: DeviceKind) {
    BLOOD_PRESSURE("Blood pressure monitor", DeviceKind.BP_MONITOR),
    GLUCOSE("Glucose meter", DeviceKind.GLUCOSE_METER),
}

/** A monitor paired with this phone; a meter remembers the last record it sent. */
data class PairedMonitor(val kind: MonitorKind, val address: String, val name: String, val lastSequence: Int? = null)

/** The blood pressure monitor and glucose meter paired with this phone (one of each). */
class MonitorStore(context: Context) {
    private val prefs = context.getSharedPreferences("vitals-monitors", Context.MODE_PRIVATE)
    private val _monitors = MutableStateFlow(read())
    val monitors: StateFlow<List<PairedMonitor>> = _monitors.asStateFlow()

    fun of(kind: MonitorKind): PairedMonitor? = _monitors.value.firstOrNull { it.kind == kind }

    fun pair(kind: MonitorKind, address: String, name: String) {
        prefs.edit {
            putString("${kind.name}.address", address)
            putString("${kind.name}.name", name.ifBlank { kind.label })
            remove("${kind.name}.sequence")
        }
        _monitors.value = read()
    }

    fun forget(kind: MonitorKind) {
        prefs.edit { remove("${kind.name}.address"); remove("${kind.name}.name"); remove("${kind.name}.sequence") }
        _monitors.value = read()
    }

    fun setSequence(kind: MonitorKind, sequence: Int) {
        prefs.edit { putInt("${kind.name}.sequence", sequence) }
        _monitors.value = read()
    }

    fun clear() {
        prefs.edit { clear() }
        _monitors.value = emptyList()
    }

    private fun read(): List<PairedMonitor> = MonitorKind.entries.mapNotNull { kind ->
        val address = prefs.getString("${kind.name}.address", null) ?: return@mapNotNull null
        PairedMonitor(
            kind, address, prefs.getString("${kind.name}.name", null) ?: kind.label,
            prefs.getInt("${kind.name}.sequence", -1).takeIf { it >= 0 },
        )
    }
}

/** A monitor found nearby, by the profile it advertises. */
data class FoundMonitor(val address: String, val name: String, val kind: MonitorKind, val rssi: Int)

object MonitorScanner {
    @SuppressLint("MissingPermission")
    fun scan(context: Context): Flow<FoundMonitor> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(BleException(if (adapter == null) "This phone has no Bluetooth" else "Bluetooth is off"))
            return@callbackFlow
        }
        val bp = ParcelUuid(VitalsProtocol.BP_SERVICE)
        val glucose = ParcelUuid(VitalsProtocol.GLUCOSE_SERVICE)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val services = result.scanRecord?.serviceUuids.orEmpty()
                val kind = when {
                    bp in services -> MonitorKind.BLOOD_PRESSURE
                    glucose in services -> MonitorKind.GLUCOSE
                    else -> return
                }
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                trySend(FoundMonitor(result.device.address, name?.ifBlank { null } ?: kind.label, kind, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException("Couldn't look for monitors ($errorCode)"))
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}

/**
 * Blood pressure monitors and glucose meters that speak the standard Bluetooth profiles. A
 * monitor is only awake for a little while after a reading, so a sync that can't reach one just
 * says so; the readings come over the next time it's in reach (most keep a memory).
 */
class VitalsConduit(
    private val context: Context,
    private val store: MonitorStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
) : Conduit {
    override val id = ID
    override val title = "Blood pressure & glucose"

    override suspend fun ready(): Boolean = store.monitors.value.isNotEmpty() && ColmiConduit.hasPermission(context)

    override suspend fun collect(since: Long?): Collected {
        val now = clock()
        val batches = ArrayList<IngestBatch>()
        val notes = ArrayList<String>()
        for (monitor in store.monitors.value) {
            try {
                val gatt = withTimeoutOrNull(CONNECT_MS) { GattConnection.connect(context, monitor.address) }
                if (gatt == null) {
                    notes += "${monitor.name} wasn't in reach"
                    continue
                }
                try {
                    batches += when (monitor.kind) {
                        MonitorKind.BLOOD_PRESSURE -> IngestBatch(device(monitor), bloodPressure = readBloodPressure(gatt))
                        MonitorKind.GLUCOSE -> IngestBatch(device(monitor), glucose = readGlucose(gatt, monitor))
                    }
                } finally {
                    gatt.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reading ${monitor.name} failed", e)
                notes += "${monitor.name} wasn't in reach"
            }
        }
        return Collected(batches.filter { it.bloodPressure.isNotEmpty() || it.glucose.isNotEmpty() }, now, notes.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    private fun device(m: PairedMonitor) = DeviceInfo(m.kind.device, m.address, m.name, model = m.name)

    /** A monitor sends the readings in its memory as soon as someone listens, then goes quiet. */
    private suspend fun readBloodPressure(gatt: GattConnection): List<BloodPressure> {
        if (!gatt.hasService(VitalsProtocol.BP_SERVICE)) throw BleException("That isn't a blood pressure monitor")
        gatt.enableNotifications(VitalsProtocol.BP_SERVICE, VitalsProtocol.BP_MEASUREMENT, indicate = true)
        val now = clock()
        val readings = ArrayList<BloodPressure>()
        withTimeoutOrNull(TOTAL_MS) {
            while (true) {
                val n = withTimeoutOrNull(QUIET_MS) { gatt.notifications.receiveCatching().getOrNull() } ?: break
                if (n.characteristic != VitalsProtocol.BP_MEASUREMENT) continue
                VitalsProtocol.bloodPressure(n.value)?.let { r ->
                    readings += BloodPressure(VitalsProtocol.epoch(r.time, zone(), now), r.systolic, r.diastolic, r.pulse)
                }
            }
        }
        return readings.distinctBy { it.at }
    }

    /** A meter is asked for the records it hasn't sent before; it answers with them, then "done". */
    private suspend fun readGlucose(gatt: GattConnection, monitor: PairedMonitor): List<GlucoseReading> {
        if (!gatt.hasService(VitalsProtocol.GLUCOSE_SERVICE)) throw BleException("That isn't a glucose meter")
        gatt.enableNotifications(VitalsProtocol.GLUCOSE_SERVICE, VitalsProtocol.GLUCOSE_MEASUREMENT)
        runCatching { gatt.enableNotifications(VitalsProtocol.GLUCOSE_SERVICE, VitalsProtocol.GLUCOSE_CONTEXT) }
        gatt.enableNotifications(VitalsProtocol.GLUCOSE_SERVICE, VitalsProtocol.RACP, indicate = true)
        gatt.write(VitalsProtocol.GLUCOSE_SERVICE, VitalsProtocol.RACP, VitalsProtocol.reportRecords(monitor.lastSequence))
        val now = clock()
        val records = LinkedHashMap<Int, VitalsProtocol.GlucoseRecord>()
        val contexts = HashMap<Int, GlucoseContext?>()
        withTimeoutOrNull(TOTAL_MS) {
            while (true) {
                val n = gatt.notifications.receiveCatching().getOrNull() ?: break
                when (n.characteristic) {
                    VitalsProtocol.GLUCOSE_MEASUREMENT -> VitalsProtocol.glucose(n.value)?.let { records[it.sequence] = it }
                    VitalsProtocol.GLUCOSE_CONTEXT -> VitalsProtocol.glucoseContext(n.value)?.let { (seq, ctx) -> contexts[seq] = ctx }
                    VitalsProtocol.RACP -> if (VitalsProtocol.racpDone(n.value) != null) break
                }
            }
        }
        records.keys.maxOrNull()?.let { store.setSequence(MonitorKind.GLUCOSE, maxOf(it, monitor.lastSequence ?: -1)) }
        return records.values.mapNotNull { r ->
            r.mmolL?.let { GlucoseReading(VitalsProtocol.epoch(r.time, zone(), now), Math.round(it * 10) / 10.0, contexts[r.sequence] ?: GlucoseContext.OTHER) }
        }
    }

    companion object {
        const val ID = "vitals_monitors"
        private const val TAG = "VitalsConduit"
        private const val CONNECT_MS = 30_000L
        private const val TOTAL_MS = 40_000L
        private const val QUIET_MS = 6_000L
    }
}
