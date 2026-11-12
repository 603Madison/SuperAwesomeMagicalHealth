package com.fitness.api

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
)

/** Something worth knowing about the day: [tone] is "good", "info" or "warn". */
@Serializable
data class Insight(val tone: String, val title: String, val body: String, val icon: String = "sparkles")

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
    val stress: Double? = null,
    val sleepSummary: SleepSummary? = null,
    val naps: List<SleepSummary> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val nutrition: Nutrients = Nutrients(),
    val nutritionTarget: NutritionTarget? = null,
    val waterMl: Int = 0,
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
    val workouts: Int = 0,
    val workoutMinutes: Int = 0,
)

@Serializable
data class Trends(val from: String, val to: String, val days: List<TrendDay>)
