package com.health.server.platform

import com.health.api.ApiJson
import com.health.api.Digest
import com.health.api.DigestSection
import com.health.api.Profile
import com.health.api.User
import com.health.api.WebhookEvent
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.util.Days
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** The weekly digest: Monday to Sunday against the week before, kept sealed, and emailed if asked. */
class DigestService(private val services: Services) {
    private val log = LoggerFactory.getLogger("samh.digest")
    private val db get() = services.db
    private val box get() = services.box

    private fun context(userId: String, week: String) = "digest:$userId:$week"

    /** Works out the digest for the week starting [monday] (not kept). */
    suspend fun build(userId: String, profile: Profile, monday: LocalDate): Digest {
        val from = monday.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val to = from.plusDays(6)
        val sections = services.hooks.filter { it.module == null || it.module in profile.modules }.mapNotNull { hook ->
            runCatching { hook.digest(userId, profile, from, to) }.onFailure { log.warn("A digest section failed: {}", it.message) }.getOrNull()
        }
        return Digest(from.toString(), to.toString(), services.clock(), headline(userId, from, to, sections), sections)
    }

    private suspend fun headline(userId: String, from: LocalDate, to: LocalDate, sections: List<DigestSection>): String {
        val rows = services.trendRows(userId, from, to)
        val sleep = rows.mapNotNull { it.sleepMinutes }.takeIf { it.isNotEmpty() }?.average()
        val steps = rows.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.average()
        val parts = listOfNotNull(sleep?.let { "${Say.minutes(it)} of sleep a night" }, steps?.let { "${Say.count(it)} steps a day" })
        return when {
            parts.isNotEmpty() -> "Your week: " + parts.joinToString(" and ") + "."
            sections.isNotEmpty() -> "Your week, ${Say.shortDate(from)} to ${Say.shortDate(to)}."
            else -> "A quiet week: nothing was recorded."
        }
    }

    suspend fun get(userId: String, week: LocalDate): Digest? {
        val monday = week.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        return db.tx { one("SELECT data FROM digests WHERE user_id = ? AND week = ?", userId, monday) { it.string("data") } }
            ?.let { runCatching { ApiJson.decodeFromString(Digest.serializer(), box.open(it, context(userId, monday))) }.getOrNull() }
            ?.let { asNow(userId, it) }
    }

    suspend fun latest(userId: String): Digest? = db.tx {
        one("SELECT week, data FROM digests WHERE user_id = ? ORDER BY week DESC LIMIT 1", userId) { it.string("week") to it.string("data") }
    }?.let { (week, data) -> runCatching { ApiJson.decodeFromString(Digest.serializer(), box.open(data, context(userId, week))) }.getOrNull() }
        ?.let { asNow(userId, it) }

    /** A kept digest as the person would see it now: without water, if they've turned it off since. */
    private suspend fun asNow(userId: String, digest: Digest): Digest = if (services.days.showsWater(userId)) digest else withoutWater(digest)

    suspend fun store(userId: String, digest: Digest) {
        val sealed = box.seal(ApiJson.encodeToString(Digest.serializer(), digest), context(userId, digest.weekStart))
        db.tx { update("INSERT OR REPLACE INTO digests (user_id, week, data, created_at) VALUES (?, ?, ?, ?)", userId, digest.weekStart, sealed, services.clock()) }
    }

    /** The digest for [week] (any day in it): kept, or worked out now if that week has begun. */
    suspend fun forWeek(userId: String, week: LocalDate): Digest {
        get(userId, week)?.let { return it }
        val profile = services.profiles.get(userId)
        val today = Days(Days.zoneOf(profile.timezone)).today(services.clock())
        if (week.isAfter(today)) throw ApiError.badRequest("That week hasn't started yet")
        return build(userId, profile, week)
    }

    /** The latest kept digest, or (before the first one) last week's, worked out now. */
    suspend fun latestOrLastWeek(userId: String): Digest {
        latest(userId)?.let { return it }
        val profile = services.profiles.get(userId)
        val today = Days(Days.zoneOf(profile.timezone)).today(services.clock())
        return build(userId, profile, today.minusWeeks(1))
    }

