package com.health.server.care

import com.health.api.BloodPressure
import com.health.api.Contraction
import com.health.api.CycleOverview
import com.health.api.CyclePhase
import com.health.api.DigestSection
import com.health.api.Feed
import com.health.api.Flow
import com.health.api.HotFlash
import com.health.api.Insight
import com.health.api.KickSession
import com.health.api.LifeStage
import com.health.api.LifeStageInfo
import com.health.api.LifeStageOverview
import com.health.api.LifeStageToday
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.PregnancyStatus
import com.health.api.Profile
import com.health.api.ReportSectionKey
import com.health.api.Screener
import com.health.api.SuggestedQuestion
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.health.ProfileStore
import com.health.server.modules.CycleService
import com.health.server.modules.EntryKind
import com.health.server.modules.EntryStore
import com.health.server.modules.MindService
import com.health.server.platform.FeatureHooks
import com.health.server.platform.ReportSection
import com.health.server.platform.ReportTable
import com.health.server.util.Days
import kotlinx.serialization.KSerializer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Life stages, with the cycle: trying to conceive, pregnancy (weeks, movements, contractions),
 * after a birth (feeds, recovery), perimenopause and after menopause (hot flashes, the twelve
 * months that mark menopause). Everything is sealed like the cycle's own entries, and the copy
 * never assumes who is pregnant or who gave birth.
 */
