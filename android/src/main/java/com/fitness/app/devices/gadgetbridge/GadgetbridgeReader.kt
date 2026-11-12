package com.fitness.app.devices.gadgetbridge

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.fitness.api.DeviceInfo
import com.fitness.api.IngestBatch
import com.fitness.api.Metric
import com.fitness.api.Sample
import com.fitness.api.SleepSession
import com.fitness.api.Workout
import com.fitness.app.devices.gadgetbridge.GadgetbridgeMapping.TableKind
import java.io.File

/**
 * Reads a copy of Gadgetbridge's database: every sample table it recognises (see
 * [GadgetbridgeMapping]), newer than a time, as one batch per device.
 */
class GadgetbridgeReader(private val file: File) {
    data class Result(val batches: List<IngestBatch>, val latest: Long?, val tables: Int)

    private class DeviceRow(val name: String?, val identifier: String?, val type: String?, val model: String?)

    private class Acc {
        val prefixes = LinkedHashSet<String>()
        val samples = ArrayList<Sample>()
        val sleep = ArrayList<SleepSession>()
        val workouts = ArrayList<Workout>()
    }

    fun read(since: Long): Result {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        db.use {
            val tables = strings(db, "SELECT name FROM sqlite_master WHERE type = 'table'")
            val devices = devices(db, tables)
            val acc = LinkedHashMap<Long, Acc>()
            fun of(device: Long) = acc.getOrPut(device) { Acc() }
            var latest: Long? = null
            fun saw(time: Long) {
                latest = maxOf(latest ?: time, time)
            }
            var used = 0

            for (table in tables) {
                val kind = GadgetbridgeMapping.classify(table) ?: continue
                val columns = columns(db, table)
                if ("DEVICE_ID" !in columns) continue
                val prefix = GadgetbridgeMapping.prefix(table)
                used++
                if (kind == TableKind.PEBBLE_OVERLAY) {
                    if (!columns.containsAll(listOf("TIMESTAMP_FROM", "TIMESTAMP_TO", "RAW_KIND"))) continue
                    val unit = unitOf(db, table, "TIMESTAMP_FROM")
                    val rows = HashMap<Long, MutableList<GadgetbridgeMapping.Overlay>>()
                    query(db, "SELECT DEVICE_ID, TIMESTAMP_FROM, TIMESTAMP_TO, RAW_KIND FROM $table WHERE TIMESTAMP_TO > ? ORDER BY TIMESTAMP_FROM", threshold(since, unit)) { c ->
                        val from = GadgetbridgeMapping.toMillis(c.getLong(1))
                        val to = GadgetbridgeMapping.toMillis(c.getLong(2))
                        rows.getOrPut(c.getLong(0)) { ArrayList() } += GadgetbridgeMapping.Overlay(from, to, c.getInt(3))
                        saw(to)
                    }
                    rows.forEach { (device, overlays) ->
                        val (nights, workouts) = GadgetbridgeMapping.pebble(overlays)
                        of(device).apply {
                            prefixes += prefix
                            sleep += nights
                            this.workouts += workouts
                        }
                    }
                    continue
                }
                if ("TIMESTAMP" !in columns) continue
                val unit = unitOf(db, table, "TIMESTAMP")
                val after = threshold(since, unit)
                when (kind) {
                    TableKind.ACTIVITY -> {
                        val calorieColumn = listOf("ACTIVE_CALORIES", "CALORIES").firstOrNull { it in columns }
                        val distanceColumn = listOf("DISTANCE_CM", "DISTANCE").firstOrNull { it in columns }
                        val select = listOf(
                            "DEVICE_ID", "TIMESTAMP",
                            if ("STEPS" in columns) "STEPS" else "NULL",
                            if ("HEART_RATE" in columns) "HEART_RATE" else "NULL",
                            calorieColumn ?: "NULL",
                            distanceColumn ?: "NULL",
                        ).joinToString(", ")
                        val rows = HashMap<Long, MutableList<GadgetbridgeMapping.ActivityRow>>()
                        query(db, "SELECT $select FROM $table WHERE TIMESTAMP > ? ORDER BY TIMESTAMP LIMIT $MAX_ROWS", after) { c ->
                            val time = GadgetbridgeMapping.toMillis(c.getLong(1))
                            saw(time)
                            val distance = if (c.isNull(5)) null else c.getDouble(5).let { if (distanceColumn == "DISTANCE_CM") it / 100.0 else it }
                            rows.getOrPut(c.getLong(0)) { ArrayList() } += GadgetbridgeMapping.ActivityRow(
                                time = time,
                                steps = if (c.isNull(2)) null else c.getInt(2),
                                heartRate = if (c.isNull(3)) null else c.getInt(3),
                                calories = if (c.isNull(4) || calorieColumn == null) null else GadgetbridgeMapping.kilocalories(calorieColumn, c.getDouble(4)),
                                distanceM = distance?.takeIf { it > 0 && it < 5_000 },
                            )
                        }
                        rows.forEach { (device, list) -> of(device).apply { prefixes += prefix; samples += GadgetbridgeMapping.activity(list) } }
                    }
                    TableKind.HEART_RATE -> {
                        val column = listOf("HEART_RATE", "VALUE").firstOrNull { it in columns } ?: continue
                        val rows = HashMap<Long, MutableList<Pair<Long, Int>>>()
                        query(db, "SELECT DEVICE_ID, TIMESTAMP, $column FROM $table WHERE TIMESTAMP > ? ORDER BY TIMESTAMP LIMIT $MAX_ROWS", after) { c ->
                            val time = GadgetbridgeMapping.toMillis(c.getLong(1))
                            saw(time)
                            rows.getOrPut(c.getLong(0)) { ArrayList() } += time to c.getInt(2)
                        }
                        rows.forEach { (device, list) -> of(device).apply { prefixes += prefix; samples += GadgetbridgeMapping.heartRates(list) } }
                    }
                    TableKind.SLEEP_STAGE -> {
                        if ("STAGE" !in columns) continue
                        val duration = if ("DURATION" in columns) "DURATION" else "NULL"
                        val rows = HashMap<Long, MutableList<GadgetbridgeMapping.StageRow>>()
                        query(db, "SELECT DEVICE_ID, TIMESTAMP, STAGE, $duration FROM $table WHERE TIMESTAMP > ? ORDER BY TIMESTAMP LIMIT $MAX_ROWS", after) { c ->
                            val time = GadgetbridgeMapping.toMillis(c.getLong(1))
                            saw(time)
                            rows.getOrPut(c.getLong(0)) { ArrayList() } += GadgetbridgeMapping.StageRow(time, c.getInt(2), if (c.isNull(3)) null else c.getInt(3))
                        }
                        rows.forEach { (device, list) -> of(device).apply { prefixes += prefix; sleep += GadgetbridgeMapping.sleep(prefix, list) } }
                    }
                    else -> {
                        val (metric, candidates) = when (kind) {
                            TableKind.SPO2 -> Metric.SPO2 to listOf("SPO2", "VALUE")
                            TableKind.STRESS -> Metric.STRESS to listOf("STRESS", "VALUE")
                            TableKind.HRV -> Metric.HRV to listOf("VALUE", "LAST_NIGHT_AVERAGE", "WEEKLY_AVERAGE")
                            TableKind.RESPIRATORY_RATE -> Metric.RESPIRATORY_RATE to listOf("RESPIRATORY_RATE", "RATE", "VALUE")
                            else -> Metric.SKIN_TEMPERATURE to listOf("TEMPERATURE", "VALUE")
                        }
                        val column = candidates.firstOrNull { it in columns } ?: continue
                        val rows = HashMap<Long, MutableList<Pair<Long, Double>>>()
                        query(db, "SELECT DEVICE_ID, TIMESTAMP, $column FROM $table WHERE TIMESTAMP > ? AND $column IS NOT NULL ORDER BY TIMESTAMP LIMIT $MAX_ROWS", after) { c ->
                            val time = GadgetbridgeMapping.toMillis(c.getLong(1))
                            saw(time)
                            rows.getOrPut(c.getLong(0)) { ArrayList() } += time to c.getDouble(2)
                        }
                        rows.forEach { (device, list) -> of(device).apply { prefixes += prefix; samples += GadgetbridgeMapping.values(metric, list) } }
                    }
                }
            }

            val batches = acc.mapNotNull { (id, data) ->
                if (data.samples.isEmpty() && data.sleep.isEmpty() && data.workouts.isEmpty()) return@mapNotNull null
                val row = devices[id]
                val name = row?.name?.ifBlank { null } ?: "Gadgetbridge device $id"
                IngestBatch(
                    device = DeviceInfo(
                        kind = GadgetbridgeMapping.kind(data.prefixes, row?.name, listOfNotNull(row?.type, row?.model).joinToString(" ")),
                        identifier = row?.identifier?.ifBlank { null } ?: "gadgetbridge:$id",
                        name = name,
                        model = row?.model ?: row?.type,
                    ),
                    samples = data.samples,
                    sleep = data.sleep,
                    workouts = data.workouts,
                )
            }
            return Result(batches, latest, used)
        }
    }

