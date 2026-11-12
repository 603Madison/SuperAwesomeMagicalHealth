package com.fitness.app.devices.gadgetbridge

import com.fitness.api.DeviceKind
import com.fitness.api.Metric
import com.fitness.api.Sample
import com.fitness.api.SleepSession
import com.fitness.api.SleepStage
import com.fitness.api.StageSpan
import com.fitness.api.Workout
import com.fitness.api.WorkoutType

/**
 * Gadgetbridge's database, read without assuming its exact schema (it differs by device and
 * version): tables are recognised by the end of their names and columns by theirs. Plain Kotlin
 * over rows, so it's tested without a phone.
 */
object GadgetbridgeMapping {
    enum class TableKind { ACTIVITY, HEART_RATE, SPO2, STRESS, HRV, RESPIRATORY_RATE, TEMPERATURE, SLEEP_STAGE, PEBBLE_OVERLAY }

    private const val MINUTE = 60_000L
    private const val QUARTER = 15 * MINUTE
    private const val FIVE = 5 * MINUTE

    fun classify(table: String): TableKind? {
        val t = table.uppercase()
        return when {
            t == "PEBBLE_HEALTH_ACTIVITY_OVERLAY" -> TableKind.PEBBLE_OVERLAY
            t.endsWith("_ACTIVITY_SAMPLE") -> TableKind.ACTIVITY
            t.endsWith("_HEART_RATE_SAMPLE") && !t.contains("RESTING") && !t.contains("MAX") && !t.contains("MANUAL") -> TableKind.HEART_RATE
            t.endsWith("_SPO2_SAMPLE") -> TableKind.SPO2
            t.endsWith("_STRESS_SAMPLE") -> TableKind.STRESS
            t.endsWith("_HRV_VALUE_SAMPLE") || t.endsWith("_HRV_SUMMARY_SAMPLE") -> TableKind.HRV
            t.endsWith("_RESPIRATORY_RATE_SAMPLE") -> TableKind.RESPIRATORY_RATE
            t.endsWith("_BODY_TEMPERATURE_SAMPLE") || t.endsWith("_TEMPERATURE_SAMPLE") -> TableKind.TEMPERATURE
            t.endsWith("_SLEEP_STAGE_SAMPLE") -> TableKind.SLEEP_STAGE
            else -> null
        }
    }

    /** The device family a table belongs to: COLMI, GARMIN, PEBBLE_HEALTH… */
    fun prefix(table: String): String {
        val t = table.uppercase()
        val kind = classify(t) ?: return t
        val suffix = when (kind) {
            TableKind.PEBBLE_OVERLAY -> return "PEBBLE"
            TableKind.ACTIVITY -> "_ACTIVITY_SAMPLE"
            TableKind.HEART_RATE -> "_HEART_RATE_SAMPLE"
            TableKind.SPO2 -> "_SPO2_SAMPLE"
            TableKind.STRESS -> "_STRESS_SAMPLE"
            TableKind.HRV -> if (t.endsWith("_HRV_VALUE_SAMPLE")) "_HRV_VALUE_SAMPLE" else "_HRV_SUMMARY_SAMPLE"
            TableKind.RESPIRATORY_RATE -> "_RESPIRATORY_RATE_SAMPLE"
            TableKind.TEMPERATURE -> if (t.endsWith("_BODY_TEMPERATURE_SAMPLE")) "_BODY_TEMPERATURE_SAMPLE" else "_TEMPERATURE_SAMPLE"
            TableKind.SLEEP_STAGE -> "_SLEEP_STAGE_SAMPLE"
        }
        return t.removeSuffix(suffix)
    }

    /** Gadgetbridge keeps some times in seconds and newer ones in milliseconds. */
    fun toMillis(raw: Long): Long = if (raw > 100_000_000_000L) raw else raw * 1000

    fun validHeartRate(bpm: Int): Boolean = bpm in 25..240

