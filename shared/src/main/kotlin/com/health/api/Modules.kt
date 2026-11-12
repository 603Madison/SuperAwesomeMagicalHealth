package com.health.api

import kotlinx.serialization.Serializable

/*
 * The parts of the app each person can turn on or off (Profile.modules). Activity, sleep, food
 * and body are on to start; the rest are opt-in. Sensitive ones are encrypted at rest, and an AI
 * connector link sees them only when the person says so when making it.
 */

@Serializable
enum class Module(val label: String, val sensitive: Boolean) {
    ACTIVITY("Activity", false),
    SLEEP("Sleep", false),
    NUTRITION("Food", false),
    BODY("Body", false),
    /** Mood, journal, check-ins like PHQ-9 and GAD-7, calming tools and support. */
    MIND("Mind", true),
    /** Periods, symptoms, predictions. */
    CYCLE("Cycle", true),
    /** A bladder and bowel diary: trips, leaks, urgency, pelvic floor exercises. */
    CONTINENCE("Bladder & bowel", true),
    /** Medications and supplements, doses, reminders, injection-site rotation, lab results. */
    MEDICATIONS("Medications", true),
    /** Blood pressure and glucose, from monitors or entered by hand. */
    VITALS("Blood pressure & glucose", false),
    /** Habits with forgiving streaks, ticked by hand or by what's recorded. */
    HABITS("Habits", false),
    /** Symptoms, pain and migraines, and what goes with them. */
    SYMPTOMS("Symptoms & pain", true),
    /** Hormone therapy: dose history, levels against the person's own targets, when labs are due. */
    HORMONES("Hormone therapy", true),
    /** Appointments, with questions to ask and notes afterwards. */
    APPOINTMENTS("Appointments", true),
    ;

    companion object {
        val DEFAULT: Set<Module> = setOf(ACTIVITY, SLEEP, NUTRITION, BODY)
    }
}

// ---------------------------------------------------------------- Mind

/** How someone feels at a moment: mood 1 (very low) to 5 (great); energy and anxiety 1–5 where given. */
@Serializable
data class MoodEntry(
    val at: Long,
    val mood: Int,
    val energy: Int? = null,
    val anxiety: Int? = null,
    /** Feelings, from the suggestions or the person's own: "calm", "anxious", "proud"… */
    val feelings: List<String> = emptyList(),
    /** What it's about: "work", "family", "sleep", "health"… */
    val context: List<String> = emptyList(),
    val note: String? = null,
    val id: String? = null,
    /** Set by the server: the local day. */
    val date: String? = null,
)

@Serializable
enum class JournalKind(val label: String) { FREE("Journal"), GRATITUDE("Gratitude"), THOUGHT_RECORD("Thought record") }

/** A CBT thought record: a situation, the thought it brought, and a more balanced one. */
@Serializable
data class ThoughtRecord(
    val situation: String = "",
    val thought: String = "",
    val feelings: String? = null,
    /** How strongly the thought was believed, 0–100, before and after. */
    val beliefBefore: Int? = null,
    val evidenceFor: String? = null,
    val evidenceAgainst: String? = null,
    val balancedThought: String? = null,
    val beliefAfter: Int? = null,
)

/** A journal entry; private, and encrypted on the server. */
@Serializable
data class JournalEntry(
    val at: Long,
    val kind: JournalKind = JournalKind.FREE,
    val title: String? = null,
    val text: String = "",
    /** The prompt it answered, if any. */
    val prompt: String? = null,
    /** For gratitude entries: up to three things. */
    val gratitude: List<String> = emptyList(),
    val thought: ThoughtRecord? = null,
    val id: String? = null,
    val date: String? = null,
)

/** PHQ-9 (depression), GAD-7 (anxiety), and the EPDS (Edinburgh, during pregnancy and after a birth). */
@Serializable
enum class Screener { PHQ9, GAD7, EPDS }

