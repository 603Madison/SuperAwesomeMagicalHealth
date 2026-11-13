package com.health.server.insights

import com.health.api.Aggregation
import com.health.api.CyclePhase
import com.health.api.DrinksDay
import com.health.api.Flow
import com.health.api.Insight
import com.health.api.ModulesToday
import com.health.api.Profile
import com.health.api.ApiJson
import com.health.api.CombinedValue
import com.health.api.DaySummary
import com.health.api.DeviceChoices
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
import com.health.server.insights.Combine.withReading
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
    /** Quarter hours awake and still, for daytime stress (see [Stress]). */
    val stressWindows: List<StressWindow> = emptyList(),
    /** Each device's own night (or day) value for the readings combined by weight, for each device's usual. */
    val readings: List<DeviceReading> = emptyList(),
    /** The day's numbers more than one device measured (or the person chose one of several for), and how each was worked out. */
    val combined: List<CombinedValue> = emptyList(),
    /** Skin temperature against each device's own usual, where more than one device measured it. */
    val skinDeviation: Double? = null,
    val version: Int = VERSION,
) {
    companion object {
        /** Raised whenever the way days are worked out changes, so kept ones are worked out again. */
        const val VERSION = 3
    }
}

/** A person's devices and the choices among them, for working out their days. */
class DeviceContext(val devices: Map<String, DeviceRef>, val choices: DeviceChoices) {
    fun chosen(metric: Metric): String? = choices.choices.lastOrNull { it.metric == metric }?.deviceId
}

