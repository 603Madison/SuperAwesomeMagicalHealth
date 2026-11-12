package com.health.server.ai

import com.health.api.AiAuditEntry
import com.health.api.AiConsent
import com.health.api.AiSettings
import com.health.api.ApiJson
import com.health.server.db.Database
import com.health.server.util.SecretBox
import kotlinx.serialization.Serializable

/**
 * Where the assistant keeps its state: the admin's settings (the `settings` table, key "ai"), the
 * Anthropic key (sealed, key "ai.key"), each person's consent (sealed, in `user_settings`), and
 * the audit log (sealed rows in `ai_audit`: what kind of request, which features and tools, the
 * model and token counts; never what was asked or answered).
 */
class AiStore(private val db: Database, private val box: SecretBox, private val clock: () -> Long) {
    @Volatile
    private var cached: AiSettings? = null

    suspend fun settings(): AiSettings = cached ?: db.tx {
        one("SELECT value FROM settings WHERE key = 'ai'") { it.string("value") }
    }?.let { runCatching { ApiJson.decodeFromString(AiSettings.serializer(), it) }.getOrNull() }.let { it ?: AiSettings() }.also { cached = it }

    suspend fun saveSettings(settings: AiSettings): AiSettings {
        val stored = settings.copy(hasKey = false)
        db.tx { update("INSERT OR REPLACE INTO settings (key, value) VALUES ('ai', ?)", ApiJson.encodeToString(AiSettings.serializer(), stored)) }
        cached = stored
        return stored
    }

    suspend fun key(): String = db.tx {
        one("SELECT value FROM settings WHERE key = 'ai.key'") { it.string("value") }
    }?.let { runCatching { box.open(it, "ai.key") }.getOrNull() }.orEmpty()

    suspend fun setKey(key: String) = db.tx {
        if (key.isEmpty()) update("DELETE FROM settings WHERE key = 'ai.key'")
        else update("INSERT OR REPLACE INTO settings (key, value) VALUES ('ai.key', ?)", box.seal(key, "ai.key"))
    }

    /** A person's consent, with the provider it was given for: consent lapses if that changes. */
    @Serializable
    data class StoredConsent(val consent: AiConsent, val provider: String)

    suspend fun consent(userId: String): StoredConsent? = db.tx {
        one("SELECT data FROM user_settings WHERE user_id = ? AND key = 'ai.consent'", userId) { it.string("data") }
    }?.let { sealed ->
        runCatching { ApiJson.decodeFromString(StoredConsent.serializer(), box.open(sealed, consentContext(userId))) }.getOrNull()
    }

    suspend fun saveConsent(userId: String, consent: StoredConsent) = db.tx {
        update(
            "INSERT OR REPLACE INTO user_settings (user_id, key, data, updated_at) VALUES (?, 'ai.consent', ?, ?)",
            userId, box.seal(ApiJson.encodeToString(StoredConsent.serializer(), consent), consentContext(userId)), clock(),
        )
    }

    suspend fun deleteConsent(userId: String) = db.tx { update("DELETE FROM user_settings WHERE user_id = ? AND key = 'ai.consent'", userId) }

    suspend fun audit(userId: String, entry: AiAuditEntry) = db.tx {
        update(
            "INSERT INTO ai_audit (user_id, at, data) VALUES (?, ?, ?)",
            userId, entry.at, box.seal(ApiJson.encodeToString(AiAuditEntry.serializer(), entry), auditContext(userId)),
        )
    }

    /** Requests since [from] (epoch ms): for the daily limit. */
    suspend fun countSince(userId: String, from: Long): Int = db.tx {
        long("SELECT COUNT(*) FROM ai_audit WHERE user_id = ? AND at >= ?", userId, from).toInt()
    }

    suspend fun auditSince(userId: String, from: Long): List<AiAuditEntry> = db.tx {
        query("SELECT data FROM ai_audit WHERE user_id = ? AND at >= ? ORDER BY at DESC", userId, from) { it.string("data") }
    }.mapNotNull { sealed ->
        runCatching { ApiJson.decodeFromString(AiAuditEntry.serializer(), box.open(sealed, auditContext(userId))) }.getOrNull()
    }

    private fun consentContext(userId: String) = "user:$userId:ai.consent"
    private fun auditContext(userId: String) = "ai_audit:$userId"
}
