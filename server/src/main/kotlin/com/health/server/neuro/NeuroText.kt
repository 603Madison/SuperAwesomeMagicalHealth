package com.health.server.neuro

import com.health.api.MilestoneStatus
import com.health.api.MomentKind
import com.health.server.Services
import com.health.server.platform.Say
import java.util.Locale

/** Autism & ADHD and transition, as plain text for the AI connector. */
object NeuroText {
    suspend fun neuro(services: Services, userId: String, days: Int): String = buildString {
        val n = services.neuro
        val zone = services.profiles.days(userId).zone
        val about = n.profile(userId)
        val s = n.summary(userId, days)
        NeuroService.describe(about)?.let { appendLine(it) }
        if (about.whatHelps.isNotEmpty()) appendLine("What helps: ${about.whatHelps.joinToString(", ")}.")
        if (about.communication.isNotEmpty()) appendLine("Communication: ${about.communication.joinToString(", ")}.")
        if (about.senses.isNotEmpty()) appendLine("Senses: " + about.senses.joinToString("; ") { p -> "${p.sense.label.lowercase(Locale.ROOT)} ${when { p.response < 0 -> "avoids"; p.response > 0 -> "seeks"; else -> "neutral" }}" })
        appendLine("Last ${s.days} days: ${plural(s.overloads, "time")} things got too much" + (if (s.byKind.isNotEmpty()) " (" + s.byKind.joinToString(", ") { "${it.count} ${it.name.lowercase(Locale.ROOT)}" } + ")" else "") + ".")
        if (s.triggers.isNotEmpty()) appendLine("Triggers: " + s.triggers.joinToString(", ") { "${it.name} (${it.count})" } + ".")
        if (s.whatHelped.isNotEmpty()) appendLine("What helped: " + s.whatHelped.joinToString(", ") { "${it.name} (${it.count})" } + ".")
        s.averageMasking?.let { appendLine("Masking averaged $it of 5.") }
        if (s.focusMinutes > 0) appendLine("Focus or interest time: ${hm(s.focusMinutes)}.")
        s.estimateRatio?.let { appendLine("Tasks took about ${it}× the time guessed.") }
        s.patterns.forEach { appendLine("Pattern: ${it.title}. ${it.body}") }
        s.burnoutRisk?.let { appendLine("Burnout signs: $it") }
        n.events(userId, days).take(10).forEach { e ->
            appendLine("- ${Say.dateTime(e.start, zone)} ${e.kind.label.lowercase(Locale.ROOT)}, ${e.intensity} of 5" +
                (if (e.triggers.isNotEmpty()) "; triggers ${e.triggers.joinToString(", ")}" else "") +
                (if (e.whatHelped.isNotEmpty()) "; helped: ${e.whatHelped.joinToString(", ")}" else ""))
        }
        n.energy(userId, minOf(days, 14)).take(7).forEach { d ->
            appendLine("- ${d.date}: energy ${d.startWith?.let { s0 -> "${s0 - d.spends.sumOf { it.amount }} of $s0 left" } ?: "not budgeted"}${d.socialBattery?.let { ", social battery $it of 10" } ?: ""}")
        }
    }.trim().ifEmpty { "Nothing logged yet." }

    suspend fun transition(services: Services, userId: String): String = buildString {
        val o = services.transition.overview(userId)
        val zone = services.profiles.days(userId).zone
        o.profile.pronouns?.let { appendLine("Pronouns: $it.") }
        o.sinceStart?.let { appendLine("Transitioning for $it.") }
        o.onHormones?.let { appendLine("On hormone therapy for $it.") }
        if (o.profile.goals.isNotEmpty()) appendLine("Goals: ${o.profile.goals.joinToString("; ")}.")
        o.highlights.forEach { appendLine(it) }
        o.milestones.forEach { m ->
            appendLine("- ${m.title} (${m.area.label.lowercase(Locale.ROOT)}): ${m.status.label.lowercase(Locale.ROOT)}${m.date?.let { ", $it" } ?: ""}" +
                (if (m.checklist.isNotEmpty() && m.status != MilestoneStatus.DONE) ", ${m.checklist.count { it.done }} of ${m.checklist.size} steps" else ""))
        }
        o.recentChanges.take(5).forEach { appendLine("- ${it.date} ${it.area}: ${it.note}") }
        if (o.voice.isNotEmpty()) appendLine("Voice practice (180 days): ${o.voice.sumOf { it.minutes }} minutes in ${plural(o.voice.size, "session")}" +
            (o.voice.mapNotNull { it.pitchAvgHz }.takeIf { it.isNotEmpty() }?.let { "; latest average pitch ${it.first().toInt()} Hz" } ?: "") + ".")
        if (o.hairByArea.isNotEmpty()) appendLine("Hair removal sessions: " + o.hairByArea.joinToString(", ") { "${it.name} ${it.count}" } + ".")
        appendLine("Last 30 days: ${plural(o.euphoria, "moment")} of euphoria, ${plural(o.dysphoria, "moment")} of dysphoria.")
        services.transition.moments(userId, 14).take(5).forEach { m ->
            appendLine("- ${Say.dateTime(m.at, zone)} ${if (m.kind == MomentKind.EUPHORIA) "euphoria" else "dysphoria"}, ${m.intensity} of 5${if (m.context.isNotEmpty()) " (${m.context.joinToString(", ")})" else ""}")
        }
    }.trim().ifEmpty { "Nothing logged yet." }
}
