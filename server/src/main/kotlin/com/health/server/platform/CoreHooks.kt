package com.health.server.platform

import com.health.api.ContinenceEntry
import com.health.api.CycleDay
import com.health.api.DeviceKind
import com.health.api.DigestSection
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.JournalEntry
import com.health.api.MindfulnessSession
import com.health.api.Module
import com.health.api.MoodEntry
import com.health.api.Profile
import com.health.api.ReminderAction
import com.health.api.ReportSectionKey
import com.health.api.ScheduleKind
import com.health.api.Screener
import com.health.api.ScreenerResult
import com.health.api.TrendDay
import com.health.api.WebhookEvent
import com.health.server.Services
import com.health.server.modules.EntryKind
import com.health.server.modules.Schedules
import com.health.server.util.Days
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** The platform's part for the features that were there first. */
object CoreHooks {
    fun all(services: Services): List<FeatureHooks> = listOf(
        OverviewHooks(services), DeviceHooks(services), SleepHooks(services), ActivityHooks(services), BodyHooks(services),
        NutritionHooks(services), MindHooks(services), CycleHooks(services), ContinenceHooks(services), MedsHooks(services),
    )
}

private fun zoneOf(profile: Profile) = Days(Days.zoneOf(profile.timezone))

/** The trend rows for [from]..[to], in pieces of at most two years. */
internal suspend fun Services.trendRows(userId: String, from: LocalDate, to: LocalDate): List<TrendDay> {
    val rows = mutableListOf<TrendDay>()
    var start = from
    while (!start.isAfter(to)) {
        val end = minOf(to, start.plusDays(729))
        rows += days.trends(userId, start, end, null).days
        start = end.plusDays(1)
    }
    return rows
}

private fun List<TrendDay>.avg(f: (TrendDay) -> Number?): Double? = mapNotNull { f(it)?.toDouble() }.takeIf { it.isNotEmpty() }?.average()

private fun dates(from: LocalDate, to: LocalDate): List<LocalDate> =
    generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

/** Weekly averages as table rows: the week's Monday, then each value. */
private fun weekly(rows: List<TrendDay>, vararg columns: (List<TrendDay>) -> String): List<List<String>> =
    rows.groupBy { LocalDate.parse(it.date).with(java.time.DayOfWeek.MONDAY) }.toSortedMap().map { (monday, week) ->
        listOf(Say.shortDate(monday)) + columns.map { it(week) }
    }

// ---------------------------------------------------------------- Overview, mornings, devices

