package com.health.api

import kotlinx.serialization.Serializable

/*
 * Autism and ADHD (Module.NEURO, shown in Mind), for anyone autistic or ADHD, diagnosed or not.
 * Neurodiversity-affirming throughout: experiences, not symptoms; meltdowns and shutdowns are
 * overwhelm, not behaviour; stims and special interests are things that help. The person picks how
 * they're described ([NeuroProfile.wording]).
 */

@Serializable
enum class Wording(val label: String) { IDENTITY_FIRST("Autistic person / ADHDer"), PERSON_FIRST("Person with autism / ADHD") }

@Serializable
enum class Sense(val label: String) {
    SOUND("Sound"), LIGHT("Light"), TOUCH("Touch and textures"), SMELL("Smell"), TASTE("Taste"), TEMPERATURE("Temperature"),
    MOVEMENT("Movement and balance"), BODY_SIGNALS("Body signals (hunger, thirst, needing the toilet)"), CROWDS("Crowds and busy places"),
}

/** How a sense is for this person: avoiding −2 … seeking +2, with what helps. */
@Serializable
data class SensePreference(val sense: Sense, val response: Int = 0, val notes: String? = null, val helps: List<String> = emptyList())

/** "About me": for the person, and to share with people who support them. */
@Serializable
data class NeuroProfile(
    /** "Autistic", "ADHD", "AuDHD", "Dyspraxic", the person's own words. */
    val identities: List<String> = emptyList(),
    val wording: Wording = Wording.IDENTITY_FIRST,
    val senses: List<SensePreference> = emptyList(),
    /** How they like to communicate: "text over calls", "direct language", "time to process"… */
    val communication: List<String> = emptyList(),
    /** Things that help: "noise-cancelling headphones", "written instructions", "movement breaks"… */
    val whatHelps: List<String> = emptyList(),
    /** Accommodations at work, school, appointments. */
    val accommodations: List<String> = emptyList(),
    /** Early signs of overwhelm, so others can notice too. */
    val warningSigns: List<String> = emptyList(),
    /** Special interests, and stims that help. */
    val interests: List<String> = emptyList(),
    val stims: List<String> = emptyList(),
    /** Free text: "If I'm overwhelmed, please…". */
    val aboutMe: String? = null,
)

@Serializable
enum class OverloadKind(val label: String) {
    OVERWHELM("Overwhelm"), SENSORY_OVERLOAD("Sensory overload"), MELTDOWN("Meltdown"), SHUTDOWN("Shutdown"), PANIC("Panic"),
}

/** A time things got too much. */
@Serializable
data class OverloadEvent(
    val start: Long,
    val end: Long? = null,
    val kind: OverloadKind,
    /** 1 mild … 5 intense. */
    val intensity: Int = 3,
    val senses: List<Sense> = emptyList(),
    /** "change of plans", "too many demands", "social", "hunger", "tired"… */
    val triggers: List<String> = emptyList(),
    val warningSigns: List<String> = emptyList(),
    val whereAt: String? = null,
    val whatHelped: List<String> = emptyList(),
    /** Minutes (or hours) to feel settled again. */
    val recoveryMinutes: Int? = null,
    val note: String? = null,
    val id: String? = null,
    val date: String? = null,
)

/** One day's energy, budget style: points of energy to start with, and what used some or gave some back. */
@Serializable
data class EnergyDay(
    val date: String,
    val startWith: Int? = null,
    val spends: List<EnergySpend> = emptyList(),
    /** Social battery, 0 empty … 10 full, at day's end. */
    val socialBattery: Int? = null,
    val note: String? = null,
)

@Serializable
data class EnergySpend(val what: String, val amount: Int, val at: Long? = null)

/** A check-in, as often as wanted: each 1–5 where given. */
@Serializable
data class NeuroCheckIn(
    val at: Long,
    /** How much masking (camouflaging) today. */
    val masking: Int? = null,
    val socialLoad: Int? = null,
    val sensoryLoad: Int? = null,
    /** Executive function: getting started, focus, organising, remembering, switching tasks. */
    val starting: Int? = null,
    val focus: Int? = null,
    val organising: Int? = null,
    val remembering: Int? = null,
    val switching: Int? = null,
    val restlessness: Int? = null,
    val emotions: Int? = null,
    /** Burnout: how depleted, 1 fine … 5 in burnout. */
    val burnout: Int? = null,
    /** How medication (for ADHD or otherwise) worked today, 1 not at all … 5 very well, and when it wore off. */
    val medEffect: Int? = null,
    val woreOffAt: String? = null,
    val note: String? = null,
    val id: String? = null,
    val date: String? = null,
)

@Serializable
enum class FocusKind(val label: String) { FOCUS("Focus timer"), BODY_DOUBLING("Body doubling"), INTEREST("Special interest time"), HYPERFOCUS("Hyperfocus") }

/** A stretch of focus: with what was guessed beforehand, to learn how long things really take. */
@Serializable
data class FocusSession(
    val start: Long,
    val end: Long? = null,
    val kind: FocusKind = FocusKind.FOCUS,
    val task: String? = null,
    val estimateMinutes: Int? = null,
    val breaks: Int = 0,
    /** 1–5: how it went. */
    val rating: Int? = null,
    val note: String? = null,
    val id: String? = null,
)

