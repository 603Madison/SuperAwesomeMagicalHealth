package com.health.app.net

import com.health.api.ApiJson
import com.health.api.Metric
import com.health.api.Photo
import com.health.api.PhotoAlbum
import com.health.api.Point
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate

/*
 * Progress photos (docs/API.md, "Progress photos"), body measurements' series, and the FIT export
 * for Garmin Connect, Strava and the like. Pictures come back as bytes, for memory only: nothing
 * here writes them to storage.
 */

suspend fun ServerApi.photos(album: String? = null, days: Int? = null): List<Photo> {
    val query = listOfNotNull(album?.let { "album=${enc(it)}" }, days?.let { "days=$it" }).joinToString("&")
    return get("${base()}/api/photos" + (if (query.isEmpty()) "" else "?$query"), ListSerializer(Photo.serializer()))
}

suspend fun ServerApi.photoAlbums(): List<PhotoAlbum> = get("${base()}/api/photos/albums", ListSerializer(PhotoAlbum.serializer()))

/** The parts of a photo's upload: the picture as `file`, and its details as `photo` (JSON). */
internal fun photoParts(bytes: ByteArray, type: String, photo: Photo): List<ServerApi.Part> = listOf(
    ServerApi.Part("photo", text = ApiJson.encodeToString(Photo.serializer(), photo)),
    ServerApi.Part("file", bytes = bytes, fileName = "photo." + (if (type.endsWith("png")) "png" else if (type.endsWith("webp")) "webp" else "jpg"), type = type),
)

/** Uploads a picture (JPEG, PNG or WebP, up to 20 MB); the server drops its location and camera details. */
suspend fun ServerApi.uploadPhoto(bytes: ByteArray, type: String, photo: Photo): Photo = multipart("/api/photos", photoParts(bytes, type, photo), Photo.serializer())

suspend fun ServerApi.photoImage(id: String): ByteArray = bytes("/api/photos/${enc(id)}/image")

suspend fun ServerApi.photoThumb(id: String): ByteArray = bytes("/api/photos/${enc(id)}/thumb")

suspend fun ServerApi.updatePhoto(photo: Photo): Photo =
    send("PUT", "${base()}/api/photos/${enc(photo.id!!)}", ApiJson.encodeToString(Photo.serializer(), photo), Photo.serializer())

suspend fun ServerApi.deletePhoto(id: String) = delete("/api/photos/${enc(id)}")

// ---- Body measurements' series ----

/** A metric over [from]..[to] (epoch ms, up to 400 days), thinned by the server to a few hundred points. */
suspend fun ServerApi.series(metric: Metric, from: Long, to: Long): List<Point> =
    get("${base()}/api/series?metric=${metric.name}&from=$from&to=$to", ListSerializer(Point.serializer()))

// ---- FIT export ----

/** Workouts, and weight with body fat, in [from]..[to] as a zip of FIT files. */
suspend fun ServerApi.fitExport(from: LocalDate, to: LocalDate): ByteArray = bytes("/api/export/fit?from=$from&to=$to")
