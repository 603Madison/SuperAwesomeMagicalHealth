package com.health.server.insights

import com.health.api.AlertKind
import com.health.api.AlertLevel
import com.health.api.AlertReading
import com.health.api.AlertSettings
import com.health.api.Metric
import com.health.api.Point
import com.health.api.SleepSession
import com.health.server.health.StoredSample
import com.health.server.util.Days
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * When a reading is worth a heads-up, as pure functions: a heart rate that stays high at rest or
 * low while awake, a chest strap's irregular rhythm, blood oxygen that stays low, the signs of
 * coming down with something, and a resting heart rate that stays up.
 *
 * Each is careful in what it claims: consumer devices misread (a ring squeezed, a strap gone
 * dry), so a spell has to last, a reading at rest has to be at rest, and the words say what else
 * it could be. What could need care now says when to call emergency services, every time.
 */
object AlertRules {
    /** A spell has to last this long (from its first reading to its last). */
    const val SUSTAIN_MS = 9 * 60_000L
    /** And have this many readings. */
    const val MIN_READINGS = 3
    /** Readings further apart than this break a spell. */
    const val GAP_MS = 20 * 60_000L
    /** Walking is about 100 steps a minute; above this, someone wasn't at rest. */
    const val ACTIVE_STEPS_PER_MIN = 10.0
    /** Just before a spell, above this, someone was still getting their breath back. */
    const val RECOVERING_STEPS_PER_MIN = 30.0
    const val URGENT_HEART_RATE = 150.0
    const val IRREGULAR_SHARE = 50.0
    /** Nights of someone's own usual needed before comparing a night with it. */
    const val BASELINE_MIN = 7
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    const val EMERGENCY = "If you have chest pain or pressure, feel faint, are very short of breath, or your heart is racing and won't settle, call your local emergency number now."

    data class Candidate(
        /** The same spell or night always has the same key, so it's one alert, brought up to date. */
        val key: String,
        val kind: AlertKind,
        val level: AlertLevel,
        val title: String,
        val body: String,
        val advice: String,
        val evidence: List<AlertReading>,
        val start: Long,
        val end: Long?,
        val devices: List<String>,
    )

    class Input(
        val now: Long,
        /** The last two days or so of heart rate, steps and a strap's rhythm; a week of blood oxygen. */
        val samples: List<StoredSample>,
        /** The last week's nights. */
        val sleep: List<SleepSession>,
        val workouts: List<LongRange>,
        val devices: Map<String, DeviceRef>,
        /** About five weeks of days, oldest first, the last being today. */
        val days: List<DayStats>,
        val luteal: Boolean,
        val settings: AlertSettings,
        val zone: Days,
        /** How far back spells are looked for. */
        val lookbackMs: Long = 36 * HOUR,
    )

    fun detect(i: Input): List<Candidate> {
        if (!i.settings.enabled) return emptyList()
        return highHeart(i) + lowHeart(i) + (if (i.settings.rhythm) rhythm(i) else emptyList()) + oxygen(i) +
            (if (i.settings.illness) illness(i) + restingRise(i) else emptyList())
    }

    // ---- Spells ----

    data class Spell(val points: List<Point>) {
        val start get() = points.first().t
        val end get() = points.last().t
        val lasted get() = end - start
        val values get() = points.map { it.v }
    }

    /** Runs of readings that pass [test], each within [GAP_MS] of the last, long enough to count. */
    fun spells(points: List<Point>, test: (Double) -> Boolean): List<Spell> {
        val out = mutableListOf<Spell>()
        var run = mutableListOf<Point>()
        fun close() {
            if (run.size >= MIN_READINGS && run.last().t - run.first().t >= SUSTAIN_MS) out += Spell(run)
            run = mutableListOf()
        }
        for (p in points) {
            if (!test(p.v) || (run.isNotEmpty() && p.t - run.last().t > GAP_MS)) close()
            if (test(p.v)) run += p
        }
        close()
        return out
    }