private class OverviewHooks(private val services: Services) : FeatureHooks {
    override val module: Module? = null

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val week = services.trendRows(userId, from, to)
        val before = services.trendRows(userId, from.minusDays(7), to.minusDays(7))
        val readiness = week.avg { it.readiness } ?: return null
        val lines = listOfNotNull(
            "Readiness averaged ${readiness.roundToInt()}" + (Say.change(readiness, before.avg { it.readiness }, { "${it.roundToInt()}" }, 1.0)?.let { ", $it" } ?: "") + ".",
            week.avg { it.restingHeartRate }?.let { rhr ->
                "Resting heart rate ${rhr.roundToInt()} bpm" + (Say.change(rhr, before.avg { it.restingHeartRate }, { "${it.roundToInt()} bpm" }, 0.9)?.let { ", $it" } ?: "") + "."
            },
            week.avg { it.hrv }?.let { hrv -> "HRV ${hrv.roundToInt()} ms" + (Say.change(hrv, before.avg { it.hrv }, { "${it.roundToInt()} ms" }, 1.5)?.let { ", $it" } ?: "") + "." },
        )
        val tone = when {
            readiness >= 75 -> "good"
            readiness < 60 -> "warn"
            else -> "info"
        }
        return DigestSection("Readiness", null, tone, lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection {
        val rows = services.trendRows(userId, from, to)
        val zone = zoneOf(profile).zone
        val recorded = rows.count { it.steps != null || it.sleepMinutes != null || it.restingHeartRate != null }
        val summary = listOf(
            "${Say.plural(rows.size, "day")}, ${Say.range(from, to)}. Something was recorded on $recorded of them.",
            "Readiness, sleep and activity scores run from 0 to 100 and compare each day with the person's own recent days.",
        )
        val table = ReportTable(
            "Averages", listOf("", "Average", "Lowest", "Highest"),
            listOfNotNull(
                row("Readiness", rows.map { it.readiness?.toDouble() }) { "${it.roundToInt()}" },
                row("Sleep score", rows.map { it.sleepScore?.toDouble() }) { "${it.roundToInt()}" },
                row("Time asleep", rows.map { it.sleepMinutes?.toDouble() }) { Say.minutes(it) },
                row("Steps", rows.map { it.steps?.toDouble() }) { Say.count(it) },
                row("Resting heart rate (bpm)", rows.map { it.restingHeartRate }) { "${it.roundToInt()}" },
                row("HRV (ms)", rows.map { it.hrv }) { "${it.roundToInt()}" },
                row("Weight", rows.map { it.weightKg }) { Say.weight(it, profile.units) },
            ),
        )
        val chart = ReportChart(
            "Readiness and sleep score", "",
            listOf(
                "Readiness" to points(rows.map { LocalDate.parse(it.date) to it.readiness?.toDouble() }, zone),
                "Sleep score" to points(rows.map { LocalDate.parse(it.date) to it.sleepScore?.toDouble() }, zone),
            ),
        )
        return ReportSection(ReportSectionKey.OVERVIEW, summary, listOf(table), listOf(chart).filter { c -> c.series.any { it.second.size >= 2 } })
    }

    private fun row(label: String, values: List<Double?>, say: (Double) -> String): List<String>? {
        val v = values.filterNotNull().takeIf { it.isNotEmpty() } ?: return null
        return listOf(label, say(v.average()), say(v.min()), say(v.max()))
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = zoneOf(profile)
        val rows = services.trendRows(userId, from, to)
        // The water column only while water is turned on.
        val water = services.days.showsWater(userId)
        val daily = listOf(
            listOf(
                "date", "readiness", "sleep_score", "activity_score", "steps", "active_kcal", "resting_hr", "hrv_ms", "sleep_min", "deep_min",
                "rem_min", "spo2", "temperature_deviation_c", "weight_kg", "body_fat_pct", "kcal_eaten", "protein_g", "carbs_g", "fat_g",
            ) + listOfNotNull("water_ml".takeIf { water }) + listOf("workouts", "workout_min"),
        ) + rows.map { d ->
            listOf(
                d.date, d.readiness?.toString().orEmpty(), d.sleepScore?.toString().orEmpty(), d.activityScore?.toString().orEmpty(), d.steps?.toString().orEmpty(),
                Csv.num(d.activeCalories, 0), Csv.num(d.restingHeartRate, 1), Csv.num(d.hrv, 1), d.sleepMinutes?.toString().orEmpty(), d.deepMinutes?.toString().orEmpty(),
                d.remMinutes?.toString().orEmpty(), Csv.num(d.spo2, 1), Csv.num(d.temperatureDeviation, 2), Csv.num(d.weightKg, 2), Csv.num(d.bodyFat, 1),
                Csv.num(d.calories, 0), Csv.num(d.protein, 1), Csv.num(d.carbs, 1), Csv.num(d.fat, 1),
            ) + listOfNotNull(d.waterMl?.toString().orEmpty().takeIf { water }) + listOf(d.workouts.toString(), d.workoutMinutes.toString())
        }
        val samples = services.records.samples(userId, zone.start(from), zone.end(to))
        val sampleRows = listOf(listOf("metric", "start", "end", "value", "unit", "source")) + samples.map {
            listOf(it.metric.name, Csv.iso(it.start, zone.zone), Csv.iso(it.end, zone.zone), Csv.num(it.value, 3), it.metric.unit, it.deviceName)
        }
        val checkIns = services.checkIns.range(userId, from, to).values.sortedBy { it.date }
        val checkInRows = listOf(listOf("date", "mood", "energy", "stress", "soreness", "tags", "note")) + checkIns.map {
            listOf(it.date, it.mood?.toString().orEmpty(), it.energy?.toString().orEmpty(), it.stress?.toString().orEmpty(), it.soreness?.toString().orEmpty(), it.tags.joinToString("; "), it.note.orEmpty())
        }
        return mapOf("daily.csv" to daily, "samples.csv" to sampleRows, "check-ins.csv" to checkInRows)
    }

    override suspend fun fhir(userId: String, profile: Profile, from: LocalDate, to: LocalDate): List<JsonObject> {
        val user = services.users.byId(userId) ?: return emptyList()
        val zone = zoneOf(profile).zone
        val rows = services.trendRows(userId, from, to)
        return listOf(Fhir.patient(userId, user.displayName)) + rows.flatMap { d ->
            val date = LocalDate.parse(d.date)
            listOfNotNull(
                d.restingHeartRate?.let {
                    Fhir.observation("rhr-${d.date}", userId, Fhir.Code("40443-4", "Heart rate --resting"), "vital-signs", it, "beats/minute", "/min", date = date, zone = zone)
                },
                d.spo2?.let {
                    Fhir.observation("spo2-${d.date}", userId, Fhir.Code("59408-5", "Oxygen saturation in Arterial blood by Pulse oximetry"), "vital-signs", it, "%", "%", date = date, zone = zone)
                },
            )
        }
    }

    /** MORNING, and READINESS_LOW with it: once a day, when the night is in (or at 10:00). Webhooks only. */
    override suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<com.health.server.platform.Reminder> {
        val zone = zoneOf(profile)
        val now = to - 60_000
        val today = zone.dateOf(now)
        val key = "morning:$today"
        val scheduler = services.platform.scheduler
        if (scheduler.wasSent(userId, key)) return emptyList()
        if (now >= zone.start(today) + 18 * 3_600_000L) return emptyList()
        val stats = services.days.stats(userId, today, today, zone).lastOrNull()
        val sleepEnd = stats?.sleep?.end?.takeIf { it <= now && zone.dateOf(it) == today }
        val tenAm = zone.start(today) + 10 * 3_600_000L
        val eventAt = sleepEnd ?: tenAm.takeIf { now >= it } ?: return emptyList()
        val at = eventAt.coerceIn(from, now)
        val day = services.days.summary(userId, today.toString())
        val readiness = day.readiness?.score
        val data = json(
            "date" to today.toString(), "readiness" to readiness, "readiness_label" to day.readiness?.label, "sleep_score" to day.sleep?.score,
            "sleep_minutes" to day.sleepSummary?.asleepMinutes, "resting_hr" to day.restingHeartRate, "hrv" to day.hrv,
        )
        val title = if (readiness != null) "Good morning: readiness $readiness" else "Good morning"
        val list = mutableListOf(Reminder(key, at, WebhookEvent.MORNING, title, "", title, "", data = data, push = false))
        if (readiness != null && readiness < 60) {
            list += Reminder(
                "readiness-low:$today", at, WebhookEvent.READINESS_LOW, "Readiness is low today ($readiness)", "A gentler day may help.",
                "Readiness is low today ($readiness)", "A gentler day may help.", data = data, push = false,
            )
        }
        return list
    }
}

private class DeviceHooks(private val services: Services) : FeatureHooks {
    override val module: Module? = null

