package com.health.server.photos

import com.health.api.ApiJson
import com.health.api.Metric
import com.health.api.Photo
import com.health.api.PhotoAlbum
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.health.ProfileStore
import com.health.server.health.Records
import com.health.server.transition.TransitionService
import com.health.server.util.Random
import com.health.server.util.SecretBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Progress photos: re-encoded on upload (no location or camera details), sealed with the server's
 * key into files under the data folder, with their details sealed in the `photos` table. They're
 * never shown to AI connectors or the assistant; a share sees them only if it includes PHOTOS.
 */
class PhotoService(
    private val db: Database,
    private val box: SecretBox,
    private val dataDir: File,
    private val profiles: ProfileStore,
    private val records: Records,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Set once transition exists, for how long someone had been on hormones when a photo was taken. */
    var transition: TransitionService? = null

    private fun folder(userId: String) = File(File(dataDir, "photos"), safe(userId))
    private fun context(userId: String, id: String, part: String) = "photo:$userId:$id:$part"

    suspend fun upload(userId: String, bytes: ByteArray, meta: Photo): Photo {
        val clean = clean(meta)
        val processed = withContext(Dispatchers.Default) { Images.process(bytes) }
        val id = Random.id()
        val dir = folder(userId)
        val file = "$id.jpg.sealed"
        val thumb = "$id.thumb.sealed"
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            File(dir, file).writeBytes(box.sealBytes(processed.jpeg, context(userId, id, "image")))
            File(dir, thumb).writeBytes(box.sealBytes(processed.thumb, context(userId, id, "thumb")))
        }
        val date = profiles.days(userId).dateOf(clean.at)
        val stored = clean.copy(width = processed.width, height = processed.height, id = null, date = null, weightKg = null, onHormones = null)
        try {
            db.tx {
                update(
                    "INSERT INTO photos (id, user_id, date, at, file, thumb, bytes, data, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, userId, date.toString(), clean.at, file, thumb, processed.jpeg.size.toLong(),
                    box.seal(ApiJson.encodeToString(Photo.serializer(), stored), context(userId, id, "meta")), clock(),
                )
            }
        } catch (e: Exception) {
            File(dir, file).delete()
            File(dir, thumb).delete()
            throw e
        }
        return decorate(userId, listOf(stored.copy(id = id, date = date.toString()))).single()
    }

    suspend fun list(userId: String, album: String? = null, days: Int? = null): List<Photo> {
        val from = days?.let { profiles.days(userId).today(clock()).minusDays(it.coerceIn(1, 36500) - 1L).toString() } ?: "0000-00-00"
        val photos = rows(userId, from).filter { album == null || it.album.equals(album.trim(), ignoreCase = true) }
        return decorate(userId, photos)
    }

    suspend fun albums(userId: String): List<PhotoAlbum> =
        rows(userId, "0000-00-00").groupBy { it.album }.map { (name, photos) ->
            val latest = photos.maxBy { it.at }
            PhotoAlbum(name, photos.size, latest.date, latest.id)
        }.sortedByDescending { it.latest }

    suspend fun image(userId: String, id: String, thumb: Boolean): ByteArray {
        val row = db.tx { one("SELECT file, thumb FROM photos WHERE id = ? AND user_id = ?", id, userId) { it.string("file") to it.string("thumb") } }
            ?: throw ApiError.notFound("No such photo")
        val name = if (thumb) row.second else row.first
        val sealed = withContext(Dispatchers.IO) { File(folder(userId), name).takeIf { it.isFile }?.readBytes() } ?: throw ApiError.notFound("The photo's file is missing")
        return box.openBytes(sealed, context(userId, id, if (thumb) "thumb" else "image"))
    }

    suspend fun update(userId: String, id: String, meta: Photo): Photo {
        val existing = rows(userId, "0000-00-00").firstOrNull { it.id == id } ?: throw ApiError.notFound("No such photo")
        val clean = clean(meta)
        val stored = existing.copy(album = clean.album, pose = clean.pose, at = clean.at, note = clean.note, tags = clean.tags, id = null, date = null, weightKg = null, onHormones = null)
        val date = profiles.days(userId).dateOf(stored.at)
        db.tx {
            update(
                "UPDATE photos SET date = ?, at = ?, data = ? WHERE id = ? AND user_id = ?",
                date.toString(), stored.at, box.seal(ApiJson.encodeToString(Photo.serializer(), stored), context(userId, id, "meta")), id, userId,
            )
        }
        return decorate(userId, listOf(stored.copy(id = id, date = date.toString()))).single()
    }

    suspend fun delete(userId: String, id: String) {
        val row = db.tx {
            val r = one("SELECT file, thumb FROM photos WHERE id = ? AND user_id = ?", id, userId) { it.string("file") to it.string("thumb") }
            if (r != null) update("DELETE FROM photos WHERE id = ? AND user_id = ?", id, userId)
            r
        } ?: throw ApiError.notFound("No such photo")
        withContext(Dispatchers.IO) {
            File(folder(userId), row.first).delete()
            File(folder(userId), row.second).delete()
        }
    }

    /** When an account is deleted: its photos' files (the rows go with the account). */
    fun deleteAll(userId: String) {
        folder(userId).deleteRecursively()
    }

    private suspend fun rows(userId: String, from: String): List<Photo> {
        val found = db.tx {
            query("SELECT id, date, data FROM photos WHERE user_id = ? AND date >= ? ORDER BY at DESC", userId, from) {
                Triple(it.string("id"), it.string("date"), it.string("data"))
            }
        }
        return found.mapNotNull { (id, date, data) ->
            runCatching { ApiJson.decodeFromString(Photo.serializer(), box.open(data, context(userId, id, "meta"))).copy(id = id, date = date) }.getOrNull()
        }
    }

    /** Adds the weight logged nearest each photo's day, and time on hormone therapy then. */
    private suspend fun decorate(userId: String, photos: List<Photo>): List<Photo> {
        if (photos.isEmpty()) return photos
        val zone = profiles.days(userId)
        val hormonesSince = transition?.hormonesSince(userId)
        return photos.map { p ->
            val day = LocalDate.parse(p.date)
            val weight = records.latest(userId, Metric.WEIGHT, zone.end(day))?.takeIf { zone.end(day) - it.start < 30L * 86_400_000 }?.value
            p.copy(
                weightKg = weight,
                onHormones = hormonesSince?.takeIf { !it.isAfter(day) }?.let { TransitionService.duration(it, day) },
            )
        }
    }

    private fun clean(p: Photo): Photo {
        val now = clock()
        if (p.at > now + 6 * 3_600_000L || p.at < 0) throw ApiError.badRequest("When was it taken?")
        return p.copy(
            album = p.album.trim().take(60).ifEmpty { "Progress" },
            note = p.note?.trim()?.take(2000)?.ifEmpty { null },
            tags = p.tags.map { it.trim().take(40) }.filter { it.isNotEmpty() }.distinct().take(20),
        )
    }

    private fun safe(userId: String) = userId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "x" }
}
