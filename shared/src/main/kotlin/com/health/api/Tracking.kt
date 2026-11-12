package com.health.api

import kotlinx.serialization.Serializable

/*
 * Blood pressure and glucose, drinks, symptoms and migraines, stress and resilience, habits,
 * hormone therapy, life stages and appointments. Units are metric on the wire (mmol/L, ml, grams
 * of alcohol); the apps convert for display.
 */

// ---------------------------------------------------------------- Blood pressure and glucose (Module.VITALS)

@Serializable
data class BloodPressure(
    val at: Long,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int? = null,
    /** "left", "right". */
    val arm: String? = null,
    val note: String? = null,
    val id: String? = null,
    val date: String? = null,
    /** The device's name, for readings a monitor sent. */
    val source: String? = null,
)

/** Where a reading sits; ranges follow the American Heart Association's categories. */
@Serializable
enum class BpCategory(val label: String) {
    LOW("Low"), NORMAL("Normal"), ELEVATED("Elevated"), STAGE_1("High (stage 1)"), STAGE_2("High (stage 2)"), CRISIS("Very high"),
}

@Serializable
enum class GlucoseContext(val label: String) {
    FASTING("Fasting"), BEFORE_MEAL("Before a meal"), AFTER_MEAL("After a meal"), BEDTIME("Bedtime"), OTHER("Other"),
    /** From a continuous glucose monitor. */
    CGM("Sensor"),
}

@Serializable
data class GlucoseReading(
    val at: Long,
    val mmolL: Double,
    val context: GlucoseContext = GlucoseContext.OTHER,
    val note: String? = null,
    val id: String? = null,
    val date: String? = null,
    val source: String? = null,
)

@Serializable
enum class GlucoseUnit(val label: String) { MMOL_L("mmol/L"), MG_DL("mg/dL") }

/** The person's own ranges (from their clinician); defaults are the usual ones. */
@Serializable
data class VitalsTargets(
    val systolicHigh: Int = 130,
    val diastolicHigh: Int = 80,
    val glucoseLow: Double = 3.9,
    val glucoseHigh: Double = 10.0,
    val glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL_L,
)

@Serializable
data class VitalsSummary(
    val days: Int,
    val bloodPressure: List<BloodPressure> = emptyList(),
    val glucose: List<GlucoseReading> = emptyList(),
    val systolicAverage: Double? = null,
    val diastolicAverage: Double? = null,
    val category: BpCategory? = null,
    val glucoseAverage: Double? = null,
    val fastingAverage: Double? = null,
    /** Percent of readings (or sensor time) below, inside and above the target range. */
    val timeBelow: Int? = null,
    val timeInRange: Int? = null,
    val timeAbove: Int? = null,
    /** Glucose management indicator (an A1c estimate), with 14 days or more of sensor data. */
    val gmi: Double? = null,
    val targets: VitalsTargets = VitalsTargets(),
    val patterns: List<Insight> = emptyList(),
)

@Serializable
data class VitalsToday(
    val bloodPressure: BloodPressure? = null,
    val category: BpCategory? = null,
    val glucose: GlucoseReading? = null,
    val timeInRange: Int? = null,
    val targets: VitalsTargets = VitalsTargets(),
)

// ---------------------------------------------------------------- Drinks (part of Module.NUTRITION)

@Serializable
enum class DrinkKind(val label: String, val defaultMl: Int, val caffeineMgPer100Ml: Double, val alcoholPercent: Double) {
    WATER("Water", 250, 0.0, 0.0),
    COFFEE("Coffee", 240, 40.0, 0.0),
    ESPRESSO("Espresso", 30, 210.0, 0.0),
    TEA("Tea", 240, 20.0, 0.0),
    GREEN_TEA("Green tea", 240, 12.0, 0.0),
    ENERGY_DRINK("Energy drink", 250, 32.0, 0.0),
    SODA("Cola", 330, 10.0, 0.0),
    DECAF("Decaf", 240, 1.0, 0.0),
    BEER("Beer", 355, 0.0, 5.0),
    WINE("Wine", 150, 0.0, 12.0),
    SPIRITS("Spirits", 45, 0.0, 40.0),
    COCKTAIL("Cocktail", 150, 0.0, 15.0),
    OTHER("Other", 250, 0.0, 0.0),
}