/** A questionnaire's text and scoring, as the server serves it so every app asks the same thing. */
@Serializable
data class ScreenerDefinition(
    val id: Screener,
    val title: String,
    val about: String,
    val question: String,
    val items: List<String>,
    val options: List<String>,
    /** Score bands: (lowest score, label). */
    val bands: List<ScoreBand>,
    val source: String,
    /** Where each question has its own answers (the EPDS): those, in the order shown. */
    val itemOptions: List<List<String>>? = null,
    /** And what each of those answers scores (some questions count in reverse). */
    val itemScores: List<List<Int>>? = null,
    /** The question about self-harm: any answer scoring above 0 shows support straight away. */
    val flagItem: Int? = null,
)

@Serializable
data class ScoreBand(val from: Int, val label: String)

/** Answers (0–3 each) to a screener; the server works out [score] and [severity]. */
@Serializable
data class ScreenerResult(
    val screener: Screener,
    val at: Long,
    val answers: List<Int>,
    val score: Int = 0,
    val severity: String = "",
    /** The self-harm question ([ScreenerDefinition.flagItem]) answered above "never": support is shown straight away. */
    val flagged: Boolean = false,
    val id: String? = null,
    val date: String? = null,
)

/** A breathing exercise, grounding or meditation, done in the app or elsewhere. */
@Serializable
data class MindfulnessSession(
    val at: Long,
    val minutes: Double,
    /** "box-breathing", "breathing-478", "coherent-breathing", "grounding", "meditation", "body-scan", or the person's own. */
    val kind: String,
    val moodBefore: Int? = null,
    val moodAfter: Int? = null,
    val note: String? = null,
    val id: String? = null,
    val date: String? = null,
)

/** Somewhere to turn: set by the server's admin for where its people live. */
@Serializable
data class CrisisResource(
    val name: String,
    /** How to reach it: "Call or text 988". */
    val contact: String,
    val region: String? = null,
    val url: String? = null,
    val note: String? = null,
)

@Serializable
data class MindDay(
    val date: String,
    val mood: Double? = null,
    val energy: Double? = null,
    val anxiety: Double? = null,
    val entries: Int = 0,
    val mindfulMinutes: Double = 0.0,
    val journal: Int = 0,
)

/** Weeks of mood at a glance, and what seems to go with better days. */
@Serializable
data class MindSummary(
    val days: List<MindDay>,
    val averageMood: Double? = null,
    /** The feelings named most, with how often. */
    val feelings: List<Count> = emptyList(),
    val context: List<Count> = emptyList(),
    /** "On days you slept 7 hours or more, your mood averaged 4.1, against 3.3." */
    val patterns: List<Insight> = emptyList(),
    val latest: List<ScreenerResult> = emptyList(),
    val mindfulMinutes: Double = 0.0,
)

@Serializable
data class Count(val name: String, val count: Int)

// ---------------------------------------------------------------- Cycle

@Serializable
enum class Flow(val label: String) { SPOTTING("Spotting"), LIGHT("Light"), MEDIUM("Medium"), HEAVY("Heavy") }

@Serializable
enum class OvulationTest(val label: String) { NEGATIVE("Negative"), POSITIVE("Positive"), PEAK("Peak") }

@Serializable
enum class Mucus(val label: String) { DRY("Dry"), STICKY("Sticky"), CREAMY("Creamy"), WATERY("Watery"), EGG_WHITE("Egg white") }

/** One day of a cycle: bleeding, symptoms and signs. Any of it can be left out. */
@Serializable
data class CycleDay(
    val date: String,
    val flow: Flow? = null,
    /** "cramps", "headache", "bloating", "breast tenderness", "hot flashes"… */
    val symptoms: List<String> = emptyList(),
    /** Pain, 0–10. */
    val pain: Int? = null,
    val mucus: Mucus? = null,
    val ovulationTest: OvulationTest? = null,
    /** Basal body temperature taken on waking, °C. */
    val temperature: Double? = null,
    val note: String? = null,
)

@Serializable
enum class CyclePhase(val label: String) {
    PERIOD("Period"), FOLLICULAR("Follicular"), FERTILE("Fertile window"), LUTEAL("Luteal"), UNKNOWN("Unknown"),
}

/** A cycle: from the first day of one period to the day before the next. */
@Serializable
data class Cycle(
    val start: String,
    /** Days, until the next period began; null for the current one. */
    val length: Int? = null,
    val periodLength: Int,
    /** When a sustained temperature rise suggests ovulation happened, where the data shows it. */
    val ovulation: String? = null,
)