/** What the health modules add to a day (see modules/ModuleService). */
interface DayExtras {
    suspend fun today(userId: String, profile: Profile, date: LocalDate): DayExtrasResult
    suspend fun trends(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, ModuleTrend>
}

data class DayExtrasResult(
    val modules: ModulesToday,
    val insights: List<Pair<Int, Insight>>,
    val cyclePhase: CyclePhase?,
    /** Coffee, tea and alcohol (with the food). */
    val drinks: DrinksDay? = null,
)

/** Several parts' days as one: each fills in what it knows. */
fun ModulesToday.merge(o: ModulesToday) = ModulesToday(
    mind = mind ?: o.mind, cycle = cycle ?: o.cycle, continence = continence ?: o.continence, meds = meds ?: o.meds,
    vitals = vitals ?: o.vitals, habits = habits ?: o.habits, symptoms = symptoms ?: o.symptoms, hormones = hormones ?: o.hormones,
    lifeStage = lifeStage ?: o.lifeStage, appointments = appointments ?: o.appointments,
    lowSupplies = lowSupplies ?: o.lowSupplies, neuro = neuro ?: o.neuro, transition = transition ?: o.transition,
)

/** A day's numbers from the health modules, for trends. */
data class ModuleTrend(
    val mood: Double? = null,
    val anxiety: Double? = null,
    val mindfulMinutes: Double? = null,
    val flow: Flow? = null,
    val voids: Int? = null,
    val nightVoids: Int? = null,
    val leaks: Int? = null,
    val dosesTaken: Int? = null,
    val dosesDue: Int? = null,
    val hotFlashes: Int? = null,
    // Blood pressure and glucose, drinks, symptoms and habits (tracking/).
    val systolic: Double? = null,
    val diastolic: Double? = null,
    val glucose: Double? = null,
    val caffeineMg: Double? = null,
    val standardDrinks: Double? = null,
    val symptomWorst: Int? = null,
    val migraine: Boolean? = null,
    val habitsDone: Int? = null,
    val habitsDue: Int? = null,
) {
    fun merge(o: ModuleTrend) = ModuleTrend(
        mood = mood ?: o.mood, anxiety = anxiety ?: o.anxiety, mindfulMinutes = mindfulMinutes ?: o.mindfulMinutes, flow = flow ?: o.flow,
        voids = voids ?: o.voids, nightVoids = nightVoids ?: o.nightVoids, leaks = leaks ?: o.leaks, dosesTaken = dosesTaken ?: o.dosesTaken,
        dosesDue = dosesDue ?: o.dosesDue, systolic = systolic ?: o.systolic, diastolic = diastolic ?: o.diastolic, glucose = glucose ?: o.glucose,
        caffeineMg = caffeineMg ?: o.caffeineMg, standardDrinks = standardDrinks ?: o.standardDrinks, symptomWorst = symptomWorst ?: o.symptomWorst,
        migraine = migraine ?: o.migraine, habitsDone = habitsDone ?: o.habitsDone, habitsDue = habitsDue ?: o.habitsDue, hotFlashes = hotFlashes ?: o.hotFlashes,
    )
}

/** Days: their numbers, scores and insights, and trends over weeks. */
class DayService(
    private val db: Database,
    private val profiles: ProfileStore,
    private val records: Records,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The health modules, once they exist (they need this service themselves). */
    var extras: DayExtras? = null

    /** More parts of each day (blood pressure, habits, symptoms, drinks…), added once they exist. */
    val parts = mutableListOf<DayExtras>()

    /**
     * Whether water shows for the person ([com.health.api.Fields.NUTRITION_WATER]): set at start-up.
     * Off, nothing the server says talks about water; what's logged stays stored. Ask once a request.
     */
    var showsWater: suspend (userId: String) -> Boolean = { true }

    /** Which device the person picked for each reading (see [com.health.server.health.DeviceChoicesService]): set at start-up. */
    var choices: suspend (userId: String) -> DeviceChoices = { DeviceChoices() }

    private suspend fun deviceContext(userId: String) = DeviceContext(records.deviceRefs(userId), choices(userId))

    private fun allExtras(): List<DayExtras> = listOfNotNull(extras) + parts

    private suspend fun extrasToday(userId: String, profile: Profile, date: LocalDate): DayExtrasResult? {
        val results = allExtras().map { it.today(userId, profile, date) }
        if (results.isEmpty()) return null
        return results.reduce { a, b ->
            DayExtrasResult(a.modules.merge(b.modules), a.insights + b.insights, a.cyclePhase ?: b.cyclePhase, a.drinks ?: b.drinks)
        }
    }

    private suspend fun extrasTrends(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, ModuleTrend> {
        val out = mutableMapOf<String, ModuleTrend>()
        allExtras().forEach { part -> part.trends(userId, profile, from, to).forEach { (date, t) -> out[date] = out[date]?.merge(t) ?: t } }
        return out
    }

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

    /**
     * Each day's stats from [from] to [to], kept ones where they're current. Readings more than one
     * device measured are then set against each device's own usual over the days before, which
     * isn't kept, so it's always up to date.
     */
    suspend fun stats(userId: String, from: LocalDate, to: LocalDate, days: Days, context: DeviceContext? = null): List<DayStats> {
        var known = context
        val ctx: suspend () -> DeviceContext = { known ?: deviceContext(userId).also { known = it } }
        val raw = kept(userId, from, to, days, ctx)
        if (raw.none(Combine::needsBaselines)) return raw
        val earlier = kept(userId, from.minusDays(Combine.BASELINE_DAYS.toLong()), from.minusDays(1), days, ctx)
        val all = earlier + raw
        val c = ctx()
        return raw.mapIndexed { i, day ->
            val at = earlier.size + i
            if (Combine.needsBaselines(day)) Combine.finish(day, all.subList(maxOf(0, at - Combine.BASELINE_DAYS), at), c.choices, c.devices) else day
        }
    }

    private suspend fun kept(userId: String, from: LocalDate, to: LocalDate, days: Days, ctx: suspend () -> DeviceContext): List<DayStats> {
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
            val stats = kept[date.toString()] ?: compute(userId, date, days, ctx()).also { fresh ->
                db.tx { update("INSERT OR REPLACE INTO day_cache (user_id, date, data) VALUES (?, ?, ?)", userId, fresh.date, ApiJson.encodeToString(DayStats.serializer(), fresh)) }
            }
            result += stats
            date = date.plusDays(1)
        }
        return result
    }

    /** Works out one day from what's stored. */
    suspend fun compute(userId: String, date: LocalDate, days: Days, context: DeviceContext? = null): DayStats {
        val ctx = context ?: deviceContext(userId)
        val devices = ctx.devices
        val start = days.start(date)
        val end = days.end(date)
        val sessions = records.sleep(userId, start, end)
        // Nights that end today began yesterday evening: their readings count too.
        val from = minOf(start - 14 * HOUR, sessions.minOfOrNull { it.start } ?: start)
        val all = records.samples(userId, from, end)
        val today = all.filter { it.start in start until end }

        // Sums (steps, calories…): each quarter hour from the device that counted most, so a ring and a
        // phone don't both count the same walk, and one taken off doesn't lose the other's steps.
        fun count(metric: Metric) = Combine.counts(metric, today, devices, ctx.chosen(metric), start, end)
        val steps = count(Metric.STEPS)
        val distance = count(Metric.DISTANCE)
        val calories = count(Metric.ACTIVE_CALORIES)
        val floors = count(Metric.FLOORS)
        val hourly = steps?.let { c ->
            DoubleArray(24).also { hours -> c.slots.forEachIndexed { i, v -> hours[days.hourOf(c.slotStart + i * Combine.SLOT_MS)] += v } }.map { it.roundToInt() }
        }.orEmpty()

        fun latest(metric: Metric) = today.lastOrNull { it.metric == metric }?.value

        val heartChoice = ctx.chosen(Metric.HEART_RATE)
        val nights = pickNights(Combine.nights(sessions, devices, ctx.choices.sleepDeviceId), all, heartChoice)
        val main = nights.firstOrNull()
        val mainSpan = main?.let { it.start..it.end }
        val nightHeartAll = mainSpan?.let { span -> all.filter { it.metric == Metric.HEART_RATE && it.start in span } }.orEmpty()
        val nightHeart = Combine.pick(nightHeartAll, Metric.HEART_RATE, heartChoice)

        val heart = Combine.heart(today, devices, heartChoice)
        val restingChoice = ctx.chosen(Metric.RESTING_HEART_RATE)
        val (resting, restingCombined) = Combine.resting(
            today.filter { it.metric == Metric.RESTING_HEART_RATE },
            // A choice for resting heart rate picks among every device's own; otherwise it follows the heart-rate choice.
            if (restingChoice != null) nightHeartAll else nightHeart,
            devices, restingChoice,
        )
        // HRV, blood oxygen, breathing and skin temperature: each device's own night (or the day, without
        // one); stress and a device's own temperature deviation: the day's.
        val readings = Combine.WEIGHTED.flatMap { metric ->
            val inNight = if (metric in NIGHT_READINGS && mainSpan != null) all.filter { it.metric == metric && it.start in mainSpan } else emptyList()
            Combine.readings(metric, inNight.ifEmpty { today.filter { it.metric == metric } }, devices, mainSpan.takeIf { inNight.isNotEmpty() })
        }
        val nightLine = if (nightHeart.map { it.deviceId }.distinct().size >= 2) Combine.line(nightHeart) else nightHeart.map { Point(it.start, it.value) }
        val lowestAt = mainSpan?.let { span -> lowestPosition(nightLine, span) }

        val workouts = records.workouts(userId, start, end)
        val (nutrition, water) = food(userId, date)
        // Tonight's sleep (which ends tomorrow) closes the day's waking hours.
        val tonight = records.sleep(userId, end, end + 16 * HOUR).filter { it.start < end }.minOfOrNull { it.start }
        val windows = stressWindows(
            listOf(Metric.HEART_RATE, Metric.HRV, Metric.STRESS).fold(today) { list, m -> Combine.pick(list, m, ctx.chosen(m)) },
            workouts.map { it.start until it.end }, sessions.map { it.start until it.end },
            wakeStart = main?.end?.takeIf { it in start until end } ?: (start + 7 * HOUR),
            wakeEnd = minOf(end, clock(), tonight ?: Long.MAX_VALUE),
            dayStart = start,
        )

        var stats = DayStats(
            date = date.toString(),
            steps = steps?.total?.roundToInt(),
            distanceM = distance?.total,
            activeCalories = calories?.total ?: workouts.mapNotNull { it.activeCalories }.takeIf { it.isNotEmpty() }?.sum(),
            floors = floors?.total,
            hourlySteps = hourly,
            restingHr = resting,
            avgHr = heart.avg,
            minHr = heart.min,
            maxHr = heart.max,
            sleep = main,
            naps = nights.drop(1),
            lowestHrPosition = lowestAt,
            weightKg = latest(Metric.WEIGHT),
            bodyFat = latest(Metric.BODY_FAT),
            nutrition = nutrition,
            waterMl = water,
            workouts = workouts.size,
            workoutMinutes = workouts.sumOf { ((it.end - it.start) / 60_000).toInt() },
            stressWindows = windows,
            readings = readings,
            combined = listOfNotNull(steps?.combined, distance?.combined, calories?.combined, floors?.combined, heart.combined, restingCombined),
        )
        // Each device's usual isn't known here (see [stats]): this is the plain weighted value.
        Combine.WEIGHTED.forEach { m -> stats = stats.withReading(m, Combine.weighted(m, readings, emptyMap(), ctx.chosen(m), devices)) }
        return stats.copy(hasData = today.isNotEmpty() || nights.isNotEmpty() || workouts.isNotEmpty() || nutrition != null || water > 0)
    }

    private suspend fun food(userId: String, date: LocalDate): Pair<Nutrients?, Int> = db.tx {
        val entries = query("SELECT nutrients FROM food_entries WHERE user_id = ? AND date = ?", userId, date.toString()) { it.string("nutrients") }
            .mapNotNull { runCatching { ApiJson.decodeFromString(Nutrients.serializer(), it) }.getOrNull() }
        val water = long("SELECT COALESCE(SUM(ml), 0) FROM water WHERE user_id = ? AND date = ?", userId, date.toString()).toInt() +
            // Drinks count towards water, all but alcohol.
            long("SELECT COALESCE(SUM(volume_ml), 0) FROM drinks WHERE user_id = ? AND date = ? AND alcohol_g = 0", userId, date.toString()).toInt()
        (if (entries.isEmpty()) null else entries.reduce(Nutrients::plus)) to water
    }

    /**
     * The main night first, then naps. Nights more than one device recorded come already combined
     * (see [Combine.nights]); where one still overlaps another (a night entered by hand), the one with stages wins.
     */
    private fun pickNights(nights: List<Combine.Night>, samples: List<StoredSample>, heartChoice: String?): List<SleepSummary> {
        val ranked = nights.sortedWith(
            compareByDescending<Combine.Night> { n -> n.session.stages.any { it.stage != SleepStage.AWAKE && it.stage != SleepStage.ASLEEP } }
                .thenByDescending { it.session.end - it.session.start },
        )
        val kept = mutableListOf<Combine.Night>()
        ranked.forEach { n ->
            val s = n.session
            val overlapping = kept.any { k -> overlap(k.session.start, k.session.end, s.start, s.end) > 0.5 * minOf(k.session.end - k.session.start, s.end - s.start) }
            if (!overlapping) kept += n
        }
        val main = kept.maxByOrNull { val d = it.session.end - it.session.start; if (d >= 3 * HOUR) d + 100 * HOUR else d }
        return listOfNotNull(main).plus(kept.filter { it !== main }.sortedBy { it.session.start }).map { night(it, samples, heartChoice) }
    }

    /** A night's summary, with time asleep from every device that recorded it. */
    private fun night(n: Combine.Night, samples: List<StoredSample>, heartChoice: String?): SleepSummary {
        val s = n.session
        val during = Combine.pick(samples.filter { it.start in s.start..s.end }, Metric.HEART_RATE, heartChoice)
        val summary = summarize(s, during).copy(combined = n.combined)
        val asleep = n.asleepMinutes ?: return summary
        return summary.copy(
            asleepMinutes = asleep,
            efficiency = if (summary.inBedMinutes > 0) (asleep * 100 / summary.inBedMinutes).coerceIn(0, 100) else 0,
            awakeMinutes = (summary.inBedMinutes - asleep).coerceAtLeast(0),
            // The stages are one device's; light sleep takes up the difference, so they add up to the time asleep.
            lightMinutes = if (summary.staged) (asleep - summary.deepMinutes - summary.remMinutes).coerceAtLeast(0) else summary.lightMinutes,
        )
    }

    private fun overlap(a1: Long, a2: Long, b1: Long, b2: Long) = (minOf(a2, b2) - maxOf(a1, b1)).coerceAtLeast(0).toDouble()

    // ---- Summaries ----

    suspend fun summary(userId: String, dateText: String): DaySummary {
        val profile = profiles.get(userId)
        val days = Days(Days.zoneOf(profile.timezone))
        val date = days.parse(dateText, clock())
        val ctx = deviceContext(userId)
        val history = stats(userId, date.minusDays(HISTORY_DAYS), date, days, ctx)
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
        val heart = Combine.pick(records.samples(userId, start, end, listOf(Metric.HEART_RATE)), Metric.HEART_RATE, ctx.chosen(Metric.HEART_RATE))
        val workouts = records.workouts(userId, start, end)
        val checkIn = checkIn(userId, date)
        val more = extrasToday(userId, profile, date)
        val isToday = date == days.today(clock())
        val stressDays = stressDays(history)
        val stressDay = stressDays.last()
        val sleepScores = history.map { Scoring.sleep(it.sleep, goals, days.zone)?.score }
        val resilience = Stress.resilience(stressDays.takeLast(14), sleepScores.takeLast(14), stressDays.dropLast(14).takeLast(14), sleepScores.dropLast(14).takeLast(14))
        val stressInsights = stressInsights(stressDay, resilience, isToday)
        val insights = InsightRules.insights(
            InsightRules.Context(
                today = today, history = before, profile = profile, target = target, readiness = readiness, sleep = sleep,
                isToday = date == days.today(clock()), hourNow = days.hourOf(clock()), restingCalories = resting,
                cyclePhase = more?.cyclePhase, extra = more?.insights.orEmpty() + stressInsights, water = showsWater(userId),
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
            stressDay = stressDay,
            resilience = resilience,
            sleepSummary = today.sleep,
            naps = today.naps,
            workouts = workouts,
            nutrition = today.nutrition ?: Nutrients(),
            nutritionTarget = target,
            waterMl = today.waterMl,
            drinks = more?.drinks,
            weightKg = today.weightKg,
            bodyFat = today.bodyFat,
            checkIn = checkIn,
            insights = insights,
            hourlySteps = today.hourlySteps,
            // Merged every five minutes, so two devices make one line.
            heartRate = Combine.line(heart).map { Point(it.t, (it.v * 10).roundToInt() / 10.0) },
            goals = goals,
            hasData = today.hasData,
            modules = more?.modules ?: ModulesToday(),
            combined = today.combined,
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
        val modules = extrasTrends(userId, profile, first, last)
        val rows = all.indices.drop(offset).map { i ->
            val day = all[i]
            val before = all.subList(maxOf(0, i - offset), i)
            val sleep = Scoring.sleep(day.sleep, goals, days.zone)
            val date = LocalDate.parse(day.date)
            val stress = Stress.day(day.date, day.stressWindows, Stress.baseline(day, before))
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
                stressedMinutes = stress?.stressedMinutes,
                restoredMinutes = stress?.restoredMinutes,
            ).let { row ->
                modules[day.date]?.let { m ->
                    row.copy(
                        mood = m.mood, anxiety = m.anxiety, mindfulMinutes = m.mindfulMinutes, flow = m.flow, voids = m.voids,
                        nightVoids = m.nightVoids, leaks = m.leaks, dosesTaken = m.dosesTaken, dosesDue = m.dosesDue,
                        hotFlashes = m.hotFlashes,
                        systolic = m.systolic, diastolic = m.diastolic, glucose = m.glucose, caffeineMg = m.caffeineMg,
                        standardDrinks = m.standardDrinks, symptomWorst = m.symptomWorst, migraine = m.migraine,
                        habitsDone = m.habitsDone, habitsDue = m.habitsDue,
                    )
                } ?: row
            }
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

    /** Each day's stress against its own baseline (the days before it), aligned with [history]. */
    private fun stressDays(history: List<DayStats>): List<com.health.api.StressDay?> =
        history.indices.map { i -> Stress.day(history[i].date, history[i].stressWindows, Stress.baseline(history[i], history.subList(0, i))) }

    private fun stressInsights(day: com.health.api.StressDay?, resilience: com.health.api.Resilience?, isToday: Boolean): List<Pair<Int, Insight>> = buildList {
        when (day?.summary) {
            "Stressful" -> add(54 to Insight("info", "A stressful day", "${Scoring.hm(day.stressedMinutes)} stressed${if (isToday) " so far" else ""}. A short walk, time outside or a few minutes of slow breathing can help you reset.", "activity"))
            "Restored" -> add(32 to Insight("good", "A restful day", "${Scoring.hm(day.restoredMinutes)} of calm, restoring time.", "sparkles"))
        }
        if (resilience != null && resilience.trend == "down" && resilience.level <= com.health.api.ResilienceLevel.ADEQUATE) {
            add(44 to Insight("info", "Resilience is ${resilience.level.label.lowercase()}", "Stress has outweighed recovery lately. Extra sleep and calm time during the day help it build back up.", "battery-low"))
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
        /** Readings taken from the night where there is one (as their own night value). */
        private val NIGHT_READINGS = setOf(Metric.HRV, Metric.SPO2, Metric.RESPIRATORY_RATE, Metric.SKIN_TEMPERATURE)
        /** Days of history behind each score: baselines look back a month. */
        const val HISTORY_DAYS = 30L

        /** The day's quarter hours awake and still, with their heart rate, HRV and stress readings. */
        fun stressWindows(
            samples: List<StoredSample>, workouts: List<LongRange>, sleep: List<LongRange>, wakeStart: Long, wakeEnd: Long, dayStart: Long,
        ): List<StressWindow> {
            if (wakeEnd - wakeStart < Stress.WINDOW_MS) return emptyList()
            val byWindow = samples.groupBy { (it.start - dayStart).floorDiv(Stress.WINDOW_MS) }
            val busy = workouts + sleep
            val out = mutableListOf<StressWindow>()
            var index = (wakeStart - dayStart + Stress.WINDOW_MS - 1).floorDiv(Stress.WINDOW_MS)
            while (dayStart + (index + 1) * Stress.WINDOW_MS <= wakeEnd) {
                val from = dayStart + index * Stress.WINDOW_MS
                val to = from + Stress.WINDOW_MS
                val list = byWindow[index].orEmpty()
                index++
                if (busy.any { it.first < to && from < it.last }) continue
                // Steps from the device that counted most, and each device's average (so two devices aren't counted twice).
                if ((list.filter { it.metric == Metric.STEPS }.groupBy { it.deviceId }.values.maxOfOrNull { l -> l.sumOf { it.value } } ?: 0.0) > Stress.ACTIVE_STEPS) continue
                fun avg(metric: Metric) = list.filter { it.metric == metric }.groupBy { it.deviceId }.values.map { l -> l.sumOf { it.value } / l.size }
                    .takeIf { it.isNotEmpty() }?.average()?.let { (it * 10).roundToInt() / 10.0 }
                val hr = avg(Metric.HEART_RATE)
                val device = avg(Metric.STRESS)
                if (hr == null && device == null) continue
                out += StressWindow(from, hr, avg(Metric.HRV), device)
            }
            return out
        }

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
            // With more than one device, heart rate merged every five minutes and each device's own HRV averaged.
            val heart = Combine.nightHeart(samples.filter { it.metric == Metric.HEART_RATE && it.start in s.start..s.end })
            val hrv = samples.filter { it.metric == Metric.HRV && it.start in s.start..s.end }.groupBy { it.deviceId }.values.map { l -> l.sumOf { it.value } / l.size }
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
                lowestHeartRate = Combine.lowest(heart),
                avgHeartRate = heart.takeIf { it.isNotEmpty() }?.average(),
                avgHrv = hrv.takeIf { it.isNotEmpty() }?.average(),
                stages = stages,
                source = s.source,
                sessionId = s.id,
            )
        }

        private fun lowestPosition(heart: List<Point>, span: LongRange): Double? {
            if (heart.size < 6 || span.last <= span.first) return null
            val window = heart.windowed(3).minByOrNull { w -> w.sumOf { it.v } } ?: return null
            return ((window[1].t - span.first).toDouble() / (span.last - span.first)).coerceIn(0.0, 1.0)
        }
    }
}
