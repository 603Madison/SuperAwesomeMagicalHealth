package com.health.server.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import com.health.api.AiAuditEntry
import com.health.api.AiBreakdownReply
import com.health.api.AiBreakdownRequest
import com.health.api.AiChatReply
import com.health.api.AiChatRequest
import com.health.api.AiConsent
import com.health.api.AiLook
import com.health.api.AiSettings
import com.health.api.AiStatus
import com.health.api.Metric
import com.health.api.Module
import com.health.api.RoutineStep
import com.health.api.User
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.care.SharedView
import com.health.server.mcp.McpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.format.TextStyle
import java.util.Locale

/**
 * The optional AI assistant: off until the admin turns it on with an Anthropic key, and then only
 * for people who opt in. It answers from the person's own data by calling the AI connector's tools
 * (through [AiTools]' allowlist), limited to the features they allowed; it never sees photos or
 * other accounts. Conversations aren't kept; each request is logged (kind, features, tools, model,
 * tokens) without its text.
 */
class AiService(private val services: Services) {
    private val log = LoggerFactory.getLogger("samh.ai")
    val store = AiStore(services.db, services.box, services.clock)
    private val mcp = McpServer(services)

    /** Where Anthropic is (a test server in tests), and how often the SDK retries. */
    @Volatile var baseUrl: String? = null
    @Volatile var maxRetries: Int = 2

    @Volatile private var client: Pair<String, AnthropicClient>? = null

    private fun client(key: String): AnthropicClient {
        val id = "$key|${baseUrl.orEmpty()}|$maxRetries"
        client?.let { (current, made) -> if (current == id) return made }
        val made = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .apply { baseUrl?.let { baseUrl(it) } }
            .maxRetries(maxRetries)
            .timeout(Duration.ofMinutes(3))
            .build()
        client?.second?.let { runCatching { it.close() } }
        client = id to made
        return made
    }

    // ---------------------------------------------------------------- Settings (admin)

    suspend fun settings(): AiSettings = store.settings().copy(hasKey = store.key().isNotEmpty())

    suspend fun saveSettings(change: AiSettings): AiSettings {
        val current = store.settings()
        if (change.model.isBlank() || !MODEL_ID.matches(change.model)) throw ApiError.badRequest("That doesn't look like an Anthropic model id")
        if (change.effort !in EFFORTS) throw ApiError.badRequest("Effort is one of ${EFFORTS.joinToString()}")
        if (change.dailyLimit !in 1..1000) throw ApiError.badRequest("The daily limit is 1 to 1000 questions")
        // Turning it on takes the admin's acknowledgement of where data goes, in the same request.
        val acknowledged = change.acknowledgedAt?.takeIf { it > 0 }
        if (change.enabled && !current.enabled && acknowledged == null) {
            throw ApiError.badRequest("Turning on the assistant needs your acknowledgement that what it looks at is sent to Anthropic")
        }
        return store.saveSettings(change.copy(acknowledgedAt = acknowledged ?: current.acknowledgedAt)).copy(hasKey = store.key().isNotEmpty())
    }

