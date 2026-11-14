package com.health.server

import com.health.api.Role
import com.health.server.auth.LoginThrottle
import com.health.server.auth.OidcClient
import com.health.server.auth.PendingLogins
import com.health.server.auth.SessionStore
import com.health.server.auth.UserStore
import com.health.server.db.Database
import com.health.server.db.Schema
import com.health.server.food.FoodStore
import com.health.server.food.OpenFoodFacts
import com.health.server.health.CheckInStore
import com.health.server.health.DeviceStore
import com.health.server.health.FitImporter
import com.health.server.health.Ingest
import com.health.server.health.ProfileStore
import com.health.server.health.Records
import com.health.server.insights.DayService
import com.health.server.modules.ContinenceService
import com.health.server.modules.CycleService
import com.health.server.modules.EntryStore
import com.health.server.modules.MedsService
import com.health.server.modules.MindService
import com.health.server.modules.ModuleService
import com.health.server.platform.CoreHooks
import com.health.server.platform.FeatureHooks
import com.health.server.tracking.DrinksHooks
import com.health.server.tracking.DrinksService
import com.health.server.tracking.HabitsHooks
import com.health.server.tracking.HabitsService
import com.health.server.tracking.StressHooks
import com.health.server.tracking.SymptomsHooks
import com.health.server.tracking.SymptomsService
import com.health.server.tracking.TrackingService
import com.health.server.tracking.VitalsHooks
import com.health.server.tracking.VitalsService
import com.health.server.tracking.Weather
import com.health.server.platform.Platform
import com.health.server.util.Http
import com.health.server.util.Passwords
import com.health.server.util.Random
import com.health.server.util.SecretBox
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.util.Properties

