package com.health.api

import kotlinx.serialization.Serializable

/*
 * The parts of the app each person can turn on or off (Profile.modules). Activity, sleep, food
 * and body are on to start; the rest are opt-in. Sensitive ones are encrypted at rest, and an AI
 * connector link sees them only when the person says so when making it.
 */

/**
 * A set of features that skips names this version doesn't know (a newer server's, or an older
 * app's), instead of failing the whole answer. Every `Set<Module>` in the API uses it.
 */
object ModuleSetSerializer : kotlinx.serialization.KSerializer<Set<Module>> {
    private val names = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
    override val descriptor = names.descriptor
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Set<Module>) = names.serialize(encoder, value.map { it.name })
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Set<Module> =
        names.deserialize(decoder).mapNotNullTo(LinkedHashSet()) { n -> Module.entries.firstOrNull { it.name == n } }
}

/** The same, for a list of features. */
object ModuleListSerializer : kotlinx.serialization.KSerializer<List<Module>> {
    private val names = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
    override val descriptor = names.descriptor
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: List<Module>) = names.serialize(encoder, value.map { it.name })
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): List<Module> =
        names.deserialize(decoder).mapNotNull { n -> Module.entries.firstOrNull { it.name == n } }.distinct()
}

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
    /** Autism and ADHD (shown in Mind): sensory life, overload and burnout, masking, focus, routines, communication cards. */
    NEURO("Autism & ADHD", true),
    /** Transition: milestones, changes, voice, hair removal, euphoria and dysphoria (with hormone therapy). */
    TRANSITION("Transition", true),
    /** Progress photos: encrypted, never shown to AI, shared only when chosen. */
    PHOTOS("Progress photos", true),
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
    // ---- In as much detail as the person wants (each shows when its field is on: see Fields).
    /** Products used: each comes off the supply's count when it has one. */
    val products: List<ProductUse> = emptyList(),
    val clots: Clots? = null,
    /** "bright red", "dark red", "brown", "pink"… */
    val color: String? = null,
    /** "lower belly", "lower back", "thighs", "pelvis", "rectum", "chest"… */
    val painAreas: List<String> = emptyList(),
    /** Symptom to how bad, 1 mild … 3 severe. */
    val symptomSeverity: Map<String, Int> = emptyMap(),
    val mood: List<String> = emptyList(),
    /** 1–5. */
    val energy: Int? = null,
    val libido: Int? = null,
    val sex: SexEntry? = null,
    val cervix: Cervix? = null,
    /** An LH test's number, where the reader gives one. */
    val lh: Double? = null,
    val pregnancyTest: PregnancyTest? = null,
    /** "none", "creamy", "watery", "unusual colour", "unusual smell"… */
    val discharge: String? = null,
    val tags: List<String> = emptyList(),
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class ProductUse(val kind: SupplyKind, val count: Int = 1, val fullness: Fullness? = null, val supplyId: String? = null)

@Serializable
enum class Clots(val label: String) { NONE("None"), SMALL("Small"), LARGE("Larger than a coin") }

@Serializable
data class SexEntry(val had: Boolean = true, val protected: Boolean? = null, val note: String? = null)

@Serializable
data class Cervix(val position: String? = null, val firmness: String? = null, val opening: String? = null)

@Serializable
enum class PregnancyTest(val label: String) { NEGATIVE("Negative"), FAINT("Faint line"), POSITIVE("Positive") }

/** How cycle tracking works for this person (GET/PUT /api/cycle/settings). */
@Serializable
data class CycleSettings(
    /** The person's own symptoms, offered first. */
    val symptoms: List<String> = emptyList(),
    /** Symptoms to leave out of the picker. */
    val hiddenSymptoms: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    val painAreas: List<String> = emptyList(),
    val customFields: List<CustomField> = emptyList(),
    /** Use these instead of the averages. */
    val cycleLength: Int? = null,
    val periodLength: Int? = null,
    /** Days from ovulation to the next period (14 to start). */
    val lutealLength: Int? = null,
    /** Reminders: before a period (days), when late (days), and a daily log nudge ("HH:mm"). */
    val remindBeforePeriod: Int? = 2,
    val remindLateAfter: Int? = 3,
    val dailyReminder: String? = null,
    /** Plain wording without "fertile" or "ovulation", for people not trying to conceive (see Fields.CYCLE_FERTILITY too). */
    val neutralWording: Boolean = false,
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
    URINATION("Toilet trip"), LEAK("Leak"), BOWEL("Bowel movement"), PAD_CHANGE("Change"), PELVIC_FLOOR("Pelvic floor exercise"),
    /** A bowel accident (leak of stool or wind). */
    BOWEL_LEAK("Bowel accident"),
    /** Emptying with a catheter. */
    CATHETER("Catheter"),
    /** A night the bed got wet. */
    BEDWETTING("Wet night"),
}