    /** Each value replaced by the median of it and its neighbours, so one odd reading neither makes nor breaks a spell. */
    fun smoothed(points: List<Point>): List<Point> = points.mapIndexed { n, p ->
        val near = points.subList(maxOf(0, n - 1), minOf(points.size, n + 2)).map { it.v }.sorted()
        Point(p.t, near[near.size / 2])
    }

    /** Heart rate a minute at a time, a strap's alone where it measured; readings no heart makes left out. */
    fun heartLine(i: Input): List<Point> {
        val from = i.now - i.lookbackMs
        val heart = i.samples.filter { it.metric == Metric.HEART_RATE && it.start >= from && it.value in 25.0..230.0 }
        return smoothed(Combine.line(heart, MINUTE, prefer = Combine.straps(i.devices)))
    }

    /** Steps a minute over [range], spreading each count over its time. Null when no device counted any steps nearby. */
    fun stepsPerMinute(i: Input, range: LongRange): Double? {
        val steps = i.samples.filter { it.metric == Metric.STEPS }
        val near = steps.any { (it.end ?: it.start) >= range.first - 2 * HOUR && it.start <= range.last + 2 * HOUR }
        if (!near) return null
        // Each device's own count, then the most any device counted (two devices count the same walk).
        val counted = steps.groupBy { it.deviceId }.values.maxOfOrNull { list ->
            list.sumOf { s ->
                val end = maxOf(s.end ?: (s.start + MINUTE), s.start + 1)
                val overlap = (minOf(end, range.last) - maxOf(s.start, range.first)).coerceAtLeast(0)
                s.value * overlap / (end - s.start)
            }
        } ?: 0.0
        return counted / ((range.last - range.first).coerceAtLeast(MINUTE) / MINUTE.toDouble())
    }

    private fun asleepDuring(i: Input, start: Long, end: Long) = i.sleep.any { it.start - 15 * MINUTE <= end && it.end + 15 * MINUTE >= start }
    private fun asleepAt(i: Input, t: Long) = i.sleep.any { t in it.start..it.end }

    private fun devicesIn(i: Input, metric: Metric, start: Long, end: Long, test: (Double) -> Boolean = { true }): List<String> =
        i.samples.filter { it.metric == metric && it.start in start..end && test(it.value) }.map { it.deviceId }.distinct()

    private fun names(i: Input, ids: List<String>) = ids.map { id -> i.devices[id]?.name ?: i.samples.firstOrNull { it.deviceId == id }?.deviceName ?: "A device" }

    // ---- Heart rate ----

    fun highHeart(i: Input): List<Candidate> {
        val threshold = i.settings.highHeartRate.toDouble()
        return spells(heartLine(i)) { it >= threshold }.mapNotNull { spell ->
            val asleep = asleepDuring(i, spell.start, spell.end)
            if (i.workouts.any { w -> w.first <= spell.end && w.last + 30 * MINUTE >= spell.start }) return@mapNotNull null
            val during = stepsPerMinute(i, spell.start - 10 * MINUTE..spell.end + MINUTE)
            val before = stepsPerMinute(i, spell.start - 20 * MINUTE..spell.start)
            if (!asleep && during != null && (during > ACTIVE_STEPS_PER_MIN || (before ?: 0.0) > RECOVERING_STEPS_PER_MIN)) return@mapNotNull null
            val ids = devicesIn(i, Metric.HEART_RATE, spell.start, spell.end + MINUTE) { it >= threshold }
            val strap = ids.any { it in Combine.straps(i.devices) }
            val typical = median(spell.values)
            val urgent = typical >= URGENT_HEART_RATE && (strap || ids.size >= 2)
            val resting = when {
                asleep -> "while you were asleep"
                during == null -> "with no steps counted (no device was counting them)"
                else -> "while you were still"
            }
            Candidate(
                key = "high-heart:${spell.start / (30 * MINUTE)}",
                kind = AlertKind.HIGH_HEART_RATE,
                level = if (urgent) AlertLevel.URGENT else AlertLevel.DOCTOR,
                title = "Your heart rate stayed high at rest",
                body = "It was ${range(spell.values)} bpm (mostly around ${typical.roundToInt()}) for ${minutes(spell.lasted)}, ${span(i, spell.start, spell.end)}, $resting.",
                advice = if (urgent) "$EMERGENCY If you feel well, it's still worth calling a doctor or a nurse line today and telling them about it."
                else "If you weren't active, upset or unwell, it's worth mentioning to a doctor, more so if it happens again. ${EMERGENCY.replace("now.", "straight away.")}",
                evidence = listOf(
                    AlertReading("Highest", "${spell.values.max().roundToInt()} bpm"),
                    AlertReading("For", minutes(spell.lasted)),
                    AlertReading("Your setting", "${i.settings.highHeartRate} bpm"),
                ) + listOfNotNull(during?.let { AlertReading("Steps meanwhile", "${(it * (spell.lasted / MINUTE + 10)).roundToInt()}") }),
                start = spell.start, end = spell.end, devices = names(i, ids),
            )
        }
    }

