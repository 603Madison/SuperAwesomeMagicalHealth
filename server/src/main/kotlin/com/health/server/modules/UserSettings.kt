package com.health.server.modules

import com.health.api.ApiJson
import com.health.server.db.Database
import com.health.server.util.SecretBox
import kotlinx.serialization.KSerializer

/**
 * Each person's own settings (their conditions, how a feature's diary works for them), one sealed
 * row per key: like the diary itself, they say a lot about someone, so they're encrypted.
 */
class UserSettingsStore(private val db: Database, private val box: SecretBox, private val clock: () -> Long = System::currentTimeMillis) {
    private fun context(userId: String, key: String) = "settings:$userId:$key"

    suspend fun <T> get(userId: String, key: String, serializer: KSerializer<T>): T? {
        val data = db.tx { one("SELECT data FROM user_settings WHERE user_id = ? AND key = ?", userId, key) { it.string("data") } } ?: return null
        return runCatching { ApiJson.decodeFromString(serializer, box.open(data, context(userId, key))) }.getOrNull()
    }

    suspend fun <T> put(userId: String, key: String, value: T, serializer: KSerializer<T>): T {
        val sealed = box.seal(ApiJson.encodeToString(serializer, value), context(userId, key))
        db.tx { update("INSERT OR REPLACE INTO user_settings (user_id, key, data, updated_at) VALUES (?, ?, ?, ?)", userId, key, sealed, clock()) }
        return value
    }

    suspend fun delete(userId: String, key: String) {
        db.tx { update("DELETE FROM user_settings WHERE user_id = ? AND key = ?", userId, key) }
    }
}
