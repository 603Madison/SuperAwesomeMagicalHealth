package com.health.server.care

import com.health.api.DaySummary
import com.health.api.Insight
import com.health.api.Metric
import com.health.api.Module
import com.health.api.ModulesToday
import com.health.api.Profile
import com.health.api.SessionKind
import com.health.api.Share
import com.health.api.ShareChange
import com.health.api.ShareRequest
import com.health.api.Shares
import com.health.api.TrendDay
import com.health.api.User
import com.health.server.ApiError
import com.health.server.auth.Principal
import com.health.server.auth.UserStore
import com.health.server.db.Database
import com.health.server.health.ProfileStore
import com.health.server.util.Random
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey

/** What a viewer of someone's shared data may see on this request: the owner, and the features. */
data class ViewScope(val ownerId: String, val modules: Set<Module>)

private val ViewScopeKey = AttributeKey<ViewScope>("samh.view")

/** Set when this request is someone viewing another account's shared data. */
val ApplicationCall.viewScope: ViewScope? get() = attributes.getOrNull(ViewScopeKey)

/**
 * Read-only access for someone who cares for you (a partner, a parent, a carer): another account
 * on this server sees the features the owner picks, and can never change anything. The viewer
 * sends `X-SAMH-View: <ownerId>`; [SharedView] decides which requests that may go with.
 */
class SharingService(
    private val db: Database,
    private val users: UserStore,
    private val profiles: ProfileStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Row(val id: String, val ownerId: String, val viewerId: String, val modules: Set<Module>, val createdAt: Long, val lastViewedAt: Long?)

    private fun modulesOf(text: String): Set<Module> = text.split(',').mapNotNull { runCatching { Module.valueOf(it.trim()) }.getOrNull() }.toSet()

    private suspend fun rows(where: String, vararg args: Any?): List<Row> = db.tx {
        query("SELECT id, owner_id, viewer_id, modules, created_at, last_viewed_at FROM shares WHERE $where ORDER BY created_at", *args) {
            Row(it.string("id"), it.string("owner_id"), it.string("viewer_id"), modulesOf(it.string("modules")), it.long("created_at"), it.longOrNull("last_viewed_at"))
        }
    }

    private suspend fun toShare(r: Row): Share {
        val owner = users.byId(r.ownerId)
        val viewer = users.byId(r.viewerId)
        return Share(r.id, r.ownerId, owner?.displayName ?: "Someone", r.viewerId, viewer?.displayName ?: "Someone", r.modules, r.createdAt, r.lastViewedAt)
    }

    suspend fun list(userId: String): Shares = Shares(
        given = rows("owner_id = ?", userId).map { toShare(it) },
        received = rows("viewer_id = ?", userId).filter { users.byId(it.ownerId)?.disabled == false }.map { toShare(it) },
    )

    /** Shares the [request]'s features with an account (or changes what it sees, if already shared). */
    suspend fun share(owner: User, request: ShareRequest): Share {
        val modules = checked(request.modules)
        val viewer = users.byUsername(request.username.trim()) ?: throw ApiError.badRequest("There's no account called ${request.username.trim()} on this server")
        if (viewer.id == owner.id) throw ApiError.badRequest("That's you")
        if (viewer.disabled) throw ApiError.badRequest("That account is turned off")
        val existing = rows("owner_id = ? AND viewer_id = ?", owner.id, viewer.id).firstOrNull()
        val id = existing?.id ?: Random.id()
        db.tx {
            if (existing != null) {
                update("UPDATE shares SET modules = ? WHERE id = ?", modules.joinToString(",") { it.name }, id)
            } else {
                update("INSERT INTO shares (id, owner_id, viewer_id, modules, created_at) VALUES (?, ?, ?, ?, ?)", id, owner.id, viewer.id, modules.joinToString(",") { it.name }, clock())
            }
        }
        return toShare(rows("id = ?", id).single())
    }

    suspend fun change(ownerId: String, id: String, change: ShareChange): Share {
        val modules = checked(change.modules)
        if (db.tx { update("UPDATE shares SET modules = ? WHERE id = ? AND owner_id = ?", modules.joinToString(",") { it.name }, id, ownerId) } == 0) {
            throw ApiError.notFound("No such share")
        }
        return toShare(rows("id = ?", id).single())
    }

    /** The owner stops sharing, or the viewer leaves. */
    suspend fun delete(userId: String, id: String) {
        if (db.tx { update("DELETE FROM shares WHERE id = ? AND (owner_id = ? OR viewer_id = ?)", id, userId, userId) } == 0) {
            throw ApiError.notFound("No such share")
        }
    }

    private fun checked(modules: Set<Module>): Set<Module> {
        // Nothing is shared unless it's picked; sensitive features only ever by name.
        if (modules.isEmpty()) throw ApiError.badRequest("Choose what to share")
        return modules
    }

    /**
     * This request, made by [principal] with `X-SAMH-View: <ownerId>`: the owner's account, if
     * they share with this person and what's asked for is among what they share. Throws otherwise.
     */
    suspend fun view(call: ApplicationCall, principal: Principal, ownerId: String): User {
        if (principal.session.kind != SessionKind.WEB && principal.session.kind != SessionKind.APP) throw ApiError.forbidden("Only the apps can view shared data")
        val share = rows("owner_id = ? AND viewer_id = ?", ownerId.trim(), principal.user.id).firstOrNull()
            ?: throw ApiError.forbidden("That account hasn't shared anything with you")
        val owner = users.byId(share.ownerId)?.takeIf { !it.disabled } ?: throw ApiError.forbidden("That account hasn't shared anything with you")
        val visible = share.modules intersect profiles.get(owner.id).modules
        val need = SharedView.requirement(call.request.path(), call.request.queryParameters["metric"])
        if (need is SharedView.Need.AnyOf && need.modules.none { it in visible }) throw ApiError.forbidden("That isn't shared with you")
        if (need is SharedView.Need.Never) throw ApiError.forbidden("That isn't something that can be shared")
        call.attributes.put(ViewScopeKey, ViewScope(owner.id, visible))
        if ((share.lastViewedAt ?: 0) < clock() - 60_000) {
            db.tx { update("UPDATE shares SET last_viewed_at = ? WHERE id = ?", clock(), share.id) }
        }
        return owner
    }
}