    override suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> {
        val zone = zoneOf(profile)
        val now = to - 60_000
        val today = zone.dateOf(now)
        return services.devices.list(userId).filter { it.kind != DeviceKind.MANUAL && it.kind != DeviceKind.FIT_FILE }.flatMap { d ->
            val synced = d.lastSyncAt ?: return@flatMap emptyList()
            val data = json("device" to d.name, "kind" to d.kind.name, "battery" to d.battery, "last_sync" to Csv.iso(synced, zone.zone))
            listOfNotNull(
                d.battery?.takeIf { it <= 15 && now - synced < 6 * 3_600_000L }?.let { battery ->
                    val title = "${d.name} is at $battery%"
                    Reminder("battery:${d.id}:$today", synced.coerceIn(from, now), WebhookEvent.DEVICE_BATTERY_LOW, title, "Time to charge it.", title, "Time to charge it.", data = data, push = false)
                },
                (now - synced).takeIf { it >= 24 * 3_600_000L && it < 7 * 24 * 3_600_000L }?.let {
                    val title = "${d.name} hasn't synced for a day"
                    Reminder("quiet:${d.id}:$synced", (synced + 24 * 3_600_000L).coerceIn(from, now), WebhookEvent.DEVICE_QUIET, title, "", title, "", data = data, push = false)
                },
            )
        }
    }
}

// ---------------------------------------------------------------- Sleep, activity, body, food

private class SleepHooks(private val services: Services) : FeatureHooks {
    override val module = Module.SLEEP

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val week = services.trendRows(userId, from, to)
        val before = services.trendRows(userId, from.minusDays(7), to.minusDays(7))
        val asleep = week.avg { it.sleepMinutes } ?: return null
        val nights = week.count { it.sleepMinutes != null }
        val lines = listOfNotNull(
            "${Say.minutes(asleep)} asleep a night on average (${Say.plural(nights, "night")} recorded)" +
                (Say.change(asleep, before.avg { it.sleepMinutes }, { Say.minutes(it) }, 10.0)?.let { ", $it" } ?: "") + ".",
            week.avg { it.sleepScore }?.let { s -> "Sleep score ${s.roundToInt()}" + (Say.change(s, before.avg { it.sleepScore }, { "${it.roundToInt()}" }, 1.0)?.let { ", $it" } ?: "") + "." },
            week.avg { it.deepMinutes }?.let { deep -> "Deep sleep ${Say.minutes(deep)} and REM ${Say.minutes(week.avg { it.remMinutes })} a night." },
        )
        val goal = profile.goals.sleepMinutes
        return DigestSection("Sleep", Module.SLEEP, if (asleep >= goal - 30) "good" else "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val zone = zoneOf(profile)
        val rows = services.trendRows(userId, from, to)
        val nights = rows.filter { it.sleepMinutes != null }
        if (nights.isEmpty()) return ReportSection(ReportSectionKey.SLEEP, listOf("No nights were recorded in this period."))
        val stats = services.days.stats(userId, from, to, zone)
        val latencies = stats.mapNotNull { it.sleep?.latencyMinutes }
        val summary = listOfNotNull(
            "${Say.plural(nights.size, "night")} recorded. Time asleep averaged ${Say.minutes(nights.avg { it.sleepMinutes })} " +
                "(from ${Say.minutes(nights.minOf { it.sleepMinutes!! })} to ${Say.minutes(nights.maxOf { it.sleepMinutes!! })}).",
            nights.avg { it.deepMinutes }?.let { "Deep sleep averaged ${Say.minutes(it)} and REM ${Say.minutes(nights.avg { n -> n.remMinutes })}." },
            latencies.takeIf { it.isNotEmpty() }?.let { "Falling asleep took ${Say.minutes(it.average())} on average." },
            "The goal set was ${Say.minutes(profile.goals.sleepMinutes)} a night.",
        )
        val table = ReportTable(
            "By week", listOf("Week of", "Asleep", "Sleep score", "Deep", "REM"),
            weekly(
                rows,
                { w -> Say.minutes(w.avg { it.sleepMinutes }) },
                { w -> w.avg { it.sleepScore }?.roundToInt()?.toString() ?: "–" },
                { w -> Say.minutes(w.avg { it.deepMinutes }) },
                { w -> Say.minutes(w.avg { it.remMinutes }) },
            ),
        )
        val chart = ReportChart(
            "Time asleep", "h",
            listOf("Asleep" to points(rows.map { LocalDate.parse(it.date) to it.sleepMinutes?.let { m -> m / 60.0 } }, zone.zone)),
            band = (profile.goals.sleepMinutes / 60.0).let { it..(it + 1.0) },
        )
        return ReportSection(ReportSectionKey.SLEEP, summary, listOf(table), listOf(chart))
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = zoneOf(profile)
        val stats = services.days.stats(userId, from, to, zone)
        val nights = stats.flatMap { listOfNotNull(it.sleep) + it.naps }.distinctBy { it.start }
        return mapOf(
            "sleep.csv" to listOf(
                listOf("start", "end", "asleep_min", "in_bed_min", "efficiency_pct", "deep_min", "rem_min", "light_min", "awake_min", "latency_min", "lowest_hr", "avg_hr", "avg_hrv_ms", "source"),
            ) + nights.map { s ->
                listOf(
                    Csv.iso(s.start, zone.zone), Csv.iso(s.end, zone.zone), s.asleepMinutes.toString(), s.inBedMinutes.toString(), s.efficiency.toString(),
                    s.deepMinutes.toString(), s.remMinutes.toString(), s.lightMinutes.toString(), s.awakeMinutes.toString(), s.latencyMinutes?.toString().orEmpty(),
                    Csv.num(s.lowestHeartRate, 0), Csv.num(s.avgHeartRate, 0), Csv.num(s.avgHrv, 0), s.source.orEmpty(),
                )
            },
        )
    }

    override suspend fun fhir(userId: String, profile: Profile, from: LocalDate, to: LocalDate): List<JsonObject> {
        val zone = zoneOf(profile)
        return services.days.stats(userId, from, to, zone).mapNotNull { day ->
            day.sleep?.let { s ->
                Fhir.observation(
                    "sleep-${day.date}", userId, Fhir.Code("93832-4", "Sleep duration"), "activity", s.asleepMinutes / 60.0, "h", "h",
                    date = LocalDate.parse(day.date), zone = zone.zone,
                )
            }
        }
    }
}

private class ActivityHooks(private val services: Services) : FeatureHooks {
    override val module = Module.ACTIVITY

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val week = services.trendRows(userId, from, to)
        val before = services.trendRows(userId, from.minusDays(7), to.minusDays(7))
        val steps = week.avg { it.steps }
        val workouts = week.sumOf { it.workouts }
        if (steps == null && workouts == 0) return null
        val minutes = week.sumOf { it.workoutMinutes }
        val lines = listOfNotNull(
            steps?.let { s -> "${Say.count(s)} steps a day" + (Say.change(s, before.avg { it.steps }, { Say.count(it) }, 250.0)?.let { ", $it" } ?: "") + "." },
            if (workouts > 0) "${Say.plural(workouts, "workout")}, ${Say.minutes(minutes)} in all (the goal is ${profile.goals.workoutsPerWeek})." else "No workouts recorded.",
            week.count { (it.steps ?: 0) >= profile.goals.steps }.takeIf { steps != null }?.let { "Step goal reached on $it of 7 days." },
        )
        val good = workouts >= profile.goals.workoutsPerWeek || (steps ?: 0.0) >= profile.goals.steps
        return DigestSection("Activity", Module.ACTIVITY, if (good) "good" else "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection {
        val zone = zoneOf(profile)
        val rows = services.trendRows(userId, from, to)
        val workouts = services.records.workouts(userId, zone.start(from), zone.end(to))
        val summary = listOfNotNull(
            rows.avg { it.steps }?.let { "Steps averaged ${Say.count(it)} a day; the goal was ${Say.count(profile.goals.steps)}." },
            "${Say.plural(workouts.size, "workout")} in this period, ${Say.minutes(workouts.sumOf { (it.end - it.start) / 60_000.0 })} in all.",
        )
        val weeks = ReportTable(
            "By week", listOf("Week of", "Steps a day", "Active kcal a day", "Workouts"),
            weekly(rows, { w -> Say.count(w.avg { it.steps }) }, { w -> Say.count(w.avg { it.activeCalories }) }, { w -> w.sumOf { it.workouts }.toString() }),
        )
        val list = ReportTable(
            "Workouts", listOf("Date", "What", "Time", "Distance", "Effort"),
            workouts.sortedBy { it.start }.takeLast(60).map { w ->
                listOf(
                    Say.shortDate(zone.dateOf(w.start)), w.name ?: w.type.label, Say.minutes((w.end - w.start) / 60_000.0),
                    w.distanceM?.let { d -> if (profile.units == com.health.api.Units.IMPERIAL) "${Say.number(d / 1609.344)} mi" else "${Say.number(d / 1000)} km" } ?: "",
                    w.effort?.let { "$it/10" }.orEmpty(),
                )
            },
        )
        val chart = ReportChart("Steps", "", listOf("Steps" to points(rows.map { LocalDate.parse(it.date) to it.steps?.toDouble() }, zone.zone)))
        return ReportSection(ReportSectionKey.ACTIVITY, summary, listOfNotNull(weeks, list.takeIf { it.rows.isNotEmpty() }), listOf(chart))
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = zoneOf(profile)
        val workouts = services.records.workouts(userId, zone.start(from), zone.end(to))
        return mapOf(
            "workouts.csv" to listOf(listOf("start", "end", "type", "name", "distance_m", "active_kcal", "avg_hr", "max_hr", "steps", "effort", "note", "source")) +
                workouts.map { w ->
                    listOf(
                        Csv.iso(w.start, zone.zone), Csv.iso(w.end, zone.zone), w.type.name, w.name.orEmpty(), Csv.num(w.distanceM, 0), Csv.num(w.activeCalories, 0),
                        Csv.num(w.avgHeartRate, 0), Csv.num(w.maxHeartRate, 0), w.steps?.toString().orEmpty(), w.effort?.toString().orEmpty(), w.note.orEmpty(), w.source.orEmpty(),
                    )
                },
        )
    }

