package com.health.server.modules

import com.health.api.ApiJson
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.util.Random
import com.health.server.util.SecretBox
import kotlinx.serialization.KSerializer
import java.time.LocalDate

/** The kinds of entry the health modules keep. */
enum class EntryKind { MOOD, JOURNAL, SCREENER, MINDFUL, CYCLE_DAY, CONTINENCE, DOSE, LAB }

/** An entry as stored: its row's id, day and time, and what it holds. */
data class Stored<T>(val id: String, val date: LocalDate, val at: Long, val value: T)

/**
 * The health modules' entries: one table, each entry's content sealed (AES-GCM, bound to its row
 * and owner), so a copy of the database alone gives none of it away. Only the kind, day and time
 * are in the clear, for finding entries.
 */
class EntryStore(private val db: Database, private val box: SecretBox, private val clock: () -> Long = System::currentTimeMillis) {
    private fun context(userId: String, id: String) = "entry:$userId:$id"

    suspend fun <T> list(userId: String, kind: EntryKind, from: LocalDate, to: LocalDate, serializer: KSerializer<T>): List<Stored<T>> {
        val rows = db.tx {
            query(
                "SELECT id, date, at, data FROM entries WHERE user_id = ? AND kind = ? AND date >= ? AND date <= ? ORDER BY at",
                userId, kind, from.toString(), to.toString(),
            ) { Row4(it.string("id"), it.string("date"), it.long("at"), it.string("data")) }
        }
        return rows.mapNotNull { r -> open(userId, r, serializer) }
    }

    suspend fun <T> get(userId: String, kind: EntryKind, id: String, serializer: KSerializer<T>): Stored<T>? {
        val row = db.tx {
            one("SELECT id, date, at, data FROM entries WHERE user_id = ? AND kind = ? AND id = ?", userId, kind, id) {
                Row4(it.string("id"), it.string("date"), it.long("at"), it.string("data"))
            }
        } ?: return null
        return open(userId, row, serializer)
    }

    /** Saves [value] as a new entry, or over entry [id] if given (which must be the person's own). Returns its id. */
    suspend fun <T> put(userId: String, kind: EntryKind, id: String?, date: LocalDate, at: Long, value: T, serializer: KSerializer<T>): String {
        val entryId = id ?: Random.id()
        val sealed = box.seal(ApiJson.encodeToString(serializer, value), context(userId, entryId))
        db.tx {
            if (id != null) {
                if (update("UPDATE entries SET date = ?, at = ?, data = ?, updated_at = ? WHERE id = ? AND user_id = ? AND kind = ?", date.toString(), at, sealed, clock(), id, userId, kind) == 0) {
                    throw ApiError.notFound("No such entry")
                }
            } else {
                update("INSERT INTO entries (id, user_id, kind, date, at, data, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)", entryId, userId, kind, date.toString(), at, sealed, clock())
            }
        }
        return entryId
    }

    /** Deletes an entry; returns its day. */
    suspend fun delete(userId: String, kind: EntryKind, id: String): LocalDate = db.tx {
        val date = one("SELECT date FROM entries WHERE id = ? AND user_id = ? AND kind = ?", id, userId, kind) { it.string("date") }
            ?: throw ApiError.notFound("No such entry")
        update("DELETE FROM entries WHERE id = ?", id)
        LocalDate.parse(date)
    }

    suspend fun deleteDay(userId: String, kind: EntryKind, date: LocalDate): Int =
        db.tx { update("DELETE FROM entries WHERE user_id = ? AND kind = ? AND date = ?", userId, kind, date.toString()) }

    private class Row4(val id: String, val date: String, val at: Long, val data: String)

    private fun <T> open(userId: String, row: Row4, serializer: KSerializer<T>): Stored<T>? = runCatching {
        Stored(row.id, LocalDate.parse(row.date), row.at, ApiJson.decodeFromString(serializer, box.open(row.data, context(userId, row.id))))
    }.getOrNull()
}