@Serializable
enum class LeakSize(val label: String) {
    DROPS("A few drops"), SMALL("Small"), MEDIUM("Medium"), LARGE("Large"),
    /** The whole bladder or bowel. */
    FULL("All of it"),
}

/** How full a pad, brief or other product was. */
@Serializable
enum class Fullness(val label: String, val level: Int) {
    DRY("Dry", 0), DAMP("Damp", 1), WET("Wet", 2), FULL("Full", 3), SOAKED("Soaked", 4), LEAKED("Leaked through", 5),
}

@Serializable
enum class Awareness(val label: String) { FELT_IT("Felt it coming"), FELT_DURING("Felt it happening"), NOTICED_AFTER("Noticed afterwards"), DIDNT_NOTICE("Didn't notice") }

@Serializable
enum class ReachedToilet(val label: String) { YES("Made it"), PARTLY("Partly"), NO("Didn't make it"), NOT_TRYING("Wasn't trying") }

/** What leaked, for accidents. */
@Serializable
enum class LeakContents(val label: String) { URINE("Urine"), STOOL("Stool"), WIND("Wind"), BOTH("Urine and stool") }

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
    // ---- In as much detail as the person wants (each shows when its field is on: see Fields).
    val contents: LeakContents? = null,
    /** "standing up", "walking", "coughing", "asleep", "on the way to the toilet"… */
    val activity: List<String> = emptyList(),
    val awareness: Awareness? = null,
    val reachedToilet: ReachedToilet? = null,
    /** Seconds between feeling the urge and the leak. */
    val warningSeconds: Int? = null,
    /** "home", "work", "out", "in bed", "travelling"… */
    val location: String? = null,
    /**
     * The product (a Supply id) and how full it was. For a PAD_CHANGE it's what was **taken off**
     * ([putOnSupplyId] is what went on); for a leak, what was being worn.
     */
    val supplyId: String? = null,
    val fullness: Fullness? = null,
    /** Weighed wet, in grams: with the product's dry weight, the amount lost. */
    val padWeightG: Double? = null,
    /** Set by the server from the product's dry weight. */
    val lossG: Double? = null,
    val changedClothes: Boolean = false,
    val changedBedding: Boolean = false,
    val leakedThrough: Boolean = false,
    /** Skin comfort 0 fine … 3 sore. */
    val skin: Int? = null,
    /** "burning", "cloudy", "strong smell", "fever", "more often"… */
    val utiSigns: List<String> = emptyList(),
    /**
     * For PAD_CHANGE: how many products (default 1); each comes off the supply's count (the one put
     * on, when [putOnSupplyId] is given; otherwise, for changes from before it was, [supplyId]).
     */
    val count: Int? = null,
    /** The person's own tags and fields (ContinenceSettings.customFields), key to value. */
    val tags: List<String> = emptyList(),
    val details: Map<String, String> = emptyMap(),
    /**
     * For PAD_CHANGE: what was **put on** (a Supply id). One (or [count]) comes off its stock; the
     * one taken off ([supplyId]) isn't counted again, since it was counted when it went on. A change
     * with only this is "put on" (nothing taken off).
     */
    val putOnSupplyId: String? = null,
    /** For PAD_CHANGE: taken off, with nothing on for now. */
    val nothingOn: Boolean = false,
    /** Set by the server: how long the product taken off was worn, from the change that put it on. */
    val wornMinutes: Int? = null,
)

/**
 * What's being worn now (GET /api/continence/wearing): from the latest change that put something
 * on, or that took everything off ([nothingOn]). Every field is null when nothing is known (no such
 * change in the last day).
 */
@Serializable
data class ContinenceWearing(
    /** The product put on (null with nothing on, or nothing known). */
    val supplyId: String? = null,
    val name: String? = null,
    val kind: SupplyKind? = null,
    /** When it went on (or, with nothing on, when the last one came off). */
    val since: Long? = null,
    /** How long that's been, as of the server's now. */
    val minutes: Int? = null,
    /** How long this product is usually worn, from the diary (with three or more wears to go on). */
    val typicalMinutes: Int? = null,
    /** When a change reminder is due ([ContinenceSettings.changeReminderHours]), moved to the end of the night when reminders wait for the morning. */
    val changeDueAt: Long? = null,
    /** Taken off, with nothing on for now. */
    val nothingOn: Boolean = false,
    /** What's on now as a wear: how full it is, and the accidents since it went on (null with nothing on). */
    val wear: ContinenceWear? = null,
)

/**
 * One product's time on: from the change that put it on ([on]) to the one that took it off
 * ([off]), with the accidents while it was on. Accidents belong to what was on at the time (and
 * never to a different product than the one they name).
 */