    private fun devices(db: SQLiteDatabase, tables: List<String>): Map<Long, DeviceRow> {
        if ("DEVICE" !in tables) return emptyMap()
        val columns = columns(db, "DEVICE")
        fun col(name: String) = if (name in columns) name else "NULL"
        val out = HashMap<Long, DeviceRow>()
        query(db, "SELECT _id, ${col("NAME")}, ${col("IDENTIFIER")}, ${col("TYPE_NAME").takeIf { it != "NULL" } ?: col("TYPE")}, ${col("MODEL")} FROM DEVICE") { c ->
            out[c.getLong(0)] = DeviceRow(
                name = c.getStringOrNull(1),
                identifier = c.getStringOrNull(2),
                type = c.getStringOrNull(3),
                model = c.getStringOrNull(4),
            )
        }
        return out
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun columns(db: SQLiteDatabase, table: String): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
            val nameIndex = c.getColumnIndex("name")
            while (c.moveToNext()) out += c.getString(nameIndex).uppercase()
        }
        return out
    }

    /** Whether a table's times are in seconds or milliseconds, from its newest one. */
    private fun unitOf(db: SQLiteDatabase, table: String, column: String): Long {
        val max = db.rawQuery("SELECT MAX($column) FROM $table", null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
        return if (max > 100_000_000_000L) 1L else 1000L
    }

    private fun threshold(sinceMs: Long, unit: Long): String = (sinceMs / unit).toString()

    private fun strings(db: SQLiteDatabase, sql: String): List<String> {
        val out = ArrayList<String>()
        db.rawQuery(sql, null).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    private fun query(db: SQLiteDatabase, sql: String, vararg args: String, row: (Cursor) -> Unit) {
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) row(c) }
    }

    companion object {
        private const val MAX_ROWS = 400_000
    }
}
