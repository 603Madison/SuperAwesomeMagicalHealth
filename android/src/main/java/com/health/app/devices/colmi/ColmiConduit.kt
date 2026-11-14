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
 * week, which is about what a ring keeps): heart rate, activity, and sleep; then what rings differ
 * in: stress (today's, all the ring gives), HRV, blood oxygen, and skin temperature on the rings
 * that keep it. A reading the ring doesn't answer is left for next time; the rest still sync.
 */
class ColmiConduit(
    private val context: Context,
    private val store: SyncStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Which readings the ring gives, and when each last came in (for the Devices page). */
    val readings: RingReadingsStore = RingReadingsStore(context),
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
        val known = readings.state.value
        val temperature = ColmiProtocol.measuresTemperature(ring.name)
        val offered = RingReading.entries.filter { it != RingReading.SKIN_TEMPERATURE || temperature }.toSet()
        val answers = HashMap<RingReading, Answer>()
        var measuring = false
        // Problems before the ring's readings (its clock, battery and settings), and with today's stress.
        var readsFrom = 0
        var stressProblems = 0
        try {
            val client = ColmiRing(gatt)
            client.start()
            attempt(problems, "clock") { client.setTime(LocalDateTime.now(zone)) }
            attempt(problems, "battery") { client.battery()?.let { battery = it.level } }
            if (firmware == null) attempt(problems, "firmware") { firmware = client.firmware() }
            // The ring's own measurements, once for each ring (and again when the app turns on more).
            if (since == null || known.needsAutoMeasure(ring.address)) {
                attempt(problems, "heart-rate setting") { client.enableHeartRateLog(5) }
                for (packet in autoMeasurements(temperature)) {
                    // Not every ring has every one: one that's refused or unanswered is fine.
                    attempt(problems, "measurement settings") { client.enableMeasurement(packet) }
                }
                measuring = true
            }
            // From here on the ring's readings: if any can't be read, the next sync starts from the same day.
            readsFrom = problems.size
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
            // What rings differ in, after the rest: one a ring doesn't answer (or that drops the
            // connection) can't cost the readings above.
            val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
            val extras = RingExtras(problems, answers)
            // Stress is only ever today's, which the next sync reads anyway: it can't hold the others back.
            val beforeStress = problems.size
            extras.read(RingReading.STRESS) {
                val reply = client.stress()
                reply.value?.let { samples += ColmiMapping.stress(today, it, zone, now) }
                reply.answer
            }
            stressProblems = problems.size - beforeStress
            extras.read(RingReading.HRV) {
                // Newest first, so a ring that doesn't keep HRV is found out with the first ask.
                var first: Answer? = null
                for (offset in 0..days) {
                    val reply = client.hrv(offset)
                    if (first == null) first = reply.answer
                    if (!reply.answer.answered) break
                    reply.value?.let { samples += ColmiMapping.hrv(today.minusDays(offset.toLong()), it, zone, now) }
                }
                first ?: Answer.SILENT
            }
            extras.read(RingReading.SPO2) {
                val reply = client.spo2()
                reply.value?.let { samples += ColmiMapping.spo2(ColmiSpo2.parse(it, today), zone, now).filter { s -> s.start >= fromMs } }
                reply.answer
            }
            if (temperature) {
                extras.read(RingReading.SKIN_TEMPERATURE) {
                    val reply = client.temperature()
                    reply.value?.let { samples += ColmiMapping.skinTemperature(ColmiTemperature.parse(it, today), zone, now).filter { s -> s.start >= fromMs } }
                    reply.answer
                }
            }
        } finally {
            gatt.close()
        }
        store.setRing(ring.copy(battery = battery, firmware = firmware))
        readings.set(
            known.after(ring.address, samples, sleep, answers, offered, now).let { if (measuring) it.copy(autoMeasure = RingReadingsState.AUTO_MEASURE) else it },
        )
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
        val readFailed = problems.size - readsFrom - stressProblems > 0
        return Collected(listOf(batch), cursorAfter(since, now, readFailed), note)
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

    /**
     * Reads what rings differ in, one reading at a time: a reading that fails is noted (and kept
     * as [Answer.SILENT]); once the ring has gone, the rest aren't asked for.
     */
    private class RingExtras(private val problems: MutableList<String>, private val answers: MutableMap<RingReading, Answer>) {
        private var connected = true

        suspend fun read(reading: RingReading, block: suspend () -> Answer) {
            if (!connected) return
            answers[reading] = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ColmiConduit", "Reading ${reading.label} failed", e)
                problems += reading.label.lowercase()
                if (e is com.health.app.devices.ble.BleException) connected = false
                Answer.SILENT
            }
        }
    }

    fun pair(address: String, name: String) {
        store.setRing(PairedRing(address, name.ifBlank { "Smart ring" }))
        store.setCursor(ID, null)
        store.clearStatus(ID)
        readings.clear()
    }

    fun forget() {
        store.setRing(null)
        store.setCursor(ID, null)
        store.clearStatus(ID)
        readings.clear()
    }

    companion object {
        const val ID = "colmi"
        private const val MAX_DAYS = 7L

        /**
         * The ring's own measurements the app turns on, besides heart rate (every 5 minutes):
         * blood oxygen, stress and HRV, and skin temperature on the rings that keep it. These are
         * only on or off (Gadgetbridge and Puxtril document no interval): the ring then keeps
         * stress, HRV and temperature each half hour, and blood oxygen's range each hour.
         */
        fun autoMeasurements(temperature: Boolean): List<ByteArray> = listOfNotNull(
            ColmiProtocol.writeSpo2Settings(true),
            ColmiProtocol.writeStressSettings(true),
            ColmiProtocol.writeHrvSettings(true),
            ColmiProtocol.writeTemperatureSettings(true).takeIf { temperature },
        )

        /**
         * Where the next sync starts: [now]; but if any of the ring's readings couldn't be read, where
         * this one did ([since]), so what it missed is asked for again (the ring keeps about a week).
         */
        fun cursorAfter(since: Long?, now: Long, readFailed: Boolean): Long? = if (readFailed) since else now

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