    fun lowHeart(i: Input): List<Candidate> {
        val threshold = i.settings.lowHeartRate.toDouble()
        return spells(heartLine(i)) { it <= threshold }.mapNotNull { spell ->
            val asleep = asleepDuring(i, spell.start, spell.end)
            val typical = median(spell.values)
            // Asleep, a low heart rate is often just deep rest: only very low counts, and gently.
            if (asleep && typical > 30) return@mapNotNull null
            val ids = devicesIn(i, Metric.HEART_RATE, spell.start, spell.end + MINUTE) { it <= threshold }
            Candidate(
                key = "low-heart:${spell.start / (30 * MINUTE)}",
                kind = AlertKind.LOW_HEART_RATE,
                level = if (asleep) AlertLevel.WATCH else AlertLevel.DOCTOR,
                title = if (asleep) "Your heart rate was very low in your sleep" else "Your heart rate stayed low while you were awake",
                body = "It was ${range(spell.values)} bpm for ${minutes(spell.lasted)}, ${span(i, spell.start, spell.end)}." +
                    if (asleep) " That's lower than most hearts go, even asleep, though a device can misread at night." else "",
                advice = "A low heart rate can be normal for very fit people and with some medicines (beta blockers, for one). " +
                    "If you felt dizzy, faint, unusually tired or short of breath, contact a doctor soon. If you fainted or have chest pain, call your local emergency number now.",
                evidence = listOf(AlertReading("Lowest", "${spell.values.min().roundToInt()} bpm"), AlertReading("For", minutes(spell.lasted)), AlertReading("Your setting", "${i.settings.lowHeartRate} bpm")),
                start = spell.start, end = spell.end, devices = names(i, ids),
            )
        }
    }

    // ---- Rhythm (a chest strap's) ----

    fun rhythm(i: Input): List<Candidate> {
        val from = i.now - i.lookbackMs
        val windows = i.samples.filter { it.metric == Metric.IRREGULAR_RHYTHM && it.start >= from }.sortedBy { it.start }
        if (windows.isEmpty()) return emptyList()
        // Stretches of irregular five-minute windows (a regular one in between doesn't end it), each at least three windows.
        val out = mutableListOf<Candidate>()
        var run = mutableListOf<StoredSample>()
        var regularSince = 0
        fun close() {
            val irregular = run.filter { it.value >= IRREGULAR_SHARE }
            if (irregular.size >= 3) out += rhythmAlert(i, run.first().start, run.last().end ?: (run.last().start + 5 * MINUTE), irregular, run.size)
            run = mutableListOf()
            regularSince = 0
        }
        for (w in windows) {
            if (run.isNotEmpty() && w.start - (run.last().end ?: run.last().start) > 10 * MINUTE) close()
            if (w.value >= IRREGULAR_SHARE) {
                run += w
                regularSince = 0
            } else if (run.isNotEmpty()) {
                regularSince++
                if (regularSince > 2) close() else run += w
            }
        }
        close()
        return out
    }