    override suspend fun fhir(userId: String, profile: Profile, from: LocalDate, to: LocalDate): List<JsonObject> {
        val zone = zoneOf(profile).zone
        return services.trendRows(userId, from, to).mapNotNull { d ->
            d.steps?.let {
                Fhir.observation("steps-${d.date}", userId, Fhir.Code("41950-7", "Number of steps in 24 hour Measured"), "activity", it.toDouble(), "steps", "{steps}/(24.h)", date = LocalDate.parse(d.date), zone = zone)
            }
        }
    }
}

private class BodyHooks(private val services: Services) : FeatureHooks {
    override val module = Module.BODY

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val weights = services.trendRows(userId, from, to).mapNotNull { it.weightKg }
        if (weights.isEmpty()) return null
        val last = weights.last()
        val line = if (weights.size >= 2) {
            val change = last - weights.first()
            "Weight ${Say.weight(last, profile.units)}, " + when {
                kotlin.math.abs(change) < 0.15 -> "steady over the week."
                change > 0 -> "up ${Say.weight(change, profile.units)} over the week."
                else -> "down ${Say.weight(-change, profile.units)} over the week."
            }
        } else "Weight ${Say.weight(last, profile.units)}."
        return DigestSection("Body", Module.BODY, "info", listOf(line))
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection {
        val zone = zoneOf(profile)
        val rows = services.trendRows(userId, from, to).filter { it.weightKg != null }
        if (rows.isEmpty()) return ReportSection(ReportSectionKey.BODY, listOf("No weights were recorded in this period."))
        val first = rows.first().weightKg!!
        val last = rows.last().weightKg!!
        val summary = listOfNotNull(
            "${Say.plural(rows.size, "weigh-in")}. From ${Say.weight(first, profile.units)} to ${Say.weight(last, profile.units)} " +
                "(lowest ${Say.weight(rows.minOf { it.weightKg!! }, profile.units)}, highest ${Say.weight(rows.maxOf { it.weightKg!! }, profile.units)}).",
            rows.mapNotNull { it.bodyFat }.takeIf { it.isNotEmpty() }?.let { "Body fat ${Say.number(it.first())}% to ${Say.number(it.last())}%." },
            profile.goals.targetWeightKg?.let { "The target set was ${Say.weight(it, profile.units)}." },
        )
        val chart = ReportChart(
            "Weight", Say.weightUnit(profile.units),
            listOf("Weight" to points(rows.map { LocalDate.parse(it.date) to Say.weightValue(it.weightKg!!, profile.units) }, zone.zone)),
        )
        return ReportSection(ReportSectionKey.BODY, summary, emptyList(), listOf(chart))
    }

