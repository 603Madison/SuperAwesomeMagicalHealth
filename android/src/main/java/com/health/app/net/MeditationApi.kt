package com.health.app.net

import com.health.api.ApiJson
import com.health.api.Meditation
import com.health.api.MeditationSession
import com.health.api.MeditationSettings
import com.health.api.MeditationSummary
import kotlinx.serialization.builtins.ListSerializer

/*
 * Guided meditation (docs/API.md, "Meditation"): the scripts, the sessions, the summary and the
 * nightly reminder. The phone reads the scripts aloud with its own offline voice: nothing is
 * streamed or fetched from anywhere but the person's server. Sessions are read through a share
 * (with Mind shared); the scripts and settings are always the person's own (Container.api).
 */

suspend fun ServerApi.meditations(): List<Meditation> =
    ServerVersion.plain("for guided meditation") { get("${base()}/api/meditations", ListSerializer(Meditation.serializer())) }

/** The last [days] days' sessions, newest first. */
suspend fun ServerApi.meditationSessions(days: Int = 30): List<MeditationSession> =
    get("${base()}/api/meditations/sessions?days=$days", ListSerializer(MeditationSession.serializer()))

suspend fun ServerApi.addMeditationSession(session: MeditationSession): MeditationSession =
    post("/api/meditations/sessions", session, MeditationSession.serializer())

/** Changes one (to add how it felt afterwards). */
suspend fun ServerApi.updateMeditationSession(id: String, session: MeditationSession): MeditationSession = send(
    "PUT", "${base()}/api/meditations/sessions/${enc(id)}", ApiJson.encodeToString(MeditationSession.serializer(), session), MeditationSession.serializer(),
)

suspend fun ServerApi.deleteMeditationSession(id: String) = delete("/api/meditations/sessions/${enc(id)}")

suspend fun ServerApi.meditationSummary(days: Int = 30): MeditationSummary =
    get("${base()}/api/meditations/summary?days=$days", MeditationSummary.serializer())

/** The nightly reminder: never shared, so only ever the person's own. */
suspend fun ServerApi.meditationSettings(): MeditationSettings =
    get("${base()}/api/meditations/settings", MeditationSettings.serializer())

suspend fun ServerApi.saveMeditationSettings(settings: MeditationSettings): MeditationSettings = send(
    "PUT", "${base()}/api/meditations/settings", ApiJson.encodeToString(MeditationSettings.serializer(), settings), MeditationSettings.serializer(),
)