    /** What kind of device a Gadgetbridge device row is, from its tables and name. */
    fun kind(prefixes: Collection<String>, name: String?, type: String?): DeviceKind {
        val text = listOfNotNull(name, type).joinToString(" ").uppercase()
        return when {
            prefixes.any { it.startsWith("PEBBLE") } || "PEBBLE" in text -> DeviceKind.PEBBLE
            prefixes.any { it.startsWith("COLMI") } || "COLMI" in text || Regex("\\bR\\d{2}\\b").containsMatchIn(text) -> DeviceKind.COLMI_RING
            prefixes.any { it.startsWith("GARMIN") } || listOf("GARMIN", "FORERUNNER", "FENIX", "VENU", "INSTINCT", "VIVOACTIVE", "EPIX").any { it in text } -> DeviceKind.GARMIN
            else -> DeviceKind.GADGETBRIDGE
        }
    }

    /** One row of an *_ACTIVITY_SAMPLE table (usually a minute). Times in ms. */
    data class ActivityRow(val time: Long, val steps: Int? = null, val heartRate: Int? = null, val calories: Double? = null, val distanceM: Double? = null)

    /**
     * Minute-by-minute activity, summed into quarter hours (steps, distance, active calories)
     * and five-minute heart-rate averages, so a month stays a reasonable upload.
     */
    fun activity(rows: List<ActivityRow>): List<Sample> {
        val out = ArrayList<Sample>()
        rows.groupBy { it.time / QUARTER * QUARTER }.toSortedMap().forEach { (start, group) ->
            val steps = group.sumOf { (it.steps ?: 0).coerceIn(0, 1_000) }
            val distance = group.sumOf { it.distanceM ?: 0.0 }
            val calories = group.sumOf { it.calories ?: 0.0 }
            if (steps > 0) out += Sample(Metric.STEPS, start, start + QUARTER, steps.toDouble())
            if (distance > 0) out += Sample(Metric.DISTANCE, start, start + QUARTER, distance)
            if (calories > 0 && calories < 500) out += Sample(Metric.ACTIVE_CALORIES, start, start + QUARTER, calories)
        }
        out += heartRates(rows.mapNotNull { r -> r.heartRate?.let { r.time to it } })
        return out
    }

    /** Heart-rate readings as five-minute averages (or as they are, when fewer). */
    fun heartRates(readings: List<Pair<Long, Int>>): List<Sample> =
        readings.filter { validHeartRate(it.second) }
            .groupBy { it.first / FIVE * FIVE }
            .toSortedMap()
            .map { (start, group) -> Sample(Metric.HEART_RATE, start, value = Math.round(group.map { it.second }.average()).toDouble()) }

    /** Readings of a single value (SpO2, stress, HRV…), dropping ones that can't be right. */
    fun values(metric: Metric, readings: List<Pair<Long, Double>>): List<Sample> = readings.mapNotNull { (time, value) ->
        val ok = when (metric) {
            Metric.SPO2 -> value in 70.0..100.0
            Metric.STRESS -> value in 0.0..100.0
            Metric.HRV -> value in 1.0..300.0
            Metric.RESPIRATORY_RATE -> value in 4.0..40.0
            Metric.SKIN_TEMPERATURE -> value in 25.0..43.0
            else -> true
        }
        if (ok) Sample(metric, time, value = value) else null
    }

    /** Calories as Gadgetbridge stored them: kcal, unless they're plainly small calories. */
    fun kilocalories(column: String, value: Double): Double = when {
        column.uppercase() == "CALORIES" && value > 50 -> value / 1000.0
        else -> value
    }

