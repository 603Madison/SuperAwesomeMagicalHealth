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

/** One day's energy, budget style: spoons (or any unit) to start, and what used or gave them. */
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
    val energyLeft: Int? = null,
    val socialBattery: Int? = null,
    val routinesDue: List<String> = emptyList(),
    val focusMinutes: Int = 0,
)
