package com.health.server.ai

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaTool
import com.health.api.Module
import com.health.server.mcp.McpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Which of the AI connector's tools the assistant may use, and for which feature. A tool that
 * isn't listed here is never offered, so a new tool stays out of the assistant until it's added
 * with the feature it reads. Admin and troubleshooting tools (the server's log, webhooks,
 * notifications) are never listed; nor is anything with photos.
 */
object AiTools {
    /** Tool to the feature it reads or logs to; null for the account itself (its devices, profile, syncs). */
    val MODULES: Map<String, Module?> = mapOf(
        "status" to null,
        "devices" to null,
        "sync_check" to null,
        "profile" to null,
        "sleep" to Module.SLEEP,
        "workouts" to Module.ACTIVITY,
        "stress" to Module.ACTIVITY,
        "food_log" to Module.NUTRITION,
        "search_foods" to Module.NUTRITION,
        "drinks" to Module.NUTRITION,
        "mind" to Module.MIND,
        "journal" to Module.MIND,
        "cycle" to Module.CYCLE,
        "life_stage" to Module.CYCLE,
        "continence" to Module.CONTINENCE,
        "medications" to Module.MEDICATIONS,
        "vitals" to Module.VITALS,
        "habits" to Module.HABITS,
        "symptoms" to Module.SYMPTOMS,
        "hormones" to Module.HORMONES,
        "appointments" to Module.APPOINTMENTS,
        "log_food" to Module.NUTRITION,
        "log_water" to Module.NUTRITION,
        "log_drink" to Module.NUTRITION,
        "log_weight" to Module.BODY,
        "log_workout" to Module.ACTIVITY,
        "log_mood" to Module.MIND,
        "check_in" to Module.MIND,
        "log_cycle_day" to Module.CYCLE,
        "log_hot_flash" to Module.CYCLE,
        "log_continence" to Module.CONTINENCE,
        "log_dose" to Module.MEDICATIONS,
        "log_blood_pressure" to Module.VITALS,
        "log_glucose" to Module.VITALS,
        "log_symptom" to Module.SYMPTOMS,
        "tick_habit" to Module.HABITS,
    )

    /** Run the assistant's own way: a day and trends limited to the features allowed, readings by their metric's feature. */
    const val DAY = "day"
    const val TRENDS = "trends"
    const val READINGS = "readings"

    /** Never, whatever the person allows. */
    val NEVER: Set<Module> = setOf(Module.PHOTOS)

    /** The tools for a person who allowed [modules], in a fixed order (so the request's prefix caches). */
    fun allowed(tools: List<McpServer.AssistantTool>, modules: Set<Module>): List<McpServer.AssistantTool> = tools.filter { t ->
        when (t.name) {
            DAY, TRENDS -> true
            READINGS -> modules.any { !it.sensitive }
            else -> MODULES.containsKey(t.name) && MODULES[t.name].let { it == null || (it in modules && it !in NEVER) }
        }
    }.sortedBy { it.name }

    /** The feature a tool belongs to, for what the assistant looked at. */
    fun moduleOf(name: String): Module? = MODULES[name]

    /** A connector tool as an Anthropic tool definition, without the connector's "account" choice (it's always the person's own). */
    fun definition(tool: McpServer.AssistantTool): BetaTool {
        val properties = (tool.inputSchema["properties"] as? JsonObject).orEmpty().filterKeys { it != "account" }
        val required = (tool.inputSchema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it != "account" }
        return BetaTool.builder()
            .name(tool.name)
            .description(tool.description)
            .inputSchema(
                BetaTool.InputSchema.builder()
                    .properties(
                        BetaTool.InputSchema.Properties.builder()
                            .putAllAdditionalProperties(properties.mapValues { (_, schema) -> JsonValue.from(plain(schema)) })
                            .build(),
                    )
                    .required(required)
                    .build(),
            )
            .build()
    }

    /** kotlinx JSON to plain maps, lists and values (what the SDK's JsonValue.from takes). */
    fun plain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.mapValues { (_, v) -> plain(v) }
        is JsonArray -> element.map { plain(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.doubleOrNull ?: element.content
        }
    }

    /** Plain maps, lists and values (from the SDK) back to kotlinx JSON. */
    fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to json(v) })
        is Iterable<*> -> JsonArray(value.map { json(it) })
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    /** One line for what the assistant looked at: "Sleep, the last 14 days". */
    fun summary(name: String, args: JsonObject, writes: Boolean): String {
        val label = name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        val parts = buildList {
            (args["days"] as? JsonPrimitive)?.contentOrNull?.let { add("the last $it days") }
            (args["date"] as? JsonPrimitive)?.contentOrNull?.let { add(it) }
            (args["metric"] as? JsonPrimitive)?.contentOrNull?.let { add(it.lowercase().replace('_', ' ')) }
        }
        return (if (writes) "Logged: " else "") + label + if (parts.isEmpty()) "" else ", " + parts.joinToString(", ")
    }
}