    private fun rhythmAlert(i: Input, start: Long, end: Long, irregular: List<StoredSample>, windows: Int): Candidate {
        val heart = heartLine(i).filter { it.t in start..end }.map { it.v }
        val fast = heart.isNotEmpty() && median(heart) >= 130
        val lasted = end - start
        return Candidate(
            key = "rhythm:${start / (30 * MINUTE)}",
            kind = AlertKind.IRREGULAR_RHYTHM,
            level = if (fast) AlertLevel.URGENT else AlertLevel.DOCTOR,
            title = if (fast) "Your strap picked up a fast, irregular rhythm" else "Your strap picked up an irregular rhythm",
            body = "For about ${minutes(lasted)}, ${span(i, start, end)}, the time between beats looked irregular (in ${irregular.size} of $windows five-minute stretches)" +
                (if (heart.isNotEmpty()) ", with your heart rate around ${median(heart).roundToInt()} bpm" else "") +
                ". That can be a sign of an irregular rhythm such as atrial fibrillation, but extra beats, a loose or dry strap and moving about can look the same.",
            advice = (if (fast) "$EMERGENCY If you feel well, call a doctor or a nurse line today." else "Worth telling a doctor, and showing them this. $EMERGENCY") +
                " An ECG taken while it's happening is what tells for sure: if it comes back, note the time and how you felt.",
            evidence = listOf(AlertReading("Irregular stretches", "${irregular.size} of $windows"), AlertReading("For about", minutes(lasted))) +
                listOfNotNull(heart.takeIf { it.isNotEmpty() }?.let { AlertReading("Heart rate", "${range(it)} bpm") }),
            start = start, end = end, devices = names(i, irregular.map { it.deviceId }.distinct()),
        )
    }

    // ---- Blood oxygen ----

    fun oxygen(i: Input): List<Candidate> {
        val low = i.settings.lowOxygen.toDouble()
        val readings = i.samples.filter { it.metric == Metric.SPO2 && it.value in 70.0..100.0 }.sortedBy { it.start }
        val out = mutableListOf<Candidate>()
        // Awake: two low readings in a row, within half an hour.
        val from = i.now - i.lookbackMs
        val awake = readings.filter { it.start >= from && !asleepAt(i, it.start) }
        awake.zipWithNext().filter { (a, b) -> a.value <= low - 2 && b.value <= low - 2 && b.start - a.start <= 30 * MINUTE }
            .distinctBy { (a, _) -> a.start / HOUR }
            .forEach { (a, b) ->
                out += Candidate(
                    key = "low-oxygen:${a.start / HOUR}",
                    kind = AlertKind.LOW_OXYGEN,
                    level = AlertLevel.DOCTOR,
                    title = "Your blood oxygen read low while you were awake",
                    body = "It read ${a.value.roundToInt()}% and then ${b.value.roundToInt()}%, ${span(i, a.start, b.start)}. Devices on a finger or wrist often misread with cold hands or movement.",
                    advice = "If you're short of breath, confused, or your lips or face look blue or grey, call your local emergency number now. " +
                        "Otherwise, sit still for a few minutes with warm hands and let it measure again; if it stays low, contact a doctor today.",
                    evidence = listOf(AlertReading("Readings", "${a.value.roundToInt()}%, ${b.value.roundToInt()}%"), AlertReading("Your setting", "${i.settings.lowOxygen}%")),
                    start = a.start, end = b.start, devices = names(i, listOf(a.deviceId, b.deviceId).distinct()),
                )
            }
        // Asleep: nights with several readings at or under the line.
        val nights = i.sleep.filter { it.end - it.start >= 3 * HOUR }.sortedBy { it.start }
        val flagged = nights.mapNotNull { night ->
            val during = readings.filter { it.start in night.start..night.end }
            val dips = during.filter { it.value <= low }
            if (dips.size >= 3 || (dips.size >= 2 && dips.size * 4 >= during.size)) Triple(night, during, dips) else null
        }
        flagged.filter { (night, _, _) -> night.end >= from }.forEach { (night, during, dips) ->
            val others = flagged.count { (n, _, _) -> n !== night && n.end < night.end && n.end >= night.end - 7 * 24 * HOUR }
            val date = i.zone.dateOf(night.end)
            out += Candidate(
                key = "oxygen-dips:$date",
                kind = AlertKind.OXYGEN_DIPS,
                level = if (others >= 1) AlertLevel.DOCTOR else AlertLevel.WATCH,
                title = if (others >= 1) "Your blood oxygen dipped on ${others + 1} nights this week" else "Your blood oxygen dipped overnight",
                body = "${dips.size} of ${during.size} readings last night were ${i.settings.lowOxygen}% or under (lowest ${dips.minOf { it.value }.roundToInt()}%).",
                advice = "Repeated dips while asleep can be a sign of sleep apnea, which is common and treatable: worth asking a doctor about, more so if you snore, wake up tired or with headaches. " +
                    "A ring can misread when the hand is squeezed or cold, so one night alone isn't much to go on.",
                evidence = listOf(AlertReading("Lowest", "${dips.minOf { it.value }.roundToInt()}%"), AlertReading("Low readings", "${dips.size} of ${during.size}")) +
                    listOfNotNull(if (others >= 1) AlertReading("Other nights this week", "$others") else null),
                start = night.start, end = night.end, devices = names(i, dips.map { it.deviceId }.distinct()),
            )
        }
        return out
    }

