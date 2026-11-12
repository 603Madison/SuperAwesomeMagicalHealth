package com.health.server.health

import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.FitImportResult
import com.health.api.IngestBatch
import com.health.api.Metric
import com.health.api.Sample
import com.health.api.SleepSession
import com.health.api.SleepStage
import com.health.api.StageSpan
import com.health.api.Workout
import com.health.api.WorkoutType
import com.health.server.ApiError
import com.garmin.fit.Decode
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.GarminProduct
import com.garmin.fit.Manufacturer
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.SleepLevel
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import java.io.ByteArrayInputStream

/**
 * Garmin's FIT files (from a watch's GARMIN/Activity folder, or exported from Garmin Connect):
 * workouts with their heart rate, the watch's all-day monitoring (heart rate, steps) and sleep.
 * Other makers' FIT files (Wahoo, Coros, Zwift…) work too.
 */
class FitImporter(private val ingest: Ingest) {
    suspend fun import(userId: String, files: List<Pair<String, ByteArray>>): FitImportResult {
        if (files.isEmpty()) throw ApiError.badRequest("Choose one or more .fit files")
        var workouts = 0
        var samples = 0
        var sleep = 0
        val problems = mutableListOf<String>()
        for ((name, bytes) in files) {
            val batch = try {
                parse(bytes)
            } catch (e: FitRuntimeException) {
                problems += "$name isn't a FIT file I can read"
                continue
            }
            if (batch == null) {
                problems += "$name has nothing I can use"
                continue
            }
            batch.samples.chunked(Ingest.MAX_SAMPLES).ifEmpty { listOf(emptyList()) }.forEachIndexed { index, part ->
                val result = ingest.ingest(
                    userId,
                    if (index == 0) batch.copy(samples = part) else batch.copy(samples = part, sleep = emptyList(), workouts = emptyList()),
                )
                samples += result.samples
                workouts += result.workouts
                sleep += result.sleep
            }
        }
        val parts = listOfNotNull(
            workouts.takeIf { it > 0 }?.let { "$it workout${if (it == 1) "" else "s"}" },
            sleep.takeIf { it > 0 }?.let { "$it night${if (it == 1) "" else "s"}" },
            samples.takeIf { it > 0 }?.let { "%,d readings".format(it) },
        )
        val message = (if (parts.isEmpty()) "Nothing new to add." else "Added ${parts.joinToString(", ")}.") +
            problems.joinToString("") { " $it." }
        return FitImportResult(workouts, samples, sleep, message)
    }

