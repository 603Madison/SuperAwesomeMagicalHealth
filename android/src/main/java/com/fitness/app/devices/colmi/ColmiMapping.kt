package com.fitness.app.devices.colmi

import com.fitness.api.Metric
import com.fitness.api.Sample
import com.fitness.api.SleepSession
import com.fitness.api.SleepStage
import com.fitness.api.StageSpan
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
