package com.health.server.care

import com.health.api.LifeStage
import com.health.api.User
import com.health.server.Services
import com.health.server.util.Days
import java.time.Instant

/** The care features as plain text, for the AI connector. */
object CareText {
    private fun time(days: Days, ms: Long) = Instant.ofEpochMilli(ms).atZone(days.zone).let { "%s %02d:%02d".format(it.toLocalDate(), it.hour, it.minute) }

    suspend fun hormones(services: Services, user: User): String = buildString {
        val o = services.hormones.overview(user.id)
        if (o.medications.isEmpty() && o.labs.isEmpty()) {
            append("No hormone medications or lab results yet. (Medications are marked as part of hormone therapy in the Medications page.)")
            return@buildString
        }
        val days = services.profiles.days(user.id)
        appendLine("Hormone therapy:")
        o.medications.forEach { m ->
            appendLine("- ${m.medication.name}: ${HormonesService.describe(m.medication)}${if (!m.medication.active) " (stopped)" else ""}${m.since?.let { ", since $it" } ?: ""}")
            m.changes.forEach { c -> appendLine("    ${days.dateOf(c.at)}: ${c.from?.let { "$it → " } ?: ""}${c.to}${c.note?.let { " ($it)" } ?: ""}") }
        }
        if (o.labs.isNotEmpty()) {
            appendLine("Labs (the person's own target ranges, from their clinician):")
            o.labs.forEach { s ->
                appendLine("- ${s.name}${s.target?.let { " (range ${HormonesService.rangeText(it)})" } ?: ""}: " + s.results.takeLast(6).joinToString("; ") { "${it.date} ${num(it.value)} ${it.unit}" })
            }
        }
        val out = HormonesService.outOfRange(o.labs)
        if (out.isNotEmpty()) appendLine("Latest result outside its range: ${out.joinToString { "${it.first.name} ${num(it.second.value)} ${it.second.unit}" }}.")
        o.lastLabs?.let { appendLine("Last labs $it; ${o.nextLabsDue?.let { d -> "next due around $d" } ?: "no interval set"}.") }
    }

    suspend fun lifeStage(services: Services, user: User, count: Int): String = buildString {
        val o = services.lifeStages.overview(user.id, count)
        val days = services.profiles.days(user.id)
        appendLine("Life stage: ${o.info.stage.label}${o.info.since?.let { " (since $it)" } ?: ""}.")
        o.pregnancy?.let { appendLine("Pregnancy: week ${it.week}+${it.day}, trimester ${it.trimester}, due ${it.dueDate} (${it.daysToGo} days to go).") }
        o.weeksSinceBirth?.let { appendLine("$it weeks since the birth (${o.info.birthDate}).") }
        o.monthsSinceLastPeriod?.let { appendLine("$it months since the last period${if (it >= 12 && o.info.stage == LifeStage.PERIMENOPAUSE) " (twelve months usually marks menopause)" else ""}.") }
        if (o.kicks.isNotEmpty()) appendLine("Movement counts: " + o.kicks.take(10).joinToString("; ") { "${time(days, it.start)} ${it.kicks}" })
        o.contractionsPattern?.let { appendLine("Contractions: $it") }
        if (o.feeds.isNotEmpty()) appendLine("Feeds (latest first): " + o.feeds.take(12).joinToString("; ") { "${time(days, it.start)} ${it.kind.label}${it.amountMl?.let { ml -> " $ml ml" } ?: ""}" })
        if (o.hotFlashes.isNotEmpty()) appendLine("Hot flashes in the last $count days: ${o.hotFlashes.size} (about ${o.hotFlashesPerDay} a day, ${o.hotFlashes.count { it.night }} at night).")
    }

    suspend fun appointments(services: Services, user: User, count: Int): String = buildString {
        val list = services.appointments.list(user.id, count, upcoming = false)
        if (list.isEmpty()) {
            append("No appointments in the last $count days or coming up.")
            return@buildString
        }
        val days = services.profiles.days(user.id)
        val now = services.clock()
        list.partition { it.at >= now }.let { (next, past) ->
            if (next.isNotEmpty()) appendLine("Coming up:")
            next.forEach { a ->
                appendLine("- ${time(days, a.at)}: ${a.title}${a.with?.let { " with $it" } ?: ""}${a.place?.let { " at $it" } ?: ""}")
                a.questions.forEach { q -> appendLine("    [${if (q.done) "x" else " "}] ${q.text}") }
            }
            if (past.isNotEmpty()) appendLine("Before:")
            past.reversed().take(20).forEach { a ->
                appendLine("- ${time(days, a.at)}: ${a.title}${a.with?.let { " with $it" } ?: ""}${a.notes?.let { "; notes: ${it.take(300)}" } ?: ""}")
            }
        }
    }
}