    suspend fun setKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isNotEmpty() && (trimmed.length < 20 || trimmed.any { it.isWhitespace() })) throw ApiError.badRequest("That doesn't look like an API key")
        store.setKey(trimmed)
        client = null
    }

    /** A tiny request, to check the key and model work. */
    suspend fun test(): AiTestResult {
        val key = store.key().ifEmpty { return AiTestResult(false, "No API key is set yet.") }
        val settings = store.settings()
        return try {
            val response = call(key) {
                base(settings.model, 1024L)
                    .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                    .addUserMessage("Reply with the single word: ready")
                    .build()
            }
            AiTestResult(true, "Anthropic answered (${response.model().asString()}).")
        } catch (e: ApiError) {
            AiTestResult(false, e.message ?: "It didn't work")
        }
    }

    // ---------------------------------------------------------------- A person's status and consent

    suspend fun status(user: User): AiStatus {
        val settings = store.settings()
        val available = settings.enabled && store.key().isNotEmpty()
        return AiStatus(
            available = available,
            provider = PROVIDER,
            model = settings.model.takeIf { available },
            consent = consentOf(user.id),
            usedToday = usedToday(user.id),
            dailyLimit = settings.dailyLimit,
            loggingAllowed = available && settings.allowLogging,
            dataNotice = DATA_NOTICE,
        )
    }

    private suspend fun consentOf(userId: String): AiConsent? = store.consent(userId)?.takeIf { it.provider == PROVIDER && it.consent.acknowledged }?.consent

    suspend fun saveConsent(user: User, consent: AiConsent): AiConsent {
        if (!consent.acknowledged) throw ApiError.badRequest("Read what the assistant sends to Anthropic, and tick that you understand, first")
        val modules = consent.modules - AiTools.NEVER
        if (modules.any { it.sensitive } && !consent.sensitiveAcknowledged) {
            throw ApiError.badRequest("Letting it see ${modules.filter { it.sensitive }.joinToString { it.label }} needs its own tick: these are private")
        }
        val settings = store.settings()
        if (consent.allowLogging && !settings.allowLogging) throw ApiError.badRequest("Your server's admin hasn't allowed the assistant to log entries")
        val saved = consent.copy(modules = modules, since = services.clock())
        store.saveConsent(user.id, AiStore.StoredConsent(saved, PROVIDER))
        return saved
    }

    suspend fun deleteConsent(user: User) = store.deleteConsent(user.id)

    suspend fun audit(user: User, days: Int): List<com.health.api.AiAuditEntry> {
        val zone = services.profiles.days(user.id)
        val from = zone.start(zone.today(services.clock()).minusDays((days - 1).toLong()))
        return store.auditSince(user.id, from)
    }

    private suspend fun usedToday(userId: String): Int {
        val zone = services.profiles.days(userId)
        return store.countSince(userId, zone.start(zone.today(services.clock())))
    }

    /** Everything a request needs: on, keyed, consented, and under the day's limit. */
    private suspend fun ready(user: User): Ready {
        val settings = store.settings()
        val key = store.key()
        if (!settings.enabled || key.isEmpty()) throw ApiError.conflict("The assistant isn't turned on on this server")
        val consent = consentOf(user.id) ?: throw ApiError.forbidden("Turn on the assistant for yourself first, in Settings › AI assistant")
        val used = usedToday(user.id)
        if (used >= settings.dailyLimit) throw ApiError.tooMany("That's today's ${settings.dailyLimit} questions; the assistant is back tomorrow")
        return Ready(settings, key, consent, used)
    }

    private class Ready(val settings: AiSettings, val key: String, val consent: AiConsent, val used: Int)

    // ---------------------------------------------------------------- Chat

    suspend fun chat(user: User, request: AiChatRequest): AiChatReply {
        val ready = ready(user)
        val history = conversation(request)
        val modules = ready.consent.modules - AiTools.NEVER
        val caller = McpServer.Caller(
            user = user,
            everyone = false,
            canLog = ready.settings.allowLogging && ready.consent.allowLogging,
            scope = modules.filter { it.sensitive }.toSet(),
            // Water turned off: log_water isn't offered, and the day and trends leave water out.
            water = services.days.showsWater(user.id),
        )
        val available = AiTools.allowed(mcp.assistantTools(caller), modules)
        val tools = available.map(AiTools::definition)
        val system = systemBlocks(user, modules, caller.canLog)
        val looked = mutableListOf<AiLook>()
        var input = 0L
        var output = 0L
        var model = ready.settings.model
        var reply: String? = null

        for (round in 1..MAX_ROUNDS) {
            val response = call(ready.key) {
                base(ready.settings.model, 16_000L)
                    .systemOfBetaTextBlockParams(system)
                    .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(ready.settings.effort)).build())
                    // Caches the growing conversation through the tool rounds, after the system prompt's own breakpoint.
                    .cacheControl(BetaCacheControlEphemeral.builder().build())
                    .apply { tools.forEach { addTool(it) } }
                    .apply { history.forEach { it.addTo(this) } }
                    .build()
            }
            input += response.usage().inputTokens()
            output += response.usage().outputTokens()
            model = response.model().asString()
            val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()
            val stop = response.stopReason().orElse(null)
            when (stop) {
                BetaStopReason.REFUSAL -> {
                    reply = "I can't help with that one. If something feels urgent, please contact your doctor or local emergency services."
                    break
                }
                BetaStopReason.TOOL_USE -> {
                    val uses = response.content().mapNotNull { it.toolUse().orElse(null) }
                    if (uses.isEmpty()) {
                        reply = text
                        break
                    }
                    // Every result goes back in one message.
                    val results = uses.map { use ->
                        val args = runCatching { AiTools.json(use._input().convert(Map::class.java)) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
                        val tool = available.firstOrNull { it.name == use.name() }
                        val (result, failed) = if (tool == null) "That tool isn't available." to true else runTool(user, caller, modules, use.name(), args)
                        if (tool != null && !failed) {
                            looked += AiLook(use.name(), AiTools.moduleOf(use.name()) ?: moduleForSpecial(use.name(), args), AiTools.summary(use.name(), args, tool.writes))
                        }
                        BetaContentBlockParam.ofToolResult(
                            BetaToolResultBlockParam.builder().toolUseId(use.id()).content(result.ifBlank { "(nothing)" }).isError(failed).build(),
                        )
                    }
                    history += Step.Reply(response)
                    history += Step.Results(results)
                }
                BetaStopReason.PAUSE_TURN -> history += Step.Reply(response)
                BetaStopReason.MAX_TOKENS -> {
                    reply = (text.ifBlank { "That was a lot to answer at once." }) + "\n\n(I had to stop there: try asking for less at once.)"
                    break
                }
                else -> {
                    reply = text
                    break
                }
            }
        }
        val final = reply?.ifBlank { null } ?: "I looked at quite a lot and couldn't finish. Could you ask something a little narrower?"
        val usedModules = looked.mapNotNull { it.module }.toSet()
        store.audit(user.id, AiAuditEntry(services.clock(), "chat", usedModules, looked.map { it.tool }.distinct(), model, input.toInt(), output.toInt()))
        return AiChatReply(final, looked, usedModules, ready.used + 1)
    }

    /** The conversation from the app: user and assistant turns, starting and ending with the person. */
    private fun conversation(request: AiChatRequest): MutableList<Step> {
        val merged = mutableListOf<Pair<String, String>>()
        request.messages.takeLast(MAX_MESSAGES).forEach { m ->
            val role = m.role.lowercase()
            if (role != "user" && role != "assistant") throw ApiError.badRequest("Messages are from the user or the assistant")
            val text = m.text.trim().take(MAX_MESSAGE_CHARS)
            if (text.isEmpty()) return@forEach
            if (merged.lastOrNull()?.first == role) merged[merged.lastIndex] = role to merged.last().second + "\n\n" + text
            else merged += role to text
        }
        while (merged.firstOrNull()?.first == "assistant") merged.removeAt(0)
        if (merged.isEmpty() || merged.last().first != "user") throw ApiError.badRequest("Ask something first")
        return merged.map { (role, text) -> if (role == "user") Step.User(text) else Step.Assistant(text) }.toMutableList()
    }

    private sealed class Step {
        abstract fun addTo(builder: MessageCreateParams.Builder)
        class User(val text: String) : Step() {
            override fun addTo(builder: MessageCreateParams.Builder) { builder.addUserMessage(text) }
        }
        class Assistant(val text: String) : Step() {
            override fun addTo(builder: MessageCreateParams.Builder) { builder.addAssistantMessage(text) }
        }
        /** The model's own turn, as it came (thinking and tool calls included). */
        class Reply(val message: BetaMessage) : Step() {
            override fun addTo(builder: MessageCreateParams.Builder) { builder.addMessage(message) }
        }
        class Results(val blocks: List<BetaContentBlockParam>) : Step() {
            override fun addTo(builder: MessageCreateParams.Builder) { builder.addUserMessageOfContentBlockParams(blocks) }
        }
    }

    /** Runs one tool for the person: its text, and whether it failed. */
    private suspend fun runTool(user: User, caller: McpServer.Caller, modules: Set<Module>, name: String, args: JsonObject): Pair<String, Boolean> = try {
        val own = JsonObject(args.filterKeys { it != "account" })
        when (name) {
            AiTools.DAY -> {
                val summary = services.days.summary(user.id, (own["date"] as? JsonPrimitive)?.contentOrNull ?: "today")
                mcp.formatDay(SharedView.limit(summary, modules), services.profiles.get(user.id).units, caller.scope, caller.water)
            }
            AiTools.TRENDS -> {
                val zone = services.profiles.days(user.id)
                val to = (own["to"] as? JsonPrimitive)?.contentOrNull?.let { zone.parse(it, services.clock()) }
                val days = (own["days"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 365) ?: 30
                mcp.trendsTable(services.days.trends(user.id, null, to, days).days.map { SharedView.limit(it, modules) }, caller.scope, caller.water)
            }
            AiTools.READINGS -> {
                val metric = (own["metric"] as? JsonPrimitive)?.contentOrNull?.uppercase()?.let { runCatching { Metric.valueOf(it) }.getOrNull() }
                    ?: throw IllegalArgumentException("Which reading? One of ${Metric.entries.joinToString { it.name.lowercase() }}")
                val module = SharedView.moduleOf(metric)
                if (module !in modules) throw IllegalArgumentException("${metric.label} is part of ${module.label}, which the person hasn't let you see")
                mcp.runAssistantTool(caller, name, own)
            }
            AiTools.CONDITIONS -> {
                // Stricter than the connector's: every feature a condition turns on must be allowed, not just its private ones.
                val visible = services.conditions.get(user.id).conditions.filter { c ->
                    com.health.server.conditions.ConditionsService.modulesOf(c).let { m -> m.isNotEmpty() && m.all { it in modules } }
                }
                if (visible.isEmpty()) "None that the person has let you see." else visible.joinToString("\n") { c ->
                    "${c.name}${c.status?.let { " (${it.label.lowercase()})" } ?: ""}${c.since?.let { ", since $it" } ?: ""}${c.notes?.let { ": $it" } ?: ""}"
                }
            }
            else -> mcp.runAssistantTool(caller, name, own)
        } to false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        (e.message ?: "That didn't work") to true
    }

    private fun moduleForSpecial(name: String, args: JsonObject): Module? = when (name) {
        AiTools.READINGS -> (args["metric"] as? JsonPrimitive)?.contentOrNull?.uppercase()
            ?.let { runCatching { Metric.valueOf(it) }.getOrNull() }?.let(SharedView::moduleOf)
        else -> null
    }

    /** The system prompt: the stable part first (cached), then today's details for this person. */
    private suspend fun systemBlocks(user: User, modules: Set<Module>, canLog: Boolean): List<BetaTextBlockParam> {
        val profile = services.profiles.get(user.id)
        val zone = services.profiles.days(user.id)
        val today = zone.today(services.clock())
        val hidden = profile.modules.filter { it !in modules }
        val support = services.settings.get().crisisResources.joinToString("; ") { r -> "${r.name}: ${r.contact}${r.region?.let { " ($it)" } ?: ""}" }
        val details = buildString {
            append("Today is ${today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} $today in the person's time zone (${zone.zone.id}). ")
            append("The person goes by ${user.displayName}. ")
            append("You may look at: ${modules.filter { it !in AiTools.NEVER }.joinToString { it.label }.ifEmpty { "nothing yet beyond their account" }}. ")
            if (hidden.isNotEmpty()) append("They use, but haven't let you see: ${hidden.joinToString { it.label }}. ")
            append(if (canLog) "You may log entries for them when they ask. " else "You can't log entries; tell them where in the app to do it. ")
            append("Their units are ${profile.units.name.lowercase()}. ")
            if (support.isNotBlank()) append("Support lines on this server: $support.")
        }
        return listOf(
            BetaTextBlockParam.builder().text(SYSTEM_PROMPT).cacheControl(BetaCacheControlEphemeral.builder().build()).build(),
            BetaTextBlockParam.builder().text(details).build(),
        )
    }

    // ---------------------------------------------------------------- Task breakdown

    suspend fun breakdown(user: User, request: AiBreakdownRequest): AiBreakdownReply {
        val ready = ready(user)
        val task = request.task.trim().take(500).ifEmpty { throw ApiError.badRequest("What's the task?") }
        val detail = request.detail?.trim()?.take(2000)?.ifEmpty { null }
        val response = call(ready.key) {
            base(ready.settings.model, 4_000L)
                .systemOfBetaTextBlockParams(listOf(BetaTextBlockParam.builder().text(BREAKDOWN_PROMPT).build()))
                .outputConfig(
                    BetaOutputConfig.builder()
                        .effort(BetaOutputConfig.Effort.LOW)
                        .format(BetaJsonOutputFormat.builder().schema(BetaJsonOutputFormat.Schema.builder().putAllAdditionalProperties(STEPS_SCHEMA).build()).build())
                        .build(),
                )
                .addUserMessage("Task: $task" + (detail?.let { "\nMore about it: $it" } ?: ""))
                .build()
        }
        val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
        val steps = when (response.stopReason().orElse(null)) {
            BetaStopReason.REFUSAL -> throw ApiError.badRequest("The assistant couldn't help with that one")
            else -> parseSteps(text)
        }
        store.audit(user.id, AiAuditEntry(services.clock(), "breakdown", emptySet(), emptyList(), response.model().asString(), response.usage().inputTokens().toInt(), response.usage().outputTokens().toInt()))
        if (steps.isEmpty()) throw ApiError.upstream("The assistant didn't come back with steps; try again")
        return AiBreakdownReply(steps)
    }

    private fun parseSteps(text: String): List<RoutineStep> = runCatching {
        Json.parseToJsonElement(text).jsonObject["steps"]!!.jsonArray.map { step ->
            val o = step.jsonObject
            RoutineStep(o["text"]!!.jsonPrimitive.content.trim().take(200), o["minutes"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 240))
        }.filter { it.text.isNotEmpty() }.take(20)
    }.getOrElse {
        text.lines().map { it.trim().trimStart('-', '*', '•', ' ').trim() }.filter { it.isNotEmpty() }.take(20).map { RoutineStep(it.take(200)) }
    }

    // ---------------------------------------------------------------- Calling Anthropic

    /** The request's common start: the model, its output budget, and the refusal fallback where the model has it. */
    private fun base(model: String, maxTokens: Long): MessageCreateParams.Builder = MessageCreateParams.builder()
        .model(model)
        .maxTokens(maxTokens)
        .apply {
            if (model in FALLBACK_MODELS) {
                addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                fallbacks(BetaFallbacksParam.ofDefault())
            }
        }

    private suspend fun call(key: String, params: () -> MessageCreateParams): BetaMessage = try {
        withContext(Dispatchers.IO) { client(key).beta().messages().create(params()) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiError) {
        throw e
    } catch (e: UnauthorizedException) {
        throw ApiError.upstream("Anthropic didn't accept this server's API key: the admin can check it in Admin › AI assistant")
    } catch (e: PermissionDeniedException) {
        throw ApiError.upstream("This server's Anthropic key isn't allowed to use that model: the admin can check it in Admin › AI assistant")
    } catch (e: RateLimitException) {
        throw ApiError.tooMany("Anthropic is limiting this server's requests right now; try again in a minute")
    } catch (e: BadRequestException) {
        log.warn("Anthropic refused a request: {}", e.message)
        throw ApiError.upstream("Anthropic didn't accept the request (${e.message?.take(200) ?: "bad request"})")
    } catch (e: AnthropicServiceException) {
        log.warn("Anthropic answered {}: {}", e.statusCode(), e.message)
        throw ApiError.upstream(if (e.statusCode() == 529 || e.statusCode() >= 500) "Anthropic is busy or unavailable right now; try again shortly" else "Anthropic couldn't answer (${e.statusCode()})")
    } catch (e: AnthropicIoException) {
        log.warn("Couldn't reach Anthropic: {}", e.message)
        throw ApiError.upstream("Couldn't reach Anthropic from this server; try again shortly")
    }

    companion object {
        const val PROVIDER = "Anthropic"
        private const val MAX_ROUNDS = 8
        private const val MAX_MESSAGES = 40
        private const val MAX_MESSAGE_CHARS = 8_000
        private val EFFORTS = setOf("low", "medium", "high", "xhigh", "max")
        private val MODEL_ID = Regex("claude-[a-z0-9.-]{2,60}")

        /** Models that take the server-side refusal fallback ("default" picks the fallback by the refusal's category). */
        private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-opus-5", "claude-fable-5-2", "claude-fable-5-1", "claude-sonnet-5-5")

        const val DATA_NOTICE =
            "When you ask the assistant something, your question, and whatever health data it looks at to answer, is sent to " +
                "Anthropic, a company outside this server, where its Claude model reads it. It only looks at the features you allow " +
                "here, and never at your photos. Anthropic handles what it's sent under its commercial terms and privacy policy, and " +
                "the settings of the Anthropic account your server's admin set up apply to it. Your conversations aren't kept on this " +
                "server; a log of when you asked and which features it looked at is, so you can check. If you'd rather your data didn't " +
                "leave your server, leave this off: everything else works without it."

        private val SYSTEM_PROMPT = """
            You are the health companion inside SuperAwesomeMagicalHealth, an app the person runs on their own server. You help one
            person understand their own health data: whatever they've chosen to let you see.

            How to help:
            - Look before you answer. Use the tools to read the person's data rather than guessing, and say what you looked at when
              it matters. If what they ask about is in a feature you can't see, say so plainly and tell them they can allow it in
              Settings › AI assistant.
            - Speak plainly and kindly, in short paragraphs, in the person's own units. No lectures, no judgement.
            - You are not a doctor. Don't diagnose or prescribe. When something looks worth checking, suggest talking to their
              doctor, nurse, pharmacist or other clinician, and say why.
            - If anything sounds urgent (chest pain, trouble breathing, signs of a stroke, a very high blood pressure reading or a low
              glucose that isn't coming up, or thoughts of harming themselves), say clearly to get help now: their local emergency
              number, and for thoughts of self-harm, a support line from the list you're given.
            - Only log entries when the person asks, and say exactly what you logged.
            - Never assume anyone's gender or what their body is like; describe bodies and experiences neutrally, and use the words
              the person uses for themselves.
            - This is the person's own private data. Don't speculate about anyone else.
        """.trimIndent()

        private val BREAKDOWN_PROMPT = """
            You help someone get started on a task by breaking it into small, concrete steps: each one obvious to begin, a few
            minutes long, in the order they'd do them. Use plain, encouraging words; no more than 12 steps; give each step a rough
            number of minutes. The first step should be tiny, to make starting easy.
        """.trimIndent()

        private val STEPS_SCHEMA: Map<String, JsonValue> = mapOf(
            "type" to JsonValue.from("object"),
            "properties" to JsonValue.from(
                mapOf(
                    "steps" to mapOf(
                        "type" to "array",
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf("text" to mapOf("type" to "string"), "minutes" to mapOf("type" to "integer")),
                            "required" to listOf("text", "minutes"),
                            "additionalProperties" to false,
                        ),
                    ),
                ),
            ),
            "required" to JsonValue.from(listOf("steps")),
            "additionalProperties" to JsonValue.from(false),
        )
    }
}

@Serializable
data class AiTestResult(val ok: Boolean, val message: String)
