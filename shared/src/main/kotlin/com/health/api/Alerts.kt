package com.health.api

import kotlinx.serialization.Serializable

/*
 * Health alerts (GET /api/alerts): the server watches what the devices send and says so when a
 * reading looks out of the ordinary for the person, worth a doctor's look, or urgent. They're a
 * heads-up from consumer devices, never a diagnosis, and say so.
 */

/** How soon to act. */
@Serializable
enum class AlertLevel {
    /** Could need care now: the advice says when to call emergency services. */
    URGENT,
    /** Worth telling a doctor about soon. */
    DOCTOR,
    /** Worth keeping an eye on. */
    WATCH,
}

@Serializable
enum class AlertKind(val label: String) {
    HIGH_HEART_RATE("High heart rate at rest"),
    LOW_HEART_RATE("Low heart rate"),
    /** Only from a chest strap's beat-to-beat timing ([Metric.IRREGULAR_RHYTHM]). */
    IRREGULAR_RHYTHM("Irregular heart rhythm"),
    LOW_OXYGEN("Low blood oxygen"),
    OXYGEN_DIPS("Blood oxygen dips at night"),
    GETTING_SICK("Signs you may be getting ill"),
    RESTING_HEART_RISE("Resting heart rate has stayed up"),
}

/** What the person said about an alert when putting it away. */
@Serializable
enum class AlertFeedback(val label: String) {
    ACTIVE("I was active"),
    EXPECTED("I know why"),
    DEVICE("The device slipped or misread"),
    DOCTOR("I'm getting it checked"),
    OTHER("Something else"),
}

/** One reading behind an alert: "Highest" · "146 bpm". */
@Serializable
data class AlertReading(val label: String, val value: String)

@Serializable
data class HealthAlert(
    val id: String,
    val kind: AlertKind,
    val level: AlertLevel,
    val title: String,
    val body: String,
    /** What to do. For [AlertLevel.URGENT], it starts with when to call emergency services. */
    val advice: String,
    val evidence: List<AlertReading> = emptyList(),
    /** When it began and (for a spell) ended. */
    val start: Long,
    val end: Long? = null,
    /** The devices whose readings it came from. */
    val devices: List<String> = emptyList(),
    val createdAt: Long,
    val seenAt: Long? = null,
    val dismissedAt: Long? = null,
    val feedback: AlertFeedback? = null,
)

/** Each person's alert settings (PUT /api/alerts/settings). */
@Serializable
data class AlertSettings(
    val enabled: Boolean = true,
    /** Heart rate (bpm) that counts as high when it stays there for 10 minutes at rest: 100–180. */
    val highHeartRate: Int = 120,
    /** Heart rate (bpm) that counts as low when it stays there for 10 minutes while awake: 30–60. */
    val lowHeartRate: Int = 40,
    /** Blood oxygen (%) that counts as low: 85–94. */
    val lowOxygen: Int = 90,
    /** Watch for the signs of getting ill (resting heart rate, HRV, temperature and breathing together). */
    val illness: Boolean = true,
    /** Watch a chest strap's rhythm. */
    val rhythm: Boolean = true,
    /** Tell the person by notification (the phone, and the browser if it's set up). */
    val notify: Boolean = true,
) {
    fun clamped() = copy(
        highHeartRate = highHeartRate.coerceIn(100, 180),
        lowHeartRate = lowHeartRate.coerceIn(30, 60),
        lowOxygen = lowOxygen.coerceIn(85, 94),
    )
}

/** What the alerts can watch with the devices the person has: "Rhythm" · "Needs a chest strap". */
@Serializable
data class AlertWatch(val what: String, val how: String, val watching: Boolean)

@Serializable
data class HealthAlerts(
    /** Newest first. */
    val alerts: List<HealthAlert> = emptyList(),
    val settings: AlertSettings = AlertSettings(),
    val watching: List<AlertWatch> = emptyList(),
    /** What they can and can't do, plainly. */
    val limits: String = "",
    val checkedAt: Long? = null,
)

/** Putting an alert away, with what the person said about it (POST /api/alerts/{id}/dismiss). */
@Serializable
data class AlertDismiss(val feedback: AlertFeedback? = null)
