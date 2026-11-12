package com.health.server.platform

import com.health.api.Profile
import com.health.server.ApiError
import com.health.server.Services
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Everything, for the person to keep or take elsewhere: CSV files, and a FHIR R4 bundle. */
class ExportService(private val services: Services) {
    private val log = LoggerFactory.getLogger("samh.export")
    private val pretty = Json { prettyPrint = true }

    private fun check(from: LocalDate, to: LocalDate) {
        if (from.isAfter(to)) throw ApiError.badRequest("The start comes after the end")
        if (ChronoUnit.DAYS.between(from, to) > 3660) throw ApiError.badRequest("An export covers ten years at most")
    }

    private suspend fun hooks(profile: Profile) = services.hooks.filter { it.module == null || it.module in profile.modules }

    suspend fun csvFiles(userId: String, from: LocalDate, to: LocalDate): Map<String, List<List<String>>> {
        check(from, to)
        val profile = services.profiles.get(userId)
        val files = linkedMapOf<String, List<List<String>>>()
        hooks(profile).forEach { hook ->
            runCatching { hook.export(userId, profile, from, to) }.onFailure { log.warn("An export part failed: {}", it.message, it) }.getOrNull()?.forEach { (name, rows) ->
                // Two features naming the same file: their rows go together under the first header.
                files[name] = files[name]?.let { it + rows.drop(1) } ?: rows
            }
        }
        return files
    }

    suspend fun fhir(userId: String, from: LocalDate, to: LocalDate): JsonObject {
        check(from, to)
        val profile = services.profiles.get(userId)
        val resources = hooks(profile).flatMap { hook ->
            runCatching { hook.fhir(userId, profile, from, to) }.onFailure { log.warn("A FHIR part failed: {}", it.message, it) }.getOrDefault(emptyList())
        }
        // Every resource is about the person: add them as the subject where a feature left it out.
        val withSubject = resources.map { r ->
            val type = (r["resourceType"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (type == "Patient" || r.containsKey("subject")) r
            else JsonObject(r + ("subject" to kotlinx.serialization.json.buildJsonObject { put("reference", kotlinx.serialization.json.JsonPrimitive(Fhir.patientRef(userId))) }))
        }
        // The person comes first; then everything else, each once.
        val sorted = withSubject.sortedBy { if ((it["resourceType"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "Patient") 0 else 1 }
            .distinctBy { (it["resourceType"]?.toString() ?: "") + "/" + (it["id"]?.toString() ?: "") }
        return Fhir.bundle(sorted, services.clock())
    }

    fun fhirText(bundle: JsonObject): String = pretty.encodeToString(JsonObject.serializer(), bundle)

    suspend fun zip(userId: String, from: LocalDate, to: LocalDate): ByteArray {
        val files = csvFiles(userId, from, to)
        val bundle = fhir(userId, from, to)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("README.txt", readme(from, to, files.keys))
            files.forEach { (name, rows) -> put(name, Csv.write(rows)) }
            put("fhir.json", fhirText(bundle))
        }
        return out.toByteArray()
    }

    private fun readme(from: LocalDate, to: LocalDate, names: Collection<String>) = buildString {
        appendLine("SuperAwesomeMagicalHealth export, ${Say.range(from, to)}.")
        appendLine()
        appendLine("Each .csv file opens in any spreadsheet. Times carry their offset from UTC; units are metric")
        appendLine("(kg, metres, kcal, ml, °C, mmol/L) whatever the app shows.")
        appendLine()
        names.forEach { appendLine("  $it") }
        appendLine("  fhir.json: the same data as a FHIR R4 Bundle, for health record systems.")
        appendLine()
        appendLine("This file holds private health information: keep it somewhere safe.")
    }
}
