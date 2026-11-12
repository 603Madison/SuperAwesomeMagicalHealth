package com.health.server.neuro

import com.health.api.CommunicationCard
import com.health.api.Count
import com.health.api.DigestSection
import com.health.api.EnergyDay
import com.health.api.EnergySpend
import com.health.api.Flow
import com.health.api.FocusSession
import com.health.api.Insight
import com.health.api.Module
import com.health.api.NeuroCheckIn
import com.health.api.NeuroProfile
import com.health.api.NeuroSummary
import com.health.api.NeuroToday
import com.health.api.OverloadEvent
import com.health.api.Profile
import com.health.api.ReminderAction
import com.health.api.ReportSectionKey
import com.health.api.Routine
import com.health.api.RoutineRun
import com.health.api.SuggestedQuestion
import com.health.api.WebhookEvent
import com.health.api.Wording
import com.health.server.ApiError
import com.health.server.health.ProfileStore
import com.health.server.insights.DayService
import com.health.server.modules.CycleService
import com.health.server.modules.EntryKind
import com.health.server.modules.EntryStore
import com.health.server.platform.FeatureHooks
import com.health.server.platform.Reminder
import com.health.server.platform.ReportSection
import com.health.server.platform.ReportTable
import com.health.server.util.Days
import com.health.server.util.Random
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Autism and ADHD (Module.NEURO, shown in Mind): about me, times things got too much, energy,
 * check-ins, focus, routines and communication cards. Everything is sealed. The words are
 * neurodiversity-affirming: experiences, not symptoms; overwhelm, not behaviour.
 */