@Serializable
data class ContinenceWear(
    val supplyId: String,
    val name: String? = null,
    val kind: SupplyKind? = null,
    /** The change that put it on, when that's in the diary (within a day). */
    val on: ContinenceEntry? = null,
    /** The change that took it off: null while it's on, or when the diary skips its removal. */
    val off: ContinenceEntry? = null,
    /** When it went on and came off (null when not known). */
    val start: Long? = null,
    val end: Long? = null,
    /** It's what's on now. */
    val current: Boolean = false,
    /** How long it was worn (or, while it's on, has been). */
    val minutes: Int? = null,
    /** How full it was when it came off, or, until then, as the latest accident left it. */
    val fullness: Fullness? = null,
    /** It leaked through: at the change, or in an accident while it was on. */
    val leakedThrough: Boolean = false,
    /** Leaks, bowel accidents and wet nights while it was on, oldest first. */
    val accidents: List<ContinenceEntry> = emptyList(),
    /** What went on after it, by name, when something did. */
    val nextName: String? = null,
)

/** A day of the diary, grouped (GET /api/continence/diary?date=): each product worn with its accidents, and the rest on its own, newest first. */
@Serializable
data class ContinenceDiary(
    val date: String,
    val items: List<ContinenceDiaryItem> = emptyList(),
)

/** One row of the grouped diary: a [wear] (with its changes and accidents) or an [entry] on its own. */
@Serializable
data class ContinenceDiaryItem(
    val at: Long,
    val wear: ContinenceWear? = null,
    val entry: ContinenceEntry? = null,
)

/** How one product goes (GET /api/continence/products?days=90): from the changes that took it off. */
@Serializable
data class ProductStats(
    val supplyId: String,
    val name: String,
    /** Times it was taken off. */
    val changes: Int,
    /** How long it was worn on average, where the change that put it on is in the diary. */
    val avgWornMinutes: Int? = null,
    /** How full it most often was. */
    val typicalFullness: Fullness? = null,
    /** Of its changes, the share that leaked through, as a percentage. */
    val leakedThroughPct: Int = 0,
    /** Of its wears, the share at night (put on or taken off in the night), 0 to 1. */
    val nightShare: Double = 0.0,
    /** How many of its changes leaked through. */
    val leakedThrough: Int = 0,
    val kind: SupplyKind? = null,
)

/** How the bladder and bowel diary works for this person (GET/PUT /api/continence/settings). */
@Serializable
data class ContinenceSettings(
    /** The buttons on the quick-log row, in order. */
    val quickButtons: List<ContinenceKind> = listOf(ContinenceKind.URINATION, ContinenceKind.LEAK, ContinenceKind.BOWEL, ContinenceKind.PAD_CHANGE),
    /** The person's own words offered in pickers. */
    val triggers: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val locations: List<String> = emptyList(),
    val customFields: List<CustomField> = emptyList(),
    /** The product usually worn, preselected when logging. */
    val defaultSupplyId: String? = null,
    /** When night begins and ends, if not from recorded sleep. */
    val nightFrom: String? = null,
    val nightTo: String? = null,
    val bladderTraining: BladderTraining = BladderTraining(),
    /** A discreet "Time for a check" this many hours after a product goes on (null: no reminder). */
    val changeReminderHours: Int? = null,
    /** Let that reminder come in the night hours too; otherwise it waits for the end of the night. */
    val changeReminderAtNight: Boolean = false,
)

/** Timed trips to the toilet, stretched a little at a time: reminders every [intervalMinutes] while awake. */
@Serializable
data class BladderTraining(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 60,
    val from: String = "08:00",
    val to: String = "21:00",
    /** Add [stepMinutes] every [stepEveryDays] days, up to [goalMinutes]. */
    val stepMinutes: Int = 15,
    val stepEveryDays: Int = 7,
    val goalMinutes: Int = 180,
    val startedOn: String? = null,
)

/** A weekly "how much does it bother you", 0 not at all … 10 a great deal, and how things compare. */
@Serializable
data class ContinenceCheckIn(
    val date: String,
    val bother: Int,
    /** "much better" … "much worse", against a month ago. */
    val compared: Int? = null,
    /** Things it got in the way of: "sleep", "work", "going out", "exercise", "sex", "travel"… */
    val impact: List<String> = emptyList(),
    val note: String? = null,
    val id: String? = null,
)

@Serializable
enum class Verdict(val label: String) {
    IMPROVING("Getting better"), STEADY("About the same"), WORSENING("Getting worse"), NOT_ENOUGH("Not enough to tell yet"),
}

/** One number, the earlier stretch against the latest, and whether lower is better. */
@Serializable
data class ProgressMetric(val key: String, val label: String, val before: Double?, val after: Double?, val unit: String = "", val lowerIsBetter: Boolean = true, val verdict: Verdict)

