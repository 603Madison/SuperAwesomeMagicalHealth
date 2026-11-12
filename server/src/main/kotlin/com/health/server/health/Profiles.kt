package com.health.server.health

import com.health.api.ApiJson
import com.health.api.CheckIn
import com.health.api.Profile
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.util.Days
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId

/** Each person's profile and goals. */
class ProfileStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun get(userId: String): Profile = db.tx {
        one("SELECT data FROM profiles WHERE user_id = ?", userId) { it.string("data") }
    }?.let { runCatching { ApiJson.decodeFromString(Profile.serializer(), it) }.getOrNull() } ?: Profile()

    suspend fun put(userId: String, profile: Profile): Profile {
        val clean = validated(profile)
        db.tx {
            update(
                "INSERT OR REPLACE INTO profiles (user_id, data, updated_at) VALUES (?, ?, ?)",
                userId, ApiJson.encodeToString(Profile.serializer(), clean), clock(),
            )
        }
        return clean
    }

    /** The person's days, in their time zone (the server's until they set one). */
    suspend fun days(userId: String): Days = Days(Days.zoneOf(get(userId).timezone))

    companion object {
        fun validated(profile: Profile): Profile {
            val thisYear = Year.now().value
            profile.birthYear?.let { if (it !in 1900..thisYear - 5) throw ApiError.badRequest("That birth year doesn't look right") }
            profile.heightCm?.let { if (it !in 50.0..260.0) throw ApiError.badRequest("Height is in centimetres, between 50 and 260") }
            if (profile.timezone.isNotBlank() && runCatching { ZoneId.of(profile.timezone) }.isFailure) {
                throw ApiError.badRequest("That time zone isn't one I know (try a name like Europe/London)")
            }
            val g = profile.goals
            fun check(ok: Boolean, message: String) { if (!ok) throw ApiError.badRequest(message) }
            check(g.steps in 0..100_000, "A step goal is between 0 and 100,000")
            check(g.activeCalories in 0..10_000, "An active calorie goal is between 0 and 10,000")
            check(g.sleepMinutes in 180..960, "A sleep goal is between 3 and 16 hours")
            check(g.calories == null || g.calories in 800..10_000, "A calorie goal is between 800 and 10,000")
            check(g.protein == null || g.protein in 0..1000, "Protein is in grams, up to 1,000")
            check(g.carbs == null || g.carbs in 0..2000, "Carbs are in grams, up to 2,000")
            check(g.fat == null || g.fat in 0..1000, "Fat is in grams, up to 1,000")
            check(g.waterMl in 0..10_000, "A water goal is up to 10 litres")
            check(g.workoutsPerWeek in 0..21, "Workouts a week: up to 21")
            check(g.targetWeightKg.let { it == null || it in 25.0..400.0 }, "A target weight is in kilograms, 25 to 400")
            check(g.weeklyChangeKg in -1.5..1.0, "Aim to change at most 1.5 kg a week")
            // Blood pressure and glucose targets, and the place for the weather.
            val v = profile.vitalsTargets
            check(v.systolicHigh in 90..200 && v.diastolicHigh in 50..130, "Blood pressure targets look like 130/80")
            check(v.glucoseLow in 2.0..8.0 && v.glucoseHigh in 5.0..25.0 && v.glucoseLow < v.glucoseHigh, "A glucose range is in mmol/L, like 3.9 to 10")
            profile.place?.let { p -> check(p.latitude in -90.0..90.0 && p.longitude in -180.0..180.0, "That place's position doesn't look right") }
            return profile.copy(timezone = profile.timezone.trim(), place = profile.place?.let { it.copy(name = it.name.trim().take(120)) })
        }
    }
}

/** How the person felt each day. */
class CheckInStore(private val db: Database) {
    suspend fun get(userId: String, date: LocalDate): CheckIn = db.tx {
        one("SELECT data FROM checkins WHERE user_id = ? AND date = ?", userId, date.toString()) { it.string("data") }
    }?.let { runCatching { ApiJson.decodeFromString(CheckIn.serializer(), it) }.getOrNull()?.copy(date = date.toString()) }
        ?: CheckIn(date.toString())

    suspend fun put(userId: String, date: LocalDate, checkIn: CheckIn): CheckIn {
        listOf(checkIn.mood, checkIn.energy, checkIn.stress, checkIn.soreness).forEach {
            if (it != null && it !in 1..5) throw ApiError.badRequest("Check-ins are rated 1 to 5")
        }
        val clean = checkIn.copy(
            date = date.toString(),
            tags = checkIn.tags.map { it.trim().lowercase().take(40) }.filter { it.isNotEmpty() }.distinct().take(20),
            note = checkIn.note?.trim()?.take(2000)?.ifBlank { null },
        )
        db.tx {
            update(
                "INSERT OR REPLACE INTO checkins (user_id, date, data) VALUES (?, ?, ?)",
                userId, date.toString(), ApiJson.encodeToString(CheckIn.serializer(), clean),
            )
        }
        return clean
    }

    suspend fun range(userId: String, from: LocalDate, to: LocalDate): Map<String, CheckIn> = db.tx {
        query("SELECT date, data FROM checkins WHERE user_id = ? AND date >= ? AND date <= ?", userId, from.toString(), to.toString()) {
            it.string("date") to it.string("data")
        }
    }.mapNotNull { (date, data) -> runCatching { ApiJson.decodeFromString(CheckIn.serializer(), data) }.getOrNull()?.let { date to it } }.toMap()
}