class LifeStageService(
    private val entries: EntryStore,
    private val profiles: ProfileStore,
    private val cycle: CycleService,
    private val mind: MindService,
    private val db: Database,
    private val clock: () -> Long = System::currentTimeMillis,
) : FeatureHooks {
    override val module = Module.CYCLE

    suspend fun info(userId: String): LifeStageInfo =
        Singletons.get(entries, userId, EntryKind.LIFE_STAGE, LifeStageInfo.serializer()) ?: LifeStageInfo()

    /** The stage, for the cycle's predictions; null while simply tracking a cycle. */
    suspend fun stageOf(userId: String): LifeStageInfo? = info(userId).takeIf { it.stage != LifeStage.CYCLING }

    suspend fun saveInfo(userId: String, info: LifeStageInfo): LifeStageInfo {
        val today = profiles.days(userId).today(clock())
        fun date(text: String?, what: String): LocalDate? = text?.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it) }.getOrElse { throw ApiError.badRequest("$what: dates look like 2026-01-31") }
        }
        val due = date(info.dueDate, "Due date")
        val last = date(info.lastPeriod, "Last period")
        val birth = date(info.birthDate, "Birth date")
        val since = date(info.since, "Since") ?: today
        val clean = when (info.stage) {
            LifeStage.PREGNANT -> {
                val d = due ?: last?.plusDays(280) ?: throw ApiError.badRequest("Add a due date, or the first day of your last period")
                if (d.isBefore(today.minusDays(42)) || d.isAfter(today.plusDays(300))) throw ApiError.badRequest("That due date doesn't look right")
                info.copy(dueDate = d.toString(), lastPeriod = last?.toString(), birthDate = null)
            }
            LifeStage.POSTPARTUM -> {
                if (birth != null && (birth.isAfter(today) || birth.isBefore(today.minusYears(5)))) throw ApiError.badRequest("That birth date doesn't look right")
                info.copy(birthDate = birth?.toString(), dueDate = null, lastPeriod = last?.toString())
            }
            else -> info.copy(dueDate = null, birthDate = null, lastPeriod = last?.toString())
        }.copy(since = since.toString())
        Singletons.put(entries, userId, EntryKind.LIFE_STAGE, clean, LifeStageInfo.serializer())
        return clean
    }

    // ---- Kicks, contractions, feeds and hot flashes ----

    private suspend fun <T> list(userId: String, kind: EntryKind, days: Int, serializer: KSerializer<T>, withId: (T, String) -> T): List<T> {
        val zone = profiles.days(userId)
        val to = zone.today(clock()).plusDays(1)
        return entries.list(userId, kind, to.minusDays(days.coerceIn(1, 3650).toLong()), to, serializer).map { withId(it.value, it.id) }
    }

    private suspend fun <T> save(userId: String, kind: EntryKind, id: String?, at: Long, value: T, serializer: KSerializer<T>): String {
        if (at > clock() + 3_600_000L) throw ApiError.badRequest("That's in the future")
        return entries.put(userId, kind, id, profiles.days(userId).dateOf(at), at, value, serializer)
    }

    private fun checkSpan(start: Long, end: Long?) {
        if (end != null && (end < start || end - start > 24 * 3_600_000L)) throw ApiError.badRequest("The end is before the start, or more than a day after it")
    }

    suspend fun kicks(userId: String, days: Int) = list(userId, EntryKind.KICKS, days, KickSession.serializer()) { v, id -> v.copy(id = id) }

    suspend fun saveKicks(userId: String, k: KickSession, id: String? = null): KickSession {
        checkSpan(k.start, k.end)
        if (k.kicks !in 0..1000) throw ApiError.badRequest("Kicks are counted from 0 to 1,000")
        val clean = k.copy(id = null)
        return clean.copy(id = save(userId, EntryKind.KICKS, id, k.start, clean, KickSession.serializer()))
    }

    suspend fun contractions(userId: String, days: Int) = list(userId, EntryKind.CONTRACTION, days, Contraction.serializer()) { v, id -> v.copy(id = id) }

    suspend fun saveContraction(userId: String, c: Contraction, id: String? = null): Contraction {
        checkSpan(c.start, c.end)
        if (c.end != null && c.end!! - c.start > 10 * 60_000L) throw ApiError.badRequest("A contraction that long isn't one to time here: if you're worried, call your maternity unit")
        val clean = c.copy(id = null)
        return clean.copy(id = save(userId, EntryKind.CONTRACTION, id, c.start, clean, Contraction.serializer()))
    }

    suspend fun feeds(userId: String, days: Int) = list(userId, EntryKind.FEED, days, Feed.serializer()) { v, id -> v.copy(id = id) }

    suspend fun saveFeed(userId: String, f: Feed, id: String? = null): Feed {
        checkSpan(f.start, f.end)
        f.amountMl?.let { if (it !in 0..1000) throw ApiError.badRequest("An amount is in ml, up to 1,000") }
        val clean = f.copy(note = f.note?.trim()?.take(500)?.ifBlank { null }, id = null)
        return clean.copy(id = save(userId, EntryKind.FEED, id, f.start, clean, Feed.serializer()))
    }

    suspend fun hotFlashes(userId: String, days: Int) = list(userId, EntryKind.HOT_FLASH, days, HotFlash.serializer()) { v, id -> v.copy(id = id) }

    suspend fun hotFlashesBetween(userId: String, from: LocalDate, to: LocalDate): List<HotFlash> =
        entries.list(userId, EntryKind.HOT_FLASH, from, to, HotFlash.serializer()).map { it.value.copy(id = it.id) }

    suspend fun saveHotFlash(userId: String, h: HotFlash, id: String? = null): HotFlash {
        if (h.intensity !in 1..3) throw ApiError.badRequest("Intensity is 1 (mild) to 3 (strong)")
        val clean = h.copy(id = null)
        return clean.copy(id = save(userId, EntryKind.HOT_FLASH, id, h.at, clean, HotFlash.serializer()))
    }

    suspend fun delete(userId: String, kind: EntryKind, id: String) {
        entries.delete(userId, kind, id)
    }

    // ---- Where things stand ----

    suspend fun overview(userId: String, days: Int): LifeStageOverview {
        val info = info(userId)
        val zone = profiles.days(userId)
        val now = clock()
        val today = zone.today(now)
        val flashes = hotFlashes(userId, days)
        val contractions = contractions(userId, 2).sortedBy { it.start }
        return LifeStageOverview(
            info = info,
            pregnancy = if (info.stage == LifeStage.PREGNANT) LifeStages.pregnancy(info, today) else null,
            weeksSinceBirth = if (info.stage == LifeStage.POSTPARTUM) LifeStages.weeksSince(info.birthDate, today) else null,
            monthsSinceLastPeriod = if (info.stage == LifeStage.PERIMENOPAUSE || info.stage == LifeStage.MENOPAUSE) monthsSinceLastPeriod(userId, info, today) else null,
            kicks = if (info.stage == LifeStage.PREGNANT) kicks(userId, days).sortedByDescending { it.start } else emptyList(),
            contractions = contractions,
            contractionsPattern = LifeStages.contractionsPattern(contractions, now),
            feeds = if (info.stage == LifeStage.POSTPARTUM) feeds(userId, days.coerceAtMost(14)).sortedByDescending { it.start } else emptyList(),
            hotFlashes = flashes.sortedByDescending { it.at },
            hotFlashesPerDay = if (flashes.isEmpty()) null else ((flashes.size.toDouble() / days.coerceIn(1, 3650)) * 10).roundToInt() / 10.0,
            tips = LifeStages.tips(info, LifeStages.pregnancy(info, today)),
        )
    }

    suspend fun monthsSinceLastPeriod(userId: String, info: LifeStageInfo, today: LocalDate): Int? {
        val logged = cycle.logged(userId, today.minusYears(5), today).filter { it.flow != null && it.flow != Flow.SPOTTING }.maxOfOrNull { it.date }
        val last = listOfNotNull(logged?.let(LocalDate::parse), info.lastPeriod?.let(LocalDate::parse)).maxOrNull() ?: return null
        return ChronoUnit.MONTHS.between(last, today).toInt()
    }

    /** Today's card and insights, given what the other features have already put on Today. */
    suspend fun today(userId: String, profile: Profile, date: LocalDate, current: ModulesToday): Pair<LifeStageToday?, List<Pair<Int, Insight>>> {
        val info = info(userId)
        if (info.stage == LifeStage.CYCLING) return null to emptyList()
        val insights = mutableListOf<Pair<Int, Insight>>()
        val pregnancy = if (info.stage == LifeStage.PREGNANT) LifeStages.pregnancy(info, date) else null
        val feeds = if (info.stage == LifeStage.POSTPARTUM) entries.list(userId, EntryKind.FEED, date, date, Feed.serializer()).map { it.value.copy(id = it.id) } else emptyList()
        val flashes = if (info.stage == LifeStage.PERIMENOPAUSE || info.stage == LifeStage.MENOPAUSE) hotFlashesBetween(userId, date, date) else emptyList()
        val headline = when (info.stage) {
            LifeStage.PREGNANT -> pregnancy?.let { "Week ${it.week}, day ${it.day} · ${ordinal(it.trimester)} trimester" } ?: "Pregnant"
            LifeStage.POSTPARTUM -> LifeStages.weeksSince(info.birthDate, date)?.let { w -> if (w == 0) "The first week after the birth" else "${plural(w, "week")} since the birth" } ?: "After a birth"
            LifeStage.PERIMENOPAUSE -> if (flashes.isNotEmpty()) "${plural(flashes.size, "hot flash", "hot flashes")} today" else "Perimenopause"
            LifeStage.MENOPAUSE -> if (flashes.isNotEmpty()) "${plural(flashes.size, "hot flash", "hot flashes")} today" else "After menopause"
            LifeStage.TRYING -> current.cycle?.let { c -> if (c.phase == CyclePhase.FERTILE) "Fertile window (an estimate)" else c.cycleDay?.let { "Cycle day $it" } } ?: "Trying to conceive"
            LifeStage.CYCLING -> ""
        }

        if (info.stage == LifeStage.PREGNANT) {
            latestBloodPressure(userId, current, clock() - 3 * 86_400_000L)?.let { bp ->
                when {
                    bp.systolic >= 160 || bp.diastolic >= 110 -> insights += 99 to Insight(
                        "warn", "Call your maternity unit now",
                        "Your blood pressure was ${bp.systolic}/${bp.diastolic}, which is very high in pregnancy. Call your maternity unit or midwife now.", "heart", Module.CYCLE,
                    )
                    bp.systolic >= 140 || bp.diastolic >= 90 -> insights += 97 to Insight(
                        "warn", "Your blood pressure is high for pregnancy",
                        "A reading of ${bp.systolic}/${bp.diastolic}. Contact your midwife or doctor today. If you also have a bad headache, problems with your vision or sudden swelling, call them now.", "heart", Module.CYCLE,
                    )
                }
            }
            LifeStages.contractionsPattern(contractions(userId, 1).sortedBy { it.start }, clock())?.let { pattern ->
                if (pattern.startsWith("About every 5")) insights += 98 to Insight("warn", "Time to call your maternity unit", pattern, "clock", Module.CYCLE)
            }
        }
        if ((info.stage == LifeStage.POSTPARTUM || info.stage == LifeStage.PREGNANT) && Module.MIND in profile.modules) {
            val weeks = LifeStages.weeksSince(info.birthDate, date)
            val recent = mind.screenerResults(userId, 28).any { it.screener == Screener.EPDS }
            if (!recent && (info.stage == LifeStage.PREGNANT || (weeks != null && weeks in 1..52))) {
                insights += 20 to Insight("info", "A check-in for this time", "The Edinburgh check-in (EPDS) is made for pregnancy and the year after a birth. It's in Mind, and takes two minutes.", "sparkles", Module.MIND)
            }
        }
        if (info.stage == LifeStage.PERIMENOPAUSE) {
            monthsSinceLastPeriod(userId, info, date)?.takeIf { it >= 12 }?.let { months ->
                insights += 40 to Insight("info", "A year since your last period", "Twelve months without a period usually marks menopause ($months months now). You can change your stage in Cycle.", "calendar", Module.CYCLE)
            }
            val week = hotFlashesBetween(userId, date.minusDays(6), date).size
            val before = hotFlashesBetween(userId, date.minusDays(27), date.minusDays(7)).size
            if (week >= 14 && before > 0 && week / 7.0 >= (before / 21.0) * 1.5) {
                insights += 38 to Insight("info", "More hot flashes this week", "About ${(week / 7.0).roundToInt()} a day, up from about ${(before / 21.0).roundToInt().coerceAtLeast(1)}. Alcohol, caffeine, spicy food and a warm room set them off for some people.", "thermometer", Module.CYCLE)
            }
        }
        if (info.stage == LifeStage.MENOPAUSE) {
            val since = info.since?.let(LocalDate::parse) ?: date.minusDays(14)
            val bled = cycle.logged(userId, maxOf(since, date.minusDays(14)), date).any { it.flow != null }
            if (bled) insights += 90 to Insight("warn", "Bleeding after menopause is worth checking", "It's usually nothing serious, but any bleeding after menopause should be checked by a doctor. It's worth a call.", "calendar", Module.CYCLE)
        }
        val today = LifeStageToday(
            stage = info.stage,
            headline = headline,
            pregnancy = pregnancy,
            feedsToday = feeds.size,
            lastFeed = feeds.maxByOrNull { it.start },
            hotFlashesToday = flashes.size,
        )
        return today to insights
    }

    private suspend fun latestBloodPressure(userId: String, current: ModulesToday, since: Long): BloodPressure? {
        current.vitals?.bloodPressure?.takeIf { it.at >= since }?.let { return it }
        // Before the vitals feature has put its card on Today: its table, blood pressure rows being those with a diastolic.
        return runCatching {
            db.tx {
                one("SELECT at, v1, v2 FROM vitals WHERE user_id = ? AND v2 IS NOT NULL AND kind <> 'GLUCOSE' AND at >= ? ORDER BY at DESC LIMIT 1", userId, since) {
                    BloodPressure(it.long("at"), it.double("v1").roundToInt(), it.double("v2").roundToInt())
                }
            }
        }.getOrNull()
    }

    // ---- Hooks ----

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val info = info(userId)
        if (info.stage == LifeStage.CYCLING) return null
        val lines = mutableListOf<String>()
        when (info.stage) {
            LifeStage.PREGNANT -> LifeStages.pregnancy(info, to)?.let { lines += "Week ${it.week} of pregnancy; ${plural(it.daysToGo.coerceAtLeast(0), "day")} to the due date." }
            LifeStage.POSTPARTUM -> {
                LifeStages.weeksSince(info.birthDate, to)?.let { lines += "${plural(it, "week")} since the birth." }
                val feeds = entries.list(userId, EntryKind.FEED, from, to, Feed.serializer()).size
                if (feeds > 0) lines += "About ${(feeds / 7.0).roundToInt()} feeds a day."
            }
            LifeStage.PERIMENOPAUSE, LifeStage.MENOPAUSE -> {
                val week = hotFlashesBetween(userId, from, to).size
                val before = hotFlashesBetween(userId, from.minusDays(7), to.minusDays(7)).size
                lines += "${plural(week, "hot flash", "hot flashes")} this week${if (before > 0 || week > 0) ", against $before the week before" else ""}."
            }
            else -> lines += info.stage.label + "."
        }
        return DigestSection(info.stage.label, Module.CYCLE, "info", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val info = info(userId)
        if (info.stage == LifeStage.CYCLING) return null
        val summary = mutableListOf("Life stage: ${info.stage.label.lowercase()}${info.since?.let { " (since ${longDate(it)})" } ?: ""}.")
        LifeStages.pregnancy(info, to)?.takeIf { info.stage == LifeStage.PREGNANT }?.let { summary += "Week ${it.week}+${it.day}, due ${longDate(it.dueDate)}." }
        if (info.stage == LifeStage.POSTPARTUM) LifeStages.weeksSince(info.birthDate, to)?.let { summary += "${plural(it, "week")} since the birth (${longDate(info.birthDate)})." }
        val tables = mutableListOf<ReportTable>()
        val flashes = hotFlashesBetween(userId, from, to)
        if (flashes.isNotEmpty()) {
            val zone = Days(Days.zoneOf(profile.timezone))
            summary += "${plural(flashes.size, "hot flash", "hot flashes")}, ${flashes.count { it.night }} of them at night."
            tables += ReportTable(
                "Hot flashes by week", listOf("Week of", "Count", "At night", "Strong"),
                flashes.groupBy { zone.dateOf(it.at).let { d -> d.minusDays((d.dayOfWeek.value - 1).toLong()) } }.toSortedMap().map { (week, l) ->
                    listOf(week.toString(), l.size.toString(), l.count { it.night }.toString(), l.count { it.intensity == 3 }.toString())
                },
            )
        }
        if (info.stage == LifeStage.PERIMENOPAUSE || info.stage == LifeStage.MENOPAUSE) {
            monthsSinceLastPeriod(userId, info, to)?.let { summary += "${plural(it, "month")} since the last period." }
        }
        return ReportSection(ReportSectionKey.CYCLE, summary, tables)
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = Days(Days.zoneOf(profile.timezone))
        fun time(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone.zone).toLocalDateTime().toString()
        val kicks = entries.list(userId, EntryKind.KICKS, from, to, KickSession.serializer()).map { listOf(time(it.value.start), it.value.end?.let(::time).orEmpty(), it.value.kicks.toString()) }
        val contractions = entries.list(userId, EntryKind.CONTRACTION, from, to, Contraction.serializer()).map { listOf(time(it.value.start), it.value.end?.let(::time).orEmpty()) }
        val feeds = entries.list(userId, EntryKind.FEED, from, to, Feed.serializer()).map { listOf(time(it.value.start), it.value.end?.let(::time).orEmpty(), it.value.kind.name.lowercase(), it.value.amountMl?.toString().orEmpty(), it.value.note.orEmpty()) }
        val flashes = hotFlashesBetween(userId, from, to).map { listOf(time(it.at), it.intensity.toString(), it.night.toString()) }
        return buildMap {
            if (kicks.isNotEmpty()) put("kick-counts.csv", listOf(listOf("start", "end", "kicks")) + kicks)
            if (contractions.isNotEmpty()) put("contractions.csv", listOf(listOf("start", "end")) + contractions)
            if (feeds.isNotEmpty()) put("feeds.csv", listOf(listOf("start", "end", "kind", "amount_ml", "note")) + feeds)
            if (flashes.isNotEmpty()) put("hot-flashes.csv", listOf(listOf("time", "intensity", "night")) + flashes)
        }
    }

    override suspend fun questions(userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
        val info = info(userId)
        val out = mutableListOf<SuggestedQuestion>()
        when (info.stage) {
            LifeStage.PERIMENOPAUSE, LifeStage.MENOPAUSE -> {
                val flashes = hotFlashesBetween(userId, since, to)
                val days = ChronoUnit.DAYS.between(since, to).toInt() + 1
                if (flashes.size >= days) out += SuggestedQuestion(
                    "I've been having about ${(flashes.size.toDouble() / days).roundToInt().coerceAtLeast(1)} hot flashes a day. What options might help?",
                    "${plural(flashes.size, "hot flash", "hot flashes")} logged since ${shortDate(since.toString())}", Module.CYCLE,
                )
                if (info.stage == LifeStage.MENOPAUSE && cycle.logged(userId, since, to).any { it.flow != null }) {
                    out += SuggestedQuestion("I've had some bleeding since menopause. Should it be checked?", "Bleeding logged in Cycle", Module.CYCLE)
                }
            }
            // After a birth, the Edinburgh check-in's questions come from Mind's own.
            else -> Unit
        }
        return out
    }

    private fun ordinal(n: Int) = when (n) { 1 -> "first"; 2 -> "second"; else -> "third" }
}

