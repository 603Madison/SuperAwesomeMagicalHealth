package com.health.server.food

import com.health.api.ApiJson
import com.health.api.Food
import com.health.server.db.Database
import kotlinx.serialization.builtins.ListSerializer

/**
 * The food databases' answers, kept a week in `food_lookups` so the same search or barcode isn't
 * asked for twice. Each source has its own key prefix ("search3:", "usda1:…"). Only answers are
 * kept: a request that fails throws, and nothing is saved for it.
 */
class FoodLookups(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /**
     * The answer kept under [key] in the last week, or [fetch]ed and kept. With [briefMisses] (a
     * barcode), finding nothing is kept only a day, as a new product may soon be added.
     */
    suspend fun cached(key: String, briefMisses: Boolean = false, fetch: suspend () -> List<Food>): List<Food> {
        val now = clock()
        val kept = db.tx {
            one("SELECT data, fetched_at FROM food_lookups WHERE key = ?", key) { it.string("data") to it.long("fetched_at") }
        }
        if (kept != null && now - kept.second < KEEP_MS) {
            runCatching { ApiJson.decodeFromString(FoodList, kept.first) }.getOrNull()
                ?.takeIf { it.isNotEmpty() || !briefMisses || now - kept.second < DAY_MS }
                ?.let { return it }
        }
        val fresh = fetch()
        db.tx {
            update("INSERT OR REPLACE INTO food_lookups (key, data, fetched_at) VALUES (?, ?, ?)", key, ApiJson.encodeToString(FoodList, fresh), now)
            // Keep the table small: lookups older than a month go.
            update("DELETE FROM food_lookups WHERE fetched_at < ?", now - 30 * DAY_MS)
        }
        return fresh
    }

    private companion object {
        const val DAY_MS = 24L * 3600 * 1000
        const val KEEP_MS = 7 * DAY_MS
        val FoodList = ListSerializer(Food.serializer())
    }
}
