package com.health.server.food

import com.health.api.Food
import com.health.api.FoodSource
import com.health.api.Micronutrient
import com.health.api.Nutrients
import org.sqlite.SQLiteConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/*
 * USDA FoodData Central kept on this server, in its own SQLite file in the data folder
 * (`usda-foods.db`), apart from the main database so backups stay small. Only what the app uses:
 * each food's name, brand, barcode, data type and serving, and its nutrients for that serving in
 * one compact row ([Compact]). Searched through an FTS5 index (a LIKE search where the SQLite
 * build has no FTS5). Built whole by [UsdaLocalWriter] beside it, then swapped in at once.
 */

/** Everyday foods (Foundation, SR Legacy, FNDDS) and packaged ones (Branded), as kept. */
enum class UsdaKind(val code: Int) { EVERYDAY(0), PACKAGED(1) }

/** A food's nutrients as one short line: the calories and the macros, then each micronutrient in [Compact.MICROS]'s order, empty where unknown. */
object Compact {
    /** The micronutrients, in the order they're kept (saved with each file, so a new one never shifts the rest). */
    val MICROS: List<Micronutrient> = Micronutrient.entries

    fun encode(n: Nutrients): String = buildList {
        add(num(n.calories)); add(num(n.protein)); add(num(n.carbs)); add(num(n.fat))
        add(n.fiber?.let(::num).orEmpty()); add(n.sugar?.let(::num).orEmpty()); add(n.saturatedFat?.let(::num).orEmpty()); add(n.sodiumMg?.let(::num).orEmpty())
        MICROS.forEach { add(n.micros[it]?.let(::num).orEmpty()) }
    }.joinToString(",").trimEnd(',')

    /** [text] back into nutrients, with the micronutrients named in the file's own order ([micros]). */
    fun decode(text: String, micros: List<Micronutrient?>): Nutrients {
        val parts = text.split(',')
        fun at(i: Int) = parts.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        return Nutrients(
            calories = at(0) ?: 0.0, protein = at(1) ?: 0.0, carbs = at(2) ?: 0.0, fat = at(3) ?: 0.0,
            fiber = at(4), sugar = at(5), saturatedFat = at(6), sodiumMg = at(7),
            micros = micros.withIndex().mapNotNull { (i, m) -> m?.let { micro -> at(8 + i)?.let { micro to it } } }.toMap(),
        )
    }

    private fun num(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}

/** Builds a new copy at [file], row by row, then indexes it. Not thread-safe: one import at a time. */
class UsdaLocalWriter(private val file: File) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")
    private val insert by lazy { connection.prepareStatement("INSERT OR REPLACE INTO foods (id, kind, type, name, brand, barcode, serving, grams, n) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)") }
    private var pending = 0
    var count = 0
        private set

    /** Whether this SQLite has FTS5 (else search is a plainer LIKE). */
    val fts: Boolean

    init {
        connection.createStatement().use { s ->
            // Built once, start to end: nothing to recover if it stops, so no journal.
            s.execute("PRAGMA journal_mode = OFF")
            s.execute("PRAGMA synchronous = OFF")
            s.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            s.execute(
                "CREATE TABLE foods (id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, brand TEXT, barcode TEXT, serving TEXT NOT NULL, grams REAL, n TEXT NOT NULL)",
            )
            fts = runCatching {
                s.execute("CREATE VIRTUAL TABLE foods_fts USING fts5(name, brand, content='foods', content_rowid='id', tokenize='unicode61 remove_diacritics 2')")
            }.isSuccess
        }
        connection.autoCommit = false
    }

    /** Adds [food] (from USDA's [type]: "Foundation", "SR Legacy", "Survey (FNDDS)" or "Branded") under its FoodData Central id. */
    fun add(id: Long, kind: UsdaKind, type: String, food: Food) {
        with(insert) {
            setLong(1, id)
            setInt(2, kind.code)
            setString(3, TYPES[type] ?: "?")
            setString(4, food.name)
            setString(5, food.brand)
            setString(6, food.barcode)
            setString(7, food.serving)
            food.servingGrams?.let { setDouble(8, it) } ?: setNull(8, java.sql.Types.REAL)
            setString(9, Compact.encode(food.nutrients))
            addBatch()
        }
        count++
        if (++pending >= 2_000) flush()
    }

    private fun flush() {
        if (pending == 0) return
        insert.executeBatch()
        connection.commit()
        pending = 0
    }

    /** Indexes what was added and saves [meta] with it; the file is then ready to swap in. */
    fun finish(meta: Map<String, String>) {
        flush()
        connection.createStatement().use { s ->
            if (fts) s.execute("INSERT INTO foods_fts(foods_fts) VALUES ('rebuild')")
            // Barcodes are matched without their leading zeros.
            s.execute("CREATE INDEX foods_barcode ON foods (ltrim(barcode, '0')) WHERE barcode IS NOT NULL")
            s.execute("CREATE INDEX foods_kind ON foods (kind)")
        }
        connection.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)").use { st ->
            (meta + mapOf("micros" to Compact.MICROS.joinToString(",") { it.name }, "fts" to if (fts) "1" else "0", "foods" to count.toString())).forEach { (k, v) ->
                st.setString(1, k); st.setString(2, v); st.addBatch()
            }
            st.executeBatch()
        }
        connection.commit()
        connection.autoCommit = true
        connection.createStatement().use { it.execute("PRAGMA optimize") }
    }

    override fun close() {
        runCatching { insert.close() }
        connection.close()
    }

    companion object {
        val TYPES = mapOf("Foundation" to "F", "SR Legacy" to "S", "Survey (FNDDS)" to "N", "Branded" to "B")
    }
}