    override suspend fun fhir(userId: String, profile: Profile, from: LocalDate, to: LocalDate): List<JsonObject> {
        val zone = zoneOf(profile)
        val samples = services.records.samples(userId, zone.start(from), zone.end(to), listOf(com.health.api.Metric.WEIGHT, com.health.api.Metric.BODY_FAT))
        return samples.map { s ->
            if (s.metric == com.health.api.Metric.WEIGHT) {
                Fhir.observation("weight-${s.id}", userId, Fhir.Code("29463-7", "Body weight"), "vital-signs", s.value, "kg", "kg", at = s.start, zone = zone.zone)
            } else {
                Fhir.observation("bodyfat-${s.id}", userId, Fhir.Code("41982-0", "Percentage of body fat Measured"), "vital-signs", s.value, "%", "%", at = s.start, zone = zone.zone)
            }
        }
    }
}

private class NutritionHooks(private val services: Services) : FeatureHooks {
    override val module = Module.NUTRITION

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val week = services.trendRows(userId, from, to)
        val logged = week.filter { (it.calories ?: 0.0) > 0 }
        val shown = services.days.showsWater(userId)
        val water = week.avg { it.waterMl?.takeIf { ml -> ml > 0 } }?.takeIf { shown }
        if (logged.isEmpty() && water == null) return null
        val lines = listOfNotNull(
            logged.takeIf { it.isNotEmpty() }?.let { days ->
                "Food logged on ${days.size} of 7 days: ${Say.count(days.avg { it.calories })} kcal and ${Say.count(days.avg { it.protein })} g of protein a day."
            },
            water?.let { "$WATER_LINE${Say.count(it)} ml a day (the goal is ${Say.count(profile.goals.waterMl)})." },
        )
        return DigestSection(if (shown) FOOD_AND_WATER else "Food", Module.NUTRITION, "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection {
        val rows = services.trendRows(userId, from, to)
        val logged = rows.filter { (it.calories ?: 0.0) > 0 }
        val water = services.days.showsWater(userId)
        val summary = if (logged.isEmpty()) listOf("No food was logged in this period.") else listOf(
            "Food was logged on ${logged.size} of ${rows.size} days. On those days: ${Say.count(logged.avg { it.calories })} kcal, " +
                "${Say.count(logged.avg { it.protein })} g protein, ${Say.count(logged.avg { it.carbs })} g carbohydrate and ${Say.count(logged.avg { it.fat })} g fat on average.",
        ) + listOfNotNull(rows.avg { it.waterMl?.takeIf { ml -> ml > 0 } }?.takeIf { water }?.let { "Water averaged ${Say.count(it)} ml a day where logged." })
        val waterColumn: (List<TrendDay>) -> String = { w -> Say.count(w.avg { it.waterMl?.takeIf { ml -> ml > 0 } }) }
        val columns = listOf<(List<TrendDay>) -> String>(
            { w -> Say.count(w.filter { (it.calories ?: 0.0) > 0 }.avg { it.calories }) },
            { w -> Say.count(w.filter { (it.calories ?: 0.0) > 0 }.avg { it.protein }) },
            { w -> Say.count(w.filter { (it.calories ?: 0.0) > 0 }.avg { it.carbs }) },
            { w -> Say.count(w.filter { (it.calories ?: 0.0) > 0 }.avg { it.fat }) },
        ) + listOfNotNull(waterColumn.takeIf { water })
        val table = ReportTable(
            "By week", listOf("Week of", "kcal", "Protein (g)", "Carbs (g)", "Fat (g)") + listOfNotNull("Water (ml)".takeIf { water }),
            weekly(rows, *columns.toTypedArray()),
        )
        return ReportSection(ReportSectionKey.NUTRITION, summary, listOf(table).filter { logged.isNotEmpty() })
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val food = services.foods.entries(userId, from, to)
        val zone = zoneOf(profile).zone
        val files = mapOf(
            "food.csv" to listOf(listOf("date", "meal", "name", "brand", "servings", "serving", "kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g")) +
                food.map { e ->
                    listOf(
                        e.date, e.meal.name, e.name, e.brand.orEmpty(), Csv.num(e.servings), e.serving, Csv.num(e.nutrients.calories, 0), Csv.num(e.nutrients.protein, 1),
                        Csv.num(e.nutrients.carbs, 1), Csv.num(e.nutrients.fat, 1), Csv.num(e.nutrients.fiber, 1), Csv.num(e.nutrients.sugar, 1),
                    )
                },
        )
        // Water that was logged stays stored, but isn't exported while it's turned off.
        if (!services.days.showsWater(userId)) return files
        val water = services.db.tx {
            query("SELECT date, ml, at FROM water WHERE user_id = ? AND date >= ? AND date <= ? ORDER BY at", userId, from.toString(), to.toString()) {
                Triple(it.string("date"), it.int("ml"), it.long("at"))
            }
        }
        return files + ("water.csv" to listOf(listOf("date", "at", "ml")) + water.map { (date, ml, at) -> listOf(date, Csv.iso(at, zone), ml.toString()) })
    }
}

/** Food's digest section title with water, and how its water line starts, so a kept digest can lose them. */
private const val FOOD_AND_WATER = "Food and water"
private const val WATER_LINE = "Water "

/** A digest kept from before water was turned off, without its water line (the section is just "Food"). */
internal fun withoutWater(digest: com.health.api.Digest): com.health.api.Digest = digest.copy(
    sections = digest.sections.mapNotNull { s ->
        if (s.module != Module.NUTRITION || s.title != FOOD_AND_WATER) return@mapNotNull s
        s.copy(title = "Food", lines = s.lines.filterNot { it.startsWith(WATER_LINE) }).takeIf { it.lines.isNotEmpty() }
    },
)

// ---------------------------------------------------------------- Mind, cycle, bladder and bowel

private class MindHooks(private val services: Services) : FeatureHooks {
    override val module = Module.MIND

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val week = services.trendRows(userId, from, to)
        val before = services.trendRows(userId, from.minusDays(7), to.minusDays(7))
        val mood = week.avg { it.mood }
        val calm = week.sumOf { it.mindfulMinutes ?: 0.0 }
        if (mood == null && calm == 0.0) return null
        val lines = listOfNotNull(
            mood?.let { m -> "Mood averaged ${Say.number(m)} of 5" + (Say.change(m, before.avg { it.mood }, { Say.number(it) }, 0.2)?.let { ", $it" } ?: "") + "." },
            calm.takeIf { it > 0 }?.let { "${Say.minutes(it)} of calm practice." },
        )
        return DigestSection("Mind", Module.MIND, if ((mood ?: 3.0) < 2.5) "warn" else "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection {
        val zone = zoneOf(profile)
        val rows = services.trendRows(userId, from, to)
        val results = services.entries.list(userId, EntryKind.SCREENER, from, to, ScreenerResult.serializer()).map { it.value }
        val sessions = services.entries.list(userId, EntryKind.MINDFUL, from, to, MindfulnessSession.serializer()).map { it.value }
        val summary = listOfNotNull(
            rows.avg { it.mood }?.let { "Mood (1 very low to 5 great) averaged ${Say.number(it)} on ${rows.count { r -> r.mood != null }} days." },
            rows.avg { it.anxiety }?.let { "Anxiety (1 to 5) averaged ${Say.number(it)}." },
            sessions.takeIf { it.isNotEmpty() }?.let { "${Say.plural(it.size, "calm practice session")}, ${Say.minutes(it.sumOf { s -> s.minutes })} in all." },
            "Questionnaire scores are a snapshot, not a diagnosis.".takeIf { results.isNotEmpty() },
        ).ifEmpty { listOf("Nothing was logged in this period.") }
        val table = ReportTable(
            "Questionnaires", listOf("Date", "Questionnaire", "Score", "Band"),
            results.sortedBy { it.at }.map { r -> listOf(Say.shortDate(zone.dateOf(r.at)), r.screener.label(), r.score.toString(), r.severity) },
        )
        val chart = ReportChart("Mood", "of 5", listOf("Mood" to points(rows.map { LocalDate.parse(it.date) to it.mood }, zone.zone)))
        return ReportSection(ReportSectionKey.MIND, summary, listOf(table).filter { it.rows.isNotEmpty() }, listOf(chart).filter { it.series[0].second.size >= 2 })
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = zoneOf(profile).zone
        val moods = services.entries.list(userId, EntryKind.MOOD, from, to, MoodEntry.serializer()).map { it.value }
        val results = services.entries.list(userId, EntryKind.SCREENER, from, to, ScreenerResult.serializer()).map { it.value }
        val sessions = services.entries.list(userId, EntryKind.MINDFUL, from, to, MindfulnessSession.serializer()).map { it.value }
        val journal = services.entries.list(userId, EntryKind.JOURNAL, from, to, JournalEntry.serializer()).map { it.value }
        return mapOf(
            "moods.csv" to listOf(listOf("at", "mood", "energy", "anxiety", "feelings", "context", "note")) + moods.map {
                listOf(Csv.iso(it.at, zone), it.mood.toString(), it.energy?.toString().orEmpty(), it.anxiety?.toString().orEmpty(), it.feelings.joinToString("; "), it.context.joinToString("; "), it.note.orEmpty())
            },
            "questionnaires.csv" to listOf(listOf("at", "questionnaire", "score", "band", "answers")) + results.map {
                listOf(Csv.iso(it.at, zone), it.screener.name, it.score.toString(), it.severity, it.answers.joinToString(" "))
            },
            "calm-practice.csv" to listOf(listOf("at", "kind", "minutes", "mood_before", "mood_after", "note")) + sessions.map {
                listOf(Csv.iso(it.at, zone), it.kind, Csv.num(it.minutes, 1), it.moodBefore?.toString().orEmpty(), it.moodAfter?.toString().orEmpty(), it.note.orEmpty())
            },
            "journal.csv" to listOf(listOf("at", "kind", "title", "text", "gratitude", "situation", "thought", "balanced_thought")) + journal.map {
                listOf(Csv.iso(it.at, zone), it.kind.name, it.title.orEmpty(), it.text, it.gratitude.joinToString("; "), it.thought?.situation.orEmpty(), it.thought?.thought.orEmpty(), it.thought?.balancedThought.orEmpty())
            },
        )
    }

