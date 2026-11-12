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
    /** Look foods up in Open Food Facts (the search terms and barcodes go to openfoodfacts.org). */
    val foodDatabase: Boolean = true,
    /** Where people can turn for support, shown in Mind: set them for where your people live. */
    val crisisResources: List<com.health.api.CrisisResource> = com.health.server.modules.MindService.DEFAULT_RESOURCES,
    val oidc: OidcSettings = OidcSettings(),
    /** Mail for weekly digests (the password is set apart, at /api/admin/smtp). */
    val smtp: com.health.server.platform.SmtpSettings = com.health.server.platform.SmtpSettings(),
    /** Let webhooks call addresses on private networks (Home Assistant usually lives on one). */
    val webhooksPrivateNetworks: Boolean = true,
)

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

    private companion object {
        val SettingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
