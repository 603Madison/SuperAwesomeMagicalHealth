package com.health.server.health

import com.garmin.fit.ActivityMesg
import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.LapMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import com.garmin.fit.WeightScaleMesg
import com.health.api.Metric
import com.health.api.Workout
import com.health.api.WorkoutType
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Workouts and weight as Garmin FIT files, for Garmin Connect, Strava and the like: one activity
 * file per workout (its sport, times, distance, calories and heart rate) and one weight-scale file
 * with every weight and body fat reading. GET /api/export/fit is a zip of them.
 */
object FitExport {
    private val NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneOffset.UTC)

    fun zip(workouts: List<Workout>, weights: List<StoredSample>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val used = mutableSetOf<String>()
            workouts.sortedBy { it.start }.forEach { w ->
                var name = "workout-${NAME.format(Instant.ofEpochMilli(w.start))}-${w.type.name.lowercase()}"
                var n = 2
                while (!used.add(name)) name = "${name.substringBeforeLast("-v")}-v${n++}"
                zip.putNextEntry(ZipEntry("$name.fit"))
                zip.write(activity(w))
                zip.closeEntry()
            }
            if (weights.any { it.metric == Metric.WEIGHT }) {
                zip.putNextEntry(ZipEntry("weight.fit"))
                zip.write(weight(weights))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(
                ("Workouts and weight from SuperAwesomeMagicalHealth, as Garmin FIT files.\n" +
                    "Upload the workout files to Garmin Connect (Import Data) or Strava (Upload activity),\n" +
                    "and weight.fit to Garmin Connect for your weight history.\n").toByteArray(),
            )
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    fun activity(w: Workout): ByteArray {
        val start = fitTime(w.start)
        val end = fitTime(w.end)
        val seconds = ((w.end - w.start) / 1000f).coerceAtLeast(1f)
        val (sport, sub) = sportOf(w.type)
        val encoder = BufferEncoder(Fit.ProtocolVersion.V2_0)
        encoder.write(FileIdMesg().apply {
            type = File.ACTIVITY
            manufacturer = Manufacturer.DEVELOPMENT
            product = 1
            serialNumber = 1L
            timeCreated = start
        })
        encoder.write(EventMesg().apply { timestamp = start; event = Event.TIMER; eventType = EventType.START })
        encoder.write(EventMesg().apply { timestamp = end; event = Event.TIMER; eventType = EventType.STOP_ALL })
        encoder.write(LapMesg().apply {
            timestamp = end
            startTime = start
            totalElapsedTime = seconds
            totalTimerTime = seconds
            w.distanceM?.let { totalDistance = it.toFloat() }
            w.activeCalories?.let { totalCalories = it.toInt() }
            w.avgHeartRate?.let { avgHeartRate = it.toInt().toShort() }
            w.maxHeartRate?.let { maxHeartRate = it.toInt().toShort() }
            this.sport = sport
            subSport = sub
            event = Event.LAP
            eventType = EventType.STOP
        })
        encoder.write(SessionMesg().apply {
            timestamp = end
            startTime = start
            totalElapsedTime = seconds
            totalTimerTime = seconds
            w.distanceM?.let { totalDistance = it.toFloat() }
            w.activeCalories?.let { totalCalories = it.toInt() }
            w.avgHeartRate?.let { avgHeartRate = it.toInt().toShort() }
            w.maxHeartRate?.let { maxHeartRate = it.toInt().toShort() }
            this.sport = sport
            subSport = sub
            firstLapIndex = 0
            numLaps = 1
            event = Event.SESSION
            eventType = EventType.STOP
            w.name?.let { sportProfileName = it.take(30) }
        })
        encoder.write(ActivityMesg().apply {
            timestamp = end
            totalTimerTime = seconds
            numSessions = 1
            type = com.garmin.fit.Activity.MANUAL
            event = Event.ACTIVITY
            eventType = EventType.STOP
        })
        return encoder.close()
    }

    fun weight(samples: List<StoredSample>): ByteArray {
        val encoder = BufferEncoder(Fit.ProtocolVersion.V2_0)
        val sorted = samples.sortedBy { it.start }
        encoder.write(FileIdMesg().apply {
            type = File.WEIGHT
            manufacturer = Manufacturer.DEVELOPMENT
            product = 1
            serialNumber = 1L
            timeCreated = fitTime(sorted.firstOrNull()?.start ?: System.currentTimeMillis())
        })
        val fat = sorted.filter { it.metric == Metric.BODY_FAT }
        sorted.filter { it.metric == Metric.WEIGHT }.forEach { s ->
            encoder.write(WeightScaleMesg().apply {
                timestamp = fitTime(s.start)
                weight = s.value.toFloat()
                fat.firstOrNull { kotlin.math.abs(it.start - s.start) < 10 * 60_000 }?.let { percentFat = it.value.toFloat() }
            })
        }
        return encoder.close()
    }

    private fun fitTime(ms: Long) = DateTime(java.util.Date(ms))

    private fun sportOf(type: WorkoutType): Pair<Sport, SubSport> = when (type) {
        WorkoutType.WALK -> Sport.WALKING to SubSport.GENERIC
        WorkoutType.RUN -> Sport.RUNNING to SubSport.GENERIC
        WorkoutType.CYCLE -> Sport.CYCLING to SubSport.GENERIC
        WorkoutType.SWIM -> Sport.SWIMMING to SubSport.GENERIC
        WorkoutType.STRENGTH -> Sport.TRAINING to SubSport.STRENGTH_TRAINING
        WorkoutType.HIIT -> Sport.HIIT to SubSport.GENERIC
        WorkoutType.YOGA -> Sport.TRAINING to SubSport.YOGA
        WorkoutType.PILATES -> Sport.TRAINING to SubSport.PILATES
        WorkoutType.HIKE -> Sport.HIKING to SubSport.GENERIC
        WorkoutType.ROW -> Sport.ROWING to SubSport.INDOOR_ROWING
        WorkoutType.ELLIPTICAL -> Sport.FITNESS_EQUIPMENT to SubSport.ELLIPTICAL
        WorkoutType.DANCE -> Sport.DANCE to SubSport.GENERIC
        WorkoutType.SPORT -> Sport.GENERIC to SubSport.GENERIC
        WorkoutType.CLIMB -> Sport.ROCK_CLIMBING to SubSport.GENERIC
        WorkoutType.SKI -> Sport.ALPINE_SKIING to SubSport.GENERIC
        WorkoutType.OTHER -> Sport.GENERIC to SubSport.GENERIC
    }
}