/**
 * A drink. Caffeine in mg and alcohol in grams (a standard drink is 14 g); the apps fill both
 * from [DrinkKind] and let the person change them. Everything but alcohol counts towards water.
 */
@Serializable
data class Drink(
    val at: Long,
    val kind: DrinkKind,
    val volumeMl: Int,
    val caffeineMg: Double = 0.0,
    val alcoholG: Double = 0.0,
    val name: String? = null,
    val id: String? = null,
    val date: String? = null,
)

@Serializable
data class DrinksDay(
    val date: String,
    val drinks: List<Drink> = emptyList(),
    val caffeineMg: Double = 0.0,
    val alcoholG: Double = 0.0,
    /** Standard drinks (14 g of alcohol each). */
    val standardDrinks: Double = 0.0,
    val lastCaffeineAt: Long? = null,
    /** Caffeine likely still in the body at the person's usual bedtime (a 5-hour half-life). */
    val caffeineAtBedtimeMg: Double? = null,
    val fluidMl: Int = 0,
)

/** How drinks went with sleep, as found in the person's own data. */
@Serializable
data class DrinksSummary(
    val days: List<DrinksDay>,
    val averageCaffeineMg: Double? = null,
    val averageStandardDrinks: Double? = null,
    val patterns: List<Insight> = emptyList(),
)

// ---------------------------------------------------------------- Symptoms and migraines (Module.SYMPTOMS)

@Serializable
data class SymptomEntry(
    val at: Long,
    /** "headache", "back pain", "fatigue", or the person's own. */
    val symptom: String,
    /** 0 (none) to 10 (worst imaginable). */
    val severity: Int,
    val bodyAreas: List<String> = emptyList(),
    val durationMinutes: Int? = null,
    val triggers: List<String> = emptyList(),
    /** What was tried: "ibuprofen", "rest", "heat". */
    val relief: List<String> = emptyList(),
    /** How much it helped: 0 not at all … 3 completely. */
    val reliefHelped: Int? = null,
    val note: String? = null,
    val id: String? = null,
    val date: String? = null,
)

@Serializable
data class Migraine(
    val start: Long,
    /** Null while it's still going. */
    val end: Long? = null,
    /** 0–10 at its worst. */
    val severity: Int,
    val aura: Boolean = false,
    /** "nausea", "light sensitivity", "sound sensitivity", "dizziness"… */
    val symptoms: List<String> = emptyList(),
    val triggers: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val reliefHelped: Int? = null,
    /** Missed work, school or plans because of it. */
    val missedPlans: Boolean = false,
    val note: String? = null,
    val id: String? = null,
    val date: String? = null,
)

/** Something that went with symptoms in the person's own data, said plainly. */
@Serializable
data class Correlation(
    val factor: String,
    val text: String,
    /** How often symptoms came on days with the factor, and without it (0–1). */
    val withRate: Double,
    val withoutRate: Double,
    val days: Int,
)

@Serializable
data class SymptomsDay(
    val date: String,
    val entries: List<SymptomEntry> = emptyList(),
    val migraines: List<Migraine> = emptyList(),
    val worst: Int? = null,
)

@Serializable
data class SymptomsSummary(
    val days: List<SymptomsDay>,
    val counts: List<Count> = emptyList(),
    val migraineDays: Int = 0,
    val triggers: List<Count> = emptyList(),
    val correlations: List<Correlation> = emptyList(),
    /** Whether weather (air pressure) was part of the comparison. */
    val weather: Boolean = false,
)

/** Where the person is, roughly, for the weather (air pressure goes with migraines for some). */
@Serializable
data class Place(val name: String, val latitude: Double, val longitude: Double)

// ---------------------------------------------------------------- Stress and resilience (with activity and sleep)

@Serializable
enum class StressState(val label: String) { STRESSED("Stressed"), ENGAGED("Engaged"), RELAXED("Relaxed"), RESTORED("Restored") }