    /**
     * Called each minute by the scheduler: when it's the person's digest time, works out the week
     * (kept), emails it if asked, and returns the DIGEST reminder to send.
     */
    suspend fun dueReminder(user: User, profile: Profile, now: Long): Reminder? {
        val prefs = profile.digest
        if (!prefs.enabled) return null
        val zone = Days(Days.zoneOf(profile.timezone))
        val today = zone.today(now)
        val digestDay = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays((prefs.day.coerceIn(1, 7) - 1).toLong())
        val dueAt = zone.at(digestDay, LocalTime.of(prefs.hour.coerceIn(0, 23), 0))
        if (now < dueAt || digestDay.isAfter(today)) return null
        // On a Sunday, the week just ending; on any other day, the week that ended last Sunday.
        val weekEnd = if (prefs.day == 7) digestDay else digestDay.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY))
        val monday = weekEnd.minusDays(6)
        val existing = db.tx { one("SELECT emailed_at FROM digests WHERE user_id = ? AND week = ?", user.id, monday.toString()) { true } }
        if (existing != null) return null
        val digest = build(user.id, profile, monday)
        store(user.id, digest)
        if (prefs.emailEnabled && !prefs.email.isNullOrBlank()) {
            runCatching { email(user, profile, digest, prefs.email!!) }
                .onSuccess { db.tx { update("UPDATE digests SET emailed_at = ? WHERE user_id = ? AND week = ?", services.clock(), user.id, digest.weekStart) } }
                .onFailure { log.warn("The digest email for {} failed: {}", user.username, it.message) }
        }
        return Reminder(
            "digest:${digest.weekStart}", now, WebhookEvent.DIGEST, "Your week is ready", digest.headline, "Your week is ready", "Tap to see it",
            url = "/digest", data = json("week_start" to digest.weekStart, "week_end" to digest.weekEnd, "headline" to digest.headline),
        )
    }

    /** Emails [digest], leaving out sensitive features the person didn't list for email. */
    suspend fun email(user: User, profile: Profile, digest: Digest, to: String) {
        val (subject, text, html) = emailContent(user, profile, digest)
        services.platform.mailer.send(to, subject, text, html)
    }

    fun emailContent(user: User, profile: Profile, digest: Digest): Triple<String, String, String> {
        val allowed = profile.digest.emailModules
        val sections = digest.sections.filter { s -> s.module == null || !s.module!!.sensitive || s.module in allowed }
        val from = LocalDate.parse(digest.weekStart)
        val to = LocalDate.parse(digest.weekEnd)
        val subject = "Your week in health, ${Say.shortDate(from)} to ${Say.shortDate(to)}"
        val text = buildString {
            appendLine("Hi ${user.displayName},")
            appendLine()
            appendLine(digest.headline)
            sections.forEach { s ->
                appendLine()
                appendLine(s.title)
                s.lines.forEach { appendLine("  • $it") }
            }
            appendLine()
            appendLine("Made with SuperAwesomeMagicalHealth. You can change or stop these emails in Settings › Weekly digest.")
        }
        val html = buildString {
            append("""<!doctype html><html><body style="margin:0;padding:24px;background:#f4f6fb;font-family:Lexend,Segoe UI,Helvetica,Arial,sans-serif;color:#111827">""")
            append("""<div style="max-width:560px;margin:0 auto">""")
            append("""<div style="border-radius:20px;padding:24px;background:linear-gradient(135deg,#ece6ff,#dcf7ec)">""")
            append("""<div style="font-size:13px;color:#4b5565">${esc(Say.shortDate(from))} to ${esc(Say.shortDate(to))}</div>""")
            append("""<h1 style="margin:6px 0 0;font-size:22px;font-weight:600">${esc(digest.headline)}</h1></div>""")
            sections.forEach { s ->
                val accent = when (s.tone) { "good" -> "#00805e"; "warn" -> "#c2410c"; else -> "#6a3fd6" }
                append("""<div style="margin-top:14px;border-radius:16px;background:#ffffff;padding:16px 20px;border-left:4px solid $accent">""")
                append("""<div style="font-weight:600;font-size:16px;margin-bottom:6px">${esc(s.title)}</div>""")
                s.lines.forEach { append("""<div style="font-size:14px;line-height:1.5;color:#1f2937">${esc(it)}</div>""") }
                append("</div>")
            }
            append("""<p style="font-size:12px;color:#4b5565;margin-top:18px">Made with SuperAwesomeMagicalHealth. You can change or stop these emails in Settings › Weekly digest.</p>""")
            append("</div></body></html>")
        }
        return Triple(subject, text, html)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
