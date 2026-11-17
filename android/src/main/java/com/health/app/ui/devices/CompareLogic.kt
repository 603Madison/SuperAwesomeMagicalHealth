package com.health.app.ui.devices

import com.health.api.ComparedMetric
import com.health.api.ComparedNight
import com.health.api.CompareKey
import com.health.api.Device
import com.health.api.DeviceComparison
import com.health.api.DeviceKind
import com.health.api.RecentDevice
import com.health.api.SleepSession
import com.health.api.Units
import com.health.app.ui.Format
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.recent.Recent
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Comparing two devices (GET /api/devices/compare): what the page says, worked out without drawing
 * anything, so it can be tested. CompareScreen.kt draws it. [b] is always the one being checked and
 * [a] the one it's set against, so "higher" means b read higher than a.
 */
object Compare {
    /** The nights the page can look back over. */
    val SPANS = listOf(7, 14, 30)
    const val DEFAULT_DAYS = 14

    // ---- Sections (android.compare): their ids are saved with the person's arrangement, so never rename one.

    const val NIGHTS = "nights"

    /** Each thing compared has a section of its own. */
    fun section(key: CompareKey): String = when (key) {
        CompareKey.ASLEEP -> "asleep"
        CompareKey.DEEP -> "deep"
        CompareKey.LIGHT -> "light"
        CompareKey.REM -> "rem"
        CompareKey.AWAKE -> "awake"
        CompareKey.BEDTIME -> "bedtime"
        CompareKey.WAKE -> "wake"
        CompareKey.NIGHT_HEART_RATE -> "heart"
        CompareKey.LOWEST_HEART_RATE -> "lowest-heart"
        CompareKey.HRV -> "hrv"
        CompareKey.SPO2 -> "spo2"
        CompareKey.RESPIRATORY_RATE -> "breathing"
        CompareKey.SKIN_TEMPERATURE -> "temperature"
        CompareKey.DAY_HEART_RATE -> "day-heart"
        CompareKey.STEPS -> "steps"
    }

    /** Every section, in the default order: each thing compared, then night by night. */
    val SECTIONS: List<ArrangeItem> = CompareKey.entries.map { ArrangeItem(section(it), it.label) } + ArrangeItem(NIGHTS, "Night by night")

    /** The sections to show (and arrange) now: each thing both measured, and the nights when there are any. */
    fun sections(c: DeviceComparison): List<ArrangeItem> {
        val present = c.metrics.map { section(it.key) }.toSet()
        return SECTIONS.filter { it.id in present || (it.id == NIGHTS && c.nights.isNotEmpty()) }
    }

    /** The metric a section shows (none for the nights). */
    fun metric(c: DeviceComparison, section: String): ComparedMetric? = c.metrics.firstOrNull { section(it.key) == section }

    // ---- Which devices

    /** Whether Devices offers Compare devices: two or more devices that record readings. */
    fun offered(devices: List<Device>): Boolean = devices.count { Recent.records(it.kind) } >= 2

    /** The devices to choose from: every one with readings, and the two chosen even if they have none in these nights. */
    fun choices(c: DeviceComparison): List<RecentDevice> = (c.devices + listOfNotNull(c.a, c.b)).distinctBy { it.id }

    /**
     * Where a device is worn, as a word ("ring", "watch", "strap", "phone"), or null when neither its
     * kind nor its name says. As the server's own (Combine.placement): Gadgetbridge and Health
     * Connect pass on many devices, so their names are looked at.
     */
    fun noun(kind: DeviceKind?, name: String): String? = when (kind) {
        DeviceKind.COLMI_RING -> "ring"
        DeviceKind.PEBBLE, DeviceKind.GARMIN -> "watch"
        DeviceKind.HEART_RATE_STRAP -> "strap"
        DeviceKind.PHONE -> "phone"
        else -> {
            val words = name.lowercase().split(Regex("[^a-z0-9]+")).toSet()
            when {
                // Before the wrist: "Polar H10" is a strap, though Polar make watches too.
                words.any { it in CHEST_WORDS || STRAP_MODEL.matches(it) } -> "strap"
                "ring" in words || "oura" in words || "colmi" in words -> "ring"
                words.any { it in WRIST_WORDS } -> "watch"
                else -> null
            }
        }
    }

    private val CHEST_WORDS = setOf("strap", "chest", "hrm", "tickr")
    private val STRAP_MODEL = Regex("h[0-9]{1,3}s?")
    private val WRIST_WORDS = setOf("watch", "band", "pebble", "garmin", "fitbit", "amazfit", "bip", "gtr", "gts", "huawei", "polar", "suunto", "withings", "bangle", "pinetime")

