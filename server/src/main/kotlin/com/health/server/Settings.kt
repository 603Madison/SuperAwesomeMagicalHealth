package com.health.server

import com.health.server.db.Database
import com.health.server.util.SecretBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Signing in through an OpenID Connect provider such as Authentik. */
@Serializable
data class OidcSettings(
    val enabled: Boolean = false,
    /** e.g. https://auth.example.com/application/o/superawesomemagicalhealth/ */
    val issuer: String = "",
    val clientId: String = "",
    val scopes: String = "openid profile email",
    val buttonLabel: String = "Sign in with Authentik",
    /** The claim holding the username; email and the subject are fallbacks. */
    val usernameClaim: String = "preferred_username",
    val groupsClaim: String = "groups",
    /** Only members of this group may sign in; blank lets in anyone the provider does. */
    val userGroup: String = "",
    /** Members of this group are admins, everyone else a user; blank leaves roles to the admin page. */
    val adminGroup: String = "",
    /** Make an account the first time someone signs in; off means an admin adds people first. */
    val autoCreateUsers: Boolean = true,
)

@Serializable
data class ServerSettings(
    val serverName: String = "SuperAwesomeMagicalHealth",
    /** The address people reach the server at, e.g. https://health.example.com (for sign-in redirects). */
    val publicUrl: String = "",
    /** Whether accounts other than the break-glass admin may sign in with a password. */
    val localLogin: Boolean = true,
    /**
     * Also ask Open Food Facts when USDA doesn't know a food (the search terms and barcodes go to
     * openfoodfacts.org). Off for new servers: USDA FoodData Central is the food database.
     */
    val foodDatabase: Boolean = false,
    /** Air pressure for symptom patterns, and place search (a rough place goes to open-meteo.com). */
    val weather: Boolean = true,
    /** Where people can turn for support, shown in Mind: set them for where your people live. */
    val crisisResources: List<com.health.api.CrisisResource> = com.health.server.modules.MindService.DEFAULT_RESOURCES,
    val oidc: OidcSettings = OidcSettings(),
    /** Mail for weekly digests (the password is set apart, at /api/admin/smtp). */
    val smtp: com.health.server.platform.SmtpSettings = com.health.server.platform.SmtpSettings(),
    /** Let webhooks and Mealie reach addresses on private networks (Home Assistant and Mealie usually live on one). */
    val webhooksPrivateNetworks: Boolean = true,
    /** Search USDA FoodData Central for foods once an API key is set (the search terms go to api.nal.usda.gov). */
    val usdaFoods: Boolean = true,
    /** Medical records from patient portals: the app registered with each system (secrets are set apart). */
    val records: RecordsSettings = RecordsSettings(),
)

/**
 * The apps this server registered to read people's medical records from their patient portals:
 * with Epic (fhir.epic.com) and athenahealth (its developer portal). Each one's client secret, for
 * lasting access, is kept sealed apart ([SettingsStore.recordSecret]).
 */
@Serializable
data class RecordsSettings(
    val epic: PortalApp = PortalApp(),
    val athena: PortalApp = PortalApp(),
) {
    fun of(provider: com.health.api.RecordProvider): PortalApp = when (provider) {
        com.health.api.RecordProvider.EPIC -> epic
        com.health.api.RecordProvider.ATHENA -> athena
    }

    /** The systems that are on, with a client ID. */
    val providers: List<com.health.api.RecordProvider> get() = com.health.api.RecordProvider.entries.filter { of(it).ready }
}

/** One registered app: on or off, and the client ID the system gave it. */
@Serializable
data class PortalApp(val enabled: Boolean = false, val clientId: String = "") {
    val ready: Boolean get() = enabled && clientId.isNotBlank()
}

/** The server's settings, kept in the database and changed from the admin page. */
class SettingsStore(private val db: Database, private val secrets: SecretBox) {
    @Volatile
    private var cached: ServerSettings? = null

    suspend fun get(): ServerSettings = cached ?: load().also { cached = it }

    private suspend fun load(): ServerSettings = db.tx {
        one("SELECT value FROM settings WHERE key = 'server'") { it.string("value") }
    }?.let { runCatching { SettingsJson.decodeFromString<ServerSettings>(it) }.getOrNull() } ?: ServerSettings()

    suspend fun update(transform: (ServerSettings) -> ServerSettings): ServerSettings {
        val next = transform(get())
        db.tx { update("INSERT OR REPLACE INTO settings (key, value) VALUES ('server', ?)", SettingsJson.encodeToString(next)) }
        cached = next
        return next
    }

    suspend fun oidcClientSecret(): String = db.tx {
        one("SELECT value FROM settings WHERE key = 'oidc.clientSecret'") { it.string("value") }
    }?.let { runCatching { secrets.open(it, "oidc.clientSecret") }.getOrNull() }.orEmpty()

    suspend fun setOidcClientSecret(secret: String) = db.tx {
        if (secret.isEmpty()) {
            update("DELETE FROM settings WHERE key = 'oidc.clientSecret'")
        } else {
            update("INSERT OR REPLACE INTO settings (key, value) VALUES ('oidc.clientSecret', ?)", secrets.seal(secret, "oidc.clientSecret"))
        }
    }

    /** USDA FoodData Central's API key (from api.data.gov), sealed; empty when there's none. */
    suspend fun usdaApiKey(): String = secret("usda.apiKey")

    suspend fun setUsdaApiKey(key: String) = setSecret("usda.apiKey", key)

    /** A records app's client secret, sealed; empty when there's none (it's then a public client, with PKCE alone). */
    suspend fun recordSecret(provider: com.health.api.RecordProvider): String = secret("records.${provider.name.lowercase()}.clientSecret")

    suspend fun setRecordSecret(provider: com.health.api.RecordProvider, value: String) = setSecret("records.${provider.name.lowercase()}.clientSecret", value)

    private suspend fun secret(key: String): String = db.tx {
        one("SELECT value FROM settings WHERE key = ?", key) { it.string("value") }
    }?.let { runCatching { secrets.open(it, key) }.getOrNull() }.orEmpty()

    private suspend fun setSecret(key: String, value: String) = db.tx {
        if (value.isEmpty()) update("DELETE FROM settings WHERE key = ?", key)
        else update("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", key, secrets.seal(value, key))
    }

    private companion object {
        val SettingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
