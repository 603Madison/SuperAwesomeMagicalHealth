package com.health.server.modules

import com.health.api.ApiJson
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.db.Tx
import com.health.server.util.Random
import com.health.server.util.SecretBox
import kotlinx.serialization.KSerializer
import java.time.LocalDate

/** The kinds of entry the health modules keep. */
enum class EntryKind {
    MOOD, JOURNAL, SCREENER, MINDFUL, CYCLE_DAY, CONTINENCE, DOSE, LAB,
    // Hormone therapy, life stages and appointments (care/).
    HORMONE_SETTINGS, DOSE_CHANGE, LIFE_STAGE, KICKS, CONTRACTION, FEED, HOT_FLASH, APPOINTMENT,
    // Symptoms and migraines (tracking/Symptoms.kt).
    SYMPTOM, MIGRAINE,
    // Autism and ADHD (neuro/), and transition (transition/).
    NEURO_EVENT, NEURO_ENERGY, NEURO_CHECKIN, NEURO_FOCUS, NEURO_ROUTINE_RUN,
    TRANSITION_MILESTONE, TRANSITION_CHANGE, TRANSITION_VOICE, TRANSITION_HAIR, TRANSITION_MOMENT,
    // Bladder and bowel check-ins, and changes to supplies (modules/Continence.kt, modules/Supplies.kt).
    CONTINENCE_CHECKIN, SUPPLY_LOG,
}

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
        val rows = db.tx { rows(this, userId, kind, from, to) }
        return rows.mapNotNull { r -> open(userId, r, serializer) }
    }

    suspend fun <T> get(userId: String, kind: EntryKind, id: String, serializer: KSerializer<T>): Stored<T>? {
        val row = db.tx { row(this, userId, kind, id) } ?: return null
        return open(userId, row, serializer)
    }

    /** Saves [value] as a new entry, or over entry [id] if given (which must be the person's own). Returns its id. */
    suspend fun <T> put(userId: String, kind: EntryKind, id: String?, date: LocalDate, at: Long, value: T, serializer: KSerializer<T>): String {
        val entryId = id ?: Random.id()
        val sealed = box.seal(ApiJson.encodeToString(serializer, value), context(userId, entryId))
        db.tx { write(this, userId, kind, id, entryId, date, at, sealed) }
        return entryId
    }

    /** Deletes an entry; returns its day. */
    suspend fun delete(userId: String, kind: EntryKind, id: String): LocalDate = db.tx { delete(this, userId, kind, id) }

    suspend fun deleteDay(userId: String, kind: EntryKind, date: LocalDate): Int = db.tx { deleteDay(this, userId, kind, date) }

    /** The day's entries of [kind] replaced by [value] (or by none, if null) all at once. Returns the new entry's id. */
    suspend fun <T : Any> replaceDay(userId: String, kind: EntryKind, date: LocalDate, at: Long, value: T?, serializer: KSerializer<T>): String? {
        val entryId = Random.id()
        val sealed = value?.let { box.seal(ApiJson.encodeToString(serializer, it), context(userId, entryId)) }
        return db.tx {
            deleteDay(this, userId, kind, date)
            sealed?.let { write(this, userId, kind, null, entryId, date, at, it); entryId }
        }
    }

    // ---- The same inside a transaction already open, to read, change and write as one ----

    fun <T> list(tx: Tx, userId: String, kind: EntryKind, from: LocalDate, to: LocalDate, serializer: KSerializer<T>): List<Stored<T>> =
        rows(tx, userId, kind, from, to).mapNotNull { r -> open(userId, r, serializer) }

    fun <T> get(tx: Tx, userId: String, kind: EntryKind, id: String, serializer: KSerializer<T>): Stored<T>? =
        row(tx, userId, kind, id)?.let { open(userId, it, serializer) }

    fun <T> put(tx: Tx, userId: String, kind: EntryKind, id: String?, date: LocalDate, at: Long, value: T, serializer: KSerializer<T>): String {
        val entryId = id ?: Random.id()
        write(tx, userId, kind, id, entryId, date, at, box.seal(ApiJson.encodeToString(serializer, value), context(userId, entryId)))
        return entryId
    }

    fun delete(tx: Tx, userId: String, kind: EntryKind, id: String): LocalDate {
        val date = tx.one("SELECT date FROM entries WHERE id = ? AND user_id = ? AND kind = ?", id, userId, kind) { it.string("date") }
            ?: throw ApiError.notFound("No such entry")
        tx.update("DELETE FROM entries WHERE id = ?", id)
        return LocalDate.parse(date)
    }

    fun deleteDay(tx: Tx, userId: String, kind: EntryKind, date: LocalDate): Int =
        tx.update("DELETE FROM entries WHERE user_id = ? AND kind = ? AND date = ?", userId, kind, date.toString())

    private class Row4(val id: String, val date: String, val at: Long, val data: String)

    private fun rows(tx: Tx, userId: String, kind: EntryKind, from: LocalDate, to: LocalDate): List<Row4> = tx.query(
        "SELECT id, date, at, data FROM entries WHERE user_id = ? AND kind = ? AND date >= ? AND date <= ? ORDER BY at",
        userId, kind, from.toString(), to.toString(),
    ) { Row4(it.string("id"), it.string("date"), it.long("at"), it.string("data")) }

    private fun row(tx: Tx, userId: String, kind: EntryKind, id: String): Row4? =
        tx.one("SELECT id, date, at, data FROM entries WHERE user_id = ? AND kind = ? AND id = ?", userId, kind, id) {
            Row4(it.string("id"), it.string("date"), it.long("at"), it.string("data"))
        }

    private fun write(tx: Tx, userId: String, kind: EntryKind, id: String?, entryId: String, date: LocalDate, at: Long, sealed: String) {
        if (id != null) {
            if (tx.update("UPDATE entries SET date = ?, at = ?, data = ?, updated_at = ? WHERE id = ? AND user_id = ? AND kind = ?", date.toString(), at, sealed, clock(), id, userId, kind) == 0) {
                throw ApiError.notFound("No such entry")
            }
        } else {
            tx.update("INSERT INTO entries (id, user_id, kind, date, at, data, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)", entryId, userId, kind, date.toString(), at, sealed, clock())
        }
    }

    private fun <T> open(userId: String, row: Row4, serializer: KSerializer<T>): Stored<T>? = runCatching {
        Stored(row.id, LocalDate.parse(row.date), row.at, ApiJson.decodeFromString(serializer, box.open(row.data, context(userId, row.id))))
    }.getOrNull()
}
