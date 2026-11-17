package com.health.app.net

import com.health.api.AlertDismiss
import com.health.api.AlertFeedback
import com.health.api.AlertSettings
import com.health.api.ApiJson
import com.health.api.HealthAlert
import com.health.api.HealthAlerts
import kotlinx.serialization.builtins.serializer

/*
 * Health alerts (GET /api/alerts, shared/…/Alerts.kt): what the server noticed in the person's
 * readings, their settings, and marking them seen or putting one away. Always the person's own:
 * call these on Container.api, never the view-aware Container.ui (a share never reaches them).
 */

/** The last [days] of alerts (newest first), the settings, what it's watching, and what alerts can and can't do. */
suspend fun ServerApi.alerts(days: Int = 30): HealthAlerts =
    ServerVersion.plain("for health alerts") { get("${base()}/api/alerts?days=$days", HealthAlerts.serializer()) }

/** Saves [settings] (kept within the server's limits); answers with what was kept. */
suspend fun ServerApi.saveAlertSettings(settings: AlertSettings): AlertSettings = ServerVersion.plain("for health alerts") {
    send("PUT", "${base()}/api/alerts/settings", ApiJson.encodeToString(AlertSettings.serializer(), settings.clamped()), AlertSettings.serializer())
}

/** Marks every alert seen. */
suspend fun ServerApi.alertsSeen() {
    send("POST", "${base()}/api/alerts/seen", null, Unit.serializer())
}

/** Marks one alert seen. */
suspend fun ServerApi.alertSeen(id: String) {
    send("POST", "${base()}/api/alerts/${enc(id)}/seen", null, Unit.serializer())
}

/** Puts an alert away, with what the person said about it (or nothing); answers with it as it now is. */
suspend fun ServerApi.dismissAlert(id: String, feedback: AlertFeedback?): HealthAlert = send(
    "POST", "${base()}/api/alerts/${enc(id)}/dismiss", ApiJson.encodeToString(AlertDismiss.serializer(), AlertDismiss(feedback)), HealthAlert.serializer(),
)
