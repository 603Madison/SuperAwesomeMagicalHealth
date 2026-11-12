package com.fitness.server

import java.io.File

/** Start-up settings, from environment variables (see README.md). */
data class ServerConfig(
    val dataDir: File,
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Trust X-Forwarded-* headers from a reverse proxy in front of the server. */
    val trustProxy: Boolean = false,
    /** How many proxies in a row are in front (each adds the address it saw to X-Forwarded-For). */
    val proxies: Int = 1,
    /** A header the proxy in front sets to the visitor's address (Cloudflare's CF-Connecting-IP). */
    val clientIpHeader: String? = null,
    val adminUsername: String = "admin",
    /** The break-glass admin's password for a new server; generated (and logged once) if empty. */
    val adminPassword: String? = null,
    /** Settings applied at every start, overriding the admin page (for configuration as code). */
    val overrides: Map<String, String> = emptyMap(),
) {
    val databaseFile: File get() = File(dataDir, "samf.db")
    val secretKeyFile: File get() = File(dataDir, "secret.key")

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig = ServerConfig(
            dataDir = File(env["SAMF_DATA_DIR"]?.ifBlank { null } ?: "/data"),
            host = env["SAMF_HOST"]?.ifBlank { null } ?: "0.0.0.0",
            port = env["SAMF_PORT"]?.toIntOrNull() ?: 8080,
            trustProxy = env["SAMF_TRUST_PROXY"]?.lowercase() in setOf("1", "true", "yes"),
            proxies = env["SAMF_PROXIES"]?.trim()?.toIntOrNull()?.coerceIn(1, 10) ?: 1,
            clientIpHeader = env["SAMF_CLIENT_IP_HEADER"]?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,64}")) },
            adminUsername = env["SAMF_ADMIN_USERNAME"]?.ifBlank { null } ?: "admin",
            adminPassword = env["SAMF_ADMIN_PASSWORD"]?.ifBlank { null },
            overrides = env.filterKeys { it in OVERRIDES }.filterValues { it.isNotBlank() },
        )

        val OVERRIDES = setOf(
            "SAMF_PUBLIC_URL",
            "SAMF_OIDC_ISSUER",
            "SAMF_OIDC_CLIENT_ID",
            "SAMF_OIDC_CLIENT_SECRET",
            "SAMF_OIDC_BUTTON_LABEL",
            "SAMF_OIDC_USER_GROUP",
            "SAMF_OIDC_ADMIN_GROUP",
            "SAMF_OIDC_SCOPES",
            "SAMF_LOCAL_LOGIN",
            "SAMF_FOOD_DATABASE",
        )
    }
}
