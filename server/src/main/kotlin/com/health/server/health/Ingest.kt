package com.health.server.health

import com.health.api.IngestBatch
import com.health.api.IngestResult
import com.health.api.Metric
import com.health.api.Sample
import com.health.api.SleepSession
import com.health.api.StageSpan
import com.health.api.Workout
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.insights.DayService
import org.slf4j.LoggerFactory

/**
 * Data coming in from devices: checked, stored (replacing what's there for the same device and
 * time, so a resend is harmless) and the affected days recalculated.
 */
class Ingest(
    private val db: Database,
    private val devices: DeviceStore,
    private val records: Records,
    private val days: DayService,
    private val profiles: ProfileStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger("samh.ingest")

    suspend fun ingest(userId: String, batch: IngestBatch): IngestResult {
        if (batch.samples.size > MAX_SAMPLES) throw ApiError.badRequest("Send at most $MAX_SAMPLES readings at a time")
        if (batch.sleep.size > MAX_SESSIONS || batch.workouts.size > MAX_SESSIONS) throw ApiError.badRequest("Send at most $MAX_SESSIONS nights or workouts at a time")
        val now = clock()
        val samples = batch.samples.filter { valid(it, now) }.distinctBy { it.metric to it.start }
        val sleep = batch.sleep.mapNotNull { clean(it, now) }.distinctBy { it.start }
        val workouts = batch.workouts.mapNotNull { clean(it, now) }.distinctBy { it.start }
        val skipped = batch.samples.size - samples.size
        if (skipped > 0) log.debug("Skipped {} readings from {} that didn't look right", skipped, batch.device.name)

        if (batch.bloodPressure.size + batch.glucose.size > MAX_SAMPLES) throw ApiError.badRequest("Send at most $MAX_SAMPLES readings at a time")
        // Blood pressure and glucose readings: dated in the person's own time zone.
        val zone = if (batch.bloodPressure.isEmpty() && batch.glucose.isEmpty()) null else profiles.days(userId)
        var readings = 0
        val deviceId = db.tx {
            val id = devices.upsert(this, userId, batch.device, synced = true)
            records.putSamples(this, userId, id, samples)
            records.putSleep(this, userId, id, sleep, manual = false)
            records.putWorkouts(this, userId, id, workouts, manual = false)
            if (zone != null) readings = com.health.server.tracking.VitalsService.putFromDevice(this, userId, id, zone, batch.bloodPressure, batch.glucose, now)
            id
        }
        val times = samples.flatMap { listOfNotNull(it.start, it.end) } + sleep.flatMap { listOf(it.start, it.end) } + workouts.flatMap { listOf(it.start, it.end) }
        if (times.isNotEmpty()) days.changed(userId, times.min(), times.max())
        return IngestResult(deviceId, samples.size, sleep.size, workouts.size, readings)
    }

    /** Readings entered by hand: kept on the person's own "Entered by hand" device. */
    suspend fun manualSample(userId: String, sample: Sample): Sample {
        if (!valid(sample, clock())) throw ApiError.badRequest("That ${sample.metric.label.lowercase()} doesn't look right")
        val stored = db.tx {
            val device = devices.manual(this, userId)
            records.putSamples(this, userId, device, listOf(sample))
            one("SELECT id FROM samples WHERE user_id = ? AND device_id = ? AND metric = ? AND start = ?", userId, device, sample.metric, sample.start) { it.long("id") }
        }
        days.changed(userId, sample.start, sample.end ?: sample.start)
        return sample.copy(id = stored, source = "Entered by hand")
    }

    suspend fun manualSleep(userId: String, session: SleepSession): SleepSession {
        val clean = clean(session, clock()) ?: throw ApiError.badRequest("A night has to end after it starts, and last under a day")
        val id = db.tx { records.putSleep(this, userId, devices.manual(this, userId), listOf(clean), manual = true).first() }
        days.changed(userId, clean.start, clean.end)
        return clean.copy(id = id, manual = true, source = "Entered by hand")
    }

    suspend fun manualWorkout(userId: String, workout: Workout): Workout {
        val clean = clean(workout, clock()) ?: throw ApiError.badRequest("A workout has to end after it starts, and last under a day")
        if (clean.effort != null && clean.effort !in 1..10) throw ApiError.badRequest("Effort is from 1 to 10")
        val before = workout.id?.let { records.workout(userId, it) }
        if (workout.id != null && (before == null || !before.manual)) throw ApiError.notFound("No such workout (only ones entered by hand can be changed)")
        val id = db.tx { records.putWorkouts(this, userId, devices.manual(this, userId), listOf(clean), manual = true).first() }
        days.changed(userId, minOf(clean.start, before?.start ?: clean.start), maxOf(clean.end, before?.end ?: clean.end))
        return records.workout(userId, id)!!
    }

    suspend fun deleteSample(userId: String, id: Long) {
        val at = records.deleteManualSample(userId, id)
        days.changed(userId, at, at)
    }

    suspend fun deleteSleep(userId: String, id: String) {
        val session = records.deleteSleep(userId, id)
        days.changed(userId, session.start, session.end)
    }

    suspend fun deleteWorkout(userId: String, id: String) {
        val workout = records.deleteWorkout(userId, id)
        days.changed(userId, workout.start, workout.end)
    }

    suspend fun deviceDeleted(userId: String) = days.changedAll(userId)

    private fun valid(s: Sample, now: Long): Boolean {
        if (!s.value.isFinite() || s.start < EARLIEST || s.start > now + FUTURE_SLACK_MS) return false
        val end = s.end
        if (end != null && (end < s.start || end - s.start > DAY_MS)) return false
        val (min, max) = RANGES[s.metric] ?: return true
        return s.value in min..max
    }

    private fun clean(s: SleepSession, now: Long): SleepSession? {
        if (s.end <= s.start || s.end - s.start > DAY_MS || s.start < EARLIEST || s.end > now + FUTURE_SLACK_MS) return null
        val stages = s.stages
            .filter { it.end > it.start }
            .map { StageSpan(it.stage, it.start.coerceAtLeast(s.start), it.end.coerceAtMost(s.end)) }
            .filter { it.end > it.start }
            .sortedBy { it.start }
        return s.copy(stages = stages, note = s.note?.trim()?.take(1000)?.ifBlank { null })
    }

    private fun clean(w: Workout, now: Long): Workout? {
        if (w.end <= w.start || w.end - w.start > DAY_MS || w.start < EARLIEST || w.start > now + FUTURE_SLACK_MS) return null
        fun Double?.ok(max: Double) = this?.takeIf { it.isFinite() && it >= 0 && it <= max }
        return w.copy(
            name = w.name?.trim()?.take(120)?.ifBlank { null },
            distanceM = w.distanceM.ok(1_000_000.0),
            activeCalories = w.activeCalories.ok(20_000.0),
            avgHeartRate = w.avgHeartRate.ok(250.0),
            maxHeartRate = w.maxHeartRate.ok(250.0),
            steps = w.steps?.takeIf { it in 0..500_000 },
            note = w.note?.trim()?.take(2000)?.ifBlank { null },
        )
    }

    companion object {
        const val MAX_SAMPLES = 20_000
        const val MAX_SESSIONS = 1_000
        private const val DAY_MS = 24L * 3600 * 1000
        private const val FUTURE_SLACK_MS = 6L * 3600 * 1000
        /** 2000-01-01: anything older is a device's clock that wasn't set. */
        private const val EARLIEST = 946_684_800_000L

        /** What a reading can plausibly be; others are a sensor's glitch. */
        private val RANGES = mapOf(
            Metric.HEART_RATE to (25.0 to 240.0),
            Metric.RESTING_HEART_RATE to (25.0 to 150.0),
            Metric.HRV to (1.0 to 300.0),
            Metric.SPO2 to (50.0 to 100.0),
            Metric.RESPIRATORY_RATE to (4.0 to 60.0),
            Metric.SKIN_TEMPERATURE to (20.0 to 43.0),
            Metric.TEMPERATURE_DEVIATION to (-5.0 to 5.0),
            Metric.STRESS to (0.0 to 100.0),
            Metric.STEPS to (0.0 to 100_000.0),
            Metric.DISTANCE to (0.0 to 300_000.0),
            Metric.ACTIVE_CALORIES to (0.0 to 20_000.0),
            Metric.FLOORS to (0.0 to 2_000.0),
            Metric.WEIGHT to (20.0 to 400.0),
            Metric.BODY_FAT to (2.0 to 75.0),
            Metric.BLOOD_PRESSURE_SYSTOLIC to (50.0 to 260.0),
            Metric.BLOOD_PRESSURE_DIASTOLIC to (30.0 to 180.0),
            Metric.BLOOD_GLUCOSE to (1.0 to 40.0),
        )
    }
}