    // ---- Coming down with something ----

    /** A robust usual: the median and how far values usually stray (scaled MAD), from at least [BASELINE_MIN] values. */
    data class Usual(val median: Double, val spread: Double)

    fun usual(values: List<Double>): Usual? {
        if (values.size < BASELINE_MIN) return null
        val m = median(values)
        return Usual(m, 1.4826 * median(values.map { abs(it - m) }))
    }

    data class Signs(val restingUp: Double?, val hrvDown: Double?, val temperatureUp: Double?, val breathingUp: Double?) {
        val count get() = listOfNotNull(restingUp, hrvDown, temperatureUp, breathingUp).size
    }

    /** How a day's night compares with the four weeks before it, each sign only where it's well past the usual. */
    fun signs(day: DayStats, before: List<DayStats>, luteal: Boolean): Signs {
        val recent = before.takeLast(28)
        val rhr = day.restingHr?.let { v -> usual(recent.mapNotNull { it.restingHr })?.let { u -> (v - u.median).takeIf { it >= maxOf(3.0, 2 * u.spread) } } }
        val hrv = day.hrv?.let { v -> usual(recent.mapNotNull { it.hrv })?.takeIf { it.median > 0 }?.let { u -> ((u.median - v) / u.median).takeIf { it >= maxOf(0.15, 2 * u.spread / u.median) } } }
        val temp = Scoring.temperatureDeviation(day, before.takeLast(30))?.takeIf { it >= 0.3 }
        val breathing = day.respiratoryRate?.let { v -> usual(recent.mapNotNull { it.respiratoryRate })?.let { u -> (v - u.median).takeIf { it >= maxOf(1.0, 2 * u.spread) } } }
        // In the second half of a cycle, a warmer, faster-beating heart is expected: those count only with HRV or breathing too.
        return if (luteal && hrv == null && breathing == null) Signs(null, null, null, null) else Signs(rhr, hrv, temp, breathing)
    }

