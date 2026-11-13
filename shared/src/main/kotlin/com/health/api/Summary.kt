package com.health.api

import kotlinx.serialization.Serializable

/**
 * Which formula estimates resting energy (Mifflin–St Jeor uses a different constant for female and
 * male bodies). People choose what fits their body best; "average" sits between the two.
 */
@Serializable
enum class EnergyProfile(val label: String) { FEMALE("Female"), MALE("Male"), AVERAGE("Average of both") }

@Serializable
enum class Units { METRIC, IMPERIAL }

@Serializable
data class Goals(
    val steps: Int = 8000,
    val activeCalories: Int = 400,
    val sleepMinutes: Int = 480,
    /** Calories eaten per day; null works it out from what's burned and [weeklyChangeKg]. */
    val calories: Int? = null,
    /** Grams per day; null works them out from the calories. */
    val protein: Int? = null,
    val carbs: Int? = null,
    val fat: Int? = null,
    val waterMl: Int = 2000,
    val workoutsPerWeek: Int = 3,
    val targetWeightKg: Double? = null,
    /** Weight change aimed for per week: negative to lose, positive to gain. */
    val weeklyChangeKg: Double = 0.0,
)

@Serializable
data class Profile(
    val birthYear: Int? = null,
    val heightCm: Double? = null,
    val energyProfile: EnergyProfile = EnergyProfile.AVERAGE,
    val units: Units = Units.METRIC,
    /** IANA time zone, e.g. America/New_York: where the person's days begin and end. */
    val timezone: String = "",
    val goals: Goals = Goals(),
    /** The parts of the app this person uses (see [Module]). */
    val modules: Set<Module> = Module.DEFAULT,
    val reminders: ReminderPrefs = ReminderPrefs(),
    val digest: DigestPrefs = DigestPrefs(),
    /** Roughly where the person is, for the weather (optional). */
    val place: Place? = null,
    val vitalsTargets: VitalsTargets = VitalsTargets(),
)

/** One part of a score, as Oura shows them: its own 0–100 and what it's based on. */
@Serializable
data class Contributor(
    val key: String,
    val label: String,
    val score: Int,
    /** What it was measured as, ready to show: "7h 32m", "52 bpm". */
    val value: String,
    val detail: String? = null,
)

@Serializable
data class Score(val score: Int, val label: String, val contributors: List<Contributor>)

@Serializable
data class SleepSummary(
    val start: Long,
    val end: Long,
    val asleepMinutes: Int,
    val inBedMinutes: Int,
    /** Asleep ÷ in bed, as a percentage. */
    val efficiency: Int,
    val deepMinutes: Int,
    val remMinutes: Int,
    val lightMinutes: Int,
    val awakeMinutes: Int,
    /** Whether the device told stages apart (deep, REM, light). */
    val staged: Boolean,
    val latencyMinutes: Int? = null,
    val lowestHeartRate: Double? = null,
    val avgHeartRate: Double? = null,
    val avgHrv: Double? = null,
    val stages: List<StageSpan> = emptyList(),
    val source: String? = null,
    val sessionId: String? = null,
    /** Where more than one device recorded this night: each one's version, and how the night was worked out. */
    val combined: CombinedSleep? = null,
)

/** Something worth knowing about the day: [tone] is "good", "info" or "warn". */
@Serializable
data class Insight(
    val tone: String,
    val title: String,
    val body: String,
    val icon: String = "sparkles",
    /** The health module it comes from, if any (shown only where that module may be seen). */
    val module: Module? = null,
    /** The page it's about, which a tap opens; without one, the apps open [module]'s page (see [AppPage.of]). */
    val link: AppPage? = null,
    /** Food ideas that would help (the protein insight, today only): each can be logged with one tap. */
    val suggestions: List<FoodSuggestion> = emptyList(),
    /** The Today card that already shows the same thing (the day's doses): the apps leave the insight out while it shows. */
    val shownBy: TodaySection? = null,
)

/** The apps' pages, for an [Insight] to open. Each app maps them to its own routes. */
@Serializable
enum class AppPage {
    TODAY, FOOD, MEDS, MIND, CYCLE, CONTINENCE, SUPPLIES, VITALS, HABITS, SYMPTOMS, HORMONES, APPOINTMENTS, NEURO, TRANSITION,
    PHOTOS, BODY, SLEEP, ACTIVITY, TRENDS, DEVICES,
    ;

    /** The feature a page belongs to (shown to someone viewing a share only if it's shared), or null for the pages everyone has. */
    val module: Module?
        get() = when (this) {
            TODAY, TRENDS, DEVICES, SUPPLIES -> null
            FOOD -> Module.NUTRITION
            MEDS -> Module.MEDICATIONS
            MIND -> Module.MIND
            CYCLE -> Module.CYCLE
            CONTINENCE -> Module.CONTINENCE
            VITALS -> Module.VITALS
            HABITS -> Module.HABITS
            SYMPTOMS -> Module.SYMPTOMS
            HORMONES -> Module.HORMONES
            APPOINTMENTS -> Module.APPOINTMENTS
            NEURO -> Module.NEURO
            TRANSITION -> Module.TRANSITION
            PHOTOS -> Module.PHOTOS
            BODY -> Module.BODY
            SLEEP -> Module.SLEEP
            ACTIVITY -> Module.ACTIVITY
        }

    /** Only the person's own (not while viewing someone's share). */
    val ownOnly: Boolean get() = this == DEVICES

