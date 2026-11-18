package com.health.server.records

import com.health.api.ApiJson
import com.health.api.RecordItem
import com.health.api.RecordKind
import com.health.api.RecordProvider
import com.health.server.db.Database
import com.health.server.util.Random
import com.health.server.util.SecretBox
import com.health.server.util.hex
import com.health.server.util.sha256
import kotlinx.serialization.Serializable

/**
 * Medical records as kept: each connection's access and what it brought in, sealed (AES-GCM, bound
 * to the person and the row), with only what finding them needs in the clear (provider, kind, day).
 */
class RecordStore(private val db: Database, private val box: SecretBox, private val clock: () -> Long) {
    /** What's sealed for a connection: where it reads from, and its access there. */
    @Serializable
    data class Access(
        val organization: String,
        val fhirBaseUrl: String,
        val tokenEndpoint: String,
        val revokeEndpoint: String? = null,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val expiresAt: Long? = null,
        val patient: String,
        val scope: String? = null,
        val problem: String? = null,
    )

    data class Connection(
        val id: String,
        val userId: String,
        val provider: RecordProvider,
        val createdAt: Long,
        val lastSyncAt: Long?,
        val lastAttemptAt: Long?,
        val access: Access,
    )

    /** A sign-in on its way to the portal and back, found by its `state` (kept only as a hash). */
    @Serializable
    data class Pending(
        val provider: RecordProvider,
        val organization: String,
        val fhirBaseUrl: String,
        val tokenEndpoint: String,
        val revokeEndpoint: String? = null,
        val verifier: String,
        val redirectUri: String,
        val app: Boolean = false,
        /**
         * Once the portal has sent the person back: its code, kept (sealed, under a new single-use
         * token) until the person who started it confirms it from their signed-in app.
         */
        val code: String? = null,
    )

    private fun connectionContext(userId: String, id: String) = "record-connection:$userId:$id"
    private fun itemContext(userId: String, id: String) = "record-item:$userId:$id"
    private fun pendingContext(userId: String, hash: String) = "record-pending:$userId:$hash"

    // ---- Connections

    suspend fun connections(userId: String): List<Connection> = db.tx {
        query("SELECT * FROM record_connections WHERE user_id = ? ORDER BY created_at", userId) { it.toRaw() }
    }.mapNotNull { open(it) }

    suspend fun connection(userId: String, id: String): Connection? = db.tx {
        one("SELECT * FROM record_connections WHERE user_id = ? AND id = ?", userId, id) { it.toRaw() }
    }?.let { open(it) }

    /** Connections with lasting access not tried since [before], for the daily round. */
    suspend fun due(before: Long): List<Connection> = db.tx {
        query("SELECT * FROM record_connections WHERE lasting = 1 AND (last_attempt_at IS NULL OR last_attempt_at < ?)", before) { it.toRaw() }
    }.mapNotNull { open(it) }

    suspend fun insert(userId: String, provider: RecordProvider, access: Access): Connection {
        val id = Random.id()
        val now = clock()
        db.tx {
            update(
                "INSERT INTO record_connections (id, user_id, provider, lasting, created_at, data) VALUES (?, ?, ?, ?, ?, ?)",
                id, userId, provider, access.refreshToken != null, now, seal(access, userId, id),
            )
        }
        return Connection(id, userId, provider, now, null, null, access)
    }

    /** Saves [access] for connection [c]; with [synced], that it was brought up to date then; with [attempted], that it was tried then. */
    suspend fun save(c: Connection, access: Access, synced: Long? = null, attempted: Long? = null) = db.tx {
        update(
            "UPDATE record_connections SET data = ?, lasting = ?, last_sync_at = COALESCE(?, last_sync_at), last_attempt_at = COALESCE(?, last_attempt_at) WHERE id = ? AND user_id = ?",
            seal(access, c.userId, c.id), access.refreshToken != null, synced, attempted, c.id, c.userId,
        )
    }

    suspend fun delete(userId: String, id: String): Boolean = db.tx { update("DELETE FROM record_connections WHERE user_id = ? AND id = ?", userId, id) > 0 }

    /** How many of each kind each of the person's connections brought in. */
    suspend fun counts(userId: String): Map<String, Map<RecordKind, Int>> = db.tx {
        query("SELECT connection_id, kind, COUNT(*) AS n FROM record_items WHERE user_id = ? GROUP BY connection_id, kind", userId) {
            Triple(it.string("connection_id"), it.string("kind"), it.int("n"))
        }
    }.groupBy { it.first }.mapValues { (_, rows) ->
        rows.mapNotNull { (_, kind, n) -> RecordKind.entries.firstOrNull { it.name == kind }?.let { it to n } }.toMap()
    }

    private class Raw(val id: String, val userId: String, val provider: String, val createdAt: Long, val lastSync: Long?, val lastAttempt: Long?, val data: String)

    private fun com.health.server.db.Row.toRaw() = Raw(
        string("id"), string("user_id"), string("provider"), long("created_at"), longOrNull("last_sync_at"), longOrNull("last_attempt_at"), string("data"),
    )