/** A routine as a short checklist, with how long each step usually takes. */
@Serializable
data class Routine(
    val name: String,
    val steps: List<RoutineStep>,
    /** "HH:mm": a gentle reminder to start. */
    val reminder: String? = null,
    val days: List<Int> = emptyList(),
    val icon: String = "list-checks",
    val active: Boolean = true,
    val id: String? = null,
)

@Serializable
data class RoutineStep(val text: String, val minutes: Int? = null)

/** A routine done (or partly done) on a day. */
@Serializable
data class RoutineRun(val routineId: String, val date: String, val done: List<Int> = emptyList(), val at: Long? = null, val id: String? = null)

/** A card to show someone when talking is hard: "I'm autistic and need a quiet minute." */
@Serializable
data class CommunicationCard(val text: String, val detail: String? = null, val color: String? = null, val id: String? = null)

@Serializable
data class NeuroSummary(
    val days: Int,
    val overloads: Int = 0,
    val byKind: List<Count> = emptyList(),
    val triggers: List<Count> = emptyList(),
    val whatHelped: List<Count> = emptyList(),
    val averageMasking: Double? = null,
    val averageEnergyUsed: Double? = null,
    val focusMinutes: Int = 0,
    /** How long things took against the guess (1.5 = half as long again). */
    val estimateRatio: Double? = null,
    /** What goes with harder days in the person's data: short nights, masking, social load, changes, the cycle… */
    val patterns: List<Insight> = emptyList(),
    /** Signs of burnout building: sustained masking, low energy, more overloads. */
    val burnoutRisk: String? = null,
)

@Serializable
data class NeuroToday(
    val overloadsToday: Int = 0,
    /** No longer worked out (the energy budget is gone); kept so older apps still read the day. */
    val energyLeft: Int? = null,
    val socialBattery: Int? = null,
    val routinesDue: List<String> = emptyList(),
    val focusMinutes: Int = 0,
    /** Today's latest energy and focus check-in. */
    val lastCheck: EnergyCheck? = null,
    /** When focus is usually best, once there's enough to say: "9 AM – noon". */
    val bestFocus: String? = null,
)

/*
 * Energy and focus (GET /api/neuro/energy-focus): quick check-ins through the day, the way research
 * measures how ADHD symptoms really move (ecological momentary assessment), and the person's own
 * patterns from them, against their sleep, doses, meals and movement. Patterns are associations in
 * their own data, never claims of cause, and only shown with enough of it.
 */

/** What else was going on at a check-in. */
@Serializable
enum class CheckTag(val label: String) {
    STRESSED("Stressed"),
    UNWELL("Unwell"),
    OVERSTIMULATED("Overstimulated"),
    HYPERFOCUS("In a hyperfocus"),
}

/** A check-in, as often as wanted: energy 1 (running on empty) … 5 (plenty), focus 1 (all over the place) … 5 (locked in). */
@Serializable
data class EnergyCheck(
    val at: Long,
    val energy: Int? = null,
    val focus: Int? = null,
    val tags: Set<CheckTag> = emptySet(),
    val note: String? = null,
    val id: String? = null,
)

@Serializable
data class EnergyFocusSettings(
    /** The ADHD medication, for focus since a dose; null: worked out from the medications' names. */
    val medicationId: String? = null,
    /** Gentle reminders to check in, at these local times ("10:00"); none by default. */
    val reminders: List<String> = emptyList(),
)

/** A stretch of hours (of the day, or since a dose) with its average energy and focus, from [count] check-ins. */
@Serializable
data class TimeBlock(val from: Int, val to: Int, val energy: Double? = null, val focus: Double? = null, val count: Int = 0)

@Serializable
enum class FactorKind { SLEEP, MEDICATION, MOVEMENT, MEAL, STRESS }

/** One thing that goes with better or worse energy or focus in the person's own check-ins. */
@Serializable
data class EnergyFactor(
    val kind: FactorKind,
    val title: String,
    /** "After 7 hours' sleep or more, your energy averaged 3.6, against 2.4 after less than 6 (9 and 5 days)." */
    val sentence: String,
    /** "energy" or "focus": which one moved. */
    val measure: String,
    val with: Double,
    val without: Double,
    val withCount: Int,
    val withoutCount: Int,
)

@Serializable
data class EnergyFocus(
    val settings: EnergyFocusSettings = EnergyFocusSettings(),
    /** The ADHD medication's name, as found or chosen. */
    val medication: String? = null,
    val today: List<EnergyCheck> = emptyList(),
    /** When today's doses of the ADHD medication were taken. */
    val dosesToday: List<Long> = emptyList(),
    val checks: Int = 0,
    val days: Int = 0,
    /** Enough check-ins to show patterns. */
    val enough: Boolean = false,
    /** "6 more check-ins over 2 more days and your patterns start to show." */
    val needed: String? = null,
    /** Three-hour blocks of the day. */
    val byHour: List<TimeBlock> = emptyList(),
    /** Hours since the day's first dose of the ADHD medication. */
    val sinceDose: List<TimeBlock> = emptyList(),
    val bestFocus: TimeBlock? = null,
    val factors: List<EnergyFactor> = emptyList(),
    /** The patterns in plain sentences, most useful first. */
    val summary: List<String> = emptyList(),
    /** How it works and what it can't tell, with the research behind it. */
    val about: String = "",
    val sources: List<String> = emptyList(),
)