/** Which requests a viewer may make, and what of the owner's they're shown. */
object SharedView {
    const val HEADER = "X-SAMH-View"

    sealed interface Need {
        /** Filtered to what's shared (days, trends, the profile). */
        data object Always : Need
        data object Never : Need
        data class AnyOf(val modules: Set<Module>) : Need
    }

    private fun any(vararg m: Module) = Need.AnyOf(m.toSet())

    private val PREFIXES: List<Pair<String, Need>> = listOf(
        "/api/day" to Need.Always,
        "/api/trends" to Need.Always,
        "/api/workouts" to any(Module.ACTIVITY),
        "/api/sleep" to any(Module.SLEEP),
        "/api/checkins" to any(Module.MIND),
        "/api/food" to any(Module.NUTRITION),
        "/api/water" to any(Module.NUTRITION),
        "/api/drinks" to any(Module.NUTRITION),
        "/api/mind" to any(Module.MIND),
        "/api/cycle" to any(Module.CYCLE),
        "/api/continence" to any(Module.CONTINENCE),
        "/api/meds" to any(Module.MEDICATIONS),
        "/api/labs" to any(Module.MEDICATIONS, Module.HORMONES),
        "/api/vitals" to any(Module.VITALS),
        "/api/habits" to any(Module.HABITS),
        "/api/symptoms" to any(Module.SYMPTOMS),
        "/api/hormones" to any(Module.HORMONES),
        "/api/appointments" to any(Module.APPOINTMENTS),
    )

    /** What viewing [path] needs; [metric] is the query's, for series and measurements. */
    fun requirement(path: String, metric: String?): Need {
        if (path == "/api/profile") return Need.Always
        if (path == "/api/series" || path == "/api/measurements" || path.startsWith("/api/measurements/")) {
            return metric?.let { runCatching { Metric.valueOf(it) }.getOrNull() }?.let { any(moduleOf(it)) } ?: Need.Never
        }
        return PREFIXES.firstOrNull { (prefix, _) -> path == prefix || path.startsWith("$prefix/") }?.second ?: Need.Never
    }

    /** The feature a metric belongs to, for sharing. */
    fun moduleOf(metric: Metric): Module = when (metric) {
        Metric.STEPS, Metric.DISTANCE, Metric.ACTIVE_CALORIES, Metric.FLOORS, Metric.HEART_RATE, Metric.STRESS -> Module.ACTIVITY
        Metric.RESTING_HEART_RATE, Metric.HRV, Metric.SPO2, Metric.RESPIRATORY_RATE, Metric.SKIN_TEMPERATURE, Metric.TEMPERATURE_DEVIATION -> Module.SLEEP
        Metric.WEIGHT, Metric.BODY_FAT -> Module.BODY
        Metric.BLOOD_PRESSURE_SYSTOLIC, Metric.BLOOD_PRESSURE_DIASTOLIC, Metric.BLOOD_GLUCOSE -> Module.VITALS
    }

