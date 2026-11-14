package com.health.api

import kotlinx.serialization.Serializable

/*
 * The optional AI assistant. Off unless the server's admin turns it on with an Anthropic API key,
 * and then only for each person who opts in after reading what it means: what the assistant looks
 * at is sent to Anthropic, a company outside this server. It reads only the features the person
 * allows (sensitive ones are never pre-selected), never progress photos, and logs what it looked
 * at. A photo of food goes only when the person snaps one to estimate a meal (FoodPhotoEstimate).
 */

/** The admin's settings (GET/PUT /api/admin/ai). The key is set separately and never returned. */
@Serializable
data class AiSettings(
    val enabled: Boolean = false,
    /** An Anthropic model id. */
    val model: String = "claude-opus-5-5",
    /** "low", "medium", "high": how hard it thinks (and what it costs). */
    val effort: String = "medium",
    /** Questions a person may ask a day. */
    val dailyLimit: Int = 40,
    /** Whether people may let it log entries for them (each person still chooses). */
    val allowLogging: Boolean = false,
    /** When the admin acknowledged the warning about sending data to Anthropic. */
    val acknowledgedAt: Long? = null,
    /** Whether a key is set (read-only). */
    val hasKey: Boolean = false,
)

@Serializable
data class AiKey(val apiKey: String)

/** A person's choice (GET/PUT/DELETE /api/ai/consent). */
@Serializable
data class AiConsent(
    /** The features the assistant may look at. Sensitive ones need [sensitiveAcknowledged]. */
    @Serializable(with = ModuleSetSerializer::class) val modules: Set<Module> = emptySet(),
    val allowLogging: Boolean = false,
    /** Ticked by the person after reading the warning; consent lapses if the admin changes the model's provider. */
    val acknowledged: Boolean = false,
    val sensitiveAcknowledged: Boolean = false,
    val since: Long? = null,
)

@Serializable
data class AiStatus(
    /** Turned on by the admin, with a key. */
    val available: Boolean,
    val provider: String = "Anthropic",
    val model: String? = null,
    val consent: AiConsent? = null,
    val usedToday: Int = 0,
    val dailyLimit: Int = 0,
    /** Whether the admin lets people allow it to log entries for them (each person still chooses). */
    val loggingAllowed: Boolean = false,
    /** Where data goes, said plainly, and the links to the terms. */
    val dataNotice: String = "",
    val termsUrl: String = "https://www.anthropic.com/legal/commercial-terms",
    val privacyUrl: String = "https://www.anthropic.com/legal/privacy",
)

@Serializable
data class AiMessage(val role: String, val text: String)

/** POST /api/ai/chat: the conversation so far (kept on the person's device, not the server). */
@Serializable
data class AiChatRequest(val messages: List<AiMessage>)

/** What the assistant looked at for a reply: shown under it, and kept in the audit log. */
@Serializable
data class AiLook(val tool: String, val module: Module? = null, val summary: String)

@Serializable
data class AiChatReply(val text: String, val looked: List<AiLook> = emptyList(), @Serializable(with = ModuleSetSerializer::class) val modules: Set<Module> = emptySet(), val usedToday: Int = 0)

/** POST /api/ai/breakdown: a task in, small steps out (sends only the task's text, no health data). */
@Serializable
data class AiBreakdownRequest(val task: String, val detail: String? = null)

@Serializable
data class AiBreakdownReply(val steps: List<RoutineStep>)

/** One request, as logged (GET /api/ai/audit?days=30). */
@Serializable
data class AiAuditEntry(val at: Long, val kind: String, @Serializable(with = ModuleSetSerializer::class) val modules: Set<Module>, val tools: List<String>, val model: String, val inputTokens: Int = 0, val outputTokens: Int = 0)

/** How close a food photo's estimate is likely to be. */
@Serializable
enum class EstimateConfidence(val label: String) { LOW("A rough guess"), MEDIUM("A fair guess"), HIGH("A close guess") }

/**
 * One food or drink seen in a photo: its portion ([grams], or millilitres when [unit] is "ml") and
 * what that portion holds, all estimated. [match] is one of the person's own foods with a similar
 * name, to log instead of the estimate.
 */
@Serializable
data class FoodPhotoItem(
    val name: String,
    val grams: Double,
    val nutrients: Nutrients,
    val confidence: EstimateConfidence = EstimateConfidence.MEDIUM,
    /** What it assumed, in a line: "assumed cooked weight", "dressing not visible". */
    val assumption: String = "",
    val match: Food? = null,
    /** "g", or "ml" for a drink. */
    val unit: String = "g",
)

/**
 * POST /api/ai/food-photo (multipart: `photo`, and optional `meal` and `note`): what's in a photo
 * of a meal, estimated, to check and change before logging it. The photo goes to Anthropic for
 * this and isn't kept. [refused] says it couldn't make the photo out ([notes] says what to try).
 */
@Serializable
data class FoodPhotoEstimate(
    val items: List<FoodPhotoItem> = emptyList(),
    val notes: String = "",
    val refused: Boolean = false,
    val usedToday: Int = 0,
    /**
     * Eating-disorder recovery: the apps show the foods and portions without calorie or macro
     * numbers. The numbers are still here, so logging works as usual.
     */
    val hideNumbers: Boolean = false,
)
