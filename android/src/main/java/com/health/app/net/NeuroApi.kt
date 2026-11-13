package com.health.app.net

import com.health.api.AiBreakdownReply
import com.health.api.AiBreakdownRequest
import com.health.api.AiStatus
import com.health.api.ApiJson
import com.health.api.CommunicationCard
import com.health.api.EnergyDay
import com.health.api.FocusSession
import com.health.api.NeuroCheckIn
import com.health.api.NeuroProfile
import com.health.api.NeuroSummary
import com.health.api.OverloadEvent
import com.health.api.Routine
import com.health.api.RoutineRun
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/*
 * Autism & ADHD (docs/API.md, "Autism & ADHD"), and the AI assistant's "break it into steps",
 * which sends only a task's words.
 */

private suspend fun <T> ServerApi.put(path: String, body: T, serializer: KSerializer<T>): T =
    send("PUT", "${base()}$path", ApiJson.encodeToString(serializer, body), serializer)

// ---- About me ----

suspend fun ServerApi.neuroProfile(): NeuroProfile = get("${base()}/api/neuro/profile", NeuroProfile.serializer())

suspend fun ServerApi.saveNeuroProfile(profile: NeuroProfile): NeuroProfile = put("/api/neuro/profile", profile, NeuroProfile.serializer())

// ---- Times things got too much ----

suspend fun ServerApi.overloads(days: Int = 90): List<OverloadEvent> =
    get("${base()}/api/neuro/events?days=$days", ListSerializer(OverloadEvent.serializer()))

suspend fun ServerApi.saveOverload(event: OverloadEvent): OverloadEvent = event.id?.let { id ->
    put("/api/neuro/events/${enc(id)}", event, OverloadEvent.serializer())
} ?: post("/api/neuro/events", event, OverloadEvent.serializer())

suspend fun ServerApi.deleteOverload(id: String) = delete("/api/neuro/events/${enc(id)}")

// ---- Energy (also Mind's energy budget) ----

suspend fun ServerApi.energyDays(days: Int = 30): List<EnergyDay> = get("${base()}/api/neuro/energy?days=$days", ListSerializer(EnergyDay.serializer()))

suspend fun ServerApi.saveEnergyDay(day: EnergyDay): EnergyDay = put("/api/neuro/energy/${enc(day.date)}", day, EnergyDay.serializer())

// ---- Check-ins ----

suspend fun ServerApi.neuroCheckIns(days: Int = 30): List<NeuroCheckIn> =
    get("${base()}/api/neuro/check-ins?days=$days", ListSerializer(NeuroCheckIn.serializer()))

suspend fun ServerApi.addNeuroCheckIn(checkIn: NeuroCheckIn): NeuroCheckIn = post("/api/neuro/check-ins", checkIn, NeuroCheckIn.serializer())

suspend fun ServerApi.deleteNeuroCheckIn(id: String) = delete("/api/neuro/check-ins/${enc(id)}")

// ---- Focus ----

suspend fun ServerApi.focusSessions(days: Int = 30): List<FocusSession> =
    get("${base()}/api/neuro/focus?days=$days", ListSerializer(FocusSession.serializer()))

suspend fun ServerApi.saveFocus(session: FocusSession): FocusSession = session.id?.let { id ->
    put("/api/neuro/focus/${enc(id)}", session, FocusSession.serializer())
} ?: post("/api/neuro/focus", session, FocusSession.serializer())

suspend fun ServerApi.deleteFocus(id: String) = delete("/api/neuro/focus/${enc(id)}")

// ---- Routines ----

suspend fun ServerApi.routines(): List<Routine> = get("${base()}/api/neuro/routines", ListSerializer(Routine.serializer()))

suspend fun ServerApi.saveRoutine(routine: Routine): Routine = routine.id?.let { id ->
    put("/api/neuro/routines/${enc(id)}", routine, Routine.serializer())
} ?: post("/api/neuro/routines", routine, Routine.serializer())

suspend fun ServerApi.deleteRoutine(id: String) = delete("/api/neuro/routines/${enc(id)}")

suspend fun ServerApi.routineRuns(days: Int = 14): List<RoutineRun> =
    get("${base()}/api/neuro/routines/runs?days=$days", ListSerializer(RoutineRun.serializer()))

/** Sets which steps of a routine are done on a day (none clears it). */
suspend fun ServerApi.saveRoutineRun(run: RoutineRun): RoutineRun =
    put("/api/neuro/routines/${enc(run.routineId)}/runs/${enc(run.date)}", run, RoutineRun.serializer())

// ---- Communication cards ----

suspend fun ServerApi.communicationCards(): List<CommunicationCard> =
    get("${base()}/api/neuro/cards", ListSerializer(CommunicationCard.serializer()))

suspend fun ServerApi.saveCommunicationCards(cards: List<CommunicationCard>): List<CommunicationCard> =
    put("/api/neuro/cards", cards, ListSerializer(CommunicationCard.serializer()))

// ---- Patterns ----

suspend fun ServerApi.neuroSummary(days: Int = 30): NeuroSummary = get("${base()}/api/neuro/summary?days=$days", NeuroSummary.serializer())

// ---- The AI assistant's "break it into steps" (only where it's on and the person has said yes) ----
// Named apart from the assistant's own calls, which live with the assistant.

/** Whether the assistant is on, and whether this person has said yes to it. */
suspend fun ServerApi.breakdownStatus(): AiStatus = get("${base()}/api/ai/status", AiStatus.serializer())

/** A task in, small steps out: only the task's words (and [detail]) are sent, no health data. */
suspend fun ServerApi.breakIntoSteps(task: String, detail: String? = null): AiBreakdownReply = send(
    "POST", "${base()}/api/ai/breakdown",
    ApiJson.encodeToString(AiBreakdownRequest.serializer(), AiBreakdownRequest(task, detail)),
    AiBreakdownReply.serializer(),
)