    override suspend fun fhir(userId: String, profile: Profile, from: LocalDate, to: LocalDate): List<JsonObject> {
        val zone = zoneOf(profile).zone
        return services.entries.list(userId, EntryKind.SCREENER, from, to, ScreenerResult.serializer()).map { stored ->
            val r = stored.value
            val code = when (r.screener) {
                Screener.PHQ9 -> Fhir.Code("44261-6", "Patient Health Questionnaire 9 item (PHQ-9) total score [Reported]")
                Screener.GAD7 -> Fhir.Code("70274-6", "Generalized anxiety disorder 7 item (GAD-7) total score [Reported.PHQ]")
                else -> Fhir.Code(null, r.screener.label())
            }
            Fhir.observation("screener-${stored.id}", userId, code, "survey", r.score.toDouble(), "{score}", "{score}", at = r.at, zone = zone)
        }
    }
}

private fun Screener.label() = when (this) {
    Screener.PHQ9 -> "PHQ-9"
    Screener.GAD7 -> "GAD-7"
    else -> "Edinburgh (EPDS)"
}

private class CycleHooks(private val services: Services) : FeatureHooks {
    override val module = Module.CYCLE

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val overview = runCatching { services.modules.cycle.overview(userId, 12, to) }.getOrNull() ?: return null
        val periodDays = overview.days.count { d -> d.flow != null && d.flow != com.health.api.Flow.SPOTTING && LocalDate.parse(d.date) in from..to }
        val lines = listOfNotNull(
            "${Say.plural(periodDays, "period day")} this week.".takeIf { periodDays > 0 },
            overview.prediction?.let { p -> "Next period likely around ${Say.shortDate(LocalDate.parse(p.nextPeriod))} (an estimate)." },
        )
        return lines.takeIf { it.isNotEmpty() }?.let { DigestSection("Cycle", Module.CYCLE, "info", it) }
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection {
        val overview = services.modules.cycle.overview(userId, (ChronoUnit.MONTHS.between(from, to) + 2).toInt().coerceIn(3, 120), to)
        val cycles = overview.cycles.filter { LocalDate.parse(it.start) in from..to }
        val summary = listOfNotNull(
            overview.averageCycle?.let { "Cycles averaged ${it.roundToInt()} days" + (overview.variation?.let { v -> ", varying by about ${Say.number(v)} days" } ?: "") + "." },
            overview.averagePeriod?.let { "Periods averaged ${it.roundToInt()} days." },
            overview.prediction?.let { p -> "Next period likely between ${Say.shortDate(LocalDate.parse(p.earliest))} and ${Say.shortDate(LocalDate.parse(p.latest))} (${p.confidence} confidence)." },
            overview.caveat,
        )
        val table = ReportTable(
            "Cycles", listOf("Started", "Length", "Period", "Temperature rise"),
            cycles.map { c -> listOf(Say.date(LocalDate.parse(c.start)), c.length?.let { "$it days" } ?: "Current", "${c.periodLength} days", c.ovulation?.let { Say.shortDate(LocalDate.parse(it)) }.orEmpty()) },
        )
        val symptoms = overview.days.filter { LocalDate.parse(it.date) in from..to }.flatMap { it.symptoms }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(10)
        val symptomTable = ReportTable("Symptoms logged", listOf("Symptom", "Days"), symptoms.map { listOf(it.key, it.value.toString()) })
        return ReportSection(ReportSectionKey.CYCLE, summary, listOf(table, symptomTable).filter { it.rows.isNotEmpty() })
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val days = services.modules.cycle.logged(userId, from, to)
        return mapOf(
            "cycle.csv" to listOf(listOf("date", "flow", "symptoms", "pain", "mucus", "ovulation_test", "temperature_c", "note")) + days.map { d: CycleDay ->
                listOf(d.date, d.flow?.name.orEmpty(), d.symptoms.joinToString("; "), d.pain?.toString().orEmpty(), d.mucus?.name.orEmpty(), d.ovulationTest?.name.orEmpty(), Csv.num(d.temperature, 2), d.note.orEmpty())
            },
        )
    }
}

private class ContinenceHooks(private val services: Services) : FeatureHooks {
    override val module = Module.CONTINENCE

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val week = services.trendRows(userId, from, to).filter { it.voids != null || it.leaks != null }
        if (week.isEmpty()) return null
        val before = services.trendRows(userId, from.minusDays(7), to.minusDays(7)).filter { it.voids != null || it.leaks != null }
        val leaks = week.sumOf { it.leaks ?: 0 }
        val lines = listOfNotNull(
            week.avg { it.voids }?.let { "${Say.number(it)} toilet trips a day, ${Say.number(week.avg { d -> d.nightVoids } ?: 0.0)} of them at night." },
            "${Say.plural(leaks, "leak")} this week" + (before.takeIf { it.isNotEmpty() }?.sumOf { it.leaks ?: 0 }?.let { ", against $it the week before" } ?: "") + ".",
        )
        return DigestSection("Bladder and bowel", Module.CONTINENCE, "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection {
        val zone = zoneOf(profile)
        val days = services.modules.continence.daysOf(userId, from, to).filterValues { it.voids + it.leaks + it.bowel + it.pads > 0 }
        if (days.isEmpty()) return ReportSection(ReportSectionKey.CONTINENCE, listOf("Nothing was logged in this period."))
        val entries = services.entries.list(userId, EntryKind.CONTINENCE, from, to, ContinenceEntry.serializer()).map { it.value }
        val triggers = entries.filter { it.kind == com.health.api.ContinenceKind.LEAK }.flatMap { it.triggers }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val list = days.values.toList()
        val summary = listOfNotNull(
            "${Say.plural(days.size, "day")} logged. On those days: ${Say.number(list.map { it.voids }.average())} toilet trips " +
                "(${Say.number(list.map { it.nightVoids }.average())} at night), ${Say.number(list.map { it.leaks }.average())} leaks and " +
                "${Say.number(list.map { it.bowel }.average())} bowel movements a day.",
            list.mapNotNull { it.voidedMl }.takeIf { it.isNotEmpty() }?.let { "Measured volume averaged ${it.average().roundToInt()} ml a day where recorded." },
            triggers.takeIf { it.isNotEmpty() }?.let { t -> "Leaks came most with: " + t.take(5).joinToString(", ") { "${it.key} (${it.value})" } + "." },
        )
        // What was drunk comes from water tracking: left out while it's turned off.
        val water = services.days.showsWater(userId)
        val table = ReportTable(
            "By day", listOf("Date", "Trips", "At night", "Leaks", "Bowel") + listOfNotNull("Fluid (ml)".takeIf { water }),
            days.toSortedMap().entries.toList().takeLast(31).map { (d, c) ->
                listOf(Say.shortDate(d), c.voids.toString(), c.nightVoids.toString(), c.leaks.toString(), c.bowel.toString()) + listOfNotNull(c.fluidMl.toString().takeIf { water })
            },
        )
        val chart = ReportChart(
            "Toilet trips and leaks", "a day",
            listOf(
                "Trips" to points(days.entries.map { it.key to it.value.voids.toDouble() }, zone.zone),
                "Leaks" to points(days.entries.map { it.key to it.value.leaks.toDouble() }, zone.zone),
            ),
        )
        return ReportSection(ReportSectionKey.CONTINENCE, summary, listOf(table), listOf(chart).filter { days.size >= 2 })
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = zoneOf(profile).zone
        val entries = services.entries.list(userId, EntryKind.CONTINENCE, from, to, ContinenceEntry.serializer()).map { it.value }
        return mapOf(
            "bladder-bowel.csv" to listOf(listOf("at", "kind", "volume_ml", "urgency", "leak", "triggers", "bristol", "pain", "blood", "note")) + entries.map { e ->
                listOf(
                    Csv.iso(e.at, zone), e.kind.name, e.volumeMl?.toString().orEmpty(), e.urgency?.toString().orEmpty(), e.leak?.name.orEmpty(), e.triggers.joinToString("; "),
                    e.bristol?.toString().orEmpty(), e.pain.toString(), e.blood.toString(), e.note.orEmpty(),
                )
            },
        )
    }
}

// ---------------------------------------------------------------- Medications

private class MedsHooks(private val services: Services) : FeatureHooks {
    override val module = Module.MEDICATIONS

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val week = services.trendRows(userId, from, to)
        val due = week.sumOf { it.dosesDue ?: 0 }
        if (due == 0) return null
        val taken = week.sumOf { it.dosesTaken ?: 0 }
        val pct = (taken * 100.0 / due).roundToInt()
        val low = services.modules.meds.day(userId, to).lowStock
        val lines = listOfNotNull(
            "$taken of $due scheduled doses taken ($pct%).",
            low.takeIf { it.isNotEmpty() }?.let { "Running low: ${it.joinToString(", ") { m -> m.name }}." },
        )
        return DigestSection("Medications", Module.MEDICATIONS, if (pct >= 90) "good" else "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection {
        val zone = zoneOf(profile)
        val meds = services.modules.meds.list(userId, all = true)
        val rows = services.trendRows(userId, from, to)
        val due = rows.sumOf { it.dosesDue ?: 0 }
        val taken = rows.sumOf { it.dosesTaken ?: 0 }
        val labs = services.modules.meds.labs(userId, 3650).filter { zone.dateOf(it.at) in from..to }
        val summary = listOfNotNull(
            "${Say.plural(meds.count { it.active }, "medication")} in use.",
            due.takeIf { it > 0 }?.let { "$taken of $it scheduled doses were marked taken (${(taken * 100.0 / it).roundToInt()}%)." },
            labs.takeIf { it.isNotEmpty() }?.let { "${Say.plural(it.size, "lab result")} in this period." },
        )
        val medTable = ReportTable(
            "Medications", listOf("Name", "Dose", "How often", "For", "Status"),
            meds.map { m -> listOf(m.name, m.dose.orEmpty(), scheduleText(m.schedule), m.purpose.orEmpty(), if (m.active) "Taking" else "Stopped") },
        )
        val labTable = ReportTable(
            "Lab results", listOf("Date", "Test", "Result", "Range"),
            labs.sortedBy { it.at }.map { l ->
                listOf(Say.shortDate(zone.dateOf(l.at)), l.name, "${Say.number(l.value, 2).trimEnd('0').trimEnd('.')} ${l.unit}", rangeText(l.low, l.high))
            },
        )
        val charts = labs.groupBy { it.name }.filterValues { it.size >= 2 }.entries.take(3).map { (name, results) ->
            ReportChart(
                name, results.first().unit,
                listOf(name to results.sortedBy { it.at }.map { com.health.api.Point(it.at, it.value) }),
                band = results.first().let { l -> if (l.low != null && l.high != null) l.low!!..l.high!! else null },
            )
        }
        return ReportSection(ReportSectionKey.MEDICATIONS, summary, listOf(medTable, labTable).filter { it.rows.isNotEmpty() }, charts)
    }

