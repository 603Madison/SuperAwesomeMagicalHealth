package com.health.app.net

import com.health.api.AiAuditEntry
import com.health.api.AiChatReply
import com.health.api.AiChatRequest
import com.health.api.AiConsent
import com.health.api.AiStatus
import com.health.api.ApiJson
import com.health.api.FoodPhotoEstimate
import com.health.api.Meal
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/*
 * The optional AI assistant (docs/API.md, "The AI assistant"): whether it's on, the person's own
 * choice, questions (the conversation stays on the phone and goes with each one) and the log.
 */

suspend fun ServerApi.aiStatus(): AiStatus = get("${base()}/api/ai/status", AiStatus.serializer())

suspend fun ServerApi.saveAiConsent(consent: AiConsent): AiConsent =
    send("PUT", "${base()}/api/ai/consent", ApiJson.encodeToString(AiConsent.serializer(), consent), AiConsent.serializer())

/** Stops the assistant for this person and forgets their choice. */
suspend fun ServerApi.deleteAiConsent() = send("DELETE", "${base()}/api/ai/consent", null, Unit.serializer())

suspend fun ServerApi.aiChat(request: AiChatRequest): AiChatReply = send(
    "POST", "${base()}/api/ai/chat", ApiJson.encodeToString(AiChatRequest.serializer(), request), AiChatReply.serializer(),
)

suspend fun ServerApi.aiAudit(days: Int = 30): List<AiAuditEntry> = get("${base()}/api/ai/audit?days=$days", ListSerializer(AiAuditEntry.serializer()))

/** The parts of a food photo: the picture (already shrunk and stripped of its details) as `photo`, the meal and a note. */
internal fun foodPhotoParts(jpeg: ByteArray, meal: Meal?, note: String?): List<ServerApi.Part> = listOfNotNull(
    meal?.let { ServerApi.Part("meal", text = it.name) },
    note?.trim()?.takeIf { it.isNotEmpty() }?.let { ServerApi.Part("note", text = it.take(200)) },
    ServerApi.Part("photo", bytes = jpeg, fileName = "meal.jpg", type = "image/jpeg"),
)

/** A photo of a meal, estimated by the assistant (never kept by the server). Can take a while. */
suspend fun ServerApi.foodPhoto(jpeg: ByteArray, meal: Meal?, note: String?): FoodPhotoEstimate =
    multipart("/api/ai/food-photo", foodPhotoParts(jpeg, meal, note), FoodPhotoEstimate.serializer(), readTimeoutSeconds = 180)