    companion object {
        /** What one FIT file holds, as an upload from its device; null if there's nothing usable. */
        fun parse(bytes: ByteArray): IngestBatch? {
            // Checking a file leaves a decoder part-way through it: reading needs a fresh one.
            if (!Decode().isFileFit(ByteArrayInputStream(bytes))) throw FitRuntimeException("Not a FIT file")
            val decode = Decode()
            val broadcaster = MesgBroadcaster(decode)
            var manufacturer: Int? = null
            var product: Int? = null
            var serial: Long? = null
            val workouts = mutableListOf<Workout>()
            val heart = mutableListOf<Sample>()
            val steps = mutableListOf<Pair<Long, Long>>()
            val levels = mutableListOf<Pair<Long, SleepLevel>>()

            broadcaster.addListener(com.garmin.fit.FileIdMesgListener { m ->
                manufacturer = manufacturer ?: m.manufacturer
                product = product ?: (m.garminProduct ?: m.product)
                serial = serial ?: m.serialNumber
            })
            broadcaster.addListener(com.garmin.fit.SessionMesgListener { m ->
                val start = m.startTime?.date?.time ?: return@SessionMesgListener
                val seconds = (m.totalElapsedTime ?: m.totalTimerTime)?.toDouble() ?: return@SessionMesgListener
                val type = typeOf(m.sport, m.subSport)
                workouts += Workout(
                    type = type,
                    start = start,
                    end = start + (seconds * 1000).toLong(),
                    name = m.sport?.let { Sport.getStringFromValue(it) }?.let(::pretty)?.takeIf { type == WorkoutType.OTHER },
                    distanceM = m.totalDistance?.toDouble()?.takeIf { it > 0 },
                    activeCalories = m.totalCalories?.toDouble()?.takeIf { it > 0 },
                    avgHeartRate = m.avgHeartRate?.toDouble()?.takeIf { it > 0 },
                    maxHeartRate = m.maxHeartRate?.toDouble()?.takeIf { it > 0 },
                    steps = m.totalStrides?.takeIf { it > 0 && (type == WorkoutType.RUN || type == WorkoutType.WALK || type == WorkoutType.HIKE) }?.let { (it * 2).toInt() },
                )
            })
            broadcaster.addListener(com.garmin.fit.RecordMesgListener { m ->
                val at = m.timestamp?.date?.time ?: return@RecordMesgListener
                m.heartRate?.toDouble()?.takeIf { it in 25.0..240.0 }?.let { heart += Sample(Metric.HEART_RATE, at, value = it) }
            })
            broadcaster.addListener(com.garmin.fit.MonitoringMesgListener { m ->
                val at = m.timestamp?.date?.time ?: return@MonitoringMesgListener
                m.heartRate?.toDouble()?.takeIf { it in 25.0..240.0 }?.let { heart += Sample(Metric.HEART_RATE, at, value = it) }
                m.steps?.let { steps += at to it }
            })
            broadcaster.addListener(com.garmin.fit.SleepLevelMesgListener { m ->
                val at = m.timestamp?.date?.time ?: return@SleepLevelMesgListener
                m.sleepLevel?.let { levels += at to it }
            })
            decode.read(ByteArrayInputStream(bytes), broadcaster, broadcaster)

            val stepSamples = stepsOf(steps)
            val nights = sleepOf(levels)
            // Workouts' per-second records are thinned to one reading every 30 seconds.
            val thinned = heart.sortedBy { it.start }.distinctBy { it.start / 30_000 }
            if (workouts.isEmpty() && thinned.isEmpty() && stepSamples.isEmpty() && nights.isEmpty()) return null

            val garmin = manufacturer == Manufacturer.GARMIN
            val model = product?.let { p -> if (garmin) GarminProduct.getStringFromValue(p)?.takeIf { it.isNotEmpty() }?.let(::pretty) else null }
            val identifier = "fit:${manufacturer ?: 0}:${serial ?: product ?: 0}"
            val device = DeviceInfo(
                kind = if (garmin) DeviceKind.GARMIN else DeviceKind.FIT_FILE,
                identifier = identifier,
                name = when {
                    model != null -> "Garmin $model"
                    garmin -> "Garmin watch"
                    else -> manufacturer?.let { Manufacturer.getStringFromValue(it) }?.takeIf { it.isNotEmpty() }?.let { "${pretty(it)} device" } ?: "FIT files"
                },
                model = model,
            )
            return IngestBatch(device, samples = thinned + stepSamples, sleep = nights, workouts = workouts)
        }

        /** Monitoring files count steps up through the day; each reading becomes the steps since the one before. */
        private fun stepsOf(counts: List<Pair<Long, Long>>): List<Sample> {
            val sorted = counts.sortedBy { it.first }
            return sorted.zipWithNext().mapNotNull { (a, b) ->
                val delta = b.second - a.second
                if (delta <= 0 || delta > 20_000 || b.first - a.first > 3_600_000) null
                else Sample(Metric.STEPS, a.first, b.first, delta.toDouble())
            }
        }

        /** Sleep files: a level every minute or so; runs of the same level become stages, gaps of over an hour split nights. */
        private fun sleepOf(levels: List<Pair<Long, SleepLevel>>): List<SleepSession> {
            if (levels.isEmpty()) return emptyList()
            val sorted = levels.sortedBy { it.first }
            val nights = mutableListOf<MutableList<Pair<Long, SleepLevel>>>(mutableListOf())
            sorted.forEach { level ->
                val last = nights.last().lastOrNull()
                if (last != null && level.first - last.first > 3_600_000) nights += mutableListOf<Pair<Long, SleepLevel>>()
                nights.last() += level
            }
            return nights.filter { it.size >= 2 }.map { night ->
                val spans = night.zipWithNext().mapNotNull { (a, b) ->
                    val stage = when (a.second) {
                        SleepLevel.AWAKE -> SleepStage.AWAKE
                        SleepLevel.LIGHT -> SleepStage.LIGHT
                        SleepLevel.DEEP -> SleepStage.DEEP
                        SleepLevel.REM -> SleepStage.REM
                        else -> null
                    }
                    stage?.let { StageSpan(it, a.first, b.first) }
                }
                val merged = spans.fold(mutableListOf<StageSpan>()) { acc, span ->
                    val last = acc.lastOrNull()
                    if (last != null && last.stage == span.stage && last.end == span.start) acc[acc.size - 1] = last.copy(end = span.end) else acc += span
                    acc
                }
                SleepSession(night.first().first, night.last().first, merged)
            }.filter { it.end - it.start >= 20 * 60_000 }
        }

        fun typeOf(sport: Sport?, sub: SubSport?): WorkoutType = when {
            sub == SubSport.STRENGTH_TRAINING -> WorkoutType.STRENGTH
            sub == SubSport.YOGA -> WorkoutType.YOGA
            sub == SubSport.PILATES -> WorkoutType.PILATES
            sub == SubSport.HIIT -> WorkoutType.HIIT
            sub == SubSport.ELLIPTICAL -> WorkoutType.ELLIPTICAL
            sub == SubSport.INDOOR_ROWING -> WorkoutType.ROW
            sport == Sport.RUNNING -> WorkoutType.RUN
            sport == Sport.WALKING -> WorkoutType.WALK
            sport == Sport.CYCLING || sport == Sport.E_BIKING -> WorkoutType.CYCLE
            sport == Sport.SWIMMING -> WorkoutType.SWIM
            sport == Sport.HIKING -> WorkoutType.HIKE
            sport == Sport.ROWING -> WorkoutType.ROW
            sport == Sport.HIIT -> WorkoutType.HIIT
            sport == Sport.DANCE -> WorkoutType.DANCE
            sport == Sport.ROCK_CLIMBING -> WorkoutType.CLIMB
            sport == Sport.ALPINE_SKIING || sport == Sport.CROSS_COUNTRY_SKIING || sport == Sport.SNOWBOARDING -> WorkoutType.SKI
            sport == Sport.SOCCER || sport == Sport.TENNIS || sport == Sport.BASKETBALL || sport == Sport.AMERICAN_FOOTBALL ||
                sport == Sport.RACKET || sport == Sport.VOLLEYBALL -> WorkoutType.SPORT
            else -> WorkoutType.OTHER
        }

        /** "FENIX7_PRO" → "Fenix 7 Pro"; "running" → "Running". */
        fun pretty(name: String): String = name.replace('_', ' ')
            .replace(Regex("([A-Za-z])(\\d)"), "$1 $2")
            .lowercase().split(' ').filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }
}
