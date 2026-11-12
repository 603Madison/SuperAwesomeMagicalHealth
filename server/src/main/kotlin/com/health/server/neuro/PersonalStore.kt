package com.health.server.neuro

import com.health.api.ApiJson
import com.health.server.db.Database
import com.health.server.util.SecretBox
import kotlinx.serialization.KSerializer

/**
 * One person's settings-like things (the Autism & ADHD profile, routines, cards; the transition
 * profile), each sealed in `user_settings` under its own key, so a copy of the database alone
 * gives none of it away.
 */
class PersonalStore(private val db: Database, private val box: SecretBox, private val clock: () -> Long = System::currentTimeMillis) {
    private fun context(userId: String, key: String) = "settings:$userId:$key"

    suspend fun <T> get(userId: String, key: String, serializer: KSerializer<T>): T? {
        val sealed = db.tx { one("SELECT data FROM user_settings WHERE user_id = ? AND key = ?", userId, key) { it.string("data") } } ?: return null
        return runCatching { ApiJson.decodeFromString(serializer, box.open(sealed, context(userId, key))) }.getOrNull()
    }

    suspend fun <T> put(userId: String, key: String, value: T, serializer: KSerializer<T>): T {
        val sealed = box.seal(ApiJson.encodeToString(serializer, value), context(userId, key))
        db.tx { update("INSERT OR REPLACE INTO user_settings (user_id, key, data, updated_at) VALUES (?, ?, ?, ?)", userId, key, sealed, clock()) }
        return value
    }
}