class NeuroService(
    private val entries: EntryStore,
    private val personal: PersonalStore,
    private val profiles: ProfileStore,
    private val days: DayService,
    private val cycle: CycleService,
    private val clock: () -> Long = System::currentTimeMillis,
) : FeatureHooks {
    override val module = Module.NEURO

    // ---------------------------------------------------------------- About me

    suspend fun profile(userId: String): NeuroProfile = personal.get(userId, PROFILE, NeuroProfile.serializer()) ?: NeuroProfile()

    suspend fun saveProfile(userId: String, p: NeuroProfile): NeuroProfile = personal.put(
        userId, PROFILE,
        p.copy(
            identities = p.identities.clean(20, 60),
            senses = p.senses.distinctBy { it.sense }.map { s ->
                s.copy(response = s.response.coerceIn(-2, 2), notes = s.notes.text(500), helps = s.helps.clean(20, 120))
            },
            communication = p.communication.clean(30, 200),
            whatHelps = p.whatHelps.clean(40, 200),
            accommodations = p.accommodations.clean(40, 200),
            warningSigns = p.warningSigns.clean(30, 200),
            interests = p.interests.clean(40, 120),
            stims = p.stims.clean(40, 120),
            aboutMe = p.aboutMe.text(4000),
        ),
        NeuroProfile.serializer(),
    )

    // ---------------------------------------------------------------- Times things got too much

    suspend fun events(userId: String, days: Int): List<OverloadEvent> {
        val zone = profiles.days(userId)
        val today = zone.today(clock())
        return entries.list(userId, EntryKind.NEURO_EVENT, today.minusDays(days.coerceIn(1, 3650) - 1L), today.plusDays(1), OverloadEvent.serializer())
            .map { it.value.copy(id = it.id, date = it.date.toString()) }.sortedByDescending { it.start }
    }

    suspend fun saveEvent(userId: String, e: OverloadEvent, id: String? = null): OverloadEvent {
        val now = clock()
        if (e.start > now + FUTURE_MS) throw ApiError.badRequest("That's in the future")
        if (e.end != null && (e.end!! < e.start || e.end!! - e.start > 7 * DAY_MS)) throw ApiError.badRequest("It has to end after it starts")
        val clean = e.copy(
            intensity = e.intensity.coerceIn(1, 5),
            triggers = e.triggers.clean(20, 120),
            warningSigns = e.warningSigns.clean(20, 120),
            whereAt = e.whereAt.text(120),
            whatHelped = e.whatHelped.clean(20, 120),
            recoveryMinutes = e.recoveryMinutes?.coerceIn(0, 60 * 24 * 14),
            note = e.note.text(4000),
            id = null, date = null,
        )
        val date = profiles.days(userId).dateOf(clean.start)
        val saved = entries.put(userId, EntryKind.NEURO_EVENT, id, date, clean.start, clean, OverloadEvent.serializer())
        return clean.copy(id = saved, date = date.toString())
    }

    // ---------------------------------------------------------------- Energy

    suspend fun energy(userId: String, days: Int): List<EnergyDay> {
        val today = profiles.days(userId).today(clock())
        return entries.list(userId, EntryKind.NEURO_ENERGY, today.minusDays(days.coerceIn(1, 3650) - 1L), today, EnergyDay.serializer())
            .map { it.value.copy(date = it.date.toString()) }.sortedByDescending { it.date }
    }

    suspend fun energyOn(userId: String, date: LocalDate): EnergyDay? =
        entries.list(userId, EntryKind.NEURO_ENERGY, date, date, EnergyDay.serializer()).lastOrNull()?.value

    suspend fun saveEnergy(userId: String, dateText: String, day: EnergyDay): EnergyDay {
        val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: throw ApiError.badRequest("Days look like 2026-03-20")
        if (date.isAfter(profiles.days(userId).today(clock()).plusDays(1))) throw ApiError.badRequest("That's in the future")
        val clean = EnergyDay(
            date = date.toString(),
            startWith = day.startWith?.coerceIn(0, 100),
            spends = day.spends.take(60).mapNotNull { s ->
                s.what.trim().take(120).takeIf { it.isNotEmpty() }?.let { EnergySpend(it, s.amount.coerceIn(-50, 100), s.at) }
            },
            socialBattery = day.socialBattery?.coerceIn(0, 10),
            note = day.note.text(2000),
        )
        entries.deleteDay(userId, EntryKind.NEURO_ENERGY, date)
        if (clean.startWith != null || clean.spends.isNotEmpty() || clean.socialBattery != null || clean.note != null) {
            entries.put(userId, EntryKind.NEURO_ENERGY, null, date, profiles.days(userId).start(date), clean, EnergyDay.serializer())
        }
        return clean
    }

    // ---------------------------------------------------------------- Check-ins

    suspend fun checkIns(userId: String, days: Int): List<NeuroCheckIn> {
        val today = profiles.days(userId).today(clock())
        return entries.list(userId, EntryKind.NEURO_CHECKIN, today.minusDays(days.coerceIn(1, 3650) - 1L), today.plusDays(1), NeuroCheckIn.serializer())
            .map { it.value.copy(id = it.id, date = it.date.toString()) }.sortedByDescending { it.at }
    }

    suspend fun saveCheckIn(userId: String, c: NeuroCheckIn): NeuroCheckIn {
        if (c.at > clock() + FUTURE_MS) throw ApiError.badRequest("That's in the future")
        fun Int?.r() = this?.coerceIn(1, 5)
        val woreOff = c.woreOffAt?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
            runCatching { LocalTime.parse(t).toString() }.getOrNull() ?: throw ApiError.badRequest("Times look like 14:30")
        }
        val clean = c.copy(
            masking = c.masking.r(), socialLoad = c.socialLoad.r(), sensoryLoad = c.sensoryLoad.r(), starting = c.starting.r(), focus = c.focus.r(),
            organising = c.organising.r(), remembering = c.remembering.r(), switching = c.switching.r(), restlessness = c.restlessness.r(),
            emotions = c.emotions.r(), burnout = c.burnout.r(), medEffect = c.medEffect.r(), woreOffAt = woreOff, note = c.note.text(2000),
            id = null, date = null,
        )
        val date = profiles.days(userId).dateOf(clean.at)
        val id = entries.put(userId, EntryKind.NEURO_CHECKIN, null, date, clean.at, clean, NeuroCheckIn.serializer())
        return clean.copy(id = id, date = date.toString())
    }

    // ---------------------------------------------------------------- Focus

    suspend fun focus(userId: String, days: Int): List<FocusSession> {
        val today = profiles.days(userId).today(clock())
        return entries.list(userId, EntryKind.NEURO_FOCUS, today.minusDays(days.coerceIn(1, 3650) - 1L), today.plusDays(1), FocusSession.serializer())
            .map { it.value.copy(id = it.id) }.sortedByDescending { it.start }
    }

    suspend fun saveFocus(userId: String, f: FocusSession, id: String? = null): FocusSession {
        if (f.start > clock() + FUTURE_MS) throw ApiError.badRequest("That's in the future")
        if (f.end != null && (f.end!! < f.start || f.end!! - f.start > 16 * 3_600_000L)) throw ApiError.badRequest("A session ends after it starts, within 16 hours")
        val clean = f.copy(
            task = f.task.text(200), estimateMinutes = f.estimateMinutes?.coerceIn(1, 1440), breaks = f.breaks.coerceIn(0, 100),
            rating = f.rating?.coerceIn(1, 5), note = f.note.text(2000), id = null,
        )
        val date = profiles.days(userId).dateOf(clean.start)
        return clean.copy(id = entries.put(userId, EntryKind.NEURO_FOCUS, id, date, clean.start, clean, FocusSession.serializer()))
    }

    // ---------------------------------------------------------------- Routines

    suspend fun routines(userId: String, all: Boolean = true): List<Routine> =
        (personal.get(userId, ROUTINES, ListSerializer(Routine.serializer())) ?: emptyList()).filter { all || it.active }

    suspend fun saveRoutine(userId: String, r: Routine, id: String? = null): Routine {
        val name = r.name.trim().take(80).ifEmpty { throw ApiError.badRequest("A routine needs a name") }
        val steps = r.steps.take(40).mapNotNull { s -> s.text.trim().take(200).takeIf { it.isNotEmpty() }?.let { s.copy(text = it, minutes = s.minutes?.coerceIn(1, 600)) } }
        if (steps.isEmpty()) throw ApiError.badRequest("A routine needs at least one step")
        val reminder = r.reminder?.trim()?.takeIf { it.isNotEmpty() }?.let { t -> runCatching { LocalTime.parse(t).toString() }.getOrNull() ?: throw ApiError.badRequest("Times look like 07:30") }
        val list = routines(userId).toMutableList()
        val routineId = id ?: Random.id()
        if (id != null && list.none { it.id == id }) throw ApiError.notFound("No such routine")
        val clean = r.copy(
            name = name, steps = steps, reminder = reminder, days = r.days.filter { it in 1..7 }.distinct().sorted(),
            icon = r.icon.trim().take(40).ifEmpty { "list-checks" }, id = routineId,
        )
        val i = list.indexOfFirst { it.id == routineId }
        if (i >= 0) list[i] = clean else {
            if (list.size >= 40) throw ApiError.badRequest("That's a lot of routines: 40 at most")
            list += clean
        }
        personal.put(userId, ROUTINES, list, ListSerializer(Routine.serializer()))
        return clean
    }

    suspend fun deleteRoutine(userId: String, id: String) {
        val list = routines(userId)
        if (list.none { it.id == id }) throw ApiError.notFound("No such routine")
        personal.put(userId, ROUTINES, list.filter { it.id != id }, ListSerializer(Routine.serializer()))
    }

    suspend fun runs(userId: String, days: Int): List<RoutineRun> {
        val today = profiles.days(userId).today(clock())
        return entries.list(userId, EntryKind.NEURO_ROUTINE_RUN, today.minusDays(days.coerceIn(1, 3650) - 1L), today, RoutineRun.serializer())
            .map { it.value.copy(id = it.id, date = it.date.toString()) }
    }

    suspend fun saveRun(userId: String, routineId: String, dateText: String, run: RoutineRun): RoutineRun {
        val routine = routines(userId).firstOrNull { it.id == routineId } ?: throw ApiError.notFound("No such routine")
        val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: throw ApiError.badRequest("Days look like 2026-03-20")
        val zone = profiles.days(userId)
        if (date.isAfter(zone.today(clock()).plusDays(1))) throw ApiError.badRequest("That's in the future")
        val existing = entries.list(userId, EntryKind.NEURO_ROUTINE_RUN, date, date, RoutineRun.serializer()).filter { it.value.routineId == routineId }
        existing.forEach { entries.delete(userId, EntryKind.NEURO_ROUTINE_RUN, it.id) }
        val clean = RoutineRun(routineId, date.toString(), run.done.filter { it in routine.steps.indices }.distinct().sorted(), run.at ?: clock())
        if (clean.done.isEmpty()) return clean
        val id = entries.put(userId, EntryKind.NEURO_ROUTINE_RUN, null, date, clean.at ?: zone.start(date), clean, RoutineRun.serializer())
        return clean.copy(id = id)
    }

    // ---------------------------------------------------------------- Communication cards

    suspend fun cards(userId: String): List<CommunicationCard> =
        personal.get(userId, CARDS, ListSerializer(CommunicationCard.serializer())) ?: DEFAULT_CARDS

    suspend fun saveCards(userId: String, cards: List<CommunicationCard>): List<CommunicationCard> {
        if (cards.size > 40) throw ApiError.badRequest("40 cards at most")
        val clean = cards.mapNotNull { c ->
            c.text.trim().take(300).takeIf { it.isNotEmpty() }?.let { CommunicationCard(it, c.detail.text(500), c.color?.trim()?.take(20), c.id ?: Random.id()) }
        }
        return personal.put(userId, CARDS, clean, ListSerializer(CommunicationCard.serializer()))
    }

    // ---------------------------------------------------------------- Patterns

    suspend fun summary(userId: String, days: Int): NeuroSummary {
        val span = days.coerceIn(7, 365)
        val zone = profiles.days(userId)
        val today = zone.today(clock())
        val from = today.minusDays(span - 1L)
        val events = events(userId, span)
        val checks = checkIns(userId, span)
        val energy = energy(userId, span)
        val focus = focus(userId, span)
        val profile = profiles.get(userId)
        val wording = profile(userId).wording

        val masking = checks.mapNotNull { it.masking }.averageOrNull()
        val used = energy.mapNotNull { d -> d.spends.filter { it.amount > 0 }.sumOf { it.amount }.takeIf { d.spends.isNotEmpty() } }.map { it.toDouble() }.averageOrNull()
        val focusMinutes = focus.sumOf { f -> f.end?.let { ((it - f.start) / 60_000).toInt() } ?: 0 }
        val ratios = focus.mapNotNull { f -> val end = f.end; val est = f.estimateMinutes; if (end != null && est != null && est > 0) (end - f.start) / 60_000.0 / est else null }
        val estimateRatio = ratios.takeIf { it.size >= 3 }?.average()?.let { (it * 10).roundToInt() / 10.0 }

        return NeuroSummary(
            days = span,
            overloads = events.size,
            byKind = events.groupingBy { it.kind.label }.eachCount().toCounts(),
            triggers = events.flatMap { it.triggers }.map { it.lowercase(Locale.ROOT) }.groupingBy { it }.eachCount().toCounts(8),
            whatHelped = events.flatMap { it.whatHelped }.map { it.lowercase(Locale.ROOT) }.groupingBy { it }.eachCount().toCounts(8),
            averageMasking = masking?.round1(),
            averageEnergyUsed = used?.round1(),
            focusMinutes = focusMinutes,
            estimateRatio = estimateRatio,
            patterns = patterns(userId, profile, zone, from, today, events, checks, energy, wording),
            burnoutRisk = burnoutRisk(today, events, checks, energy),
        )
    }

    /** Things that went together in the person's own days, said plainly, never as causes. */
    private suspend fun patterns(
        userId: String, profile: Profile, zone: Days, from: LocalDate, to: LocalDate,
        events: List<OverloadEvent>, checks: List<NeuroCheckIn>, energy: List<EnergyDay>, wording: Wording,
    ): List<com.health.api.Insight> {
        if (events.size < 3) return emptyList()
        val dates = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        val hard = events.map { LocalDate.parse(it.date) }.toSet()
        val out = mutableListOf<com.health.api.Insight>()
        fun compare(label: String, with: Set<LocalDate>, text: (Int, Int, Int, Int) -> String) {
            val withDays = dates.filter { it in with }
            val without = dates.filter { it !in with }
            if (withDays.size < 4 || without.size < 4) return
            val a = withDays.count { it in hard }
            val b = without.count { it in hard }
            val rateA = a.toDouble() / withDays.size
            val rateB = b.toDouble() / without.size
            if (a >= 2 && rateA - rateB >= 0.15 && rateA >= 1.5 * rateB) {
                out += com.health.api.Insight("info", label, text(a, withDays.size, b, without.size), "sparkles", Module.NEURO)
            }
        }
        val stats = runCatching { days.stats(userId, from, to, zone) }.getOrDefault(emptyList())
        val shortNights = stats.filter { (it.sleep?.asleepMinutes ?: Int.MAX_VALUE) < 390 }.map { LocalDate.parse(it.date) }.toSet()
        compare("Short nights and hard days", shortNights) { a, n, b, m ->
            "Overwhelm came on $a of $n days after under 6½ hours' sleep, against $b of $m other days. Rest may be part of what helps."
        }
        val masked = checks.filter { (it.masking ?: 0) >= 4 }.mapNotNull { it.date?.let(LocalDate::parse) }.toSet()
        compare("Masking and hard days", masked) { a, n, b, m ->
            "On days with a lot of masking, overwhelm came on $a of $n, against $b of $m other days. Time to unmask afterwards can help."
        }
        val social = (checks.filter { (it.socialLoad ?: 0) >= 4 }.mapNotNull { it.date?.let(LocalDate::parse) } +
            energy.filter { (it.socialBattery ?: 10) <= 3 }.map { LocalDate.parse(it.date) }).toSet()
        compare("Social days", social) { a, n, b, m ->
            "Busy social days (or a low social battery) went with overwhelm on $a of $n days, against $b of $m others."
        }
        val changes = events.filter { e -> e.triggers.any { it.contains("change", ignoreCase = true) || it.contains("plan", ignoreCase = true) } }
        if (changes.size >= 2 && changes.size * 3 >= events.size) {
            out += com.health.api.Insight("info", "Changes of plan", "${changes.size} of ${events.size} hard times came with a change of plans. A heads-up, or a plan B written down, can help.", "sparkles", Module.NEURO)
        }
        if (Module.CYCLE in profile.modules) {
            val periods = runCatching { cycle.logged(userId, from.minusDays(5), to.plusDays(5)) }.getOrDefault(emptyList())
                .filter { it.flow != null && it.flow != Flow.SPOTTING }.map { LocalDate.parse(it.date) }
            val around = dates.filter { d -> periods.any { p -> !d.isBefore(p.minusDays(3)) && !d.isAfter(p.plusDays(2)) } }.toSet()
            compare("Around a period", around) { a, n, b, m ->
                "Overwhelm came on $a of $n days around a period, against $b of $m other days. Many people find sensory things harder then."
            }
        }
        return out
    }

    /** Signs of burnout building over the last three weeks: a gentle note, never an alarm. */
    private fun burnoutRisk(today: LocalDate, events: List<OverloadEvent>, checks: List<NeuroCheckIn>, energy: List<EnergyDay>): String? {
        val recentFrom = today.minusDays(20)
        val beforeFrom = today.minusDays(41)
        fun inRecent(d: String?) = d?.let { !LocalDate.parse(it).isBefore(recentFrom) } ?: false
        val recentChecks = checks.filter { inRecent(it.date) }
        if (recentChecks.size < 5 && energy.count { inRecent(it.date) } < 5) return null
        var signs = 0
        val masking = recentChecks.mapNotNull { it.masking }.averageOrNull()
        val burnout = recentChecks.mapNotNull { it.burnout }.averageOrNull()
        if ((masking ?: 0.0) >= 3.5 || (burnout ?: 0.0) >= 3.5) signs++
        val battery = energy.filter { inRecent(it.date) }.mapNotNull { it.socialBattery?.toDouble() }.averageOrNull()
        val left = energy.filter { inRecent(it.date) }.mapNotNull { d -> d.startWith?.takeIf { it > 0 }?.let { s -> (s - d.spends.sumOf { it.amount }).toDouble() / s } }.averageOrNull()
        if ((battery != null && battery <= 4) || (left != null && left <= 0.2)) signs++
        val recent = events.count { inRecent(it.date) }
        val before = events.count { e -> e.date?.let { val d = LocalDate.parse(it); !d.isBefore(beforeFrom) && d.isBefore(recentFrom) } ?: false }
        if (recent >= 3 && recent >= before * 1.5) signs++
        if (signs < 2) return null
        return "The last few weeks have asked a lot of you: more masking and less energy, with more overwhelm than before. " +
            "That can lead towards burnout. Rest, fewer demands and support from people you trust can help, and it may be worth talking it through with someone you trust or your doctor."
    }

    // ---------------------------------------------------------------- Today

    suspend fun today(userId: String, profile: Profile, date: LocalDate): Pair<NeuroToday, List<Pair<Int, Insight>>> {
        val zone = Days(Days.zoneOf(profile.timezone))
        val events = entries.list(userId, EntryKind.NEURO_EVENT, date, date, OverloadEvent.serializer()).map { it.value }
        val energy = energyOn(userId, date)
        val focus = entries.list(userId, EntryKind.NEURO_FOCUS, date, date, FocusSession.serializer()).map { it.value }
        val runs = entries.list(userId, EntryKind.NEURO_ROUTINE_RUN, date, date, RoutineRun.serializer()).map { it.value }
        val routines = routines(userId, all = false)
        val dow = date.dayOfWeek.value
        val due = routines.filter { r -> (r.days.isEmpty() || dow in r.days) && runs.none { it.routineId == r.id && it.done.size >= r.steps.size } }
        val left = energy?.startWith?.let { s -> s - energy.spends.sumOf { it.amount } }
        val today = NeuroToday(
            overloadsToday = events.size,
            energyLeft = left,
            socialBattery = energy?.socialBattery,
            routinesDue = due.map { it.name },
            focusMinutes = focus.sumOf { f -> f.end?.let { ((it - f.start) / 60_000).toInt() } ?: 0 },
        )
        val insights = mutableListOf<Pair<Int, Insight>>()
        if (events.isNotEmpty()) {
            val helps = profile(userId).whatHelps.take(2)
            insights += 72 to Insight(
                "info", "Go gently today",
                "Today's been a lot. Making room for rest${if (helps.isNotEmpty()) " and what helps you (${helps.joinToString(", ")})" else ""} is worth it.",
                "heart", Module.NEURO,
            )
        }
        if (left != null && energy.startWith != null && left <= maxOf(1, energy.startWith!! / 6)) {
            insights += 64 to Insight("info", "Running low on energy", "${plural(left.coerceAtLeast(0), "spoon")} left today. It's OK to leave things for tomorrow.", "battery-low", Module.NEURO)
        }
        if (date == zone.today(clock())) {
            burnoutRisk(date, events(userId, 42), checkIns(userId, 42), energy(userId, 42))?.let {
                insights += 78 to Insight("info", "Look after yourself", it, "heart", Module.NEURO)
            }
        }
        return today to insights
    }

    // ---------------------------------------------------------------- Hooks

    override suspend fun reminders(userId: String, profile: Profile, from: Long, to: Long): List<Reminder> {
        val zone = Days(Days.zoneOf(profile.timezone))
        val routines = routines(userId, all = false).filter { it.reminder != null }
        if (routines.isEmpty()) return emptyList()
        val out = mutableListOf<Reminder>()
        var date = zone.dateOf(from)
        while (!date.isAfter(zone.dateOf(to))) {
            val runs = entries.list(userId, EntryKind.NEURO_ROUTINE_RUN, date, date, RoutineRun.serializer()).map { it.value }
            for (r in routines) {
                if (r.days.isNotEmpty() && date.dayOfWeek.value !in r.days) continue
                val at = date.atTime(LocalTime.parse(r.reminder)).atZone(zone.zone).toInstant().toEpochMilli()
                if (at < from || at >= to) continue
                if (runs.any { it.routineId == r.id && it.done.size >= r.steps.size }) continue
                val minutes = r.steps.mapNotNull { it.minutes }.sum()
                out += Reminder(
                    key = "routine:${r.id}:$date", at = at, event = WebhookEvent.HABIT_DUE,
                    title = "Time for: ${r.name}",
                    body = "${plural(r.steps.size, "step")}${if (minutes > 0) ", about ${minutes} minutes" else ""}. One step at a time.",
                    privateTitle = "A routine reminder", privateBody = "One of your routines is ready when you are.",
                    url = "/mind", actions = listOf(ReminderAction("open", "Open")),
                )
            }
            date = date.plusDays(1)
        }
        return out
    }

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val events = entries.list(userId, EntryKind.NEURO_EVENT, from, to, OverloadEvent.serializer()).map { it.value }
        val before = entries.list(userId, EntryKind.NEURO_EVENT, from.minusDays(7), from.minusDays(1), OverloadEvent.serializer()).size
        val checks = entries.list(userId, EntryKind.NEURO_CHECKIN, from, to, NeuroCheckIn.serializer()).map { it.value }
        val focus = entries.list(userId, EntryKind.NEURO_FOCUS, from, to, FocusSession.serializer()).map { it.value }
        if (events.isEmpty() && checks.isEmpty() && focus.isEmpty()) return null
        val minutes = focus.sumOf { f -> f.end?.let { ((it - f.start) / 60_000).toInt() } ?: 0 }
        val lines = listOfNotNull(
            when {
                events.isEmpty() -> "No hard times logged this week."
                else -> "${plural(events.size, "time")} things got too much (${if (events.size < before) "fewer than" else if (events.size == before) "the same as" else "more than"} the week before)."
            },
            checks.mapNotNull { it.masking }.averageOrNull()?.let { "Masking averaged ${it.round1()} of 5." },
            minutes.takeIf { it > 0 }?.let { "${hm(it)} of focus or interest time." },
            events.flatMap { it.whatHelped }.groupingBy { it.lowercase(Locale.ROOT) }.eachCount().maxByOrNull { it.value }?.let { "What helped most: ${it.key}." },
        )
        val tone = if (events.size > before && events.size >= 3) "warn" else "info"
        return DigestSection("Autism & ADHD", Module.NEURO, tone, lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val zone = Days(Days.zoneOf(profile.timezone))
        val about = profile(userId)
        val events = entries.list(userId, EntryKind.NEURO_EVENT, from, to, OverloadEvent.serializer()).map { it.value }
        val checks = entries.list(userId, EntryKind.NEURO_CHECKIN, from, to, NeuroCheckIn.serializer()).map { it.value }
        if (events.isEmpty() && checks.isEmpty() && about == NeuroProfile()) return null
        val summary = mutableListOf<String>()
        describe(about)?.let { summary += it }
        if (about.whatHelps.isNotEmpty()) summary += "What helps: ${about.whatHelps.joinToString(", ")}."
        if (about.accommodations.isNotEmpty()) summary += "Accommodations that help: ${about.accommodations.joinToString(", ")}."
        if (about.communication.isNotEmpty()) summary += "How they like to communicate: ${about.communication.joinToString(", ")}."
        summary += if (events.isEmpty()) "No times of overwhelm were logged in this period."
        else "${plural(events.size, "time")} things got too much: " + events.groupingBy { it.kind.label.lowercase(Locale.ROOT) }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" } + "."
        checks.mapNotNull { it.medEffect }.averageOrNull()?.let { summary += "Medication worked ${it.round1()} of 5 on average${checks.mapNotNull { c -> c.woreOffAt }.lastOrNull()?.let { t -> ", wearing off around $t" } ?: ""}." }
        checks.mapNotNull { it.masking }.averageOrNull()?.let { summary += "Masking averaged ${it.round1()} of 5." }
        val table = ReportTable(
            "Times things got too much", listOf("When", "What", "Intensity", "Triggers", "What helped"),
            events.sortedBy { it.start }.takeLast(40).map { e ->
                listOf(com.health.server.platform.Say.dateTime(e.start, zone.zone), e.kind.label, "${e.intensity} of 5", e.triggers.joinToString(", "), e.whatHelped.joinToString(", "))
            },
        )
        return ReportSection(ReportSectionKey.NEURO, summary, if (events.isEmpty()) emptyList() else listOf(table))
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = Days(Days.zoneOf(profile.timezone))
        fun iso(ms: Long?) = com.health.server.platform.Csv.iso(ms, zone.zone)
        val events = entries.list(userId, EntryKind.NEURO_EVENT, from, to, OverloadEvent.serializer()).map { it.value }
        val checks = entries.list(userId, EntryKind.NEURO_CHECKIN, from, to, NeuroCheckIn.serializer()).map { it.value }
        val energy = entries.list(userId, EntryKind.NEURO_ENERGY, from, to, EnergyDay.serializer()).map { it.value }
        val focus = entries.list(userId, EntryKind.NEURO_FOCUS, from, to, FocusSession.serializer()).map { it.value }
        val files = linkedMapOf<String, List<List<String>>>()
        if (events.isNotEmpty()) files["overwhelm.csv"] = listOf(listOf("start", "end", "kind", "intensity", "senses", "triggers", "warning_signs", "where", "what_helped", "recovery_minutes", "note")) +
            events.map { e -> listOf(iso(e.start), iso(e.end), e.kind.name, "${e.intensity}", e.senses.joinToString(";"), e.triggers.joinToString(";"), e.warningSigns.joinToString(";"), e.whereAt.orEmpty(), e.whatHelped.joinToString(";"), e.recoveryMinutes?.toString().orEmpty(), e.note.orEmpty()) }
        if (checks.isNotEmpty()) files["neuro-check-ins.csv"] = listOf(listOf("at", "masking", "social_load", "sensory_load", "starting", "focus", "organising", "remembering", "switching", "restlessness", "emotions", "burnout", "med_effect", "wore_off_at", "note")) +
            checks.map { c -> listOf(iso(c.at), c.masking, c.socialLoad, c.sensoryLoad, c.starting, c.focus, c.organising, c.remembering, c.switching, c.restlessness, c.emotions, c.burnout, c.medEffect).let { l -> listOf(l[0].toString()) + l.drop(1).map { it?.toString().orEmpty() } } + listOf(c.woreOffAt.orEmpty(), c.note.orEmpty()) }
        if (energy.isNotEmpty()) files["energy.csv"] = listOf(listOf("date", "start_with", "used", "social_battery", "note")) +
            energy.map { d -> listOf(d.date, d.startWith?.toString().orEmpty(), d.spends.sumOf { it.amount }.toString(), d.socialBattery?.toString().orEmpty(), d.note.orEmpty()) }
        if (focus.isNotEmpty()) files["focus.csv"] = listOf(listOf("start", "end", "kind", "task", "estimate_minutes", "breaks", "rating", "note")) +
            focus.map { f -> listOf(iso(f.start), iso(f.end), f.kind.name, f.task.orEmpty(), f.estimateMinutes?.toString().orEmpty(), "${f.breaks}", f.rating?.toString().orEmpty(), f.note.orEmpty()) }
        return files
    }

    override suspend fun questions(userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
        val span = ChronoUnit.DAYS.between(since, to).toInt().coerceIn(14, 365) + 1
        val s = summary(userId, span)
        val checks = checkIns(userId, span)
        val out = mutableListOf<SuggestedQuestion>()
        if (s.patterns.any { it.title == "Short nights and hard days" }) {
            out += SuggestedQuestion("Overwhelm has come more often after short nights. What could help with sleep?", "From your Autism & ADHD log", Module.NEURO)
        }
        s.burnoutRisk?.let { out += SuggestedQuestion("I've had signs of burnout lately. What support or adjustments could help?", "Masking, energy and overwhelm over the last few weeks", Module.NEURO) }
        checks.mapNotNull { it.medEffect }.takeIf { it.size >= 5 }?.average()?.takeIf { it <= 2.5 }?.let { avg ->
            val off = checks.mapNotNull { it.woreOffAt }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            out += SuggestedQuestion(
                "My medication hasn't seemed to help much lately (${avg.round1()} of 5)${off?.let { ", and wears off around $it" } ?: ""}. Could the dose or timing change?",
                "From your check-ins", Module.NEURO,
            )
        }
        if (s.overloads >= 6) out += SuggestedQuestion("Things have got too much ${s.overloads} times lately. Could we talk about support or accommodations?", "From your Autism & ADHD log", Module.NEURO)
        return out
    }

    companion object {
        private const val PROFILE = "neuro.profile"
        private const val ROUTINES = "neuro.routines"
        private const val CARDS = "neuro.cards"
        private const val DAY_MS = 86_400_000L
        private const val FUTURE_MS = 6 * 3_600_000L

        /** Cards offered until the person makes their own: kind, plain, ready to show. */
        val DEFAULT_CARDS = listOf(
            CommunicationCard("I'm autistic and need a quiet minute.", "I'm OK. Please give me a little space and time.", "violet", "default-quiet"),
            CommunicationCard("I can't talk right now. Please text me.", null, "ocean", "default-text"),
            CommunicationCard("Too loud for me. Can we go somewhere quieter?", null, "ocean", "default-loud"),
            CommunicationCard("I need a few minutes, then I'll be OK.", null, "violet", "default-minutes"),
            CommunicationCard("Please give me instructions in writing.", "Written steps help me get them right.", "green", "default-writing"),
            CommunicationCard("I have ADHD. Could you say that again, or write it down?", null, "green", "default-again"),
            CommunicationCard("I'm not ignoring you: I'm focused. Tap my shoulder or send a message.", null, "amber", "default-focused"),
            CommunicationCard("Changes are hard for me. Could you tell me what's happening and when?", null, "amber", "default-changes"),
        )

        /** "An autistic and ADHD person" or "A person with autism and ADHD", as the person prefers; else their own words. */
        fun describe(p: NeuroProfile): String? {
            if (p.identities.isEmpty()) return null
            val known = p.identities.map { it.trim().lowercase(Locale.ROOT) }
            val adjectives = mutableListOf<String>()
            val nouns = mutableListOf<String>()
            for (id in known) when {
                id.startsWith("audhd") -> { adjectives += listOf("autistic", "ADHD"); nouns += listOf("autism", "ADHD") }
                id.startsWith("autis") -> { adjectives += "autistic"; nouns += "autism" }
                id == "adhd" || id == "add" -> { adjectives += "ADHD"; nouns += "ADHD" }
                id.startsWith("dyspra") -> { adjectives += "dyspraxic"; nouns += "dyspraxia" }
                id.startsWith("dyslex") -> { adjectives += "dyslexic"; nouns += "dyslexia" }
                else -> return "In their own words: ${p.identities.joinToString(", ")}."
            }
            fun join(words: List<String>) = words.distinct().let { w -> if (w.size <= 1) w.joinToString() else w.dropLast(1).joinToString(", ") + " and " + w.last() }
            return when (p.wording) {
                Wording.IDENTITY_FIRST -> "${join(adjectives).replaceFirstChar { it.uppercase() }} (in their own words)."
                Wording.PERSON_FIRST -> "A person with ${join(nouns)} (in their own words)."
            }
        }
    }
}

internal fun List<String>.clean(max: Int, length: Int): List<String> = map { it.trim().take(length) }.filter { it.isNotEmpty() }.distinct().take(max)

internal fun String?.text(max: Int): String? = this?.trim()?.take(max)?.ifEmpty { null }

internal fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()

@JvmName("averageOrNullDouble")
internal fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

internal fun Double.round1(): Double = (this * 10).roundToInt() / 10.0

internal fun Map<String, Int>.toCounts(limit: Int = 20): List<Count> = entries.sortedByDescending { it.value }.take(limit).map { Count(it.key, it.value) }

internal fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

internal fun hm(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}
