package com.health.server.transition

import com.health.api.BodyChange
import com.health.api.ChecklistItem
import com.health.api.Count
import com.health.api.DigestSection
import com.health.api.GenderMoment
import com.health.api.HairRemovalSession
import com.health.api.Insight
import com.health.api.MeasurementSet
import com.health.api.Metric
import com.health.api.Milestone
import com.health.api.MilestoneArea
import com.health.api.MilestoneStatus
import com.health.api.MilestoneTemplate
import com.health.api.Module
import com.health.api.MomentKind
import com.health.api.Point
import com.health.api.Profile
import com.health.api.ReportSectionKey
import com.health.api.SuggestedQuestion
import com.health.api.TransitionOverview
import com.health.api.TransitionProfile
import com.health.api.TransitionToday
import com.health.api.VoiceSession
import com.health.server.ApiError
import com.health.server.care.HormonesService
import com.health.server.health.ProfileStore
import com.health.server.health.Records
import com.health.server.modules.EntryKind
import com.health.server.modules.EntryStore
import com.health.server.modules.UserSettingsStore
import com.health.server.neuro.clean
import com.health.server.neuro.plural
import com.health.server.neuro.text
import com.health.server.platform.Csv
import com.health.server.platform.FeatureHooks
import com.health.server.platform.ReportChart
import com.health.server.platform.ReportSection
import com.health.server.platform.ReportTable
import com.health.server.platform.Say
import com.health.server.util.Days
import kotlinx.serialization.KSerializer
import java.time.LocalDate
import java.time.Period
import java.util.Locale

/**
 * Transition (Module.TRANSITION), whatever it means to each person and at their pace: milestones,
 * changes they notice, voice practice, hair removal, and moments of euphoria and dysphoria. Nothing
 * assumes a direction, a goal or medical steps. Everything is sealed.
 */
