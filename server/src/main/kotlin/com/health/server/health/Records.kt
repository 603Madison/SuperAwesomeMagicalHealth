package com.health.server.health

import com.health.api.ApiJson
import com.health.api.Device
import com.health.api.DeviceInfo
import com.health.api.DeviceKind
import com.health.api.Metric
import com.health.api.Sample
import com.health.api.SleepSession
import com.health.api.StageSpan
import com.health.api.Workout
import com.health.api.WorkoutType
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.db.Row
import com.health.server.db.Tx
import com.health.server.insights.DeviceRef
import com.health.server.util.Random
import kotlinx.serialization.builtins.ListSerializer

/** The devices each person's data came from. Entries made by hand belong to a "device" of their own. */
class DeviceStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** The device's id, adding it the first time it sends anything; refreshes its name and battery. */
    fun upsert(tx: Tx, userId: String, info: DeviceInfo, synced: Boolean): String = with(tx) {
        val identifier = info.identifier.trim().take(200).ifBlank { throw ApiError.badRequest("A device needs an identifier") }
        val name = info.name.trim().take(120).ifBlank { info.kind.label }
        val existing = one("SELECT id FROM devices WHERE user_id = ? AND kind = ? AND identifier = ?", userId, info.kind, identifier) { it.string("id") }
        if (existing != null) {
            update(
                """UPDATE devices SET model = COALESCE(?, model), firmware = COALESCE(?, firmware), battery = COALESCE(?, battery),
                   last_sync_at = CASE WHEN ? THEN ? ELSE last_sync_at END WHERE id = ?""",
                info.model?.take(120), info.firmware?.take(60), info.battery?.coerceIn(0, 100), synced, clock(), existing,
            )
            return existing
        }
        val id = Random.id()
        update(
            """INSERT INTO devices (id, user_id, kind, identifier, name, model, firmware, battery, last_sync_at, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, userId, info.kind, identifier, name, info.model?.take(120), info.firmware?.take(60), info.battery?.coerceIn(0, 100),
            if (synced) clock() else null, clock(),
        )
        id
    }

    /** The "device" for what the person enters by hand. */
    fun manual(tx: Tx, userId: String): String = upsert(tx, userId, DeviceInfo(DeviceKind.MANUAL, "manual", "Entered by hand"), synced = false)

    suspend fun list(userId: String): List<Device> = db.tx {
        query(
            """SELECT d.*, (SELECT COUNT(*) FROM samples s WHERE s.device_id = d.id) AS sample_count
               FROM devices d WHERE d.user_id = ? ORDER BY COALESCE(d.last_sync_at, d.created_at) DESC""",
            userId,
        ) { row ->
            Device(
                id = row.string("id"),
                kind = runCatching { DeviceKind.valueOf(row.string("kind")) }.getOrDefault(DeviceKind.OTHER),
                identifier = row.string("identifier"),
                name = row.string("name"),
                model = row.stringOrNull("model"),
                firmware = row.stringOrNull("firmware"),
                battery = row.intOrNull("battery"),
                lastSyncAt = row.longOrNull("last_sync_at"),
                createdAt = row.long("created_at"),
                samples = row.long("sample_count"),
            )
        }
    }

    suspend fun rename(userId: String, id: String, name: String): Device {
        val clean = name.trim().take(120).ifBlank { throw ApiError.badRequest("Give it a name") }
        if (db.tx { update("UPDATE devices SET name = ? WHERE id = ? AND user_id = ?", clean, id, userId) } == 0) throw ApiError.notFound("No such device")
        return list(userId).first { it.id == id }
    }

    /** Forgets a device and everything it recorded. */
    suspend fun delete(userId: String, id: String) {
        if (db.tx { update("DELETE FROM devices WHERE id = ? AND user_id = ?", id, userId) } == 0) throw ApiError.notFound("No such device")
    }
}

/** Readings, nights and workouts. */
class Records(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** Each of the person's devices, by id: its name and kind, for combining their readings. */
    suspend fun deviceRefs(userId: String): Map<String, DeviceRef> = db.tx {
        query("SELECT id, name, kind FROM devices WHERE user_id = ?", userId) { row ->
            DeviceRef(row.string("id"), row.string("name"), runCatching { DeviceKind.valueOf(row.string("kind")) }.getOrNull())
        }
    }.associateBy { it.id }

    // ---- Samples ----

    fun putSamples(tx: Tx, userId: String, deviceId: String, samples: List<Sample>): Int = tx.batch(
        """INSERT INTO samples (user_id, device_id, metric, start, end, value) VALUES (?, ?, ?, ?, ?, ?)
           ON CONFLICT (user_id, device_id, metric, start) DO UPDATE SET end = excluded.end, value = excluded.value""",
        samples.map { arrayOf(userId, deviceId, it.metric, it.start, it.end, it.value) },
    ).let { samples.size }

    /** Every reading that starts in [from, to), oldest first. */
    suspend fun samples(userId: String, from: Long, to: Long, metrics: Collection<Metric>? = null): List<StoredSample> = db.tx {
        val filter = metrics?.takeIf { it.isNotEmpty() }?.joinToString(",", " AND s.metric IN (", ")") { "'${it.name}'" }.orEmpty()
        query(
            """SELECT s.id, s.device_id, s.metric, s.start, s.end, s.value, d.name AS device_name, d.kind AS device_kind
               FROM samples s JOIN devices d ON d.id = s.device_id
               WHERE s.user_id = ? AND s.start >= ? AND s.start < ?$filter ORDER BY s.start""",
            userId, from, to,
        ) { row ->
            metricOf(row)?.let { metric ->
                StoredSample(
                    id = row.long("id"),
                    deviceId = row.string("device_id"),
                    deviceName = row.string("device_name"),
                    manual = row.string("device_kind") == DeviceKind.MANUAL.name,
                    metric = metric,
                    start = row.long("start"),
                    end = row.longOrNull("end"),
                    value = row.double("value"),
                )
            }
        }.filterNotNull()
    }

    /** A reading's metric; null (and the reading left out) for one no longer known. */
    private fun metricOf(row: Row): Metric? = runCatching { Metric.valueOf(row.string("metric")) }.getOrNull()

    /** The latest reading of [metric] before [before]. */
    suspend fun latest(userId: String, metric: Metric, before: Long): StoredSample? =
        samplesBefore(userId, metric, before, 1).firstOrNull()

    suspend fun samplesBefore(userId: String, metric: Metric, before: Long, limit: Int): List<StoredSample> = db.tx {
        query(
            """SELECT s.id, s.device_id, s.metric, s.start, s.end, s.value, d.name AS device_name, d.kind AS device_kind
               FROM samples s JOIN devices d ON d.id = s.device_id
               WHERE s.user_id = ? AND s.metric = ? AND s.start < ? ORDER BY s.start DESC LIMIT ?""",
            userId, metric, before, limit,
        ) { row ->
            metricOf(row)?.let { known ->
                StoredSample(
                    row.long("id"), row.string("device_id"), row.string("device_name"), row.string("device_kind") == DeviceKind.MANUAL.name,
                    known, row.long("start"), row.longOrNull("end"), row.double("value"),
                )
            }
        }.filterNotNull()
    }

    /** Deletes a reading the person entered (not one a device recorded). Returns when it was. */
    suspend fun deleteManualSample(userId: String, id: Long): Long = db.tx {
        val start = one(
            """SELECT s.start FROM samples s JOIN devices d ON d.id = s.device_id
               WHERE s.id = ? AND s.user_id = ? AND d.kind = 'MANUAL'""",
            id, userId,
        ) { it.long("start") } ?: throw ApiError.notFound("No such entry (only ones entered by hand can be deleted)")
        update("DELETE FROM samples WHERE id = ?", id)
        start
    }

    // ---- Sleep ----

    fun putSleep(tx: Tx, userId: String, deviceId: String, sessions: List<SleepSession>, manual: Boolean): List<String> = sessions.map { session ->
        val id = tx.one("SELECT id FROM sleep_sessions WHERE user_id = ? AND device_id = ? AND start = ?", userId, deviceId, session.start) { it.string("id") }
            ?: Random.id()
        tx.update(
            """INSERT INTO sleep_sessions (id, user_id, device_id, start, end, stages, manual, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (user_id, device_id, start) DO UPDATE SET end = excluded.end, stages = excluded.stages, note = excluded.note""",
            id, userId, deviceId, session.start, session.end, ApiJson.encodeToString(StagesSerializer, session.stages), manual, session.note?.take(1000),
        )
        id
    }

    /** Sessions that end in [from, to). */
    suspend fun sleep(userId: String, from: Long, to: Long): List<SleepSession> = db.tx {
        query(
            """SELECT s.*, d.name AS device_name FROM sleep_sessions s JOIN devices d ON d.id = s.device_id
               WHERE s.user_id = ? AND s.end >= ? AND s.end < ? ORDER BY s.end""",
            userId, from, to, map = ::toSleep,
        )
    }

    suspend fun deleteSleep(userId: String, id: String): SleepSession = db.tx {
        val session = one(
            "SELECT s.*, d.name AS device_name FROM sleep_sessions s JOIN devices d ON d.id = s.device_id WHERE s.id = ? AND s.user_id = ?",
            id, userId, map = ::toSleep,
        ) ?: throw ApiError.notFound("No such night")
        update("DELETE FROM sleep_sessions WHERE id = ?", id)
        session
    }

    private fun toSleep(row: Row) = SleepSession(
        start = row.long("start"),
        end = row.long("end"),
        stages = runCatching { ApiJson.decodeFromString(StagesSerializer, row.string("stages")) }.getOrDefault(emptyList()),
        id = row.string("id"),
        deviceId = row.string("device_id"),
        source = row.string("device_name"),
        manual = row.bool("manual"),
        note = row.stringOrNull("note"),
    )

    // ---- Workouts ----

    fun putWorkouts(tx: Tx, userId: String, deviceId: String, workouts: List<Workout>, manual: Boolean): List<String> = workouts.map { w ->
        val id = w.id?.takeIf { manual }
            ?: tx.one("SELECT id FROM workouts WHERE user_id = ? AND device_id = ? AND start = ?", userId, deviceId, w.start) { it.string("id") }
            ?: Random.id()
        tx.update(
            """INSERT INTO workouts (id, user_id, device_id, type, start, end, name, distance_m, active_calories, avg_hr, max_hr, steps, effort, note, manual)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (id) DO UPDATE SET type = excluded.type, start = excluded.start, end = excluded.end, name = excluded.name,
                 distance_m = excluded.distance_m, active_calories = excluded.active_calories, avg_hr = excluded.avg_hr, max_hr = excluded.max_hr,
                 steps = excluded.steps, effort = excluded.effort, note = excluded.note
               ON CONFLICT (user_id, device_id, start) DO UPDATE SET type = excluded.type, end = excluded.end, name = excluded.name,
                 distance_m = excluded.distance_m, active_calories = excluded.active_calories, avg_hr = excluded.avg_hr, max_hr = excluded.max_hr,
                 steps = excluded.steps, effort = excluded.effort, note = excluded.note""",
            id, userId, deviceId, w.type, w.start, w.end, w.name?.take(120), w.distanceM, w.activeCalories, w.avgHeartRate, w.maxHeartRate,
            w.steps, w.effort, w.note?.take(2000), manual,
        )
        id
    }

    /** The id of the device's workout starting at [start], if there is one. */
    fun workoutAt(tx: Tx, userId: String, deviceId: String, start: Long): String? =
        tx.one("SELECT id FROM workouts WHERE user_id = ? AND device_id = ? AND start = ?", userId, deviceId, start) { it.string("id") }

    /** Workouts that start in [from, to), newest first. */
    suspend fun workouts(userId: String, from: Long, to: Long): List<Workout> = db.tx {
        query(
            """SELECT w.*, d.name AS device_name FROM workouts w JOIN devices d ON d.id = w.device_id
               WHERE w.user_id = ? AND w.start >= ? AND w.start < ? ORDER BY w.start DESC""",
            userId, from, to, map = ::toWorkout,
        )
    }

    suspend fun workout(userId: String, id: String): Workout? = db.tx {
        one("SELECT w.*, d.name AS device_name FROM workouts w JOIN devices d ON d.id = w.device_id WHERE w.id = ? AND w.user_id = ?", id, userId, map = ::toWorkout)
    }

    suspend fun deleteWorkout(userId: String, id: String): Workout = db.tx {
        val workout = one(
            "SELECT w.*, d.name AS device_name FROM workouts w JOIN devices d ON d.id = w.device_id WHERE w.id = ? AND w.user_id = ?",
            id, userId, map = ::toWorkout,
        ) ?: throw ApiError.notFound("No such workout")
        update("DELETE FROM workouts WHERE id = ?", id)
        workout
    }

    private fun toWorkout(row: Row) = Workout(
        type = runCatching { WorkoutType.valueOf(row.string("type")) }.getOrDefault(WorkoutType.OTHER),
        start = row.long("start"),
        end = row.long("end"),
        name = row.stringOrNull("name"),
        distanceM = row.doubleOrNull("distance_m"),
        activeCalories = row.doubleOrNull("active_calories"),
        avgHeartRate = row.doubleOrNull("avg_hr"),
        maxHeartRate = row.doubleOrNull("max_hr"),
        steps = row.intOrNull("steps"),
        effort = row.intOrNull("effort"),
        note = row.stringOrNull("note"),
        id = row.string("id"),
        deviceId = row.string("device_id"),
        source = row.string("device_name"),
        manual = row.bool("manual"),
    )

    companion object {
        val StagesSerializer = ListSerializer(StageSpan.serializer())
    }
}

/** A reading as stored, with where it came from. */
data class StoredSample(
    val id: Long,
    val deviceId: String,
    val deviceName: String,
    val manual: Boolean,
    val metric: Metric,
    val start: Long,
    val end: Long?,
    val value: Double,
) {
    fun toApi() = Sample(metric, start, end, value, id, deviceName)
}