    /**
     * What the page calls the two devices: "ring" and "strap" where that tells them apart ([short]),
     * else their names.
     */
    data class Names(val a: String, val b: String, val short: Boolean) {
        /** At the start of a sentence: "The ring", or the name. */
        fun subject(name: String) = if (short) "The $name" else name

        /** Inside a sentence: "the ring", or the name. */
        fun within(name: String) = if (short) "the $name" else name

        /** On its own (a column, a picker's line): "Ring", or the name. */
        fun label(name: String) = if (short) name.replaceFirstChar { it.uppercase() } else name

        val labelA get() = label(a)
        val labelB get() = label(b)
    }

    fun names(a: RecentDevice, b: RecentDevice): Names {
        val na = noun(a.kind, a.name)
        val nb = noun(b.kind, b.name)
        return if (na != null && nb != null && na != nb) Names(na, nb, true) else Names(a.name, b.name, false)
    }

    // ---- Numbers, by unit

    /** A value in the person's units: temperatures in °F with imperial; the rest as they come. */
    private fun shown(key: CompareKey, v: Double, units: Units): Double = if (key.unit == "°C") Recent.temperature(v, units) else v

    /** A difference in the person's units (a °F difference is 9/5 as big, with nothing added). */
    private fun shownDifference(key: CompareKey, v: Double, units: Units): Double = if (key.unit == "°C") Recent.temperatureChange(v, units) else v

    private fun temperatureUnit(units: Units) = if (units == Units.IMPERIAL) "°F" else "°C"

    /** "7 h 12 min", "45 min", "0 min". */
    fun duration(minutes: Double): String {
        val total = abs(minutes).roundToInt()
        val h = total / 60
        val m = total % 60
        return when {
            h > 0 && m > 0 -> "$h h $m min"
            h > 0 -> "$h h"
            else -> "$m min"
        }
    }

    /** Minutes after 6 pm the evening before [date] as the time on the clock there; without a date, the clock's time alone. */
    fun clock(minutes: Double, date: LocalDate? = null, zone: ZoneId? = null, locale: Locale = Locale.getDefault()): String {
        val format = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val m = minutes.roundToLong()
        return if (date != null && zone != null) format.format(date.minusDays(1).atTime(EVENING).atZone(zone).plusMinutes(m))
        else format.format(EVENING.plusMinutes(m))
    }

    private val EVENING: LocalTime = LocalTime.of(18, 0)

    private fun decimals(v: Double, places: Int, locale: Locale) = String.format(locale, "%.${places}f", v)

    /** A number alone as the page shows it for [key]: whole for beats, ms and steps; a decimal for the rest. */
    private fun number(key: CompareKey, v: Double, locale: Locale): String = when (key.unit) {
        "bpm", "ms" -> abs(v).roundToInt().toString()
        "steps" -> Format.number(abs(v))
        "°C" -> decimals(abs(v), 1, locale)
        else -> Format.compact(abs(v))
    }

    /**
     * A value with its unit: "7 h 12 min", "11:24 PM" (the clock on the night of [date], in [zone]),
     * "56 bpm", "48 ms", "96.4%", "14.2 per min", "33.6 °C" and steps grouped ("8,214").
     */
    fun value(key: CompareKey, v: Double, units: Units = Units.METRIC, date: LocalDate? = null, zone: ZoneId? = null, locale: Locale = Locale.getDefault()): String {
        val s = shown(key, v, units)
        val n = number(key, s, locale).let { if (s < 0 && key.unit !in setOf("min", "clock")) "−$it" else it }
        return when (key.unit) {
            "min" -> duration(s)
            "clock" -> clock(s, date, zone, locale)
            "bpm" -> "$n bpm"
            "ms" -> "$n ms"
            "%" -> "$n%"
            "/min" -> "$n per min"
            "°C" -> "$n ${temperatureUnit(units)}"
            else -> n
        }
    }

    /** How big a difference is, without its sign: "3" (beats), "12 min", "0.4" (degrees), "1,240" (steps). */
    fun amount(key: CompareKey, difference: Double, units: Units = Units.METRIC, locale: Locale = Locale.getDefault()): String {
        val d = shownDifference(key, difference, units)
        return if (key.unit == "min" || key.unit == "clock") duration(d) else number(key, d, locale)
    }

    /** Whether a difference shows as nothing at all at the page's precision. */
    fun none(key: CompareKey, difference: Double, units: Units = Units.METRIC): Boolean {
        val d = abs(shownDifference(key, difference, units))
        return when (key.unit) {
            "min", "clock", "bpm", "ms", "steps" -> d < 0.5
            else -> d < 0.05
        }
    }

