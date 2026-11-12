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

    /** Each feature's part in digests, reports, exports, reminders and appointment questions. */
    val hooks: List<FeatureHooks> by lazy {
        listOf<FeatureHooks>(
            *CoreHooks.all(this).toTypedArray(),
            // Add each feature's service here.
        )
    }

    /** Reminders, push, webhooks, digests, reports, exports and the watch. */
    val platform: Platform by lazy { Platform(this) }

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
                log.warn(
                    "\n\n  Created the break-glass admin account.\n  Username: {}\n  Password: {}\n" +
                        "  This is shown once. Sign in and keep it somewhere safe; reset it with `samh-server reset-admin-password`.\n",
                    config.adminUsername, password,
                )
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
                        clientId = env["SAMH_OIDC_CLIENT_ID"] ?: current.oidc.clientId,
                        buttonLabel = env["SAMH_OIDC_BUTTON_LABEL"] ?: current.oidc.buttonLabel,
                        userGroup = env["SAMH_OIDC_USER_GROUP"] ?: current.oidc.userGroup,
                        adminGroup = env["SAMH_OIDC_ADMIN_GROUP"] ?: current.oidc.adminGroup,
                        scopes = env["SAMH_OIDC_SCOPES"] ?: current.oidc.scopes,
                    ),
                )
            }
            env["SAMH_OIDC_CLIENT_SECRET"]?.let { settings.setOidcClientSecret(it) }
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