@Serializable
data class CyclePrediction(
    val nextPeriod: String,
    /** The likely range: earliest and latest. */
    val earliest: String,
    val latest: String,
    val fertileStart: String? = null,
    val fertileEnd: String? = null,
    val ovulation: String? = null,
    /** "low" (fewer than three cycles), "medium" or "high" (regular cycles). */
    val confidence: String,
)

@Serializable
data class CycleOverview(
    val days: List<CycleDay>,
    val cycles: List<Cycle>,
    val averageCycle: Double? = null,
    val averagePeriod: Double? = null,
    /** How much cycle lengths vary, in days (standard deviation). */
    val variation: Double? = null,
    val prediction: CyclePrediction? = null,
    /** Today: which day of the cycle, which phase, and how long until the next period. */
    val cycleDay: Int? = null,
    val phase: CyclePhase = CyclePhase.UNKNOWN,
    val daysUntilPeriod: Int? = null,
    /** Nightly temperature (from a ring or watch, or BBT) by date, for the chart. */
    val temperatures: List<DatedValue> = emptyList(),
    val caveat: String = "Predictions are estimates from your own history. They can't be used as contraception.",
)

@Serializable
data class DatedValue(val date: String, val value: Double)

// ---------------------------------------------------------------- Bladder & bowel

@Serializable
enum class ContinenceKind(val label: String) {
    URINATION("Toilet trip"), LEAK("Leak"), BOWEL("Bowel movement"), PAD_CHANGE("Pad change"), PELVIC_FLOOR("Pelvic floor exercise"),
}

@Serializable
enum class LeakSize(val label: String) { DROPS("A few drops"), SMALL("Small"), MEDIUM("Medium"), LARGE("Large") }

/** One entry in the bladder and bowel diary. */
@Serializable
data class ContinenceEntry(
    val at: Long,
    val kind: ContinenceKind,
    /** Measured volume, for a toilet trip, in ml. */
    val volumeMl: Int? = null,
    /** 0 none, 1 mild, 2 strong, 3 couldn't wait. */
    val urgency: Int? = null,
    val leak: LeakSize? = null,
    /** "cough", "sneeze", "laugh", "lifting", "exercise", "urge", "on the way", "asleep"… */
    val triggers: List<String> = emptyList(),
    /** The Bristol stool scale, 1–7. */
    val bristol: Int? = null,
    val pain: Boolean = false,
    val blood: Boolean = false,
    /** Pelvic floor sets: repetitions, and seconds each squeeze is held. */
    val reps: Int? = null,
    val holdSeconds: Int? = null,
    val note: String? = null,
    val id: String? = null,
    val date: String? = null,
    /** Set by the server: whether it was during the night's sleep. */
    val night: Boolean = false,
)

@Serializable
data class ContinenceDay(
    val date: String,
    val voids: Int = 0,
    val nightVoids: Int = 0,
    val leaks: Int = 0,
    val bowel: Int = 0,
    val pads: Int = 0,
    val pelvicFloor: Int = 0,
    val voidedMl: Int? = null,
    /** Water logged that day. */
    val fluidMl: Int = 0,
    val urgentTrips: Int = 0,
)

@Serializable
data class ContinenceSummary(
    val days: List<ContinenceDay>,
    val averageVoids: Double? = null,
    val averageNightVoids: Double? = null,
    val averageLeaks: Double? = null,
    /** What leaks came with, most often first. */
    val triggers: List<Count> = emptyList(),
    /** The longest gap between toilet trips while awake, in minutes, on average. */
    val averageInterval: Int? = null,
    val patterns: List<Insight> = emptyList(),
)

// ---------------------------------------------------------------- Medications

@Serializable
enum class MedForm(val label: String) {
    PILL("Pill"), CAPSULE("Capsule"), LIQUID("Liquid"), INJECTION("Injection"), PATCH("Patch"), GEL("Gel"), SPRAY("Spray"),
    INHALER("Inhaler"), CREAM("Cream"), DROPS("Drops"), IMPLANT("Implant"), SUPPLEMENT("Supplement"), OTHER("Other"),
}

