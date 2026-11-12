package com.health.server.tracking

import com.health.api.BloodPressure
import com.health.api.BpCategory
import com.health.api.GlucoseContext
import com.health.api.GlucoseReading
import com.health.api.GlucoseUnit
import com.health.api.Insight
import com.health.api.Metric
import com.health.api.Module
import com.health.api.VitalsSummary
import com.health.api.VitalsTargets
import com.health.api.VitalsToday
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.db.Tx
import com.health.server.health.ProfileStore
import com.health.server.health.Records
import com.health.server.util.Days
import com.health.server.util.Random
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Blood pressure and glucose: entered by hand, sent by a monitor (in an ingest batch), or as
 * samples of the BLOOD_PRESSURE_* and BLOOD_GLUCOSE metrics (from Health Connect), paired by time.
 */
class VitalsService(
    private val db: Database,
    private val records: Records,
    private val profiles: ProfileStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // ---- Blood pressure ----

    /** Readings in [from, to) (epoch ms), newest first. */
    suspend fun bloodPressure(userId: String, from: Long, to: Long): List<BloodPressure> {
        val days = profiles.days(userId)
        val rows = db.tx {
            query(
                """SELECT v.*, d.name AS device_name FROM vitals v LEFT JOIN devices d ON d.id = v.device_id
                   WHERE v.user_id = ? AND v.kind = 'BP' AND v.at >= ? AND v.at < ? ORDER BY v.at DESC""",
                userId, from, to,
            ) { r ->
                BloodPressure(
                    at = r.long("at"), systolic = r.double("v1").roundToInt(), diastolic = (r.doubleOrNull("v2") ?: 0.0).roundToInt(),
                    pulse = r.doubleOrNull("v3")?.roundToInt(), arm = r.stringOrNull("context"), note = r.stringOrNull("note"),
                    id = r.string("id"), date = r.string("date"), source = r.stringOrNull("device_name") ?: "Entered by hand",
                )
            }
        }
        // Samples (from Health Connect, or entered as measurements): systolic and diastolic at the same moment.
        val samples = records.samples(userId, from, to, listOf(Metric.BLOOD_PRESSURE_SYSTOLIC, Metric.BLOOD_PRESSURE_DIASTOLIC, Metric.HEART_RATE))
        val paired = samples.filter { it.metric == Metric.BLOOD_PRESSURE_SYSTOLIC }.mapNotNull { sys ->
            val dia = samples.firstOrNull { it.metric == Metric.BLOOD_PRESSURE_DIASTOLIC && it.deviceId == sys.deviceId && kotlin.math.abs(it.start - sys.start) <= 60_000 }
                ?: return@mapNotNull null
            val pulse = samples.firstOrNull { it.metric == Metric.HEART_RATE && it.deviceId == sys.deviceId && it.start == sys.start }
            BloodPressure(sys.start, sys.value.roundToInt(), dia.value.roundToInt(), pulse?.value?.roundToInt(), date = days.dateOf(sys.start).toString(), source = sys.deviceName)
        }
        return (rows + paired.filter { p -> rows.none { kotlin.math.abs(it.at - p.at) <= 60_000 } }).sortedByDescending { it.at }
    }

    suspend fun bloodPressure(userId: String, count: Int): List<BloodPressure> {
        val (from, to) = range(userId, count)
        return bloodPressure(userId, from, to)
    }

    suspend fun saveBloodPressure(userId: String, input: BloodPressure): BloodPressure {
        val bp = validated(input, clock())
        val days = profiles.days(userId)
        val id = Random.id()
        val date = days.dateOf(bp.at)
        db.tx {
            update(
                "INSERT INTO vitals (id, user_id, kind, at, date, v1, v2, v3, context, note, device_id, created_at) VALUES (?, ?, 'BP', ?, ?, ?, ?, ?, ?, ?, NULL, ?)",
                id, userId, bp.at, date.toString(), bp.systolic.toDouble(), bp.diastolic.toDouble(), bp.pulse?.toDouble(), bp.arm, bp.note, clock(),
            )
        }
        return bp.copy(id = id, date = date.toString(), source = "Entered by hand")
    }

    // ---- Glucose ----

    suspend fun glucose(userId: String, from: Long, to: Long): List<GlucoseReading> {
        val days = profiles.days(userId)
        val rows = db.tx {
            query(
                """SELECT v.*, d.name AS device_name FROM vitals v LEFT JOIN devices d ON d.id = v.device_id
                   WHERE v.user_id = ? AND v.kind = 'GLUCOSE' AND v.at >= ? AND v.at < ? ORDER BY v.at DESC""",
                userId, from, to,
            ) { r ->
                GlucoseReading(
                    at = r.long("at"), mmolL = r.double("v1"),
                    context = r.stringOrNull("context")?.let { runCatching { GlucoseContext.valueOf(it) }.getOrNull() } ?: GlucoseContext.OTHER,
                    note = r.stringOrNull("note"), id = r.string("id"), date = r.string("date"), source = r.stringOrNull("device_name") ?: "Entered by hand",
                )
            }
        }
        val samples = records.samples(userId, from, to, listOf(Metric.BLOOD_GLUCOSE)).map {
            GlucoseReading(it.start, it.value, GlucoseContext.OTHER, date = days.dateOf(it.start).toString(), source = it.deviceName)
        }
        return (rows + samples.filter { s -> rows.none { kotlin.math.abs(it.at - s.at) <= 60_000 } }).sortedByDescending { it.at }
    }

    suspend fun glucose(userId: String, count: Int): List<GlucoseReading> {
        val (from, to) = range(userId, count)
        return glucose(userId, from, to)
    }

    suspend fun saveGlucose(userId: String, input: GlucoseReading): GlucoseReading {
        val g = validated(input, clock())
        val date = profiles.days(userId).dateOf(g.at)
        val id = Random.id()
        db.tx {
            update(
                "INSERT INTO vitals (id, user_id, kind, at, date, v1, context, note, device_id, created_at) VALUES (?, ?, 'GLUCOSE', ?, ?, ?, ?, ?, NULL, ?)",
                id, userId, g.at, date.toString(), g.mmolL, g.context, g.note, clock(),
            )
        }
        return g.copy(id = id, date = date.toString(), source = "Entered by hand")
    }

    /** Deletes a reading entered by hand. */
    suspend fun delete(userId: String, kind: String, id: String) = db.tx {
        if (update("DELETE FROM vitals WHERE id = ? AND user_id = ? AND kind = ? AND device_id IS NULL", id, userId, kind) == 0) {
            throw ApiError.notFound("No such reading (only ones entered by hand can be deleted)")
        }
    }

    // ---- Summaries ----

    suspend fun summary(userId: String, count: Int): VitalsSummary {
        val profile = profiles.get(userId)
        val (from, to) = range(userId, count)
        return summarize(count, bloodPressure(userId, from, to), glucose(userId, from, to), profile.vitalsTargets, Days(Days.zoneOf(profile.timezone)))
    }

    /** Today's latest readings, with the time in range (for a sensor) over the day. */
    suspend fun today(userId: String, targets: VitalsTargets, date: LocalDate, days: Days): VitalsToday? {
        val end = days.end(date)
        val bp = bloodPressure(userId, days.start(date.minusDays(6)), end)
        val glucose = glucose(userId, days.start(date), end)
        val latestBp = bp.firstOrNull()
        if (latestBp == null && glucose.isEmpty()) return null
        return VitalsToday(
            bloodPressure = latestBp,
            category = latestBp?.let { category(it.systolic, it.diastolic) },
            glucose = glucose.firstOrNull(),
            timeInRange = glucose.takeIf { it.size >= 3 }?.let { inRange(it, targets).second },
            targets = targets,
        )
    }

    /** The day's insights: a very high reading, lows, and how the last two weeks have gone. */
    suspend fun insights(userId: String, targets: VitalsTargets, date: LocalDate, days: Days, isToday: Boolean): List<Pair<Int, Insight>> = buildList {
        val end = days.end(date)
        val todayBp = bloodPressure(userId, days.start(date), end)
        val recentBp = bloodPressure(userId, days.start(date.minusDays(13)), end)
        val crisis = todayBp.firstOrNull { category(it.systolic, it.diastolic) == BpCategory.CRISIS }
        if (crisis != null && (isToday || todayBp.first() == crisis)) {
            add(
                98 to Insight(
                    "warn", "Very high blood pressure: ${crisis.systolic}/${crisis.diastolic}",
                    "Sit quietly for five minutes and measure again. If it's still this high, or you have chest pain, shortness of breath, " +
                        "back pain, numbness, weakness, trouble speaking or changes in your vision, get emergency help now.",
                    "heart", Module.VITALS,
                ),
            )
        } else if (recentBp.size >= 3) {
            val sys = recentBp.map { it.systolic }.average()
            val dia = recentBp.map { it.diastolic }.average()
            if (sys >= targets.systolicHigh + 10 || dia >= targets.diastolicHigh + 10) {
                add(62 to Insight("info", "Blood pressure has been high lately", "Averaging ${sys.roundToInt()}/${dia.roundToInt()} over two weeks. Worth sharing with your doctor.", "heart", Module.VITALS))
            } else if (sys < targets.systolicHigh && dia < targets.diastolicHigh && recentBp.size >= 6) {
                add(28 to Insight("good", "Blood pressure in your range", "Averaging ${sys.roundToInt()}/${dia.roundToInt()} over two weeks.", "heart", Module.VITALS))
            }
        }
        val glucose = glucose(userId, days.start(date), end)
        val low = glucose.filter { it.mmolL < targets.glucoseLow }.maxByOrNull { it.at }
        if (low != null) {
            add(
                92 to Insight(
                    "warn", "Low glucose: ${glucoseText(low.mmolL, targets.glucoseUnit)} at ${time(days, low.at)}",
                    "If you feel shaky, sweaty or unwell, have something sugary like juice or glucose tablets, and check again in 15 minutes.",
                    "droplet", Module.VITALS,
                ),
            )
        } else if (glucose.size >= 12) {
            val (_, tir, above) = inRange(glucose, targets)
            if (tir >= 70) add(30 to Insight("good", "$tir% of the day in range", "Your glucose stayed between ${glucoseText(targets.glucoseLow, targets.glucoseUnit)} and ${glucoseText(targets.glucoseHigh, targets.glucoseUnit)} for most of the day.", "droplet", Module.VITALS))
            else if (above >= 40) add(45 to Insight("info", "Glucose above your range for $above% of the day", "Meals, stress, illness and less movement than usual can all raise it.", "droplet", Module.VITALS))
        } else glucose.firstOrNull { it.mmolL > targets.glucoseHigh && it.context != GlucoseContext.CGM }?.let { high ->
            add(45 to Insight("info", "Glucose above your range", "${glucoseText(high.mmolL, targets.glucoseUnit)} at ${time(days, high.at)} (${high.context.label.lowercase()}).", "droplet", Module.VITALS))
        }
    }

    /** Readings that arrived (were sent or entered) in [from, to) and were taken in the day before, for alerts. */
    suspend fun arrived(userId: String, from: Long, to: Long): Pair<List<BloodPressure>, List<GlucoseReading>> {
        val rows = db.tx {
            query("SELECT * FROM vitals WHERE user_id = ? AND created_at >= ? AND created_at < ? AND at >= ? ORDER BY at", userId, from, to, to - 86_400_000L) { r ->
                Triple(r.string("kind"), r.string("id"), Triple(r.long("at"), r.double("v1"), Pair(r.doubleOrNull("v2"), r.stringOrNull("context"))))
            }
        }
        val bp = rows.filter { it.first == "BP" }.map { (_, id, v) -> BloodPressure(v.first, v.second.roundToInt(), (v.third.first ?: 0.0).roundToInt(), id = id) }
        val glucose = rows.filter { it.first == "GLUCOSE" }.map { (_, id, v) ->
            GlucoseReading(v.first, v.second, v.third.second?.let { c -> runCatching { GlucoseContext.valueOf(c) }.getOrNull() } ?: GlucoseContext.OTHER, id = id)
        }
        return bp to glucose
    }

    /** Per-day averages, for trends. */
    suspend fun daily(userId: String, from: LocalDate, to: LocalDate, days: Days): Map<LocalDate, Triple<Double?, Double?, Double?>> {
        val start = days.start(from)
        val end = days.end(to)
        val bp = bloodPressure(userId, start, end).groupBy { days.dateOf(it.at) }
        val glucose = glucose(userId, start, end).groupBy { days.dateOf(it.at) }
        return (bp.keys + glucose.keys).associateWith { d ->
            Triple(bp[d]?.map { it.systolic.toDouble() }?.average(), bp[d]?.map { it.diastolic.toDouble() }?.average(), glucose[d]?.map { it.mmolL }?.average())
        }
    }

    private suspend fun range(userId: String, count: Int): Pair<Long, Long> {
        val days = profiles.days(userId)
        val today = days.today(clock())
        return days.start(today.minusDays((count.coerceIn(1, 3650) - 1).toLong())) to days.end(today)
    }

    companion object {
        const val MG_PER_MMOL = 18.0182

        /** The American Heart Association's categories; low below 90/60. */
        fun category(systolic: Int, diastolic: Int): BpCategory = when {
            systolic > 180 || diastolic > 120 -> BpCategory.CRISIS
            systolic >= 140 || diastolic >= 90 -> BpCategory.STAGE_2
            systolic >= 130 || diastolic >= 80 -> BpCategory.STAGE_1
            systolic >= 120 -> BpCategory.ELEVATED
            systolic < 90 || diastolic < 60 -> BpCategory.LOW
            else -> BpCategory.NORMAL
        }

        /** Percent of readings below, in and above range. Sensor readings, evenly spaced, stand for time. */
        fun inRange(readings: List<GlucoseReading>, targets: VitalsTargets): Triple<Int, Int, Int> {
            if (readings.isEmpty()) return Triple(0, 0, 0)
            val sensor = readings.filter { it.context == GlucoseContext.CGM }
            val list = if (sensor.size >= readings.size / 2) sensor.ifEmpty { readings } else readings
            val below = list.count { it.mmolL < targets.glucoseLow } * 100.0 / list.size
            val above = list.count { it.mmolL > targets.glucoseHigh } * 100.0 / list.size
            val b = below.roundToInt()
            val a = above.roundToInt()
            return Triple(b, (100 - b - a).coerceAtLeast(0), a)
        }

        /** The glucose management indicator: an A1c estimate from 14 days or more of sensor data. */
        fun gmi(readings: List<GlucoseReading>, days: Days): Double? {
            val sensor = readings.filter { it.context == GlucoseContext.CGM }
            if (sensor.map { days.dateOf(it.at) }.distinct().size < 14) return null
            val mgdl = sensor.map { it.mmolL }.average() * MG_PER_MMOL
            return ((3.31 + 0.02392 * mgdl) * 10).roundToInt() / 10.0
        }

        fun summarize(count: Int, bp: List<BloodPressure>, glucose: List<GlucoseReading>, targets: VitalsTargets, days: Days? = null): VitalsSummary {
            val sys = bp.map { it.systolic.toDouble() }.takeIf { it.isNotEmpty() }?.average()
            val dia = bp.map { it.diastolic.toDouble() }.takeIf { it.isNotEmpty() }?.average()
            val (below, tir, above) = inRange(glucose, targets)
            val patterns = buildList {
                // Morning and evening readings, where both are taken.
                if (days != null && bp.size >= 6) {
                    val morning = bp.filter { days.hourOf(it.at) in 4..11 }
                    val evening = bp.filter { days.hourOf(it.at) in 17..23 }
                    if (morning.size >= 3 && evening.size >= 3) {
                        val diff = morning.map { it.systolic }.average() - evening.map { it.systolic }.average()
                        if (kotlin.math.abs(diff) >= 10) add(Insight("info", "Higher in the ${if (diff > 0) "morning" else "evening"}", "Your top number averages ${kotlin.math.abs(diff).roundToInt()} higher in the ${if (diff > 0) "morning" else "evening"}. Worth mentioning to your doctor.", "heart", Module.VITALS))
                    }
                }
                val afterMeal = glucose.filter { it.context == GlucoseContext.AFTER_MEAL }
                if (afterMeal.size >= 4 && afterMeal.count { it.mmolL > targets.glucoseHigh } * 2 >= afterMeal.size) {
                    add(Insight("info", "Higher after meals", "Most readings after a meal were above your range. A short walk after eating often helps.", "droplet", Module.VITALS))
                }
                val lows = glucose.count { it.mmolL < targets.glucoseLow }
                if (lows >= 2) add(Insight("warn", "$lows low readings", "Lows that keep happening are worth talking to your doctor about.", "droplet", Module.VITALS))
            }
            return VitalsSummary(
                days = count,
                bloodPressure = bp,
                glucose = glucose,
                systolicAverage = sys?.let { (it * 10).roundToInt() / 10.0 },
                diastolicAverage = dia?.let { (it * 10).roundToInt() / 10.0 },
                category = if (sys != null && dia != null) category(sys.roundToInt(), dia.roundToInt()) else null,
                glucoseAverage = glucose.map { it.mmolL }.takeIf { it.isNotEmpty() }?.average()?.let { (it * 10).roundToInt() / 10.0 },
                fastingAverage = glucose.filter { it.context == GlucoseContext.FASTING }.map { it.mmolL }.takeIf { it.isNotEmpty() }?.average()?.let { (it * 10).roundToInt() / 10.0 },
                timeBelow = below.takeIf { glucose.isNotEmpty() },
                timeInRange = tir.takeIf { glucose.isNotEmpty() },
                timeAbove = above.takeIf { glucose.isNotEmpty() },
                gmi = days?.let { gmi(glucose, it) },
                targets = targets,
                patterns = patterns,
            )
        }

        fun glucoseText(mmol: Double, unit: GlucoseUnit): String = when (unit) {
            GlucoseUnit.MMOL_L -> String.format(Locale.ROOT, "%.1f mmol/L", mmol)
            GlucoseUnit.MG_DL -> "${(mmol * MG_PER_MMOL).roundToInt()} mg/dL"
        }

        private fun time(days: Days, ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(days.zone).toLocalTime().let { String.format(Locale.ROOT, "%d:%02d", it.hour, it.minute) }

        fun validated(bp: BloodPressure, now: Long): BloodPressure {
            if (bp.systolic !in 50..260 || bp.diastolic !in 30..180 || bp.diastolic >= bp.systolic) throw ApiError.badRequest("That blood pressure doesn't look right: the top number is 50–260 and above the bottom one, 30–180")
            bp.pulse?.let { if (it !in 25..240) throw ApiError.badRequest("A pulse is between 25 and 240") }
            if (bp.at > now + 3_600_000 || bp.at < 946_684_800_000L) throw ApiError.badRequest("That time doesn't look right")
            return bp.copy(arm = bp.arm?.trim()?.lowercase()?.take(20)?.ifBlank { null }, note = bp.note?.trim()?.take(1000)?.ifBlank { null }, id = null, date = null, source = null)
        }

        fun validated(g: GlucoseReading, now: Long): GlucoseReading {
            if (!g.mmolL.isFinite() || g.mmolL !in 1.0..40.0) throw ApiError.badRequest("Glucose is in mmol/L, between 1 and 40 (mg/dL ÷ 18)")
            if (g.at > now + 3_600_000 || g.at < 946_684_800_000L) throw ApiError.badRequest("That time doesn't look right")
            return g.copy(mmolL = (g.mmolL * 100).roundToInt() / 100.0, note = g.note?.trim()?.take(1000)?.ifBlank { null }, id = null, date = null, source = null)
        }

        /**
         * A monitor's readings, from an ingest batch: each replaces what the same device sent for the
         * same moment, so a resend is harmless. Returns how many were kept.
         */
        fun putFromDevice(tx: Tx, userId: String, deviceId: String, days: Days, bp: List<BloodPressure>, glucose: List<GlucoseReading>, now: Long): Int = with(tx) {
            val cleanBp = bp.mapNotNull { runCatching { validated(it, now) }.getOrNull() }.distinctBy { it.at }
            val cleanGlucose = glucose.mapNotNull { runCatching { validated(it, now) }.getOrNull() }.distinctBy { it.at }
            cleanBp.forEach { r ->
                update("DELETE FROM vitals WHERE device_id = ? AND kind = 'BP' AND at = ?", deviceId, r.at)
                update(
                    "INSERT INTO vitals (id, user_id, kind, at, date, v1, v2, v3, context, note, device_id, created_at) VALUES (?, ?, 'BP', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Random.id(), userId, r.at, days.dateOf(r.at).toString(), r.systolic.toDouble(), r.diastolic.toDouble(), r.pulse?.toDouble(), r.arm, r.note, deviceId, now,
                )
            }
            cleanGlucose.forEach { r ->
                update("DELETE FROM vitals WHERE device_id = ? AND kind = 'GLUCOSE' AND at = ?", deviceId, r.at)
                update(
                    "INSERT INTO vitals (id, user_id, kind, at, date, v1, context, note, device_id, created_at) VALUES (?, ?, 'GLUCOSE', ?, ?, ?, ?, ?, ?, ?)",
                    Random.id(), userId, r.at, days.dateOf(r.at).toString(), r.mmolL, r.context, r.note, deviceId, now,
                )
            }
            cleanBp.size + cleanGlucose.size
        }
    }
}