    fun illness(i: Input): List<Candidate> {
        val n = i.days.indexOfLast { it.sleep != null }
        if (n < 0 || n < i.days.size - 2) return emptyList()
        val day = i.days[n]
        val signs = signs(day, i.days.subList(0, n), i.luteal)
        if (signs.count < 2) return emptyList()
        val yesterday = if (n >= 1 && i.days[n - 1].sleep != null) signs(i.days[n - 1], i.days.subList(0, n - 1), false).count >= 2 else false
        val parts = listOfNotNull(
            signs.restingUp?.let { "resting heart rate ${it.roundToInt()} bpm above your usual" },
            signs.hrvDown?.let { "HRV ${(it * 100).roundToInt()}% below" },
            signs.temperatureUp?.let { "temperature +${"%.1f".format(Locale.ROOT, it)} °C" },
            signs.breathingUp?.let { "breathing ${"%.1f".format(Locale.ROOT, it)} breaths a minute faster" },
        )
        return listOf(
            Candidate(
                key = "sick:${day.date}",
                kind = AlertKind.GETTING_SICK,
                level = AlertLevel.WATCH,
                title = if (yesterday) "Your body may be fighting something off (a second night)" else "Your body may be fighting something off",
                body = "Last night: ${parts.joinToString(", ")}. Together, these often show a day or two before a cold or another bug.",
                advice = "Take it easy if you can: rest, fluids and an earlier night. Alcohol, a late meal, a hard workout or stress can do the same, so see how you feel. " +
                    "If you get a fever that won't come down, trouble breathing, or feel very unwell, contact a doctor.",
                evidence = listOfNotNull(
                    signs.restingUp?.let { AlertReading("Resting heart rate", "+${it.roundToInt()} bpm") },
                    signs.hrvDown?.let { AlertReading("HRV", "−${(it * 100).roundToInt()}%") },
                    signs.temperatureUp?.let { AlertReading("Temperature", "+${"%.1f".format(Locale.ROOT, it)} °C") },
                    signs.breathingUp?.let { AlertReading("Breathing", "+${"%.1f".format(Locale.ROOT, it)} a minute") },
                ),
                start = day.sleep!!.start, end = day.sleep.end, devices = emptyList(),
            ),
        )
    }

    /** Resting heart rate 10 or more above the usual three days running. */
    fun restingRise(i: Input): List<Candidate> {
        val days = i.days.takeLast(3)
        if (days.size < 3 || days.any { it.restingHr == null }) return emptyList()
        val u = usual(i.days.dropLast(3).takeLast(28).mapNotNull { it.restingHr }) ?: return emptyList()
        val rises = days.map { it.restingHr!! - u.median }
        if (rises.any { it < 10 }) return emptyList()
        return listOf(
            Candidate(
                key = "resting-rise:${days.first().date}",
                kind = AlertKind.RESTING_HEART_RISE,
                level = AlertLevel.WATCH,
                title = "Your resting heart rate has stayed up",
                body = "For three days it's been ${days.joinToString(", ") { it.restingHr!!.roundToInt().toString() }} bpm, against your usual ${u.median.roundToInt()}.",
                advice = "Illness, stress, poor sleep, hard training, some medicines and dehydration can all do it. If it stays up for more than a week, or comes with feeling unwell, breathless or dizzy, see a doctor.",
                evidence = listOf(AlertReading("Usual", "${u.median.roundToInt()} bpm"), AlertReading("Last three days", days.joinToString(", ") { "${it.restingHr!!.roundToInt()}" } + " bpm")),
                start = i.zone.start(java.time.LocalDate.parse(days.first().date)), end = null, devices = emptyList(),
            ),
        )
    }

    // ---- Words ----

    fun median(values: List<Double>): Double = values.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    private fun range(values: List<Double>): String {
        val lo = values.min().roundToInt()
        val hi = values.max().roundToInt()
        return if (lo == hi) "$lo" else "$lo–$hi"
    }

    fun minutes(ms: Long): String {
        val m = ((ms + MINUTE) / MINUTE).toInt()
        return if (m < 60) "$m minutes" else "${m / 60} h ${m % 60} min"
    }

    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    /** "from 2:14 to 2:36 am today", "from 11:50 pm to 12:20 am yesterday", "on Nov 14 from …". */
    fun span(i: Input, start: Long, end: Long): String {
        fun clock(t: Long) = Instant.ofEpochMilli(t).atZone(i.zone.zone).format(CLOCK).lowercase()
        val day = i.zone.dateOf(start)
        val today = i.zone.today(i.now)
        val whenDay = when (day) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            else -> "on " + day.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
        }
        return "from ${clock(start)} to ${clock(end)} $whenDay"
    }
}
