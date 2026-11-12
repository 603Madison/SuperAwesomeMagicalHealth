package com.fitness.server

import com.fitness.api.Role
import com.fitness.server.auth.LoginThrottle
import com.fitness.server.auth.OidcClient
import com.fitness.server.auth.PendingLogins
import com.fitness.server.auth.SessionStore
import com.fitness.server.auth.UserStore
import com.fitness.server.db.Database
import com.fitness.server.db.Schema
import com.fitness.server.food.FoodStore
import com.fitness.server.food.OpenFoodFacts
import com.fitness.server.health.CheckInStore
import com.fitness.server.health.DeviceStore
import com.fitness.server.health.FitImporter
import com.fitness.server.health.Ingest
import com.fitness.server.health.ProfileStore
import com.fitness.server.health.Records
import com.fitness.server.insights.DayService
import com.fitness.server.util.Http
import com.fitness.server.util.Passwords
import com.fitness.server.util.Random
import com.fitness.server.util.SecretBox
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

    override fun close() {
        db.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger("samf")

        val version: String by lazy {
            runCatching {
                Properties().apply { Services::class.java.getResourceAsStream("/samf-version.properties")?.use(::load) }.getProperty("version")
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
                        "  This is shown once. Sign in and keep it somewhere safe; reset it with `samf-server reset-admin-password`.\n",
                    config.adminUsername, password,
                )
            } else {
                log.info("Created the break-glass admin account {} with the password from SAMF_ADMIN_PASSWORD", config.adminUsername)
            }
        }
        val env = config.overrides
        if (env.isNotEmpty()) {
            fun flag(name: String): Boolean? = env[name]?.let { it.lowercase() in setOf("1", "true", "yes") }
            settings.update { current ->
                current.copy(
                    publicUrl = env["SAMF_PUBLIC_URL"]?.trimEnd('/') ?: current.publicUrl,
                    localLogin = flag("SAMF_LOCAL_LOGIN") ?: current.localLogin,
                    foodDatabase = flag("SAMF_FOOD_DATABASE") ?: current.foodDatabase,
                    oidc = current.oidc.copy(
                        enabled = if (env["SAMF_OIDC_ISSUER"] != null && env["SAMF_OIDC_CLIENT_ID"] != null) true else current.oidc.enabled,
                        issuer = env["SAMF_OIDC_ISSUER"] ?: current.oidc.issuer,
                        clientId = env["SAMF_OIDC_CLIENT_ID"] ?: current.oidc.clientId,
                        buttonLabel = env["SAMF_OIDC_BUTTON_LABEL"] ?: current.oidc.buttonLabel,
                        userGroup = env["SAMF_OIDC_USER_GROUP"] ?: current.oidc.userGroup,
                        adminGroup = env["SAMF_OIDC_ADMIN_GROUP"] ?: current.oidc.adminGroup,
                        scopes = env["SAMF_OIDC_SCOPES"] ?: current.oidc.scopes,
                    ),
                )
            }
            env["SAMF_OIDC_CLIENT_SECRET"]?.let { settings.setOidcClientSecret(it) }
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
