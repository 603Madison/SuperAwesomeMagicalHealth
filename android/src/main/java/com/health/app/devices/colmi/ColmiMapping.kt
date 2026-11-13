package com.health.app.devices.colmi

import com.health.api.Metric
import com.health.api.Sample
import com.health.api.SleepSession
import com.health.api.SleepStage
import com.health.api.StageSpan
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** What the ring recorded, as the server's samples and sleep sessions. Times are wall-clock in [zone]. */
object ColmiMapping {
    private const val QUARTER_MS = 15 * 60_000L

    /** Average kilocalories a step burns, for when the ring's own calories don't make sense. */
    private const val KCAL_PER_STEP = 0.04

    private fun at(time: LocalDateTime, zone: ZoneId): Long = time.atZone(zone).toInstant().toEpochMilli()

    /** A reading per interval that had one; none from the future (the ring pads the day with zeros). */
    fun heartRates(date: LocalDate, log: HeartRateLog, zone: ZoneId, now: Long): List<Sample> =
        log.values.mapIndexedNotNull { i, bpm ->
            if (bpm !in 25..240) return@mapIndexedNotNull null
            val t = at(date.atStartOfDay().plusMinutes(i.toLong() * log.intervalMinutes), zone)
            if (t > now) null else Sample(Metric.HEART_RATE, t, value = bpm.toDouble())
        }

    /** Steps, active calories and distance, each over its quarter hour. */
    fun activity(slots: List<StepSlot>, zone: ZoneId): List<Sample> = slots.flatMap { slot ->
        val start = at(slot.date.atStartOfDay().plusMinutes(slot.quarter * 15L), zone)
        val end = start + QUARTER_MS
        buildList {
            if (slot.steps > 0) add(Sample(Metric.STEPS, start, end, slot.steps.toDouble()))
            if (slot.distanceM > 0) add(Sample(Metric.DISTANCE, start, end, slot.distanceM.toDouble()))
            val kcal = plausibleCalories(slot)
            if (kcal > 0) add(Sample(Metric.ACTIVE_CALORIES, start, end, kcal))
        }
    }

    /**
     * The ring's calories for a quarter hour, unless they're out of all proportion to its steps
     * (rings differ in how they count), when they're estimated from the steps instead.
     */
    fun plausibleCalories(slot: StepSlot): Double {
        if (slot.steps <= 0) return slot.calories.takeIf { it in 0.0..60.0 } ?: 0.0
        val perStep = slot.calories / slot.steps
        return if (perStep in 0.005..0.25) slot.calories else slot.steps * KCAL_PER_STEP
    }

    /** Stress as the ring rates it, 1–100, at each half hour that has a reading. */
    fun stress(date: LocalDate, log: HalfHourLog, zone: ZoneId, now: Long): List<Sample> =
        halfHourly(Metric.STRESS, date, log, 1..100, zone, now)

    /** HRV in ms at each half hour that has a reading (a byte's top values are taken as no reading). */
    fun hrv(date: LocalDate, log: HalfHourLog, zone: ZoneId, now: Long): List<Sample> =
        halfHourly(Metric.HRV, date, log, 1..250, zone, now)

    /**
     * A reading per half hour of [date] in [valid]; none from the future (as with heart rate, a
     * ring can hold an earlier day's value in a slot it hasn't reached yet).
     */
    private fun halfHourly(metric: Metric, date: LocalDate, log: HalfHourLog, valid: IntRange, zone: ZoneId, now: Long): List<Sample> =
        log.values.take(HalfHourLog.SLOTS).mapIndexedNotNull { slot, value ->
            if (value !in valid) return@mapIndexedNotNull null
            val t = at(date.atStartOfDay().plusMinutes(slot.toLong() * HalfHourLog.MINUTES), zone)
            if (t > now) null else Sample(metric, t, value = value.toDouble())
        }

    /** Each hour's blood oxygen, the middle of its lowest and highest (as Gadgetbridge keeps it), on the hour. */
    fun spo2(hours: List<Spo2Hour>, zone: ZoneId, now: Long): List<Sample> = hours.mapNotNull { h ->
        val value = (h.min + h.max) / 2.0
        val t = at(h.date.atTime(h.hour, 0), zone)
        if (value < MIN_SPO2 || value > 100.0 || t > now) null else Sample(Metric.SPO2, t, value = value)
    }

    /**
     * Skin temperatures in °C. Readings outside what a finger can be are left out: a ring off the
     * finger (charging, on a table) reads the room.
     */
    fun skinTemperature(readings: List<TemperatureReading>, zone: ZoneId, now: Long): List<Sample> = readings.mapNotNull { r ->
        val t = at(r.time, zone)
        if (r.celsius !in SKIN_CELSIUS || t > now) null else Sample(Metric.SKIN_TEMPERATURE, t, value = r.celsius)
    }

    /** Below this, a blood-oxygen reading is a poor fit on the finger rather than a reading. */
    private const val MIN_SPO2 = 70.0
    private val SKIN_CELSIUS = 25.0..42.0

    fun sleep(nights: List<RingNight>, zone: ZoneId): List<SleepSession> = nights.map { night ->
        var cursor = night.start
        val spans = night.stages.map { (stage, minutes) ->
            val from = cursor
            cursor = cursor.plusMinutes(minutes.toLong())
            StageSpan(
                stage = when (stage) {
                    RingStage.LIGHT -> SleepStage.LIGHT
                    RingStage.DEEP -> SleepStage.DEEP
                    RingStage.REM -> SleepStage.REM
                    RingStage.AWAKE -> SleepStage.AWAKE
                },
                start = at(from, zone),
                end = at(cursor, zone),
            )
        }
        SleepSession(start = at(night.start, zone), end = at(night.end, zone), stages = spans)
    }
}