    /** Checks a request that carries the header: only reading, and only what can be shared. */
    fun check(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Get && call.request.httpMethod != HttpMethod.Head) {
            throw ApiError.forbidden("You're viewing someone else's data, which you can't change")
        }
        if (requirement(call.request.path(), call.request.queryParameters["metric"]) == Need.Never) {
            throw ApiError.forbidden("That isn't something that can be shared")
        }
    }

    /** The day's core insights carry no tag; their icon says what they're about. */
    private val ICONS: Map<String, Module?> = mapOf(
        "zap" to Module.SLEEP, "battery-low" to Module.SLEEP, "heart" to Module.SLEEP, "activity" to Module.SLEEP,
        "thermometer" to Module.SLEEP, "moon" to Module.SLEEP,
        "footprints" to Module.ACTIVITY, "dumbbell" to Module.ACTIVITY,
        "egg" to Module.NUTRITION, "flame" to Module.NUTRITION, "droplet" to Module.NUTRITION, "coffee" to Module.NUTRITION, "wine" to Module.NUTRITION,
        "scale" to Module.BODY,
        // A welcome, or "learning your baseline": about nothing in particular.
        "sparkles" to null,
    )

    /**
     * Whether a viewer who may see [modules] sees [insight]: by its tag, or for an untagged one by
     * the subject its icon shows. An untagged insight with an icon not listed here is the owner's only.
     */
    fun visible(insight: Insight, modules: Set<Module>): Boolean {
        insight.module?.let { return it in modules }
        if (insight.icon !in ICONS) return false
        return ICONS[insight.icon]?.let { it in modules } ?: true
    }

    /** The day, with only what [modules] cover. */
    fun limit(d: DaySummary, modules: Set<Module>): DaySummary {
        fun on(m: Module) = m in modules
        val activity = on(Module.ACTIVITY)
        val sleep = on(Module.SLEEP)
        val food = on(Module.NUTRITION)
        val body = on(Module.BODY)
        val m = d.modules
        return d.copy(
            readiness = d.readiness.takeIf { sleep },
            sleep = d.sleep.takeIf { sleep },
            activity = d.activity.takeIf { activity },
            steps = if (activity) d.steps else 0,
            distanceM = if (activity) d.distanceM else 0.0,
            activeCalories = if (activity) d.activeCalories else 0.0,
            restingCalories = d.restingCalories.takeIf { food },
            floors = d.floors.takeIf { activity },
            restingHeartRate = d.restingHeartRate.takeIf { sleep },
            avgHeartRate = d.avgHeartRate.takeIf { activity },
            minHeartRate = d.minHeartRate.takeIf { activity },
            maxHeartRate = d.maxHeartRate.takeIf { activity },
            hrv = d.hrv.takeIf { sleep },
            spo2 = d.spo2.takeIf { sleep },
            respiratoryRate = d.respiratoryRate.takeIf { sleep },
            temperatureDeviation = d.temperatureDeviation.takeIf { sleep },
            stress = d.stress.takeIf { activity },
            stressDay = d.stressDay.takeIf { activity },
            resilience = d.resilience.takeIf { activity },
            sleepSummary = d.sleepSummary.takeIf { sleep },
            naps = if (sleep) d.naps else emptyList(),
            workouts = if (activity) d.workouts else emptyList(),
            nutrition = if (food) d.nutrition else com.health.api.Nutrients(),
            nutritionTarget = d.nutritionTarget.takeIf { food },
            waterMl = if (food) d.waterMl else 0,
            drinks = d.drinks.takeIf { food },
            weightKg = d.weightKg.takeIf { body },
            bodyFat = d.bodyFat.takeIf { body },
            checkIn = d.checkIn.takeIf { on(Module.MIND) },
            insights = d.insights.filter { visible(it, modules) },
            hourlySteps = if (activity) d.hourlySteps else emptyList(),
            heartRate = if (activity) d.heartRate else emptyList(),
            modules = ModulesToday(
                mind = m.mind.takeIf { on(Module.MIND) },
                cycle = m.cycle.takeIf { on(Module.CYCLE) },
                continence = m.continence.takeIf { on(Module.CONTINENCE) },
                meds = m.meds.takeIf { on(Module.MEDICATIONS) },
                vitals = m.vitals.takeIf { on(Module.VITALS) },
                habits = m.habits.takeIf { on(Module.HABITS) },
                symptoms = m.symptoms.takeIf { on(Module.SYMPTOMS) },
                hormones = m.hormones.takeIf { on(Module.HORMONES) },
                lifeStage = m.lifeStage.takeIf { on(Module.CYCLE) },
                appointments = m.appointments.takeIf { on(Module.APPOINTMENTS) },
            ),
        )
    }

    /** A trend day, with only what [modules] cover. */
    fun limit(t: TrendDay, modules: Set<Module>): TrendDay {
        fun on(m: Module) = m in modules
        val activity = on(Module.ACTIVITY)
        val sleep = on(Module.SLEEP)
        val food = on(Module.NUTRITION)
        val body = on(Module.BODY)
        return t.copy(
            readiness = t.readiness.takeIf { sleep },
            sleepScore = t.sleepScore.takeIf { sleep },
            activityScore = t.activityScore.takeIf { activity },
            steps = t.steps.takeIf { activity },
            activeCalories = t.activeCalories.takeIf { activity },
            restingHeartRate = t.restingHeartRate.takeIf { sleep },
            hrv = t.hrv.takeIf { sleep },
            sleepMinutes = t.sleepMinutes.takeIf { sleep },
            deepMinutes = t.deepMinutes.takeIf { sleep },
            remMinutes = t.remMinutes.takeIf { sleep },
            spo2 = t.spo2.takeIf { sleep },
            temperatureDeviation = t.temperatureDeviation.takeIf { sleep },
            weightKg = t.weightKg.takeIf { body },
            bodyFat = t.bodyFat.takeIf { body },
            calories = t.calories.takeIf { food },
            protein = t.protein.takeIf { food },
            carbs = t.carbs.takeIf { food },
            fat = t.fat.takeIf { food },
            waterMl = t.waterMl.takeIf { food },
            workouts = if (activity) t.workouts else 0,
            workoutMinutes = if (activity) t.workoutMinutes else 0,
            mood = t.mood.takeIf { on(Module.MIND) },
            anxiety = t.anxiety.takeIf { on(Module.MIND) },
            mindfulMinutes = t.mindfulMinutes.takeIf { on(Module.MIND) },
            flow = t.flow.takeIf { on(Module.CYCLE) },
            voids = t.voids.takeIf { on(Module.CONTINENCE) },
            nightVoids = t.nightVoids.takeIf { on(Module.CONTINENCE) },
            leaks = t.leaks.takeIf { on(Module.CONTINENCE) },
            dosesTaken = t.dosesTaken.takeIf { on(Module.MEDICATIONS) },
            dosesDue = t.dosesDue.takeIf { on(Module.MEDICATIONS) },
            stressedMinutes = t.stressedMinutes.takeIf { activity },
            restoredMinutes = t.restoredMinutes.takeIf { activity },
            caffeineMg = t.caffeineMg.takeIf { food },
            standardDrinks = t.standardDrinks.takeIf { food },
            systolic = t.systolic.takeIf { on(Module.VITALS) },
            diastolic = t.diastolic.takeIf { on(Module.VITALS) },
            glucose = t.glucose.takeIf { on(Module.VITALS) },
            symptomWorst = t.symptomWorst.takeIf { on(Module.SYMPTOMS) },
            migraine = t.migraine.takeIf { on(Module.SYMPTOMS) },
            habitsDone = t.habitsDone.takeIf { on(Module.HABITS) },
            habitsDue = t.habitsDue.takeIf { on(Module.HABITS) },
            hotFlashes = t.hotFlashes.takeIf { on(Module.CYCLE) },
        )
    }

    /**
     * The owner's profile as a viewer sees it: units, time zone and goals (to draw the day), the
     * features they may see, and nothing else (no birth year, height unless body is shared,
     * reminders, digest email or place).
     */
    fun limit(p: Profile, modules: Set<Module>): Profile = Profile(
        birthYear = null,
        heightCm = p.heightCm.takeIf { Module.BODY in modules },
        energyProfile = p.energyProfile,
        units = p.units,
        timezone = p.timezone,
        goals = p.goals,
        modules = p.modules intersect modules,
        vitalsTargets = p.vitalsTargets,
    )
}