/** Everything the server is made of, built once at start-up. */
class Services(
    val config: ServerConfig,
    val db: Database,
    val box: SecretBox,
    val http: OkHttpClient = Http.createClient(),
    passwords: Passwords = Passwords(),
    val clock: () -> Long = System::currentTimeMillis,
    /** Where Open Food Facts is (a test server in tests). */
    foodDatabaseUrls: OpenFoodFacts.Urls = OpenFoodFacts.Urls(),
) : AutoCloseable {
    val users = UserStore(db, passwords, clock)
    val sessions = SessionStore(db, clock)
    val settings = SettingsStore(db, box)
    val oidc = OidcClient(http, clock)
    val pending = PendingLogins(clock)
    val throttle = LoginThrottle(clock = clock)
    val profiles = ProfileStore(db, clock)
    val devices = DeviceStore(db, clock)
    val records = Records(db, clock)
    val checkIns = CheckInStore(db)
    val days = DayService(db, profiles, records, clock)
    val ingest = Ingest(db, devices, records, days, profiles, clock)
    val fit = FitImporter(ingest)
    val foodDatabase = OpenFoodFacts(http, db, clock, foodDatabaseUrls)
    val foods = FoodStore(db, clock)
    val entries = EntryStore(db, box, clock)
    val modules = ModuleService(
        mind = MindService(entries, profiles, settings, days, clock),
        cycle = CycleService(entries, profiles, days, clock),
        continence = ContinenceService(entries, profiles, records, days, clock),
        meds = MedsService(db, box, entries, profiles, clock),
        clock = clock,
    ).also { days.extras = it }

    // Hormone therapy, life stages, appointments and sharing (care/).
    val hormones = com.health.server.care.HormonesService(modules.meds, entries, profiles, clock).also { modules.meds.onChanged = it::medicationChanged }
    val lifeStages = com.health.server.care.LifeStageService(entries, profiles, modules.cycle, modules.mind, db, clock).also { modules.cycle.stageOf = it::stageOf }
    val appointments = com.health.server.care.AppointmentsService(entries, profiles, { hooks }, clock)
    val sharing = com.health.server.care.SharingService(db, users, profiles, clock)
    init {
        modules.care = com.health.server.care.CareDay(hormones, lifeStages, appointments)
    }

    // Blood pressure and glucose, drinks, symptoms and migraines, habits; the weather for symptom patterns.
    val weather = Weather(db, http, settings, clock)
    val tracking: TrackingService = run {
        val vitals = VitalsService(db, records, profiles, clock)
        val drinks = DrinksService(db, profiles, records, days, clock)
        val symptoms = SymptomsService(entries, profiles, days, drinks, modules.cycle, weather, clock)
        val habits = HabitsService(db, profiles, days, modules, drinks, clock)
        TrackingService(vitals, drinks, symptoms, habits, weather, days, clock).also { days.parts += it }
    }

    /** Each person's own settings, sealed one row per key (conditions, a feature's diary settings, Autism & ADHD, transition). */
    val userSettings = com.health.server.modules.UserSettingsStore(db, box, clock)
    val mealie = com.health.server.food.MealieService(http, userSettings, foods, { settings.get().webhooksPrivateNetworks }, clock, loopback = { config.outboundLoopback })

    /** How each person arranges the apps' screens. */
    val layouts = com.health.server.platform.LayoutService(userSettings)

    /** Which device to use for a reading, where the person would rather not combine them. */
    val deviceChoices = com.health.server.health.DeviceChoicesService(userSettings, records, days).also { c -> days.choices = { userId -> c.get(userId) } }

    // Autism & ADHD, transition and progress photos (neuro/, transition/, photos/).
    val neuro = com.health.server.neuro.NeuroService(entries, userSettings, profiles, days, modules.cycle, clock)
    val transition = com.health.server.transition.TransitionService(entries, userSettings, profiles, hormones, records, clock)
    val photos = com.health.server.photos.PhotoService(db, box, config.dataDir, profiles, records, clock).also { it.transition = transition }
    init {
        days.parts += com.health.server.neuro.NeuroTransitionDay(neuro, transition)
    }

    // Conditions and goals, supplies (bladder and bowel, periods).
    val conditions = com.health.server.conditions.ConditionsService(userSettings, profiles)
    val supplies = com.health.server.modules.SuppliesService(db, box, entries, profiles, clock)
    init {
        modules.continence.supplies = supplies
        modules.continence.settings = userSettings
        modules.continence.caffeine = { userId, from, to ->
            tracking.drinks.list(userId, from, to).groupBy { profiles.days(userId).dateOf(it.at) }.mapValues { (_, l) -> l.sumOf { it.caffeineMg } }
        }
        modules.cycle.supplies = supplies
        modules.cycle.settings = userSettings
        modules.supplies = supplies
        modules.plainCycle = { userId ->
            modules.cycle.settings(userId).neutralWording || !conditions.shows(userId, com.health.api.Fields.CYCLE_FERTILITY)
        }
        // Water can be turned off: insights, habits, drinks, the digest, the report, the watch and the connector leave it out.
        days.showsWater = { userId -> conditions.shows(userId, com.health.api.Fields.NUTRITION_WATER) }
    }

    /** Each person's own nutrient targets; with pregnancy, what the day's vitamin and mineral targets need. */
    val nutrientTargets = com.health.server.food.NutrientTargetsService(userSettings, profiles, lifeStages).also { t ->
        days.nutrientContext = { userId, date -> com.health.server.insights.NutrientContext(t.own(userId), t.pregnant(userId, date)) }
    }

    /** Food ideas from what each person has eaten before, for the Food page and Today's protein insight. */
    val suggestions = com.health.server.food.SuggestionService(foods, days, profiles, conditions, clock).also { s ->
        days.foodForInsights = { userId, date, isToday, today, target -> s.forInsights(userId, date, isToday, today, target) }
    }

    /** Each feature's part in digests, reports, exports, reminders and appointment questions. */
    val hooks: List<FeatureHooks> by lazy {
        listOf<FeatureHooks>(
            *CoreHooks.all(this).toTypedArray(),
            // Add each feature's service here.
            hormones, lifeStages, appointments,
            *com.health.server.care.CoreQuestions.all(modules.mind, modules.cycle, modules.continence, modules.meds, days, clock).toTypedArray(),
            VitalsHooks(tracking.vitals, clock), DrinksHooks(tracking.drinks), SymptomsHooks(tracking.symptoms),
            HabitsHooks(tracking.habits), StressHooks(days),
            neuro, transition, com.health.server.transition.MeasurementHooks(records),
            com.health.server.modules.ContinenceHooks(modules.continence, clock, supplies),
            com.health.server.modules.SuppliesHooks(supplies, profiles, clock),
            com.health.server.modules.CycleHooks(modules.cycle, clock),
        )
    }

    /** Reminders, push, webhooks, digests, reports, exports and the watch. */
    val platform: Platform by lazy { Platform(this) }

    /** The optional AI assistant (off until the admin turns it on; each person opts in). */
    val ai: com.health.server.ai.AiService by lazy { com.health.server.ai.AiService(this) }

    override fun close() {
        db.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger("samh")

        val version: String by lazy {
            runCatching {
                Properties().apply { Services::class.java.getResourceAsStream("/samh-version.properties")?.use(::load) }.getProperty("version")
            }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("\$") } ?: "dev"
        }

        /** Opens the data folder and database. */
        fun open(config: ServerConfig): Services {
            config.dataDir.mkdirs()
            val db = Database.file(config.databaseFile.path)
            Schema.migrate(db)
            return Services(config, db, SecretBox.fromFile(config.secretKeyFile))
        }
    }

    /** First start: makes the break-glass admin. Every start: applies settings given as environment variables. */
    suspend fun bootstrap() {
        if (users.count() == 0L) {
            val password = config.adminPassword ?: Random.password()
            users.create(config.adminUsername, "Administrator", Role.ADMIN, password, breakGlass = true)
            if (config.adminPassword == null) {
                // Straight to the console, never through the log: the log is kept in memory, and admins can read it.
                System.err.println(
                    "\n\n  Created the break-glass admin account.\n  Username: ${config.adminUsername}\n  Password: $password\n" +
                        "  This is shown once. Sign in and keep it somewhere safe; reset it with `samh-server reset-admin-password`.\n",
                )
                log.warn("Created the break-glass admin account {}; its password was printed once on the console", config.adminUsername)
            } else {
                log.info("Created the break-glass admin account {} with the password from SAMH_ADMIN_PASSWORD", config.adminUsername)
            }
        }
        val env = config.overrides
        if (env.isNotEmpty()) {
            fun flag(name: String): Boolean? = env[name]?.let { it.lowercase() in setOf("1", "true", "yes") }
            settings.update { current ->
                current.copy(
                    publicUrl = env["SAMH_PUBLIC_URL"]?.trimEnd('/') ?: current.publicUrl,
                    localLogin = flag("SAMH_LOCAL_LOGIN") ?: current.localLogin,
                    foodDatabase = flag("SAMH_FOOD_DATABASE") ?: current.foodDatabase,
                    oidc = current.oidc.copy(
                        enabled = if (env["SAMH_OIDC_ISSUER"] != null && env["SAMH_OIDC_CLIENT_ID"] != null) true else current.oidc.enabled,
                        issuer = env["SAMH_OIDC_ISSUER"] ?: current.oidc.issuer,
                        clientId = env["SAMH_OIDC_CLIENT_ID"]?.trim() ?: current.oidc.clientId,
                        buttonLabel = env["SAMH_OIDC_BUTTON_LABEL"] ?: current.oidc.buttonLabel,
                        userGroup = env["SAMH_OIDC_USER_GROUP"] ?: current.oidc.userGroup,
                        adminGroup = env["SAMH_OIDC_ADMIN_GROUP"] ?: current.oidc.adminGroup,
                        scopes = env["SAMH_OIDC_SCOPES"] ?: current.oidc.scopes,
                    ),
                )
            }
            env["SAMH_OIDC_CLIENT_SECRET"]?.let { settings.setOidcClientSecret(it.trim()) }
        }
        sessions.purgeExpired()
    }

    /** Sets a new password for the break-glass admin (from the command line) and returns it. */
    suspend fun resetBreakGlass(password: String?): Pair<String, String> {
        val admin = users.breakGlass() ?: users.create(config.adminUsername, "Administrator", Role.ADMIN, Random.password(), breakGlass = true)
        val newPassword = password ?: Random.password()
        users.setPassword(admin.id, newPassword)
        users.update(admin.id, role = Role.ADMIN, disabled = false)
        sessions.revokeAll(admin.id)
        return admin.username to newPassword
    }
}