    companion object {
        /** A feature's own page. */
        fun of(module: Module): AppPage = when (module) {
            Module.ACTIVITY -> ACTIVITY
            Module.SLEEP -> SLEEP
            Module.NUTRITION -> FOOD
            Module.BODY -> BODY
            Module.MIND -> MIND
            Module.CYCLE -> CYCLE
            Module.CONTINENCE -> CONTINENCE
            Module.MEDICATIONS -> MEDS
            Module.VITALS -> VITALS
            Module.HABITS -> HABITS
            Module.SYMPTOMS -> SYMPTOMS
            Module.HORMONES -> HORMONES
            Module.APPOINTMENTS -> APPOINTMENTS
            Module.NEURO -> NEURO
            Module.TRANSITION -> TRANSITION
            Module.PHOTOS -> PHOTOS
        }

        /** The page [insight] opens: its link, else its feature's page; null when it's about nothing in particular. */
        fun of(insight: Insight): AppPage? = insight.link ?: insight.module?.let(::of)
    }
}

/** Everything about one day, for the Today page. */
@Serializable
data class DaySummary(
    val date: String,
    val readiness: Score? = null,
    val sleep: Score? = null,
    val activity: Score? = null,
    val steps: Int = 0,
    val distanceM: Double = 0.0,
    val activeCalories: Double = 0.0,
    /** Resting energy (estimated from the profile), for the whole day. */
    val restingCalories: Double? = null,
    val floors: Double? = null,
    val restingHeartRate: Double? = null,
    val avgHeartRate: Double? = null,
    val minHeartRate: Double? = null,
    val maxHeartRate: Double? = null,
    val hrv: Double? = null,
    val spo2: Double? = null,
    val respiratoryRate: Double? = null,
    val temperatureDeviation: Double? = null,
    /** The device's own stress reading, 0–100, where it gives one. */
    val stress: Double? = null,
    /** The day while awake: stressed, engaged, relaxed and restored minutes. */
    val stressDay: StressDay? = null,
    val resilience: Resilience? = null,
    val sleepSummary: SleepSummary? = null,
    val naps: List<SleepSummary> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val nutrition: Nutrients = Nutrients(),
    val nutritionTarget: NutritionTarget? = null,
    val waterMl: Int = 0,
    /** Coffee, tea, alcohol and the rest (with the food). */
    val drinks: DrinksDay? = null,
    val weightKg: Double? = null,
    val bodyFat: Double? = null,
    val checkIn: CheckIn? = null,
    val insights: List<Insight> = emptyList(),
    /** Steps in each hour of the day, 0–23. */
    val hourlySteps: List<Int> = emptyList(),
    /** The day's heart rate, a point every few minutes. */
    val heartRate: List<Point> = emptyList(),
    val goals: Goals = Goals(),
    /** Whether anything at all was recorded or entered that day. */
    val hasData: Boolean = false,
    /** Mood, cycle, bladder and bowel, and medications, for the modules that are on. */
    val modules: ModulesToday = ModulesToday(),
    /** The day's numbers that more than one device measured: each device's value, and the app's own (see Devices.kt). */
    val combined: List<CombinedValue> = emptyList(),
)

/** One day's numbers, for charts over weeks and months. Absent values weren't measured. */
@Serializable
data class TrendDay(
    val date: String,
    val readiness: Int? = null,
    val sleepScore: Int? = null,
    val activityScore: Int? = null,
    val steps: Int? = null,
    val activeCalories: Double? = null,
    val restingHeartRate: Double? = null,
    val hrv: Double? = null,
    val sleepMinutes: Int? = null,
    val deepMinutes: Int? = null,
    val remMinutes: Int? = null,
    val spo2: Double? = null,
    val temperatureDeviation: Double? = null,
    val weightKg: Double? = null,
    val bodyFat: Double? = null,
    val calories: Double? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
    val waterMl: Int? = null,
    /** Fibre, sugar, saturated fat, sodium (mg) and the micronutrients: only where a food that day carried them. */
    val fiber: Double? = null,
    val sugar: Double? = null,
    val saturatedFat: Double? = null,
    val sodiumMg: Double? = null,
    @Serializable(with = MicrosSerializer::class)
    val micros: Map<Micronutrient, Double> = emptyMap(),
    val workouts: Int = 0,
    val workoutMinutes: Int = 0,
    /** The modules' numbers, where they're on and anything was logged. */
    val mood: Double? = null,
    val anxiety: Double? = null,
    val mindfulMinutes: Double? = null,
    val flow: Flow? = null,
    val voids: Int? = null,
    val nightVoids: Int? = null,
    val leaks: Int? = null,
    val dosesTaken: Int? = null,
    val dosesDue: Int? = null,
    val stressedMinutes: Int? = null,
    val restoredMinutes: Int? = null,
    val caffeineMg: Double? = null,
    val standardDrinks: Double? = null,
    val systolic: Double? = null,
    val diastolic: Double? = null,
    val glucose: Double? = null,
    /** The worst symptom that day, 0–10. */
    val symptomWorst: Int? = null,
    val migraine: Boolean? = null,
    val habitsDone: Int? = null,
    val habitsDue: Int? = null,
    val hotFlashes: Int? = null,
)

@Serializable
data class Trends(
    val from: String,
    val to: String,
    val days: List<TrendDay>,
    /** The last day's food targets, for charting a nutrient against its target (with Food on). */
    val nutritionTarget: NutritionTarget? = null,
)