@Serializable
data class StressSpan(val start: Long, val end: Long, val state: StressState)

/** The day while awake, from heart rate and HRV (or the device's own stress readings). */
@Serializable
data class StressDay(
    val date: String,
    val stressedMinutes: Int = 0,
    val engagedMinutes: Int = 0,
    val relaxedMinutes: Int = 0,
    val restoredMinutes: Int = 0,
    /** "Restored", "Normal", "Stressful": the day as a whole. */
    val summary: String,
    val spans: List<StressSpan> = emptyList(),
)

@Serializable
enum class ResilienceLevel(val label: String) {
    LIMITED("Limited"), ADEQUATE("Adequate"), SOLID("Solid"), STRONG("Strong"), EXCEPTIONAL("Exceptional"),
}

/** How well stress has been balanced by recovery over the last two weeks. */
@Serializable
data class Resilience(
    val level: ResilienceLevel,
    val contributors: List<Contributor>,
    /** "up", "down" or "steady" against the two weeks before. */
    val trend: String = "steady",
)

// ---------------------------------------------------------------- Habits (Module.HABITS)

/** Where a habit's tick comes from: the person, or something already recorded. */
@Serializable
enum class HabitSource(val label: String) {
    MANUAL("I'll tick it"),
    STEPS_GOAL("Reach my step goal"),
    SLEEP_GOAL("Sleep my sleep goal"),
    WATER_GOAL("Drink my water goal"),
    WORKOUT("Work out"),
    MOOD_LOGGED("Log my mood"),
    JOURNAL("Write in my journal"),
    MINDFUL("A calm practice"),
    MEDS_TAKEN("Take all my medications"),
    PELVIC_FLOOR("Pelvic floor exercises"),
    NO_ALCOHOL("No alcohol"),
    CAFFEINE_CUTOFF("No caffeine after 2 pm"),
}

@Serializable
data class Habit(
    val name: String,
    /** A lucide icon name: "sparkles", "dumbbell", "droplet"… */
    val icon: String = "sparkles",
    val source: HabitSource = HabitSource.MANUAL,
    /** Days a week to aim for, 1–7: streaks count weeks that reach it, so a rest day never breaks one. */
    val timesPerWeek: Int = 7,
    /** For manual habits: ticks a day that count as done (e.g. 3 short walks). */
    val perDay: Int = 1,
    /** "HH:mm": a nudge at this time if it isn't done yet. */
    val reminderTime: String? = null,
    val active: Boolean = true,
    val id: String? = null,
)

@Serializable
data class HabitStatus(
    val habit: Habit,
    val date: String,
    val count: Int,
    val done: Boolean,
    val weekDone: Int,
    /** Weeks in a row that reached the target, this one included once it's reached or still can be. */
    val streakWeeks: Int,
    /** Days in a row, forgiving one missed day in any seven. */
    val streakDays: Int,
    /** The last 14 days, oldest first: true done, false missed, null before the habit began. */
    val recent: List<Boolean?> = emptyList(),
)

@Serializable
data class HabitTick(val date: String, val count: Int)

// ---------------------------------------------------------------- Hormone therapy (Module.HORMONES)

/** A change to a hormone medication's dose or schedule: recorded when one is edited, or added by hand. */
@Serializable
data class DoseChange(
    val medicationId: String,
    val at: Long,
    val from: String? = null,
    val to: String,
    val note: String? = null,
    val id: String? = null,
)

/** The person's own target range for a lab test, as their clinician set it. */
@Serializable
data class LabTarget(val name: String, val unit: String, val low: Double? = null, val high: Double? = null, val note: String? = null)

@Serializable
data class HormoneSettings(
    /** Labs are usually checked every few months: a reminder when the next ones are due. */
    val labsEveryWeeks: Int? = 12,
    val targets: List<LabTarget> = emptyList(),
)

@Serializable
data class LabSeries(val name: String, val unit: String, val target: LabTarget? = null, val results: List<LabResult>)

@Serializable
data class HormoneMed(
    val medication: Medication,
    /** When it was first started (the medication's startedOn, or its first dose). */
    val since: String? = null,
    val changes: List<DoseChange> = emptyList(),
)