@Serializable
enum class ScheduleKind { DAILY, WEEKLY, EVERY_N_DAYS, AS_NEEDED }

/** When a medication is taken: at [times] ("08:00") on the days the kind says. */
@Serializable
data class MedSchedule(
    val kind: ScheduleKind = ScheduleKind.DAILY,
    val times: List<String> = listOf("09:00"),
    /** For EVERY_N_DAYS: every this many days, counted from [startDate]. */
    val everyDays: Int = 1,
    /** For WEEKLY: 1 Monday … 7 Sunday. */
    val weekdays: List<Int> = emptyList(),
    val startDate: String? = null,
)

@Serializable
data class Medication(
    val name: String,
    /** As written on the label: "2 mg", "0.1 mg/day", "5 mg/ml, 0.25 ml". */
    val dose: String? = null,
    val form: MedForm = MedForm.PILL,
    val schedule: MedSchedule = MedSchedule(),
    val purpose: String? = null,
    val notes: String? = null,
    /** Where injections or patches go, to rotate through: "left thigh", "right abdomen"… */
    val sites: List<String> = emptyList(),
    val reminders: Boolean = true,
    val active: Boolean = true,
    /** Doses left, if counted; the app warns when it's low. */
    val stock: Int? = null,
    val id: String? = null,
    /** Part of hormone therapy (shown there, with its dose history), when that feature is on. */
    val hormone: Boolean = false,
    /** When it was first started, "YYYY-MM-DD". */
    val startedOn: String? = null,
)

@Serializable
enum class DoseStatus { TAKEN, SKIPPED }

@Serializable
data class DoseLog(
    val medicationId: String,
    val at: Long,
    val status: DoseStatus = DoseStatus.TAKEN,
    /** The scheduled time this dose was for, if it was one. */
    val scheduledFor: Long? = null,
    val site: String? = null,
    val note: String? = null,
    val id: String? = null,
    val date: String? = null,
)

/** A scheduled dose on a day, and what happened to it. */
@Serializable
data class DueDose(
    val medicationId: String,
    val name: String,
    val dose: String? = null,
    val form: MedForm,
    val scheduledFor: Long,
    val log: DoseLog? = null,
    /** For injections and patches: the site used longest ago, to go next. */
    val nextSite: String? = null,
)

@Serializable
data class MedsDay(
    val date: String,
    val due: List<DueDose>,
    /** As-needed medications, and doses of them taken that day. */
    val asNeeded: List<Medication> = emptyList(),
    val extra: List<DoseLog> = emptyList(),
    /** Doses taken of those scheduled, over the last 7 days, as a percentage. */
    val adherence7d: Int? = null,
    /** Medications with few doses left. */
    val lowStock: List<Medication> = emptyList(),
)

/** A lab result (estradiol, ferritin, TSH, HbA1c…), with its reference range where known. */
@Serializable
data class LabResult(
    val name: String,
    val value: Double,
    val unit: String,
    val at: Long,
    val low: Double? = null,
    val high: Double? = null,
    val note: String? = null,
    val id: String? = null,
    val date: String? = null,
)

// ---------------------------------------------------------------- Today

/** The day's view of each module that's turned on (null when it's off or there's nothing). */
@Serializable
data class ModulesToday(
    val mind: MindDay? = null,
    val cycle: CycleToday? = null,
    val continence: ContinenceDay? = null,
    val meds: MedsDay? = null,
    val vitals: VitalsToday? = null,
    val habits: List<HabitStatus>? = null,
    val symptoms: SymptomsDay? = null,
    val hormones: HormonesToday? = null,
    /** Pregnancy, after a birth, perimenopause and after (with the cycle). */
    val lifeStage: LifeStageToday? = null,
    /** In the next seven days. */
    val appointments: List<Appointment>? = null,
)

@Serializable
data class CycleToday(
    val cycleDay: Int? = null,
    val phase: CyclePhase = CyclePhase.UNKNOWN,
    val daysUntilPeriod: Int? = null,
    val logged: CycleDay? = null,
)