/** The arithmetic of life stages: pure, so it can be tested on its own. */
object LifeStages {
    /** How far along a pregnancy is: from the due date, or the last period plus 280 days. */
    fun pregnancy(info: LifeStageInfo, today: LocalDate): PregnancyStatus? {
        val due = info.dueDate?.let(LocalDate::parse) ?: info.lastPeriod?.let { LocalDate.parse(it).plusDays(280) } ?: return null
        val start = due.minusDays(280)
        val elapsed = ChronoUnit.DAYS.between(start, today).toInt().coerceAtLeast(0)
        val week = elapsed / 7
        return PregnancyStatus(
            week = week,
            day = elapsed % 7,
            trimester = when { week < 14 -> 1; week < 28 -> 2; else -> 3 },
            dueDate = due.toString(),
            daysToGo = ChronoUnit.DAYS.between(today, due).toInt(),
        )
    }

    fun weeksSince(date: String?, today: LocalDate): Int? =
        date?.let { (ChronoUnit.DAYS.between(LocalDate.parse(it), today) / 7).toInt().coerceAtLeast(0) }

    /**
     * Contractions over the last hour or two, said plainly; "About every 5 minutes…" when they
     * follow the 5-1-1 pattern (about five minutes apart, about a minute long, for an hour).
     */
    fun contractionsPattern(list: List<Contraction>, now: Long): String? {
        val done = list.filter { it.end != null && it.start >= now - 2 * 3_600_000L && it.start <= now }.sortedBy { it.start }
        if (done.size < 3) return null
        val window = done.filter { it.start >= now - 70 * 60_000L }
        val usable = if (window.size >= 3) window else done
        val intervals = usable.zipWithNext { a, b -> (b.start - a.start) / 60_000.0 }
        val every = intervals.average()
        val lasting = usable.map { (it.end!! - it.start) / 1000.0 }.average()
        val span = (usable.last().start - usable.first().start) / 60_000.0
        if (every <= 5.5 && lasting >= 45 && span >= 55) {
            return "About every 5 minutes, lasting about a minute, for an hour: the usual time to call your midwife or maternity unit."
        }
        val lastHour = done.count { it.start >= now - 3_600_000L }
        return "Last hour: ${plural(lastHour, "contraction")}, about every ${every.roundToInt().coerceAtLeast(1)} minutes, lasting about ${roundSeconds(lasting)}."
    }