class TransitionService(
    private val entries: EntryStore,
    private val personal: UserSettingsStore,
    private val profiles: ProfileStore,
    private val hormones: HormonesService,
    private val records: Records,
    private val clock: () -> Long = System::currentTimeMillis,
) : FeatureHooks {
    override val module = Module.TRANSITION

    // ---------------------------------------------------------------- Profile

    suspend fun profile(userId: String): TransitionProfile = personal.get(userId, PROFILE, TransitionProfile.serializer()) ?: TransitionProfile()

    suspend fun saveProfile(userId: String, p: TransitionProfile): TransitionProfile {
        val started = p.startedOn?.trim()?.takeIf { it.isNotEmpty() }?.let { parseLoose(it) ?: throw ApiError.badRequest("Dates look like 2025-03 or 2025-03-20") }
        return personal.put(
            userId, PROFILE,
            TransitionProfile(
                pronouns = p.pronouns.text(40),
                startedOn = started,
                goals = p.goals.clean(30, 200),
                voiceTargetHz = p.voiceTargetHz?.takeIf { it.isFinite() }?.coerceIn(50.0, 400.0),
            ),
            TransitionProfile.serializer(),
        )
    }

    fun templates(): List<MilestoneTemplate> = TEMPLATES

    // ---------------------------------------------------------------- Milestones

    suspend fun milestones(userId: String): List<Milestone> =
        entries.list(userId, EntryKind.TRANSITION_MILESTONE, EVER, FOREVER, Milestone.serializer()).map { it.value.copy(id = it.id) }
            // In progress first, then planned (soonest first), ideas, and what's done.
            .sortedWith(compareBy<Milestone> { STATUS_ORDER.indexOf(it.status) }.thenBy { it.date ?: "9999" })

    suspend fun saveMilestone(userId: String, m: Milestone, id: String? = null): Milestone {
        val title = m.title.trim().take(200).ifEmpty { throw ApiError.badRequest("A milestone needs a title") }
        val date = m.date?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: throw ApiError.badRequest("Days look like 2026-03-20") }
        val clean = m.copy(
            title = title, date = date?.toString(), notes = m.notes.text(4000), id = null,
            checklist = m.checklist.take(60).mapNotNull { c -> c.text.trim().take(200).takeIf { it.isNotEmpty() }?.let { ChecklistItem(it, c.done) } },
        )
        val day = date ?: profiles.days(userId).today(clock())
        return clean.copy(id = entries.put(userId, EntryKind.TRANSITION_MILESTONE, id, day, profiles.days(userId).start(day), clean, Milestone.serializer()))
    }

    // ---------------------------------------------------------------- Changes

    suspend fun changes(userId: String): List<BodyChange> =
        entries.list(userId, EntryKind.TRANSITION_CHANGE, EVER, FOREVER, BodyChange.serializer()).map { it.value.copy(id = it.id) }.sortedByDescending { it.date }

    suspend fun saveChange(userId: String, c: BodyChange, id: String? = null): BodyChange {
        val date = runCatching { LocalDate.parse(c.date) }.getOrNull() ?: throw ApiError.badRequest("Days look like 2026-03-20")
        if (date.isAfter(profiles.days(userId).today(clock()).plusDays(1))) throw ApiError.badRequest("That's in the future")
        val clean = BodyChange(
            date.toString(),
            c.area.trim().take(60).ifEmpty { throw ApiError.badRequest("What changed? (skin, voice, hair…)") },
            c.note.trim().take(4000),
            c.feeling?.coerceIn(1, 5),
        )
        return clean.copy(id = entries.put(userId, EntryKind.TRANSITION_CHANGE, id, date, profiles.days(userId).start(date), clean, BodyChange.serializer()))
    }

    // ---------------------------------------------------------------- Voice, hair removal, moments

    suspend fun voice(userId: String, days: Int): List<VoiceSession> = timed(userId, EntryKind.TRANSITION_VOICE, days, VoiceSession.serializer()) { v, id -> v.copy(id = id) }.sortedByDescending { it.at }

    suspend fun saveVoice(userId: String, v: VoiceSession): VoiceSession {
        notFuture(v.at)
        fun Double?.hz() = this?.takeIf { it.isFinite() }?.coerceIn(40.0, 600.0)
        val clean = v.copy(
            minutes = v.minutes.coerceIn(1, 600), exercises = v.exercises.clean(20, 80),
            pitchAvgHz = v.pitchAvgHz.hz(), pitchMinHz = v.pitchMinHz.hz(), pitchMaxHz = v.pitchMaxHz.hz(),
            feeling = v.feeling?.coerceIn(1, 5), note = v.note.text(2000), id = null,
        )
        return clean.copy(id = put(userId, EntryKind.TRANSITION_VOICE, clean.at, clean, VoiceSession.serializer()))
    }

    suspend fun hairRemoval(userId: String, days: Int): List<HairRemovalSession> =
        timed(userId, EntryKind.TRANSITION_HAIR, days, HairRemovalSession.serializer()) { h, id -> h.copy(id = id) }.sortedByDescending { it.at }

    suspend fun saveHairRemoval(userId: String, h: HairRemovalSession): HairRemovalSession {
        notFuture(h.at)
        val clean = h.copy(
            area = h.area.trim().take(60).ifEmpty { throw ApiError.badRequest("Which area?") },
            minutes = h.minutes?.coerceIn(0, 1440), pulses = h.pulses?.coerceIn(0, 1_000_000), provider = h.provider.text(120),
            pain = h.pain?.coerceIn(0, 10), cost = h.cost?.takeIf { it.isFinite() && it >= 0 }, note = h.note.text(2000), id = null,
        )
        return clean.copy(id = put(userId, EntryKind.TRANSITION_HAIR, clean.at, clean, HairRemovalSession.serializer()))
    }

    suspend fun moments(userId: String, days: Int): List<GenderMoment> =
        timed(userId, EntryKind.TRANSITION_MOMENT, days, GenderMoment.serializer()) { m, id -> m.copy(id = id) }.sortedByDescending { it.at }

    suspend fun saveMoment(userId: String, m: GenderMoment): GenderMoment {
        notFuture(m.at)
        val clean = m.copy(intensity = m.intensity.coerceIn(1, 5), context = m.context.clean(20, 80), note = m.note.text(2000), id = null)
        return clean.copy(id = put(userId, EntryKind.TRANSITION_MOMENT, clean.at, clean, GenderMoment.serializer()))
    }

    suspend fun delete(userId: String, kind: EntryKind, id: String) {
        require(kind.name.startsWith("TRANSITION_"))
        entries.delete(userId, kind, id)
    }

    // ---------------------------------------------------------------- Overview

    suspend fun overview(userId: String): TransitionOverview {
        val zone = profiles.days(userId)
        val today = zone.today(clock())
        val profile = profile(userId)
        val milestones = milestones(userId)
        val changes = changes(userId)
        val moments = moments(userId, 30)
        val hair = hairRemoval(userId, 3650)
        val hormonesSince = hormonesSince(userId)
        val euphoria = moments.count { it.kind == MomentKind.EUPHORIA }
        val dysphoria = moments.count { it.kind == MomentKind.DYSPHORIA }
        val highlights = mutableListOf<String>()
        if (euphoria > 0) highlights += "${plural(euphoria, "moment")} of euphoria this month."
        milestones.filter { it.status == MilestoneStatus.DONE && it.date != null && !LocalDate.parse(it.date).isBefore(today.minusDays(30)) }
            .forEach { highlights += "Done: ${it.title}." }
        milestones.filter { it.status != MilestoneStatus.DONE && it.checklist.isNotEmpty() }.take(3).forEach { m ->
            highlights += "${m.title}: ${m.checklist.count { it.done }} of ${m.checklist.size} steps done."
        }
        milestones.firstOrNull { it.status == MilestoneStatus.PLANNED && it.date != null && !LocalDate.parse(it.date).isBefore(today) }?.let {
            highlights += "Next: ${it.title}, ${Say.shortDate(LocalDate.parse(it.date))}."
        }
        val measurements = records.samples(userId, zone.start(today.minusDays(730)), zone.end(today), MEASUREMENTS)
            .groupBy { it.start }.toSortedMap().map { (at, list) -> MeasurementSet(at, list.associate { it.metric to it.value }) }
        return TransitionOverview(
            profile = profile,
            sinceStart = profile.startedOn?.let { duration(LocalDate.parse(it.padDay()), today) },
            onHormones = hormonesSince?.let { duration(it, today) },
            milestones = milestones,
            recentChanges = changes.take(10),
            voice = voice(userId, 180),
            hairByArea = hair.groupingBy { it.area.lowercase(Locale.ROOT) }.eachCount().entries.sortedByDescending { it.value }.map { Count(it.key, it.value) },
            euphoria = euphoria,
            dysphoria = dysphoria,
            highlights = highlights.take(6),
            measurements = measurements,
        )
    }

    /** When hormone therapy began: the earliest hormone medication's start. */
    suspend fun hormonesSince(userId: String): LocalDate? = runCatching {
        hormones.overview(userId).medications.mapNotNull { m -> m.since?.let { runCatching { LocalDate.parse(it.padDay()) }.getOrNull() } }.minOrNull()
    }.getOrNull()

    suspend fun today(userId: String, date: LocalDate): Pair<TransitionToday, List<Pair<Int, Insight>>> {
        val week = moments(userId, 7).filter { it.kind == MomentKind.EUPHORIA }
        val due = milestones(userId).filter { m ->
            m.status != MilestoneStatus.DONE && m.date != null && LocalDate.parse(m.date).let { !it.isBefore(date) && !it.isAfter(date.plusDays(7)) }
        }
        val profile = profile(userId)
        val headline = when {
            profile.startedOn != null -> "${duration(LocalDate.parse(profile.startedOn!!.padDay()), date)} since you began"
            else -> hormonesSince(userId)?.let { "${duration(it, date)} on hormones" }
        }
        val insights = mutableListOf<Pair<Int, Insight>>()
        if (week.size >= 3) {
            insights += 55 to Insight("good", "Moments of euphoria", "${week.size} moments of gender euphoria this week. Worth remembering.", "sparkles", Module.TRANSITION)
        }
        due.firstOrNull { LocalDate.parse(it.date).let { d -> !d.isAfter(date.plusDays(3)) } }?.let { m ->
            val d = LocalDate.parse(m.date)
            insights += 50 to Insight("info", m.title, if (d == date) "Planned for today." else "Coming up on ${Say.shortDate(d)}.", "calendar", Module.TRANSITION)
        }
        return TransitionToday(headline, due.map { it.title }, week.size) to insights
    }

    // ---------------------------------------------------------------- Hooks

    override suspend fun digest(userId: String, profile: Profile, from: LocalDate, to: LocalDate): DigestSection? {
        val zone = Days(Days.zoneOf(profile.timezone))
        val moments = between(userId, EntryKind.TRANSITION_MOMENT, from, to, GenderMoment.serializer())
        val voice = between(userId, EntryKind.TRANSITION_VOICE, from, to, VoiceSession.serializer())
        val done = milestones(userId).filter { it.status == MilestoneStatus.DONE && it.date != null && LocalDate.parse(it.date) in from..to }
        if (moments.isEmpty() && voice.isEmpty() && done.isEmpty()) return null
        val lines = listOfNotNull(
            moments.count { it.kind == MomentKind.EUPHORIA }.takeIf { it > 0 }?.let { "${plural(it, "moment")} of euphoria." },
            voice.takeIf { it.isNotEmpty() }?.let { v -> "Voice practice: ${v.sumOf { it.minutes }} minutes over ${plural(v.map { zone.dateOf(it.at) }.distinct().size, "day")}." },
            done.takeIf { it.isNotEmpty() }?.let { d -> "Done: ${d.joinToString(", ") { it.title }}." },
        )
        return DigestSection("Transition", Module.TRANSITION, "good", lines)
    }

    override suspend fun report(userId: String, profile: Profile, from: LocalDate, to: LocalDate): ReportSection? {
        val o = overview(userId)
        val voice = between(userId, EntryKind.TRANSITION_VOICE, from, to, VoiceSession.serializer()).sortedBy { it.at }
        val hair = between(userId, EntryKind.TRANSITION_HAIR, from, to, HairRemovalSession.serializer())
        val changes = o.recentChanges.filter { LocalDate.parse(it.date) in from..to }
        if (o.milestones.isEmpty() && voice.isEmpty() && hair.isEmpty() && changes.isEmpty() && o.profile == TransitionProfile()) return null
        val summary = listOfNotNull(
            o.profile.pronouns?.let { "Pronouns: $it." },
            o.sinceStart?.let { "Transitioning for $it." },
            o.onHormones?.let { "On hormone therapy for $it." },
            voice.takeIf { it.isNotEmpty() }?.let { v -> "Voice practice: ${v.sumOf { it.minutes }} minutes in ${plural(v.size, "session")}." },
            hair.takeIf { it.isNotEmpty() }?.let { h -> "Hair removal: ${plural(h.size, "session")} (${h.groupingBy { it.area }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" }})." },
        )
        val tables = listOfNotNull(
            o.milestones.takeIf { it.isNotEmpty() }?.let { m ->
                ReportTable("Milestones", listOf("Milestone", "Area", "Status", "Date"), m.map { listOf(it.title, it.area.label, it.status.label, it.date.orEmpty()) })
            },
            changes.takeIf { it.isNotEmpty() }?.let { c -> ReportTable("Changes noticed", listOf("Date", "Area", "Note"), c.map { listOf(it.date, it.area, it.note) }) },
        )
        val pitch = voice.filter { it.pitchAvgHz != null }
        val charts = listOfNotNull(
            pitch.takeIf { it.size >= 2 }?.let { v -> ReportChart("Voice pitch", "Hz", listOf("Average pitch" to v.map { Point(it.at, it.pitchAvgHz!!) })) },
        )
        return ReportSection(ReportSectionKey.TRANSITION, summary, tables, charts)
    }

    override suspend fun export(userId: String, profile: Profile, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        val zone = Days(Days.zoneOf(profile.timezone)).zone
        val files = linkedMapOf<String, List<List<String>>>()
        milestones(userId).takeIf { it.isNotEmpty() }?.let { m ->
            files["transition-milestones.csv"] = listOf(listOf("title", "area", "status", "date", "checklist_done", "checklist_total", "notes")) +
                m.map { listOf(it.title, it.area.name, it.status.name, it.date.orEmpty(), "${it.checklist.count { c -> c.done }}", "${it.checklist.size}", it.notes.orEmpty()) }
        }
        changes(userId).filter { LocalDate.parse(it.date) in from..to }.takeIf { it.isNotEmpty() }?.let { c ->
            files["transition-changes.csv"] = listOf(listOf("date", "area", "note", "feeling")) + c.map { listOf(it.date, it.area, it.note, it.feeling?.toString().orEmpty()) }
        }
        between(userId, EntryKind.TRANSITION_VOICE, from, to, VoiceSession.serializer()).takeIf { it.isNotEmpty() }?.let { v ->
            files["voice.csv"] = listOf(listOf("at", "minutes", "exercises", "pitch_avg_hz", "pitch_min_hz", "pitch_max_hz", "feeling", "note")) +
                v.map { listOf(Csv.iso(it.at, zone), "${it.minutes}", it.exercises.joinToString(";"), Csv.num(it.pitchAvgHz), Csv.num(it.pitchMinHz), Csv.num(it.pitchMaxHz), it.feeling?.toString().orEmpty(), it.note.orEmpty()) }
        }
        between(userId, EntryKind.TRANSITION_HAIR, from, to, HairRemovalSession.serializer()).takeIf { it.isNotEmpty() }?.let { h ->
            files["hair-removal.csv"] = listOf(listOf("at", "method", "area", "minutes", "pulses", "provider", "pain", "cost", "note")) +
                h.map { listOf(Csv.iso(it.at, zone), it.method.name, it.area, it.minutes?.toString().orEmpty(), it.pulses?.toString().orEmpty(), it.provider.orEmpty(), it.pain?.toString().orEmpty(), Csv.num(it.cost), it.note.orEmpty()) }
        }
        between(userId, EntryKind.TRANSITION_MOMENT, from, to, GenderMoment.serializer()).takeIf { it.isNotEmpty() }?.let { m ->
            files["gender-moments.csv"] = listOf(listOf("at", "kind", "intensity", "context", "note")) +
                m.map { listOf(Csv.iso(it.at, zone), it.kind.name, "${it.intensity}", it.context.joinToString(";"), it.note.orEmpty()) }
        }
        return files
    }

    override suspend fun questions(userId: String, profile: Profile, since: LocalDate, to: LocalDate): List<SuggestedQuestion> {
        val out = mutableListOf<SuggestedQuestion>()
        val hair = between(userId, EntryKind.TRANSITION_HAIR, since, to, HairRemovalSession.serializer())
        hair.mapNotNull { it.pain }.takeIf { it.size >= 2 }?.average()?.takeIf { it >= 7 }?.let {
            out += SuggestedQuestion("Hair removal has been quite painful (about ${it.toInt()} of 10). What could make it more comfortable?", "From your hair removal log", Module.TRANSITION)
        }
        val dysphoria = between(userId, EntryKind.TRANSITION_MOMENT, since, to, GenderMoment.serializer()).count { it.kind == MomentKind.DYSPHORIA && it.intensity >= 4 }
        if (dysphoria >= 4) out += SuggestedQuestion("I've had strong dysphoria $dysphoria times lately. What support is there?", "From your euphoria and dysphoria log", Module.TRANSITION)
        milestones(userId).firstOrNull { it.area == MilestoneArea.MEDICAL && it.status != MilestoneStatus.DONE }?.let {
            out += SuggestedQuestion("What are the next steps for: ${it.title}?", "A medical milestone you've planned", Module.TRANSITION)
        }
        return out
    }

    // ---------------------------------------------------------------- Helpers

    private suspend fun <T> timed(userId: String, kind: EntryKind, days: Int, serializer: KSerializer<T>, withId: (T, String) -> T): List<T> {
        val today = profiles.days(userId).today(clock())
        return entries.list(userId, kind, today.minusDays(days.coerceIn(1, 3650) - 1L), today.plusDays(1), serializer).map { withId(it.value, it.id) }
    }

    private suspend fun <T> between(userId: String, kind: EntryKind, from: LocalDate, to: LocalDate, serializer: KSerializer<T>): List<T> =
        entries.list(userId, kind, from, to, serializer).map { it.value }

    private suspend fun <T> put(userId: String, kind: EntryKind, at: Long, value: T, serializer: KSerializer<T>): String =
        entries.put(userId, kind, null, profiles.days(userId).dateOf(at), at, value, serializer)

    private fun notFuture(at: Long) {
        if (at > clock() + 6 * 3_600_000L) throw ApiError.badRequest("That's in the future")
    }

    companion object {
        private const val PROFILE = "transition.profile"
        private val STATUS_ORDER = listOf(MilestoneStatus.IN_PROGRESS, MilestoneStatus.PLANNED, MilestoneStatus.IDEA, MilestoneStatus.DONE)
        private val EVER = LocalDate.of(1900, 1, 1)
        private val FOREVER = LocalDate.of(2200, 1, 1)
        val MEASUREMENTS = listOf(Metric.WAIST, Metric.HIPS, Metric.CHEST, Metric.BUST, Metric.UNDERBUST, Metric.SHOULDERS, Metric.NECK, Metric.UPPER_ARM, Metric.THIGH, Metric.CALF)

        /** "2025-03" or "2025-03-20" to a full date, or null. */
        fun parseLoose(text: String): String? = runCatching { LocalDate.parse(text.padDay()).toString().let { if (text.length == 7) text else it } }.getOrNull()

        private fun String.padDay() = if (length == 7) "$this-01" else this

        /** "1 year, 3 months", "5 months", "3 weeks", "4 days". */
        fun duration(from: LocalDate, to: LocalDate): String {
            if (to.isBefore(from)) return "not yet"
            val p = Period.between(from, to)
            return when {
                p.years > 0 -> plural(p.years, "year") + if (p.months > 0) ", " + plural(p.months, "month") else ""
                p.months > 0 -> plural(p.months, "month")
                p.days >= 7 -> plural(p.days / 7, "week")
                else -> plural(p.days, "day")
            }
        }

        /** Places to start, written to fit anywhere and any direction: the person keeps, edits or ignores them. */
        val TEMPLATES = listOf(
            MilestoneTemplate("Tell someone close to me", MilestoneArea.SOCIAL, about = "A friend, family member or partner: whoever feels right first."),
            MilestoneTemplate("Tell my family", MilestoneArea.SOCIAL, listOf("Decide who to tell first", "Choose how (in person, a call, a letter)", "Plan support for afterwards")),
            MilestoneTemplate("Tell friends", MilestoneArea.SOCIAL),
            MilestoneTemplate("Come out at work or school", MilestoneArea.SOCIAL, listOf("Talk to someone I trust there", "Check the policies on name and pronouns", "Agree how colleagues or classmates will be told")),
            MilestoneTemplate("Use my name and pronouns everywhere", MilestoneArea.SOCIAL, listOf("Email and messaging", "Social media", "Accounts and loyalty cards")),
            MilestoneTemplate("Change my name legally", MilestoneArea.LEGAL, listOf("Find out how it's done where I live", "Fill in the forms", "Get the document or order", "Keep copies somewhere safe"),
                "The way it works differs from place to place."),
            MilestoneTemplate("Update my ID card or driving licence", MilestoneArea.LEGAL, listOf("Gather what's needed", "Apply", "New one arrives")),
            MilestoneTemplate("Update my passport", MilestoneArea.LEGAL, listOf("Check what's needed (name, gender marker)", "Photo", "Apply", "New passport arrives")),
            MilestoneTemplate("Update my birth certificate", MilestoneArea.LEGAL, listOf("Find out if and how it can be changed where I was born", "Apply", "New certificate arrives")),
            MilestoneTemplate("Update my bank and money accounts", MilestoneArea.LEGAL, listOf("Bank", "Cards", "Pension or savings", "Tax office")),
            MilestoneTemplate("Update my records at work or school", MilestoneArea.LEGAL, listOf("HR or registry", "Email address", "Payroll")),
            MilestoneTemplate("Update my doctors' and health records", MilestoneArea.LEGAL, listOf("Doctor's surgery or clinic", "Dentist", "Pharmacy", "Insurance")),
            MilestoneTemplate("Update utilities and other accounts", MilestoneArea.LEGAL, listOf("Phone", "Energy and water", "Internet", "Subscriptions")),
            MilestoneTemplate("First appointment about gender-affirming care", MilestoneArea.MEDICAL, listOf("Find a clinic or doctor", "Make the appointment", "Write down questions")),
            MilestoneTemplate("Start hormone therapy", MilestoneArea.MEDICAL, about = "Hormone therapy's own page keeps doses, changes and lab results."),
            MilestoneTemplate("A consultation about a procedure", MilestoneArea.MEDICAL, listOf("Research options", "Referral", "Consultation", "Decide what's right for me")),
            MilestoneTemplate("Voice lessons", MilestoneArea.VOICE, listOf("Find a teacher or course", "First lesson", "Practise between lessons")),
            MilestoneTemplate("A daily voice practice routine", MilestoneArea.VOICE, listOf("Warm-ups", "Pitch and resonance", "Reading aloud", "Using it in conversation")),
        )
    }
}