@Serializable
data class ProgressWeek(
    val weekStart: String,
    val days: Int,
    val voidsPerDay: Double? = null,
    val nightVoidsPerDay: Double? = null,
    val leaksPerDay: Double? = null,
    val bowelLeaksPerDay: Double? = null,
    val urgency: Double? = null,
    val productsPerDay: Double? = null,
    val fullness: Double? = null,
    val lossGPerDay: Double? = null,
    val bother: Double? = null,
    val pelvicFloorSets: Int = 0,
)

/** Whether things are getting better (GET /api/continence/progress?weeks=12): the last weeks against the ones before. */
@Serializable
data class ContinenceProgress(
    val verdict: Verdict,
    val headline: String,
    val recentWeeks: Int,
    val metrics: List<ProgressMetric>,
    val weeks: List<ProgressWeek>,
    /** What may have helped or got in the way, in plain words (pelvic floor practice, caffeine, a new medication…). */
    val notes: List<String> = emptyList(),
)

// ---------------------------------------------------------------- Supplies (bladder and bowel, periods)

@Serializable
enum class SupplyCategory(val label: String) { CONTINENCE("Bladder and bowel"), PERIOD("Periods"), OTHER("Other") }

@Serializable
enum class SupplyKind(val label: String, val category: SupplyCategory) {
    LINER("Liner", SupplyCategory.CONTINENCE),
    PAD("Pad", SupplyCategory.CONTINENCE),
    BOOSTER("Booster pad", SupplyCategory.CONTINENCE),
    PULL_UP("Pull-up / protective underwear", SupplyCategory.CONTINENCE),
    BRIEF("Brief / diaper (with tabs)", SupplyCategory.CONTINENCE),
    BED_PAD("Bed pad", SupplyCategory.CONTINENCE),
    REUSABLE_UNDERWEAR("Reusable underwear", SupplyCategory.CONTINENCE),
    CATHETER("Catheter", SupplyCategory.CONTINENCE),
    WIPES("Wipes", SupplyCategory.OTHER),
    BARRIER_CREAM("Barrier cream", SupplyCategory.OTHER),
    PERIOD_PAD("Period pad", SupplyCategory.PERIOD),
    TAMPON("Tampon", SupplyCategory.PERIOD),
    CUP("Cup", SupplyCategory.PERIOD),
    DISC("Disc", SupplyCategory.PERIOD),
    PERIOD_UNDERWEAR("Period underwear", SupplyCategory.PERIOD),
    OTHER("Other", SupplyCategory.OTHER),
}

/** Something kept on hand. Encrypted on the server, like the diary. */
@Serializable
data class Supply(
    val name: String,
    val kind: SupplyKind,
    val brand: String? = null,
    val size: String? = null,
    /** As the pack says: "Maxi", "Super", "6 drops", "overnight". */
    val absorbency: String? = null,
    /** Dry weight in grams, for weighing used ones. */
    val dryWeightG: Double? = null,
    val onHand: Int = 0,
    val perPack: Int? = null,
    /** Warn when this few are left (default: about a week's use). */
    val lowAt: Int? = null,
    /** Reusable things (a cup, reusable underwear) aren't counted down. */
    val reusable: Boolean = false,
    val notes: String? = null,
    val active: Boolean = true,
    val id: String? = null,
)

@Serializable
enum class SupplyReason { USED, RESTOCKED, COUNTED, GAVE_AWAY }

/** A change to what's on hand: used ones come from the diary; restocks and counts by hand. */
@Serializable
data class SupplyAdjustment(val at: Long, val delta: Int, val reason: SupplyReason, val note: String? = null, val id: String? = null, val supplyId: String? = null)

@Serializable
data class SupplyStatus(
    val supply: Supply,
    /** Used a day, over the last two weeks. */
    val perDay: Double? = null,
    val daysLeft: Int? = null,
    val low: Boolean = false,
    val lastRestocked: String? = null,
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
    val bowelLeaks: Int = 0,
    val wetNights: Int = 0,
    /** Products used (pads, briefs…), counting each change's count. */
    val products: Int = 0,
    val leakedThrough: Int = 0,
    val catheters: Int = 0,
    val catheterMl: Int? = null,
    /** Grams lost, from weighed pads. */
    val lossG: Double? = null,
    /** On Today only: what's being worn now (ModulesToday.continence). */
    val wearing: ContinenceWearing? = null,
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
    /** Supplies running low (bladder and bowel, periods). */
    val lowSupplies: List<SupplyStatus>? = null,
    val neuro: NeuroToday? = null,
    val transition: TransitionToday? = null,
)

@Serializable
data class CycleToday(
    val cycleDay: Int? = null,
    val phase: CyclePhase = CyclePhase.UNKNOWN,
    val daysUntilPeriod: Int? = null,
    val logged: CycleDay? = null,
)