    private fun roundSeconds(s: Double): String = if (s >= 90) "${(s / 60).roundToInt()} minutes" else "${(s / 5).roundToInt() * 5} seconds"

    /**
     * The cycle's predictions for the stage: none while pregnant, after a birth or after
     * menopause; wider and less sure in perimenopause.
     */
    fun adjust(o: CycleOverview, info: LifeStageInfo): CycleOverview = when (info.stage) {
        LifeStage.PREGNANT -> o.paused("Predictions are paused while you're pregnant.")
        LifeStage.POSTPARTUM -> o.paused("Predictions are paused after a birth: periods can take a while to come back, especially while feeding. They start again once you log a period and change your stage.")
        LifeStage.MENOPAUSE -> o.paused("Predictions are off after menopause. Any bleeding now is worth mentioning to a doctor.")
        LifeStage.PERIMENOPAUSE -> o.prediction?.let { p ->
            val next = LocalDate.parse(p.nextPeriod)
            val spread = maxOf(7L, ChronoUnit.DAYS.between(LocalDate.parse(p.earliest), next) * 2)
            o.copy(
                prediction = p.copy(earliest = next.minusDays(spread).toString(), latest = next.plusDays(spread).toString(), confidence = "low"),
                caveat = "Cycles often vary a lot in perimenopause, so predictions are rough. They can't be used as contraception.",
            )
        } ?: o.copy(caveat = "Cycles often vary a lot in perimenopause, so predictions are rough. They can't be used as contraception.")
        LifeStage.TRYING, LifeStage.CYCLING -> o
    }

