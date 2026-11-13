package com.health.app.ui.ai

import com.health.api.AiConsent
import com.health.api.AiLook
import com.health.api.AiMessage
import com.health.api.AiStatus
import com.health.api.Module
import com.health.app.net.ServerException

/** One turn of a conversation, as the phone keeps it (in memory only): who said it, and what was looked at for a reply. */
data class ChatTurn(val role: String, val text: String, val looked: List<AiLook> = emptyList()) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/**
 * The assistant's words and choices in plain Kotlin: what may be offered and what's chosen to
 * start, when the choice can be saved, how usage, what was looked at and errors are said.
 */
object AiText {
    /** Never offered: photos stay out, whatever is chosen. */
    val NEVER = setOf(Module.PHOTOS)

    /** The features to offer: those the person has on, less [NEVER], in the usual order. */
    fun offered(on: Set<Module>): List<Module> = Module.entries.filter { it in on && it !in NEVER }

    /** What's ticked to start: the person's earlier choice, else the everyday features. Private ones are never ticked for them. */
    fun initial(consent: AiConsent?, on: Set<Module>): Set<Module> =
        consent?.modules?.filter { it !in NEVER }?.toSet() ?: offered(on).filter { !it.sensitive }.toSet()

    /** Whether the choice can be saved: the warning read, and its own tick for any private feature. */
    fun canAgree(acknowledged: Boolean, modules: Set<Module>, sensitiveAcknowledged: Boolean): Boolean =
        acknowledged && (modules.none { it.sensitive } || sensitiveAcknowledged)

    /** The choice as it's sent: logging only where the admin allows it, and photos never. */
    fun consent(modules: Set<Module>, acknowledged: Boolean, sensitiveAcknowledged: Boolean, allowLogging: Boolean, status: AiStatus): AiConsent {
        val allowed = modules - NEVER
        return AiConsent(
            modules = allowed,
            allowLogging = allowLogging && status.loggingAllowed,
            acknowledged = acknowledged,
            sensitiveAcknowledged = sensitiveAcknowledged && allowed.any { it.sensitive },
        )
    }

    /** "3 of 40 questions today". */
    fun usage(used: Int, limit: Int): String = if (limit <= 0) "$used ${if (used == 1) "question" else "questions"} today" else "$used of $limit questions today"

    fun atLimit(status: AiStatus): Boolean = status.dailyLimit > 0 && status.usedToday >= status.dailyLimit

    /** "Looked at: your sleep, last 7 days · your diary" under a reply. */
    fun looked(looks: List<AiLook>): String {
        val parts = looks.map { it.summary.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (parts.isEmpty()) "Looked at: nothing of your data, only the conversation" else "Looked at: " + parts.joinToString(" · ")
    }

    /** The conversation as it's sent with each question. */
    fun messages(turns: List<ChatTurn>): List<AiMessage> = turns.map { AiMessage(it.role, it.text) }

    /** A failed question, in plain words. */
    fun error(e: Throwable): String {
        val code = (e as? ServerException)?.code
        val message = e.message?.takeIf { it.isNotBlank() }
        return when (code) {
            0 -> "Can't reach your server right now. Check your connection and try again."
            409 -> "The assistant isn't turned on on this server."
            429 -> message ?: "That's all the questions for today. The assistant is back tomorrow."
            403 -> message ?: "Turn on the assistant for yourself first."
            in 500..599 -> message ?: "The assistant couldn't answer just now. Try again in a minute."
            null -> "Something went wrong. Try again in a moment."
            else -> message ?: "The assistant couldn't answer that. Try again in a moment."
        }
    }

    /** A few questions to start with, from what it may look at. */
    fun suggestions(modules: Set<Module>): List<String> = buildList {
        if (Module.SLEEP in modules) add("How has my sleep been this week?")
        if (Module.ACTIVITY in modules) add("Am I moving more or less than last month?")
        if (Module.CONTINENCE in modules) add("Are my leaks getting better or worse?")
        if (Module.CYCLE in modules) add("How regular have my cycles been?")
        if (Module.MIND in modules) add("What seems to go with my better days?")
        if (Module.MEDICATIONS in modules) add("Have I missed any doses lately?")
        add("What stands out from my last week?")
    }.take(4)
}