    /** A difference with its sign: "+7", "−1", "0", "+12 min". */
    fun signed(key: CompareKey, difference: Double, units: Units = Units.METRIC, locale: Locale = Locale.getDefault()): String {
        val sign = when {
            none(key, difference, units) -> ""
            difference > 0 -> "+"
            else -> "−"
        }
        return sign + amount(key, difference, units, locale)
    }

    // ---- A summary card's words

    /** The nights (for steps and the day's heart rate, days) it covers: "9 nights", "1 day". */
    fun count(key: CompareKey, count: Int): String {
        val days = key == CompareKey.STEPS || key == CompareKey.DAY_HEART_RATE
        val word = if (days) (if (count == 1) "day" else "days") else (if (count == 1) "night" else "nights")
        return "$count $word"
    }

    /** What the thing compared is called inside a sentence: "deep sleep", "time awake". */
    private fun thing(key: CompareKey): String = when (key) {
        CompareKey.ASLEEP -> "sleep"
        CompareKey.DEEP -> "deep sleep"
        CompareKey.LIGHT -> "light sleep"
        CompareKey.REM -> "REM sleep"
        CompareKey.AWAKE -> "time awake"
        else -> key.label.replaceFirstChar { it.lowercase() }
    }

    /**
     * How b read against a, in a few words: "The ring read 3 bpm higher than the strap", "The
     * ring counted 18 min more deep sleep than the watch", "The ring said you fell asleep 12 min
     * later than the watch did". HRV also as a share of a's average ("(12% lower)").
     */
    fun headline(m: ComparedMetric, names: Names, units: Units = Units.METRIC, locale: Locale = Locale.getDefault()): String {
        val b = names.subject(names.b)
        val a = names.within(names.a)
        if (none(m.key, m.difference, units)) {
            return when (m.key.unit) {
                "min", "steps" -> "$b and $a counted the same on average"
                "clock" -> "$b and $a agreed on the time, on average"
                else -> "$b and $a read the same on average"
            }
        }
        val up = m.difference > 0
        val x = amount(m.key, m.difference, units, locale)
        return when (m.key.unit) {
            "min" -> "$b counted $x ${if (up) "more" else "less"} ${thing(m.key)} than $a"
            "clock" -> if (m.key == CompareKey.BEDTIME) "$b said you fell asleep $x ${if (up) "later" else "earlier"} than $a did"
            else "$b said you woke $x ${if (up) "later" else "earlier"} than $a did"
            "steps" -> "$b counted $x ${if (up) "more" else "fewer"} steps a day than $a"
            "bpm" -> "$b read $x bpm ${if (up) "higher" else "lower"} than $a"
            "ms" -> "$b read $x ms ${if (up) "higher" else "lower"} than $a" + (percent(m)?.let { " ($it% ${if (up) "higher" else "lower"})" } ?: "")
            "%" -> "$b read $x% ${if (up) "higher" else "lower"} than $a"
            "/min" -> "$b read $x ${if (up) "more" else "fewer"} breaths a minute than $a"
            "°C" -> "$b read $x ${temperatureUnit(units)} ${if (up) "warmer" else "cooler"} than $a"
            else -> "$b read $x ${if (up) "higher" else "lower"} than $a"
        }
    }

    /** HRV's difference as a share of a's average, rounded: 12 for 12%. Null where that says nothing. */
    fun percent(m: ComparedMetric): Int? {
        if (m.a <= 0) return null
        return (abs(m.difference) / m.a * 100).roundToInt().takeIf { it > 0 }
    }

    /**
     * The card's line: "The ring read 3 bpm higher than the strap · 4 apart on average · from −1
     * to +7 · 9 nights". How far apart and the spread only when there's more than one night to say
     * it of, and something to say ("0 apart", "from 0 to 0" are left out).
     */
    fun sentence(m: ComparedMetric, names: Names, units: Units = Units.METRIC, locale: Locale = Locale.getDefault()): String {
        val lowest = signed(m.key, m.lowest, units, locale)
        val highest = signed(m.key, m.highest, units, locale)
        return listOfNotNull(
            headline(m, names, units, locale),
            if (m.count > 1 && !none(m.key, m.apart, units)) "${amount(m.key, m.apart, units, locale)} apart on average" else null,
            if (m.count > 1 && lowest != highest) "from $lowest to $highest" else null,
            count(m.key, m.count),
        ).joinToString(" · ")
    }

    // ---- Night by night

