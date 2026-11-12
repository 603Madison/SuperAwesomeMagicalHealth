package com.health.app.devices.colmi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.IngestBatch
import com.health.api.Sample
import com.health.api.SleepSession
import com.health.app.devices.ble.GattConnection
import com.health.app.sync.Collected
import com.health.app.sync.Conduit
import com.health.app.sync.PairedRing
import com.health.app.sync.SyncStore
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A Colmi (or other QRing) ring, synced straight over Bluetooth: the app is the ring's companion.
 * Each sync sets the ring's clock, reads its battery, then the days since the last sync (up to a
 * week, which is about what a ring keeps): heart rate, activity, and sleep.
 */
class ColmiConduit(
    private val context: Context,
    private val store: SyncStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
) : Conduit {
    override val id = ID
    override val title = "Smart ring"

    override suspend fun ready(): Boolean = store.ring.value != null && hasPermission(context)

    override suspend fun collect(since: Long?): Collected {
        val ring = store.ring.value ?: return Collected(emptyList(), clock())
        val now = clock()
        val zone = zone()
        val today = LocalDate.now(zone)
        // From the day of the last sync (it may have been partway through), at most a week back.
        val from = since?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?.coerceAtLeast(today.minusDays(MAX_DAYS - 1))
            ?: today.minusDays(MAX_DAYS - 1)
        val days = ChronoUnit.DAYS.between(from, today).toInt()

        val gatt = GattConnection.connect(context, ring.address)
        val samples = ArrayList<Sample>()
        val sleep = ArrayList<SleepSession>()
        val problems = ArrayList<String>()
        var battery: Int? = ring.battery
        var firmware: String? = ring.firmware
        try {
            val client = ColmiRing(gatt)
            client.start()
            attempt(problems, "clock") { client.setTime(LocalDateTime.now(zone)) }
            attempt(problems, "battery") { client.battery()?.let { battery = it.level } }
            if (firmware == null) attempt(problems, "firmware") { firmware = client.firmware() }
            if (since == null) attempt(problems, "heart-rate setting") { client.enableHeartRateLog(5) }
            for (offset in days downTo 0) {
                val date = today.minusDays(offset.toLong())
                attempt(problems, "heart rate") {
                    client.heartRates(date)?.let { samples += ColmiMapping.heartRates(date, it, zone, now) }
                }
                attempt(problems, "activity") {
                    samples += ColmiMapping.activity(client.steps(offset), zone)
                }
            }
            attempt(problems, "sleep") {
                client.sleep()?.let { reply ->
                    val earliest = from.atStartOfDay(zone).toInstant().toEpochMilli() - 12 * 3_600_000L
                    sleep += ColmiMapping.sleep(ColmiSleep.parse(reply, today), zone).filter { it.end > earliest }
                }
            }
        } finally {
            gatt.close()
        }
        store.setRing(ring.copy(battery = battery, firmware = firmware))
        val batch = IngestBatch(
            device = DeviceInfo(
                kind = DeviceKind.COLMI_RING,
                identifier = ring.address,
                name = ring.name,
                model = ring.name,
                firmware = firmware,
                battery = battery,
            ),
            samples = samples,
            sleep = sleep,
        )
        val note = if (problems.isEmpty()) null else "Couldn't read the ring's ${problems.distinct().joinToString(", ")} this time"
        return Collected(listOf(batch), now, note)
    }

    /** Runs one part of a sync; a ring that doesn't do it (or hiccups) shouldn't stop the rest. */
    private suspend fun attempt(problems: MutableList<String>, what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ColmiConduit", "Reading $what failed", e)
            problems += what
            if (e is com.health.app.devices.ble.BleException && e.message?.contains("disconnected") == true) throw e
        }
    }

    fun pair(address: String, name: String) {
        store.setRing(PairedRing(address, name.ifBlank { "Smart ring" }))
        store.setCursor(ID, null)
        store.clearStatus(ID)
    }

    fun forget() {
        store.setRing(null)
        store.setCursor(ID, null)
        store.clearStatus(ID)
    }

    companion object {
        const val ID = "colmi"
        private const val MAX_DAYS = 7L

        /** The permissions Bluetooth needs on this phone. */
        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        fun hasPermission(context: Context): Boolean = permissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