    private fun rangeText(low: Double?, high: Double?) = when {
        low != null && high != null -> "${Say.number(low, 2).trimEnd('0').trimEnd('.')}–${Say.number(high, 2).trimEnd('0').trimEnd('.')}"
        low != null -> "above ${Say.number(low, 2).trimEnd('0').trimEnd('.')}"
        high != null -> "below ${Say.number(high, 2).trimEnd('0').trimEnd('.')}"
        else -> ""
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = zoneOf(profile).zone
        val meds = services.modules.meds.list(userId, all = true)
        val names = meds.associate { it.id to it.name }
        val doses = services.modules.meds.doses(userId, from, to)
        val labs = services.modules.meds.labs(userId, 3650).filter { zoneOf(profile).dateOf(it.at) in from..to }
        return mapOf(
            "medications.csv" to listOf(listOf("name", "dose", "form", "schedule", "times", "purpose", "active", "stock", "started_on", "notes")) + meds.map { m ->
                listOf(m.name, m.dose.orEmpty(), m.form.name, scheduleText(m.schedule), m.schedule.times.joinToString(" "), m.purpose.orEmpty(), m.active.toString(), m.stock?.toString().orEmpty(), m.startedOn.orEmpty(), m.notes.orEmpty())
            },
            "doses.csv" to listOf(listOf("medication", "at", "status", "scheduled_for", "site", "note")) + doses.map { d: DoseLog ->
                listOf(names[d.medicationId] ?: d.medicationId, Csv.iso(d.at, zone), d.status.name, Csv.iso(d.scheduledFor, zone), d.site.orEmpty(), d.note.orEmpty())
            },
            "labs.csv" to listOf(listOf("at", "test", "value", "unit", "low", "high", "note")) + labs.map { l ->
                listOf(Csv.iso(l.at, zone), l.name, Csv.num(l.value, 3), l.unit, Csv.num(l.low, 3), Csv.num(l.high, 3), l.note.orEmpty())
            },
        )
    }