    /** A sleep stage code, as each family numbers them. */
    fun sleepStage(prefix: String, code: Int): SleepStage? = when {
        prefix.startsWith("COLMI") -> when (code) {
            2 -> SleepStage.LIGHT
            3 -> SleepStage.DEEP
            4 -> SleepStage.REM
            5 -> SleepStage.AWAKE
            else -> null
        }
        // Garmin (and most others) use the FIT file's sleep levels.
        else -> when (code) {
            1 -> SleepStage.AWAKE
            2 -> SleepStage.LIGHT
            3 -> SleepStage.DEEP
            4 -> SleepStage.REM
            else -> null
        }
    }

    data class StageRow(val time: Long, val stage: Int, val durationMinutes: Int? = null)

    /**
     * Nights from stage rows: each stage lasts its duration if the table has one, else until the
     * next row (at most two hours); a gap of more than 90 minutes starts a new night, and anything
     * under 20 minutes asleep isn't one.
     */
    fun sleep(prefix: String, rows: List<StageRow>): List<SleepSession> {
        val sorted = rows.sortedBy { it.time }
        val spans = ArrayList<StageSpan>()
        sorted.forEachIndexed { i, row ->
            val stage = sleepStage(prefix, row.stage) ?: return@forEachIndexed
            val end = when {
                row.durationMinutes != null && row.durationMinutes > 0 -> row.time + row.durationMinutes * MINUTE
                i + 1 < sorted.size -> minOf(sorted[i + 1].time, row.time + 120 * MINUTE)
                else -> row.time + MINUTE
            }
            if (end > row.time) spans += StageSpan(stage, row.time, end)
        }
        return sessions(spans)
    }

    private fun sessions(spans: List<StageSpan>): List<SleepSession> {
        val nights = ArrayList<MutableList<StageSpan>>()
        for (span in spans) {
            val current = nights.lastOrNull()
            if (current == null || span.start - current.last().end > 90 * MINUTE) nights += mutableListOf(span) else current += span
        }
        return nights.mapNotNull { night ->
            val asleep = night.filter { it.stage != SleepStage.AWAKE }.sumOf { it.end - it.start }
            if (asleep < 20 * MINUTE) null else SleepSession(start = night.first().start, end = night.maxOf { it.end }, stages = night)
        }
    }

    /** One of a Pebble's activity "overlays": sleep, restful sleep, naps, walks and runs. */
    data class Overlay(val from: Long, val to: Long, val kind: Int)

    /**
     * Pebble Health's sleep and walks. A sleep (1) or nap (3) is the night; restful sleep (2, 4)
     * within it is deep sleep and the rest light. Runs (6), and walks (5) of ten minutes or more,
     * are workouts.
     */
    fun pebble(overlays: List<Overlay>): Pair<List<SleepSession>, List<Workout>> {
        val sleeps = overlays.filter { it.kind == 1 || it.kind == 3 }.sortedBy { it.from }
        val deep = overlays.filter { it.kind == 2 || it.kind == 4 }
        val nights = sleeps.filter { it.to > it.from }.map { night ->
            val restful = deep.filter { it.from < night.to && it.to > night.from }
                .map { maxOf(it.from, night.from) to minOf(it.to, night.to) }
                .sortedBy { it.first }
            val stages = ArrayList<StageSpan>()
            var cursor = night.from
            for ((from, to) in restful) {
                if (from > cursor) stages += StageSpan(SleepStage.LIGHT, cursor, from)
                if (to > maxOf(from, cursor)) stages += StageSpan(SleepStage.DEEP, maxOf(from, cursor), to)
                cursor = maxOf(cursor, to)
            }
            if (night.to > cursor) stages += StageSpan(SleepStage.LIGHT, cursor, night.to)
            SleepSession(start = night.from, end = night.to, stages = stages)
        }
        val workouts = overlays.filter { (it.kind == 6 || it.kind == 5) && it.to > it.from }
            .filter { it.kind == 6 || it.to - it.from >= 10 * MINUTE }
            .map { Workout(type = if (it.kind == 6) WorkoutType.RUN else WorkoutType.WALK, start = it.from, end = it.to) }
        return nights to workouts
    }
}