@Serializable
data class HormoneOverview(
    val medications: List<HormoneMed>,
    val labs: List<LabSeries>,
    val settings: HormoneSettings,
    val lastLabs: String? = null,
    val nextLabsDue: String? = null,
)

@Serializable
data class HormonesToday(
    val medications: Int = 0,
    val nextLabsDue: String? = null,
    /** Lab names whose latest result is outside the person's target range. */
    val outOfRange: List<String> = emptyList(),
)

// ---------------------------------------------------------------- Life stages (with Module.CYCLE)

@Serializable
enum class LifeStage(val label: String) {
    CYCLING("Tracking my cycle"),
    TRYING("Trying to conceive"),
    PREGNANT("Pregnant"),
    POSTPARTUM("After a birth"),
    PERIMENOPAUSE("Perimenopause"),
    MENOPAUSE("After menopause"),
}

@Serializable
data class LifeStageInfo(
    val stage: LifeStage = LifeStage.CYCLING,
    /** Pregnant: the due date (or worked out from [lastPeriod]). */
    val dueDate: String? = null,
    val lastPeriod: String? = null,
    /** After a birth: the date. */
    val birthDate: String? = null,
    val since: String? = null,
)

@Serializable
data class KickSession(val start: Long, val end: Long? = null, val kicks: Int, val id: String? = null)

@Serializable
data class Contraction(val start: Long, val end: Long? = null, val id: String? = null)

@Serializable
enum class FeedKind(val label: String) { LEFT("Left"), RIGHT("Right"), BOTH("Both sides"), BOTTLE("Bottle"), PUMP("Pump") }

@Serializable
data class Feed(val start: Long, val end: Long? = null, val kind: FeedKind, val amountMl: Int? = null, val note: String? = null, val id: String? = null)

@Serializable
data class HotFlash(val at: Long, /** 1 mild … 3 strong. */ val intensity: Int = 2, val night: Boolean = false, val id: String? = null)

@Serializable
data class PregnancyStatus(val week: Int, val day: Int, val trimester: Int, val dueDate: String, val daysToGo: Int)

@Serializable
data class LifeStageOverview(
    val info: LifeStageInfo,
    val pregnancy: PregnancyStatus? = null,
    /** After a birth: weeks since. */
    val weeksSinceBirth: Int? = null,
    /** Perimenopause and after: months since the last period (12 marks menopause). */
    val monthsSinceLastPeriod: Int? = null,
    val kicks: List<KickSession> = emptyList(),
    val contractions: List<Contraction> = emptyList(),
    /** Contractions about 5 minutes apart, lasting a minute, for an hour: time to call. */
    val contractionsPattern: String? = null,
    val feeds: List<Feed> = emptyList(),
    val hotFlashes: List<HotFlash> = emptyList(),
    val hotFlashesPerDay: Double? = null,
    val tips: List<Insight> = emptyList(),
)

@Serializable
data class LifeStageToday(
    val stage: LifeStage,
    val headline: String,
    val pregnancy: PregnancyStatus? = null,
    val feedsToday: Int = 0,
    val lastFeed: Feed? = null,
    val hotFlashesToday: Int = 0,
)

// ---------------------------------------------------------------- Appointments (Module.APPOINTMENTS)

@Serializable
data class AppointmentQuestion(val text: String, val done: Boolean = false, val suggested: Boolean = false)

@Serializable
data class Appointment(
    val at: Long,
    val title: String,
    val durationMinutes: Int? = null,
    /** Who it's with: "Dr Patel", "Pelvic health physio". */
    val with: String? = null,
    val place: String? = null,
    val questions: List<AppointmentQuestion> = emptyList(),
    /** Afterwards: what was said and decided. */
    val notes: String? = null,
    /** Minutes before to remind: a day and an hour, to start. */
    val remindBefore: List<Int> = listOf(1440, 60),
    val id: String? = null,
    val date: String? = null,
)

/** A question worth asking, from what was logged since the last appointment. */
@Serializable
data class SuggestedQuestion(val text: String, val why: String, val module: Module? = null)