    private fun open(r: Raw): Connection? = runCatching {
        Connection(
            r.id, r.userId, RecordProvider.valueOf(r.provider), r.createdAt, r.lastSync, r.lastAttempt,
            ApiJson.decodeFromString(Access.serializer(), box.open(r.data, connectionContext(r.userId, r.id))),
        )
    }.getOrNull()

    private fun seal(access: Access, userId: String, id: String) = box.seal(ApiJson.encodeToString(Access.serializer(), access), connectionContext(userId, id))

    // ---- Items

    suspend fun items(userId: String, kind: RecordKind? = null, connectionId: String? = null): List<RecordItem> {
        val rows = db.tx {
            query(
                "SELECT id, connection_id, data FROM record_items WHERE user_id = ?" +
                    (if (kind != null) " AND kind = ?" else "") + (if (connectionId != null) " AND connection_id = ?" else ""),
                *listOfNotNull(userId, kind?.name, connectionId).toTypedArray(),
            ) { Triple(it.string("id"), it.string("connection_id"), it.string("data")) }
        }
        return rows.mapNotNull { (id, connection, data) ->
            runCatching { ApiJson.decodeFromString(RecordItem.serializer(), box.open(data, itemContext(userId, id))).copy(id = id, connectionId = connection) }.getOrNull()
        }.sortedWith(compareByDescending<RecordItem> { it.date ?: "" }.thenByDescending { it.at ?: 0L }.thenBy { it.title.lowercase() })
    }

    /**
     * Saves what a sync read for [kinds] (each [Fhir.Mapped] in place of the one with its key, or
     * new) and, for the kinds in [complete], removes what's no longer there. Returns the items removed.
     */
    suspend fun replace(userId: String, connectionId: String, mapped: List<Fhir.Mapped>, complete: Set<RecordKind>): Int {
        val now = clock()
        return db.tx {
            val existing = query("SELECT id, source_key, kind FROM record_items WHERE user_id = ? AND connection_id = ?", userId, connectionId) {
                Triple(it.string("id"), it.string("source_key"), it.string("kind"))
            }
            val byKey = existing.associate { it.second to it.first }
            for (m in mapped) {
                val id = byKey[m.key]
                val rowId = id ?: Random.id()
                val sealed = box.seal(ApiJson.encodeToString(RecordItem.serializer(), m.item.copy(id = "", connectionId = "")), itemContext(userId, rowId))
                if (id != null) {
                    update("UPDATE record_items SET kind = ?, date = ?, at = ?, data = ?, updated_at = ? WHERE id = ?", m.item.kind, m.item.date, m.item.at, sealed, now, id)
                } else {
                    update(
                        "INSERT INTO record_items (id, user_id, connection_id, source_key, kind, date, at, data, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        rowId, userId, connectionId, m.key, m.item.kind, m.item.date, m.item.at, sealed, now,
                    )
                }
            }
            val seen = mapped.map { it.key }.toSet()
            val gone = existing.filter { (_, key, kind) -> key !in seen && complete.any { it.name == kind } }
            gone.forEach { (id, _, _) -> update("DELETE FROM record_items WHERE id = ?", id) }
            gone.size
        }
    }

    // ---- Sign-ins on their way

    /** Keeps a sign-in for ten minutes, by a hash of its [state]. */
    suspend fun startPending(userId: String, state: String, pending: Pending, ttlMs: Long) {
        val hash = hashOf(state)
        val now = clock()
        db.tx {
            update("DELETE FROM record_pending WHERE expires_at < ?", now)
            // Someone opening sign-in after sign-in keeps their last few.
            update(
                "DELETE FROM record_pending WHERE user_id = ? AND state_hash NOT IN (SELECT state_hash FROM record_pending WHERE user_id = ? ORDER BY created_at DESC LIMIT 9)",
                userId, userId,
            )
            update(
                "INSERT INTO record_pending (state_hash, user_id, data, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                hash, userId, box.seal(ApiJson.encodeToString(Pending.serializer(), pending), pendingContext(userId, hash)), now, now + ttlMs,
            )
        }
    }

    /** The sign-in [state] belongs to, and whose it is, once: it's gone after (and never if it expired). */
    suspend fun finishPending(state: String): Pair<String, Pending>? {
        val hash = hashOf(state)
        val now = clock()
        val row = db.tx {
            val found = one("SELECT user_id, data, expires_at FROM record_pending WHERE state_hash = ?", hash) {
                Triple(it.string("user_id"), it.string("data"), it.long("expires_at"))
            }
            if (found != null) update("DELETE FROM record_pending WHERE state_hash = ?", hash)
            found
        } ?: return null
        if (row.third < now) return null
        return runCatching { row.first to ApiJson.decodeFromString(Pending.serializer(), box.open(row.second, pendingContext(row.first, hash))) }.getOrNull()
    }

    private fun hashOf(state: String) = sha256("records:$state").hex()

    // ---- Epic's list of organizations (public)

    suspend fun directory(provider: RecordProvider): Pair<String, Long>? = db.tx {
        one("SELECT data, fetched_at FROM record_directory WHERE provider = ?", provider) { it.string("data") to it.long("fetched_at") }
    }

    suspend fun saveDirectory(provider: RecordProvider, data: String) = db.tx {
        update("INSERT OR REPLACE INTO record_directory (provider, data, fetched_at) VALUES (?, ?, ?)", provider, data, clock())
    }
}