    override suspend fun fhir(userId: String, profile: Profile, from: LocalDate, to: LocalDate): List<JsonObject> {
        val zone = zoneOf(profile)
        val meds = services.modules.meds.list(userId, all = true).map { m ->
            Fhir.medicationStatement("med-${m.id}", userId, m.name, listOfNotNull(m.dose, scheduleText(m.schedule)).joinToString(", "), m.active, m.startedOn)
        }
        val labs = services.modules.meds.labs(userId, 3650).filter { zone.dateOf(it.at) in from..to }.map { l ->
            Fhir.observation("lab-${l.id}", userId, Fhir.Code(null, l.name), "laboratory", l.value, l.unit, null, at = l.at, zone = zone.zone, low = l.low, high = l.high)
        }
        return meds + labs
    }

    /**
     * Doses: due at the scheduled time; again every few minutes while unmarked; "missed" for
     * webhooks after a while. Refills once a day when doses left won't last long.
     */
    override suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> {
        val prefs = profile.reminders
        val zone = zoneOf(profile)
        val meds = services.modules.meds.list(userId).filter { it.reminders }
        if (meds.isEmpty()) return emptyList()
        val lookback = maxOf(prefs.missedAfterMinutes, prefs.remindAgainMinutes * prefs.remindAgainTimes).toLong() * 60_000
        val firstDay = zone.dateOf(from - lookback)
        val lastDay = zone.dateOf(to)
        val logs = services.modules.meds.doses(userId, firstDay.minusDays(1), lastDay.plusDays(1))
        val out = mutableListOf<Reminder>()
        for (med in meds) {
            if (med.schedule.kind == ScheduleKind.AS_NEEDED) continue
            for (day in dates(firstDay, lastDay)) {
                for (slot in Schedules.times(med.schedule, day, zone)) {
                    val marked = logs.any { it.medicationId == med.id && it.scheduledFor == slot }
                    if (marked) continue
                    val time = Say.time(slot, zone.zone)
                    val base = "dose:${med.id}:$slot"
                    val detailed = json("medication" to med.name, "dose" to med.dose, "scheduled_for" to Csv.iso(slot, zone.zone), "time" to time)
                    val plain = json("scheduled_for" to Csv.iso(slot, zone.zone), "time" to time)
                    val actions = listOf(ReminderAction("taken", "Taken"), ReminderAction("skip", "Skip"))
                    fun dose(key: String, at: Long, again: Boolean) = Reminder(
                        key = key, at = at, event = WebhookEvent.DOSE_DUE,
                        title = if (again) "Still to take: ${med.name}" else "Time for ${med.name}",
                        body = listOfNotNull(med.dose, time).joinToString(" · "),
                        privateTitle = if (again) "A $time medication is still to take" else "Time for your $time medication",
                        privateBody = "Tap to see which",
                        url = "/meds", actions = actions, data = plain, detailedData = detailed,
                        medicationId = med.id, scheduledFor = slot,
                    )
                    if (slot in from until to) out += dose(base, slot, again = false)
                    if (prefs.remindAgainMinutes > 0) {
                        for (n in 1..prefs.remindAgainTimes.coerceIn(0, 6)) {
                            val at = slot + n * prefs.remindAgainMinutes * 60_000L
                            if (at in from until to) out += dose("$base:again$n", at, again = true)
                        }
                    }
                    val missedAt = slot + prefs.missedAfterMinutes * 60_000L
                    if (prefs.missedAfterMinutes > 0 && missedAt in from until to) {
                        out += Reminder(
                            "missed:${med.id}:$slot", missedAt, WebhookEvent.DOSE_MISSED, "${med.name} wasn't marked ($time)", "",
                            "A $time medication wasn't marked", "", data = plain, detailedData = detailed, medicationId = med.id, scheduledFor = slot, push = false,
                        )
                    }
                }
            }
            // Refills: once a day at the chosen time, while doses left would last refillDays or fewer.
            val stock = med.stock
            val perDay = Schedules.perWeek(med.schedule) / 7.0
            if (stock != null && perDay > 0 && prefs.refillDays > 0) {
                val daysLeft = stock / perDay
                if (daysLeft <= prefs.refillDays) {
                    val time = runCatching { LocalTime.parse(prefs.refillTime) }.getOrDefault(LocalTime.of(10, 0))
                    for (day in dates(zone.dateOf(from), lastDay)) {
                        val at = day.atTime(time).atZone(zone.zone).toInstant().toEpochMilli()
                        if (at !in from until to) continue
                        val left = if (stock == 0) "None left" else "About ${Say.plural(daysLeft.toInt().coerceAtLeast(1), "day")} left ($stock)"
                        out += Reminder(
                            "refill:${med.id}:$day", at, WebhookEvent.REFILL, "Time to refill ${med.name}", left,
                            "Time to refill a medication", "Tap to see which", url = "/meds",
                            data = json("days_left" to daysLeft.roundToInt(), "doses_left" to stock),
                            detailedData = json("medication" to med.name, "days_left" to daysLeft.roundToInt(), "doses_left" to stock),
                            medicationId = med.id,
                        )
                    }
                }
            }
        }
        // Taken: a webhook when a dose is marked taken (within the window it was marked for).
        logs.filter { it.status == DoseStatus.TAKEN && it.at in from until to }.forEach { log ->
            val med = meds.firstOrNull { it.id == log.medicationId } ?: return@forEach
            out += Reminder(
                "taken:${log.id}", log.at.coerceAtMost(to - 60_000), WebhookEvent.DOSE_TAKEN, "${med.name} taken", "", "A medication was taken", "",
                data = json("at" to Csv.iso(log.at, zone.zone)), detailedData = json("medication" to med.name, "dose" to med.dose, "at" to Csv.iso(log.at, zone.zone)),
                medicationId = med.id, scheduledFor = log.scheduledFor, push = false,
            )
        }
        return out
    }

    override suspend fun questions(userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<com.health.api.SuggestedQuestion> = emptyList()
}

internal fun scheduleText(s: com.health.api.MedSchedule): String = when (s.kind) {
    ScheduleKind.DAILY -> if (s.times.size == 1) "Daily at ${s.times[0]}" else "Daily at ${s.times.joinToString(", ")}"
    ScheduleKind.WEEKLY -> "Weekly (${s.weekdays.joinToString(", ") { java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.UK) }}) at ${s.times.joinToString(", ")}"
    ScheduleKind.EVERY_N_DAYS -> "Every ${s.everyDays} days at ${s.times.joinToString(", ")}"
    ScheduleKind.AS_NEEDED -> "As needed"
}