/**
 * The copy in use, read only. One connection, opened when first asked and closed for a [swap]
 * (the file is replaced whole, so a search in between still reads the old copy to its end).
 */
class UsdaLocalDb(val file: File) {
    private var connection: Connection? = null
    private var meta: Map<String, String> = emptyMap()
    private var micros: List<Micronutrient?> = emptyList()

    /** Whether there's a copy to search. */
    val ready: Boolean get() = file.isFile

    /** What the copy holds ("release", "files", "packaged", "foods", "builtAt", …), or empty with none. */
    fun meta(): Map<String, String> = synchronized(this) { if (open() == null) emptyMap() else meta }

    /** Whether the copy has packaged foods (and so barcodes). */
    val packaged: Boolean get() = meta()["packaged"] == "true"

    private fun open(): Connection? {
        connection?.let { return it }
        if (!file.isFile) return null
        val c = SQLiteConfig().apply { setReadOnly(true) }.createConnection("jdbc:sqlite:${file.path}")
        meta = c.createStatement().use { s -> s.executeQuery("SELECT key, value FROM meta").use { r -> buildMap { while (r.next()) put(r.getString(1), r.getString(2)) } } }
        micros = meta["micros"].orEmpty().split(',').map { name -> Micronutrient.entries.firstOrNull { it.name == name } }
        connection = c
        return c
    }

    /** Up to [limit] foods of [kind] whose name or brand has every word of [text] (as a word's start), best first. */
    fun search(text: String, kind: UsdaKind, limit: Int): List<Food> = synchronized(this) {
        val c = open() ?: return emptyList()
        val words = WORD.findAll(text.lowercase()).map { it.value }.filter { it.isNotEmpty() }.take(6).toList()
        if (words.isEmpty()) return emptyList()
        // A few more than asked, as packaged foods repeat (the same yogurt, each year it was sent).
        val found = if (meta["fts"] == "1") {
            val match = words.joinToString(" ") { "\"$it\"*" }
            c.prepareStatement(
                "SELECT f.* FROM foods_fts JOIN foods f ON f.id = foods_fts.rowid WHERE foods_fts MATCH ? AND f.kind = ? ORDER BY bm25(foods_fts, 4.0, 1.0), length(f.name) LIMIT ?",
            ).use { st ->
                st.setString(1, match); st.setInt(2, kind.code); st.setInt(3, limit * 4)
                st.executeQuery().use(::foods)
            }
        } else {
            val where = words.joinToString(" AND ") { "(LOWER(name) LIKE ? OR LOWER(COALESCE(brand, '')) LIKE ?)" }
            c.prepareStatement("SELECT * FROM foods WHERE kind = ? AND $where ORDER BY length(name) LIMIT ?").use { st ->
                st.setInt(1, kind.code)
                words.forEachIndexed { i, w -> st.setString(2 + i * 2, "%$w%"); st.setString(3 + i * 2, "%$w%") }
                st.setInt(2 + words.size * 2, limit * 4)
                st.executeQuery().use(::foods)
            }
        }
        // Of the best matches, a name that's just the words searched comes first, then one that starts with them
        // ("Greek Yogurt" before "Greek Yogurt Dressing, Greek Yogurt"); otherwise the index's order.
        val asked = words.joinToString(" ")
        found.distinctBy { it.name.lowercase() to it.brand?.lowercase() }
            .sortedBy { f -> plain(f.name).let { if (it == asked) 0 else if (it.startsWith(asked)) 1 else 2 } }
            .take(limit)
    }

    /** "Greek Yogurt, Plain" as "greek yogurt plain": its words, to compare with what was searched. */
    private fun plain(name: String) = WORD.findAll(name.lowercase()).joinToString(" ") { it.value }

    /** The packaged food with exactly this barcode (digits only, leading zeros aside); the newest where it was sent more than once. */
    fun barcode(code: String): Food? = synchronized(this) {
        val c = open() ?: return null
        val bare = code.filter { it.isDigit() }.trimStart('0').takeIf { it.length >= 6 } ?: return null
        c.prepareStatement("SELECT * FROM foods WHERE ltrim(barcode, '0') = ? AND barcode IS NOT NULL ORDER BY id DESC LIMIT 1").use { st ->
            st.setString(1, bare)
            st.executeQuery().use(::foods).firstOrNull()
        }
    }

    /** Puts [next] in place of the copy in use, at once (the old one is read to the end by nothing: its connection is closed first). */
    fun swap(next: File) = synchronized(this) {
        close()
        Files.move(next.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Removes the copy. */
    fun remove() = synchronized(this) {
        close()
        Files.deleteIfExists(file.toPath())
    }

    fun close() = synchronized(this) {
        runCatching { connection?.close() }
        connection = null
        meta = emptyMap()
    }

    private fun foods(r: ResultSet): List<Food> = buildList {
        while (r.next()) {
            add(
                Food(
                    name = r.getString("name"),
                    nutrients = Compact.decode(r.getString("n"), micros),
                    serving = r.getString("serving"),
                    servingGrams = r.getDouble("grams").takeIf { !r.wasNull() },
                    brand = r.getString("brand"),
                    barcode = r.getString("barcode"),
                    source = FoodSource.USDA,
                ),
            )
        }
    }

    private companion object {
        val WORD = Regex("[\\p{L}\\p{N}]+")
    }
}