    private fun CycleOverview.paused(caveat: String) = copy(prediction = null, cycleDay = null, daysUntilPeriod = null, phase = CyclePhase.UNKNOWN, caveat = caveat)

    /** A few plain things worth knowing at each stage. */
    fun tips(info: LifeStageInfo, pregnancy: PregnancyStatus?): List<Insight> = when (info.stage) {
        LifeStage.PREGNANT -> listOfNotNull(
            Insight("warn", "If movements slow down or stop", "Contact your maternity unit straight away, any time of day or night. Don't wait until tomorrow. There's no set number that's normal: what matters is a change from the usual pattern.", "heart", Module.CYCLE)
                .takeIf { (pregnancy?.week ?: 0) >= 16 },
            Insight("info", "Headaches, vision changes or sudden swelling", "A headache that won't go, problems with your vision, or sudden swelling of the face, hands or feet: contact your midwife or doctor the same day.", "heart", Module.CYCLE),
            Insight("info", "Timing contractions", "About every 5 minutes, lasting a minute, for an hour is the usual time to call your maternity unit. Call sooner if your waters break, you're bleeding, or you're worried.", "clock", Module.CYCLE)
                .takeIf { (pregnancy?.week ?: 0) >= 34 },
        )
        LifeStage.POSTPARTUM -> listOf(
            Insight("warn", "Heavy bleeding, a fever or feeling very unwell", "Contact your midwife or doctor now if you're soaking a pad in an hour, have a fever, or feel very unwell.", "heart", Module.CYCLE),
            Insight("info", "How you're feeling matters too", "Low mood, worry or feeling detached are common after a birth, and support helps. The Edinburgh check-in in Mind can help you notice how you're doing.", "heart", Module.MIND),
            Insight("info", "Pelvic floor exercises help recovery", "A few minutes a day, starting gently. The guide in Bladder & bowel walks through them.", "sparkles", Module.CONTINENCE),
        )
        LifeStage.PERIMENOPAUSE -> listOf(
            Insight("info", "Periods often change", "They can come closer together, further apart or be heavier for a while, so predictions are rough for now.", "calendar", Module.CYCLE),
            Insight("info", "Hot flashes", "Layers, a cool bedroom, and less alcohol and caffeine help some people. Logging them shows what sets them off.", "thermometer", Module.CYCLE),
            Insight("info", "Twelve months", "A year without a period usually marks menopause.", "calendar", Module.CYCLE),
        )
        LifeStage.MENOPAUSE -> listOf(
            Insight("info", "Bleeding after menopause", "It's usually nothing serious, but any bleeding after menopause is worth checking with a doctor.", "calendar", Module.CYCLE),
        )
        LifeStage.TRYING -> listOf(
            Insight("info", "The fertile window", "The five days before ovulation and the day of it. Ovulation tests, cervical mucus and temperature help pin it down.", "calendar", Module.CYCLE),
        )
        LifeStage.CYCLING -> emptyList()
    }
}
