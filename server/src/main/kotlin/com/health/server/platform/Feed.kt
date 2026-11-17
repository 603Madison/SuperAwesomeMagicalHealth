package com.health.server.platform

import com.health.api.AppPage
import com.health.api.DayFeed
import com.health.api.DoseLog
import com.health.api.DoseStatus
import com.health.api.DueDose
import com.health.api.EnergyDay
import com.health.api.Medication
import com.health.api.Module
import com.health.api.NowAction
import com.health.api.NowItem
import com.health.api.NowKind
import com.health.api.TimelineItem
import com.health.api.TimelineKind
import com.health.server.Services
import com.health.server.auth.principal
import com.health.server.auth.requireUser
import com.health.server.care.SharedView
import com.health.server.care.ViewScope
import com.health.server.care.viewScope
import com.health.server.util.Days
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Today's feed (GET /api/feed): "Right now" and "Your day", gathered from every feature that's on
 * through [FeatureHooks.timeline] and [FeatureHooks.now]. Someone viewing a share gets only the
 * shared features' timeline: no "Right now", no health alerts ([SharedView.limit]).
 */
class FeedService(private val services: Services) {
    private val log = LoggerFactory.getLogger("samh.feed")

    /**
     * [userId]'s day ([dateText], `YYYY-MM-DD` or `today`, in their time zone). With [scope], it's
     * what [viewerId] may see of it through a share.
     */
    suspend fun feed(userId: String, dateText: String?, scope: ViewScope? = null, viewerId: String? = null): DayFeed {
        val profile = services.profiles.get(userId)
        val days = Days(Days.zoneOf(profile.timezone))
        val now = services.clock()
        val date = days.parse(dateText?.trim()?.ifEmpty { null } ?: "today", now)
        val on = profile.modules
        // A viewer's feed asks only the shared features (and never the health alerts, which aren't shared).
        val hooks = services.hooks.filter { it.module == null || it.module in on }
            .filter { scope == null || ((it.module == null || it.module in scope.modules) && it !== services.alerts) }
        fun mine(module: Module?) = module == null || module in on
        val items = hooks.flatMap { hook -> gather(hook, "timeline") { hook.timeline(userId, profile, date, date) } }
            .filter { mine(it.module) }
            .distinctBy { it.id }
            .sortedWith(TIMELINE_ORDER)
        val live = if (scope == null && date == days.today(now)) {
            hooks.flatMap { hook -> gather(hook, "now") { hook.now(userId, profile, now) } }
                .filter { mine(it.module) }
                .distinctBy { it.id }
                .sortedWith(NOW_ORDER)
        } else {
            emptyList()
        }
        val feed = DayFeed(date.toString(), live, items)
        if (scope == null) return feed
        // Amounts of food show only when both the owner and the viewer have them (FieldLayout.foodNumbers).
        val numbers = services.suggestions.numbers(userId) && (viewerId == null || services.suggestions.numbers(viewerId))
        return SharedView.limit(feed, scope.modules, numbers)
    }

    /** One feature's part: what goes wrong in one never takes the rest of the day with it. Logs say which part, never what's in it. */
    private suspend fun <T> gather(hook: FeatureHooks, what: String, read: suspend () -> List<T>): List<T> = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("A feature's {} for the feed failed ({}): {}", what, hook.javaClass.simpleName, e.javaClass.simpleName)
        emptyList()
    }

    companion object {
        /** Newest first; the day's untimed things (a cycle day, habits ticked, the check-in) after. */
        val TIMELINE_ORDER: Comparator<TimelineItem> = compareBy<TimelineItem> { it.allDay }
            .thenByDescending { it.at }
            .thenByDescending { it.end ?: it.at }
            .thenBy { it.kind.ordinal }
            .thenBy { it.id }

        /** Most pressing first; at the same priority, the soonest. */
        val NOW_ORDER: Comparator<NowItem> = compareByDescending<NowItem> { it.priority }
            .thenBy { it.at ?: Long.MAX_VALUE }
            .thenBy { it.id }
    }
}

/** Today's feed. A viewer of a share sees only the shared features' timeline (see [SharedView]). */
fun Route.feedRoutes(services: Services) {
    get("/api/feed") {
        val user = call.requireUser(services)
        val scope = call.viewScope
        val viewer = if (scope != null) call.principal(services)?.user?.id else null
        call.respond(services.feed.feed(user.id, call.request.queryParameters["date"], scope, viewer))
    }
}

/**
 * How the feed says things, the same for every feature: short, plain and factual. Detail lines are
 * parts joined with " · ".
 */
object Feed {
    /** A timeline item's id: its kind and the source's own id. */
    fun id(kind: TimelineKind, source: Any): String = "${kind.name.lowercase()}:$source"

    /** The parts that are there, joined with " · "; null with none. */
    fun line(vararg parts: String?): String? = line(parts.toList())

