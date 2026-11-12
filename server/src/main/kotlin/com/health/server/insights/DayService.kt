package com.health.server.insights

import com.health.api.Aggregation
import com.health.api.ApiJson
import com.health.api.DaySummary
import com.health.api.Metric
import com.health.api.Nutrients
import com.health.api.Point
import com.health.api.SleepSession
import com.health.api.SleepStage
import com.health.api.SleepSummary
import com.health.api.TrendDay
import com.health.api.Trends
import com.health.server.db.Database
import com.health.server.health.ProfileStore
import com.health.server.health.Records
import com.health.server.health.StoredSample
import com.health.server.util.Days
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.roundToInt

/** One day's numbers, worked out from everything recorded and entered; kept until something for that day changes. */
@Serializable
data class DayStats(
    val date: String,
    val steps: Int? = null,
    val distanceM: Double? = null,
    val activeCalories: Double? = null,
    val floors: Double? = null,
    val hourlySteps: List<Int> = emptyList(),
    val restingHr: Double? = null,
    val avgHr: Double? = null,
    val minHr: Double? = null,
    val maxHr: Double? = null,
    val hrv: Double? = null,
    val spo2: Double? = null,
    val respiratoryRate: Double? = null,
    val skinTemp: Double? = null,
    val tempDeviation: Double? = null,
    val stress: Double? = null,
    val sleep: SleepSummary? = null,
    val naps: List<SleepSummary> = emptyList(),
    /** How far through the night (0–1) the heart rate was lowest. */
    val lowestHrPosition: Double? = null,
    val weightKg: Double? = null,
    val bodyFat: Double? = null,
    val nutrition: Nutrients? = null,
    val waterMl: Int = 0,
    val workouts: Int = 0,
    val workoutMinutes: Int = 0,
    val hasData: Boolean = false,
    val version: Int = VERSION,
) {
    companion object {
        /** Raised whenever the way days are worked out changes, so kept ones are worked out again. */
        const val VERSION = 1
    }
}