    /** "Last night", else the evening it started: "Sunday night, 8 Nov". [date] is the morning it ended. */
    fun nightTitle(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String =
        if (date == today) "Last night" else DateTimeFormatter.ofPattern("EEEE 'night', d MMM", locale).format(date.minusDays(1))

    /** The span both nights are drawn across, so they line up: the earliest start to the latest end. Null without either. */
    fun span(night: ComparedNight): Pair<Long, Long>? {
        val sessions = listOfNotNull(night.a, night.b)
        if (sessions.isEmpty()) return null
        return sessions.minOf { it.start } to sessions.maxOf { it.end }
    }

    /** "11:08 PM – 6:30 AM". */
    fun sessionLine(session: SleepSession, zone: ZoneId): String =
        "${Format.timeOfDay(session.start, zone)} – ${Format.timeOfDay(session.end, zone)}"

    /** Whether a device recorded no night at all here (a strap, say): its readings are set against the other's nights. */
    fun noNights(c: DeviceComparison, ofA: Boolean): Boolean =
        c.nights.isNotEmpty() && c.nights.all { (if (ofA) it.a else it.b) == null } && c.nights.any { (if (ofA) it.b else it.a) != null }

    /** Said once above the nights when one device never records sleep. */
    fun noNightsLine(names: Names, ofA: Boolean): String {
        val none = if (ofA) names.a else names.b
        val other = if (ofA) names.b else names.a
        val possessive = if (names.short) "the $other's" else "$other's"
        return "${names.subject(none)} doesn't record sleep here, so its readings are set against $possessive nights."
    }

    /** One row of a night's table: what, then a's and b's values ("—" where one had none). */
    data class Row(val key: CompareKey, val label: String, val a: String, val b: String)

    /**
     * What both devices measure, on one night or another: a night's table lists only these, so a
     * strap's empty column for sleep it never records doesn't fill it.
     */
    fun measuredByBoth(c: DeviceComparison): Set<CompareKey> {
        val values = c.nights.flatMap { it.values }
        return values.filter { it.a != null }.map { it.key }.toSet() intersect values.filter { it.b != null }.map { it.key }.toSet()
    }

    /** A night's table: each thing in [keys] either device had that night, in the usual order, with the clock's times on that night in [zone]. */
    fun rows(night: ComparedNight, keys: Set<CompareKey>, units: Units = Units.METRIC, zone: ZoneId? = null, locale: Locale = Locale.getDefault()): List<Row> {
        val date = dateOf(night.date)
        return night.values.filter { it.key in keys && (it.a != null || it.b != null) }.sortedBy { it.key.ordinal }.map { v ->
            fun of(x: Double?) = x?.let { value(v.key, it, units, date, zone, locale) } ?: "—"
            Row(v.key, v.key.label, of(v.a), of(v.b))
        }
    }

    private fun dateOf(text: String): LocalDate? = com.health.app.attempt { LocalDate.parse(text) }.getOrNull()

    /** What a night's two hypnograms say to a screen reader. */
    fun nightDescription(name: String, session: SleepSession): String {
        val t = Recent.totals(listOf(session))
        return "$name: " + Recent.sleepDescription(t).replaceFirstChar { it.lowercase() }
    }

    // ---- When there's nothing to show

    enum class Empty { NO_DEVICES, ONE_DEVICE, NOTHING_TOGETHER }

    /** Why the page has nothing to compare, or null when it has. */
    fun empty(c: DeviceComparison): Empty? = when {
        c.a == null && c.b == null && c.devices.isEmpty() -> Empty.NO_DEVICES
        c.a == null || c.b == null -> Empty.ONE_DEVICE
        c.metrics.isEmpty() && c.nights.isEmpty() -> Empty.NOTHING_TOGETHER
        else -> null
    }

    /** The empty page's title and words: kind, and what to do about it. */
    fun emptyText(e: Empty, days: Int): Pair<String, String> = when (e) {
        Empty.NO_DEVICES -> "Nothing to compare yet" to "No device has readings in the last $days nights. Wear two on the same night to compare them."
        Empty.ONE_DEVICE -> "One device so far" to "Only one device has readings in the last $days nights. Wear both on the same night to compare them."
        Empty.NOTHING_TOGETHER -> "No nights together yet" to "Wear both on the same night to compare them, then sync."
    }

    /** Over a page with nights but nothing measured by both at once. */
    const val NOTHING_AT_ONCE = "They haven't measured anything at the same time yet. Wear both on the same night to compare them."

    /** Under the choices: how the comparing is done. */
    const val HOW = "Each reading is set against the other only over the time both were measuring."
}