    fun line(parts: List<String?>): String? = parts.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }

    /** "45 min", "2 h", "2 h 30 min". */
    fun duration(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return when {
            m < 60 -> "$m min"
            m % 60 == 0 -> "${m / 60} h"
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    /** "08:30", in the person's time zone. */
    fun time(ms: Long, days: Days): String = Say.time(ms, days.zone)

    /** "cramps, bloating and headache"; past [max], "and 2 more". */
    fun words(list: List<String>, max: Int = 3): String? {
        val w = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (w.isEmpty()) return null
        val shown = w.take(max)
        val more = w.size - shown.size
        return when {
            more > 0 -> shown.joinToString(", ") + " and $more more"
            shown.size == 1 -> shown[0]
            else -> shown.dropLast(1).joinToString(", ") + " and " + shown.last()
        }
    }

    fun capitalised(text: String): String = text.trim().replaceFirstChar { it.uppercase() }

    /** "1 standard drink", "1.5 standard drinks". */
    fun standardDrinks(n: Double): String {
        val r = (n * 10).roundToInt() / 10.0
        val text = if (r % 1.0 == 0.0) r.roundToInt().toString() else r.toString()
        return "$text standard ${if (r == 1.0) "drink" else "drinks"}"
    }

    /** A dose marked taken or skipped, for the timeline. */
    fun dose(log: DoseLog, med: Medication?, days: Days, module: Module, page: AppPage): TimelineItem {
        val taken = log.status == DoseStatus.TAKEN
        val kind = if (taken) TimelineKind.DOSE_TAKEN else TimelineKind.DOSE_SKIPPED
        val slot = log.scheduledFor
        // The time it was for, when it was marked a while after.
        val forTime = slot?.takeIf { kotlin.math.abs(log.at - it) > 30 * 60_000L }?.let { "for ${time(it, days)}" }
        return TimelineItem(
            id = id(kind, log.id ?: "${log.medicationId}:${log.at}"),
            kind = kind,
            at = log.at,
            title = med?.name ?: "A medication",
            detail = if (taken) line("Taken", med?.dose, forTime, log.site) else line("Skipped", slot?.let { "was due at ${time(it, days)}" }),
            module = module,
            page = page,
            ref = log.id,
        )
    }

    /** A dose to take now: due within the hour, or its time passed without a mark. Null for one that's marked or later. */
    fun doseNow(d: DueDose, at: Long, days: Days, module: Module, page: AppPage): NowItem? {
        if (d.log != null || d.scheduledFor > at + DUE_SOON_MS) return null
        val overdue = d.scheduledFor < at
        val clock = time(d.scheduledFor, days)
        return NowItem(
            id = "dose:${d.medicationId}:${d.scheduledFor}",
            kind = if (overdue) NowKind.DOSE_OVERDUE else NowKind.DOSE_DUE,
            title = d.name,
            detail = line(d.dose, if (overdue) "was due at $clock" else "due at $clock", d.nextSite?.let { "$it next" }),
            module = module,
            page = page,
            action = NowAction("Take", NowAction.DOSE_TAKE, mapOf("medicationId" to d.medicationId, "scheduledFor" to d.scheduledFor.toString())),
            priority = if (overdue) 85 else 80,
            at = d.scheduledFor,
        )
    }

    /** A dose this close to its time counts as due now (as the web app's Up next has it). */
    const val DUE_SOON_MS = 60 * 60_000L

    /** What's left of a day's energy budget, or null without a start. */
    fun energyLeft(e: EnergyDay): Int? = e.startWith?.let { s -> s - e.spends.sumOf { it.amount } }

    /** The day's energy budget, for the timeline (with Autism & ADHD, or Mind's energy budget). */
    fun energy(e: EnergyDay, date: LocalDate, days: Days, module: Module, page: AppPage): TimelineItem? {
        val left = energyLeft(e)
        val detail = line(
            e.startWith?.let { "Started with $it" },
            left?.let { "${it.coerceAtLeast(0)} left" },
            e.spends.size.takeIf { it > 0 }?.let { Say.plural(it, "thing") + " logged" },
            e.socialBattery?.let { "social battery $it of 10" },
        ) ?: return null
        return TimelineItem(
            id = id(TimelineKind.ENERGY, date), kind = TimelineKind.ENERGY, at = days.start(date), allDay = true,
            title = "Energy", detail = detail, module = module, page = page, ref = date.toString(),
        )
    }

    /** Today's energy budget, for "Right now": what's left, or a nudge to set it. */
    fun energyNow(e: EnergyDay?, date: LocalDate, module: Module, page: AppPage): NowItem {
        val start = e?.startWith
        val left = e?.let(::energyLeft)
        val low = start != null && left != null && left <= maxOf(1, start / 6)
        return NowItem(
            id = "energy:$date",
            kind = NowKind.ENERGY_TODAY,
            title = "Energy today",
            detail = if (start != null && left != null) {
                line("${left.coerceAtLeast(0)} of $start left", e.socialBattery?.let { "social battery $it of 10" }, "it's OK to leave things for tomorrow".takeIf { low })
            } else {
                line("How much do you have to start with?", e?.socialBattery?.let { "social battery $it of 10" })
            },
            progress = if (start != null && left != null && start > 0) (left.toDouble() / start).coerceIn(0.0, 1.0) else null,
            module = module,
            page = page,
            action = NowAction("Add", NowAction.ENERGY_ADD, mapOf("date" to date.toString())),
            priority = when {
                low -> 65
                start != null -> 25
                else -> 20
            },
        )
    }
}