/** Days: their numbers, scores and insights, and trends over weeks. */
class DayService(
    private val db: Database,
    private val profiles: ProfileStore,
    private val records: Records,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // ---- Keeping days up to date ----

    /** Something recorded between [from] and [to] changed: those days (and the next, for nights) are worked out again. */
    suspend fun changed(userId: String, from: Long, to: Long) {
        val days = profiles.days(userId)
        val first = days.dateOf(minOf(from, to))
        val last = days.dateOf(maxOf(from, to)).plusDays(1)
        db.tx {
            if (first.plusDays(800).isBefore(last)) update("DELETE FROM day_cache WHERE user_id = ?", userId)
            else update("DELETE FROM day_cache WHERE user_id = ? AND date >= ? AND date <= ?", userId, first.toString(), last.toString())
        }
    }

    suspend fun changedDate(userId: String, date: LocalDate) = db.tx {
        update("DELETE FROM day_cache WHERE user_id = ? AND date = ?", userId, date.toString())
    }

    suspend fun changedAll(userId: String) = db.tx { update("DELETE FROM day_cache WHERE user_id = ?", userId) }

    // ---- Stats ----

    /** Each day's stats from [from] to [to], kept ones where they're current. */
    suspend fun stats(userId: String, from: LocalDate, to: LocalDate, days: Days): List<DayStats> {
        val kept = db.tx {
            query("SELECT date, data FROM day_cache WHERE user_id = ? AND date >= ? AND date <= ?", userId, from.toString(), to.toString()) {
                it.string("date") to it.string("data")
            }
        }.mapNotNull { (date, data) ->
            runCatching { ApiJson.decodeFromString(DayStats.serializer(), data) }.getOrNull()?.takeIf { it.version == DayStats.VERSION }?.let { date to it }
        }.toMap()
        val result = mutableListOf<DayStats>()
        var date = from
        while (!date.isAfter(to)) {
            val stats = kept[date.toString()] ?: compute(userId, date, days).also { fresh ->
                db.tx { update("INSERT OR REPLACE INTO day_cache (user_id, date, data) VALUES (?, ?, ?)", userId, fresh.date, ApiJson.encodeToString(DayStats.serializer(), fresh)) }
            }
            result += stats
            date = date.plusDays(1)
        }
        return result
    }

    /** Works out one day from what's stored. */
    suspend fun compute(userId: String, date: LocalDate, days: Days): DayStats {
        val start = days.start(date)
        val end = days.end(date)
        val sessions = records.sleep(userId, start, end)
        // Nights that end today began yesterday evening: their readings count too.
        val from = minOf(start - 14 * HOUR, sessions.minOfOrNull { it.start } ?: start)
        val all = records.samples(userId, from, end)
        val today = all.filter { it.start in start until end }

        // Sums (steps, calories…) come from one device a day, the one that counted most, so a ring and a
        // phone don't both count the same walk.
        fun sum(metric: Metric): Pair<Double, List<StoredSample>>? = today.filter { it.metric == metric }.groupBy { it.deviceId }
            .map { (_, list) -> list.sumOf { it.value } to list }.maxByOrNull { it.first }

        val steps = sum(Metric.STEPS)
        val hourly = steps?.let { (_, list) ->
            IntArray(24).also { hours -> list.forEach { hours[days.hourOf(it.start)] += it.value.roundToInt() } }.toList()
        }.orEmpty()

        fun values(metric: Metric, within: LongRange? = null) = today.filter { it.metric == metric && (within == null || it.start in within) }.map { it.value }
        fun latest(metric: Metric) = today.lastOrNull { it.metric == metric }?.value

        val nights = pickNights(sessions, all)
        val main = nights.firstOrNull()
        val mainSpan = main?.let { it.start..it.end }
        val inNight = { metric: Metric -> mainSpan?.let { span -> all.filter { it.metric == metric && it.start in span }.map { it.value } }.orEmpty() }

        val heart = values(Metric.HEART_RATE)
        val restingExplicit = values(Metric.RESTING_HEART_RATE)
        val resting = restingExplicit.takeIf { it.isNotEmpty() }?.average() ?: main?.lowestHeartRate
        val hrv = inNight(Metric.HRV).ifEmpty { values(Metric.HRV) }
        val spo2 = inNight(Metric.SPO2).ifEmpty { values(Metric.SPO2) }
        val breathing = inNight(Metric.RESPIRATORY_RATE).ifEmpty { values(Metric.RESPIRATORY_RATE) }
        val skin = inNight(Metric.SKIN_TEMPERATURE).ifEmpty { values(Metric.SKIN_TEMPERATURE) }
        val deviation = values(Metric.TEMPERATURE_DEVIATION)
        val stress = values(Metric.STRESS)
        val lowestAt = mainSpan?.let { span -> lowestPosition(all.filter { it.metric == Metric.HEART_RATE && it.start in span }, span) }

        val workouts = records.workouts(userId, start, end)
        val (nutrition, water) = food(userId, date)

        val stats = DayStats(
            date = date.toString(),
            steps = steps?.first?.roundToInt(),
            distanceM = sum(Metric.DISTANCE)?.first,
            activeCalories = sum(Metric.ACTIVE_CALORIES)?.first ?: workouts.mapNotNull { it.activeCalories }.takeIf { it.isNotEmpty() }?.sum(),
            floors = sum(Metric.FLOORS)?.first,
            hourlySteps = hourly,
            restingHr = resting,
            avgHr = heart.takeIf { it.isNotEmpty() }?.average(),
            minHr = heart.minOrNull(),
            maxHr = heart.maxOrNull(),
            hrv = hrv.takeIf { it.isNotEmpty() }?.average(),
            spo2 = spo2.takeIf { it.isNotEmpty() }?.average(),
            respiratoryRate = breathing.takeIf { it.isNotEmpty() }?.average(),
            skinTemp = skin.takeIf { it.isNotEmpty() }?.average(),
            tempDeviation = deviation.takeIf { it.isNotEmpty() }?.average(),
            stress = stress.takeIf { it.isNotEmpty() }?.average(),
            sleep = main,
            naps = nights.drop(1),
            lowestHrPosition = lowestAt,
            weightKg = latest(Metric.WEIGHT),
            bodyFat = latest(Metric.BODY_FAT),
            nutrition = nutrition,
            waterMl = water,
            workouts = workouts.size,
            workoutMinutes = workouts.sumOf { ((it.end - it.start) / 60_000).toInt() },
        )
        return stats.copy(hasData = today.isNotEmpty() || nights.isNotEmpty() || workouts.isNotEmpty() || nutrition != null || water > 0)
    }

    private suspend fun food(userId: String, date: LocalDate): Pair<Nutrients?, Int> = db.tx {
        val entries = query("SELECT nutrients FROM food_entries WHERE user_id = ? AND date = ?", userId, date.toString()) { it.string("nutrients") }
            .mapNotNull { runCatching { ApiJson.decodeFromString(Nutrients.serializer(), it) }.getOrNull() }
        val water = long("SELECT COALESCE(SUM(ml), 0) FROM water WHERE user_id = ? AND date = ?", userId, date.toString()).toInt()
        (if (entries.isEmpty()) null else entries.reduce(Nutrients::plus)) to water
    }

    /** The main night first, then naps; where devices recorded the same night twice, the one with stages wins. */
    private fun pickNights(sessions: List<SleepSession>, samples: List<StoredSample>): List<SleepSummary> {
        val ranked = sessions.sortedWith(compareByDescending<SleepSession> { s -> s.stages.any { it.stage != SleepStage.AWAKE && it.stage != SleepStage.ASLEEP } }.thenByDescending { it.end - it.start })
        val kept = mutableListOf<SleepSession>()
        ranked.forEach { s ->
            val overlapping = kept.any { k -> overlap(k.start, k.end, s.start, s.end) > 0.5 * minOf(k.end - k.start, s.end - s.start) }
            if (!overlapping) kept += s
        }
        val main = kept.maxByOrNull { if (it.end - it.start >= 3 * HOUR) it.end - it.start + 100 * HOUR else it.end - it.start }
        return listOfNotNull(main).plus(kept.filter { it !== main }.sortedBy { it.start }).map { summarize(it, samples) }
    }

    private fun overlap(a1: Long, a2: Long, b1: Long, b2: Long) = (minOf(a2, b2) - maxOf(a1, b1)).coerceAtLeast(0).toDouble()

    // ---- Summaries ----

    suspend fun summary(userId: String, dateText: String): DaySummary {
        val profile = profiles.get(userId)
        val days = Days(Days.zoneOf(profile.timezone))
        val date = days.parse(dateText, clock())
        val history = stats(userId, date.minusDays(HISTORY_DAYS), date, days)
        val today = history.last()
        val before = history.dropLast(1)
        val goals = profile.goals
        val sleep = Scoring.sleep(today.sleep, goals, days.zone)
        val readiness = Scoring.readiness(today, before, goals, sleep?.score)
        val activity = Scoring.activity(today, before, goals, hoursSoFar(date, days))
        val weight = today.weightKg ?: records.latest(userId, Metric.WEIGHT, days.end(date))?.value
        val resting = Energy.restingCalories(profile, weight, date)
        val target = Energy.target(profile, weight, resting, averageActive(before))
        val start = days.start(date)
        val end = days.end(date)
        val heart = records.samples(userId, start, end, listOf(Metric.HEART_RATE))
        val workouts = records.workouts(userId, start, end)
        val checkIn = checkIn(userId, date)
        val insights = InsightRules.insights(
            InsightRules.Context(
                today = today, history = before, profile = profile, target = target, readiness = readiness, sleep = sleep,
                isToday = date == days.today(clock()), hourNow = days.hourOf(clock()), restingCalories = resting,
            ),
        )
        // Resting energy counts up through the day: all of it for a day that's over, the part so far for today.
        val restingSoFar = resting?.let { r ->
            val fraction = ((clock() - start).toDouble() / (end - start)).coerceIn(0.0, 1.0)
            if (date == days.today(clock())) r * fraction else r
        }
        return DaySummary(
            date = date.toString(),
            readiness = readiness,
            sleep = sleep,
            activity = activity,
            steps = today.steps ?: 0,
            distanceM = today.distanceM ?: 0.0,
            activeCalories = today.activeCalories ?: 0.0,
            restingCalories = restingSoFar,
            floors = today.floors,
            restingHeartRate = today.restingHr,
            avgHeartRate = today.avgHr,
            minHeartRate = today.minHr,
            maxHeartRate = today.maxHr,
            hrv = today.hrv,
            spo2 = today.spo2,
            respiratoryRate = today.respiratoryRate,
            temperatureDeviation = Scoring.temperatureDeviation(today, before.takeLast(30)),
            stress = today.stress,
            sleepSummary = today.sleep,
            naps = today.naps,
            workouts = workouts,
            nutrition = today.nutrition ?: Nutrients(),
            nutritionTarget = target,
            waterMl = today.waterMl,
            weightKg = today.weightKg,
            bodyFat = today.bodyFat,
            checkIn = checkIn,
            insights = insights,
            hourlySteps = today.hourlySteps,
            heartRate = bucket(heart.map { Point(it.start, it.value) }, 5 * 60_000L),
            goals = goals,
            hasData = today.hasData,
        )
    }

    /** The day's nutrition target and the calories burned so far, for the food diary. */
    suspend fun nutritionFor(userId: String, date: LocalDate): Pair<com.health.api.NutritionTarget, Double?> {
        val profile = profiles.get(userId)
        val days = Days(Days.zoneOf(profile.timezone))
        val history = stats(userId, date.minusDays(14), date, days)
        val today = history.last()
        val weight = today.weightKg ?: records.latest(userId, Metric.WEIGHT, days.end(date))?.value
        val resting = Energy.restingCalories(profile, weight, date)
        val target = Energy.target(profile, weight, resting, averageActive(history.dropLast(1)))
        val burned = resting?.let { r ->
            val isToday = date == days.today(clock())
            val fraction = if (isToday) ((clock() - days.start(date)).toDouble() / (days.end(date) - days.start(date))).coerceIn(0.0, 1.0) else 1.0
            r * fraction + (today.activeCalories ?: 0.0)
        }
        return target to burned
    }

    suspend fun trends(userId: String, from: LocalDate?, to: LocalDate?, dayCount: Int?): Trends {
        val profile = profiles.get(userId)
        val days = Days(Days.zoneOf(profile.timezone))
        val last = to ?: days.today(clock())
        val first = from ?: last.minusDays(((dayCount ?: 30).coerceIn(1, 730) - 1).toLong())
        if (first.isAfter(last) || first.plusDays(730).isBefore(last)) throw com.health.server.ApiError.badRequest("Trends cover a day to two years")
        val all = stats(userId, first.minusDays(HISTORY_DAYS), last, days)
        val offset = HISTORY_DAYS.toInt()
        val goals = profile.goals
        val rows = all.indices.drop(offset).map { i ->
            val day = all[i]
            val before = all.subList(maxOf(0, i - offset), i)
            val sleep = Scoring.sleep(day.sleep, goals, days.zone)
            val date = LocalDate.parse(day.date)
            TrendDay(
                date = day.date,
                readiness = Scoring.readiness(day, before, goals, sleep?.score)?.score,
                sleepScore = sleep?.score,
                activityScore = Scoring.activity(day, before, goals, hoursSoFar(date, days))?.score,
                steps = day.steps,
                activeCalories = day.activeCalories,
                restingHeartRate = day.restingHr,
                hrv = day.hrv,
                sleepMinutes = day.sleep?.asleepMinutes,
                deepMinutes = day.sleep?.deepMinutes?.takeIf { day.sleep.staged },
                remMinutes = day.sleep?.remMinutes?.takeIf { day.sleep.staged },
                spo2 = day.spo2,
                temperatureDeviation = Scoring.temperatureDeviation(day, before.takeLast(30)),
                weightKg = day.weightKg,
                bodyFat = day.bodyFat,
                calories = day.nutrition?.calories,
                protein = day.nutrition?.protein,
                carbs = day.nutrition?.carbs,
                fat = day.nutrition?.fat,
                waterMl = day.waterMl.takeIf { it > 0 },
                workouts = day.workouts,
                workoutMinutes = day.workoutMinutes,
            )
        }
        return Trends(first.toString(), last.toString(), rows)
    }

    /** A metric's readings over [from, to), thinned to about [points] for a chart. */
    suspend fun series(userId: String, metric: Metric, from: Long, to: Long, points: Int = 400): List<Point> {
        val raw = records.samples(userId, from, to, listOf(metric)).map { Point(it.start, it.value) }
        if (raw.size <= points) return raw
        val width = ((to - from) / points).coerceAtLeast(60_000)
        return if (metric.aggregation == Aggregation.SUM) {
            raw.groupBy { (it.t - from) / width }.map { (bucket, list) -> Point(from + bucket * width, list.sumOf { it.v }) }
        } else {
            bucket(raw, width)
        }
    }

    private suspend fun checkIn(userId: String, date: LocalDate) = db.tx {
        one("SELECT data FROM checkins WHERE user_id = ? AND date = ?", userId, date.toString()) { it.string("data") }
    }?.let { runCatching { ApiJson.decodeFromString(com.health.api.CheckIn.serializer(), it) }.getOrNull() }

    private fun hoursSoFar(date: LocalDate, days: Days): Int {
        if (date != days.today(clock())) return 14
        return (days.hourOf(clock()) - 8 + 1).coerceIn(0, 14)
    }

    companion object {
        private const val HOUR = 3_600_000L
        /** Days of history behind each score: baselines look back a month. */
        const val HISTORY_DAYS = 30L

        /** Active calories on recent days with any, for estimating what's burned. */
        fun averageActive(history: List<DayStats>): Double? =
            history.takeLast(14).mapNotNull { it.activeCalories }.takeIf { it.size >= 3 }?.average()

        /** Averages readings into one point per [width] of time. */
        fun bucket(points: List<Point>, width: Long): List<Point> =
            points.groupBy { it.t / width }.map { (bucket, list) -> Point(bucket * width, (list.sumOf { it.v } / list.size * 10).roundToInt() / 10.0) }

        /** A night's numbers from its stages and the heart readings taken during it. */
        fun summarize(s: SleepSession, samples: List<StoredSample>): SleepSummary {
            val inBed = ((s.end - s.start) / 60_000).toInt()
            val stages = s.stages
            val staged = stages.any { it.stage == SleepStage.DEEP || it.stage == SleepStage.REM || it.stage == SleepStage.LIGHT }
            fun minutes(stage: SleepStage) = (stages.filter { it.stage == stage }.sumOf { it.end - it.start } / 60_000).toInt()
            val asleep = if (stages.isEmpty()) inBed else stages.filter { it.stage != SleepStage.AWAKE }.sumOf { it.end - it.start }.let { (it / 60_000).toInt() }
            val firstAsleep = stages.firstOrNull { it.stage != SleepStage.AWAKE }?.start
            val heart = samples.filter { it.metric == Metric.HEART_RATE && it.start in s.start..s.end }.map { it.value }
            val hrv = samples.filter { it.metric == Metric.HRV && it.start in s.start..s.end }.map { it.value }
            return SleepSummary(
                start = s.start,
                end = s.end,
                asleepMinutes = asleep,
                inBedMinutes = inBed,
                efficiency = if (inBed > 0) (asleep * 100 / inBed).coerceIn(0, 100) else 0,
                deepMinutes = minutes(SleepStage.DEEP),
                remMinutes = minutes(SleepStage.REM),
                lightMinutes = minutes(SleepStage.LIGHT) + minutes(SleepStage.ASLEEP),
                awakeMinutes = (inBed - asleep).coerceAtLeast(0),
                staged = staged,
                latencyMinutes = firstAsleep?.let { ((it - s.start) / 60_000).toInt() }?.takeIf { stages.isNotEmpty() },
                lowestHeartRate = lowest(heart),
                avgHeartRate = heart.takeIf { it.isNotEmpty() }?.average(),
                avgHrv = hrv.takeIf { it.isNotEmpty() }?.average(),
                stages = stages,
                source = s.source,
                sessionId = s.id,
            )
        }

        /** The lowest of three readings in a row, so one dropped reading isn't taken for the lowest. */
        private fun lowest(values: List<Double>): Double? = when {
            values.isEmpty() -> null
            values.size < 3 -> values.min()
            else -> values.windowed(3).minOf { it.average() }
        }

        private fun lowestPosition(heart: List<StoredSample>, span: LongRange): Double? {
            if (heart.size < 6 || span.last <= span.first) return null
            val window = heart.windowed(3).minByOrNull { w -> w.sumOf { it.value } } ?: return null
            return ((window[1].start - span.first).toDouble() / (span.last - span.first)).coerceIn(0.0, 1.0)
        }
    }
}
