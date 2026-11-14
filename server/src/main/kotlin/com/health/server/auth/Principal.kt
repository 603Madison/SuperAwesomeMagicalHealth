package com.health.server.auth

import com.health.server.ApiError
import com.health.server.ServerConfig
import com.health.server.Services
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.util.AttributeKey
import com.health.api.SessionInfo
import com.health.api.SessionKind
import com.health.api.User
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The signed-in person making a request, and the sign-in they used. */
data class Principal(val user: User, val session: SessionInfo, val viaCookie: Boolean)

private class Resolved(val principal: Principal?)

private val PrincipalKey = AttributeKey<Resolved>("samh.principal")

const val SESSION_COOKIE = "samh_session"

/** The header the web app sends with every change, which other sites' pages can't send. */
const val CSRF_HEADER = "X-SAMH"

/**
 * Who's asking: from the session cookie (the web app) or an `Authorization: Bearer` token (the
 * phone app, or an upload key). Changes made with a cookie must carry [CSRF_HEADER], which a page
 * on another site can't add, so another site can't act for a signed-in person. Upload keys only
 * count where [allowUpload] says so (sending data in).
 */
suspend fun ApplicationCall.principal(services: Services, allowUpload: Boolean = false): Principal? {
    // Someone viewing another account's shared data may only read, and only what can be shared.
    if (request.header(com.health.server.care.SharedView.HEADER) != null) com.health.server.care.SharedView.check(this)
    attributes.getOrNull(PrincipalKey)?.let { resolved ->
        return resolved.principal?.takeIf { it.session.kind.opensApi(allowUpload) }
    }
    val bearer = request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
    val cookie = request.cookies[SESSION_COOKIE]
    val token = bearer ?: cookie
    val found = token?.let { services.sessions.resolve(it) }
        // Upload keys and connector links never come as a cookie, and links only open /mcp.
        ?.takeIf { (bearer != null || it.second.kind == SessionKind.WEB) && !it.second.kind.isConnector }
        ?.let { (user, session) -> Principal(user, session, viaCookie = bearer == null) }
    if (found != null && found.viaCookie && request.local.method !in SAFE_METHODS && request.header(CSRF_HEADER) == null) {
        throw ApiError.forbidden("Missing $CSRF_HEADER header")
    }
    attributes.put(PrincipalKey, Resolved(found))
    return found?.takeIf { it.session.kind.opensApi(allowUpload) }
}

/** Upload routes take upload keys too; a watch's key only ever opens the watch's own routes. */
private fun SessionKind.opensApi(allowUpload: Boolean) =
    this == SessionKind.WEB || this == SessionKind.APP || (allowUpload && this == SessionKind.UPLOAD)

/**
 * The account a request is about: the signed-in person's own, or, with `X-SAMH-View: <ownerId>`,
 * the owner's, when they share what's asked for with this person (care/Sharing.kt).
 */
suspend fun ApplicationCall.requireUser(services: Services): User {
    val principal = principal(services) ?: throw ApiError.unauthorized()
    request.header(com.health.server.care.SharedView.HEADER)?.let { owner -> return services.sharing.view(this, principal, owner) }
    return principal.user
}

suspend fun ApplicationCall.requireAdmin(services: Services): User =
    requireUser(services).also { if (!it.isAdmin) throw ApiError.forbidden() }

private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

/** Whether the person reached the server over HTTPS (directly, or through a trusted proxy). */
val ApplicationCall.isHttps: Boolean get() = request.origin.scheme == "https"

/**
 * Whether the browser is on HTTPS: directly or through a trusted proxy, or at the server's public
 * address when that's https (a TLS proxy the server wasn't told to trust). A plain-http address on
 * the home network still works, so its cookie can't be Secure.
 */
suspend fun ApplicationCall.onHttps(services: Services): Boolean {
    if (isHttps) return true
    val public = services.settings.get().publicUrl.toHttpUrlOrNull() ?: return false
    return public.scheme == "https" && public.host.equals(request.host(), ignoreCase = true)
}

fun ApplicationCall.sessionCookie(token: String, maxAgeSeconds: Long, secure: Boolean = isHttps) = Cookie(
    name = SESSION_COOKIE,
    value = token,
    encoding = CookieEncoding.RAW,
    maxAge = maxAgeSeconds.toInt(),
    path = "/",
    secure = secure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Strict"),
)

fun ApplicationCall.clearedSessionCookie(secure: Boolean = isHttps) = Cookie(
    name = SESSION_COOKIE,
    value = "",
    encoding = CookieEncoding.RAW,
    maxAge = 0,
    path = "/",
    secure = secure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Strict"),
)

/**
 * Where a request came from, for slowing down password guessing. Behind a reverse proxy, the
 * address the proxy saw: the header it sets ([ServerConfig.clientIpHeader], like Cloudflare's
 * CF-Connecting-IP), else the X-Forwarded-For entry added by the first of [ServerConfig.proxies]
 * proxies, counted from the end. Never simply the first entry: whoever sends the request can
 * write that one, and so get round the limit or use up someone else's.
 */
fun clientAddress(direct: String, headers: Headers, config: ServerConfig): String {
    if (!config.trustProxy) return direct
    config.clientIpHeader?.let { name -> headers[name]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it } }
    val chain = headers.getAll("X-Forwarded-For").orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    return chain.getOrNull((chain.size - config.proxies).coerceAtLeast(0)) ?: direct
}

/**
 * Slows down password guessing, without letting anyone lock someone else out: after
 * [maxFailures] wrong passwords for an account from one address, [maxFailuresPerAddress] from one
 * address (a reverse proxy can make everyone one address), or [maxFailuresPerAccount] for an
 * account from everywhere (guessing spread across many addresses), within [windowMs], more
 * attempts are refused until the window passes. A stranger failing on purpose only blocks
 * themselves, short of the account-wide limit.
 */
class LoginThrottle(
    private val maxFailures: Int = 10,
    private val maxFailuresPerAddress: Int = 50,
    private val maxFailuresPerAccount: Int = 200,
    private val windowMs: Long = 15L * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val failures = ConcurrentHashMap<String, List<Long>>()

    private fun keys(address: String, account: String) = listOf(address to maxFailuresPerAddress, "pair:$address|$account" to maxFailures, account to maxFailuresPerAccount)

    fun check(address: String, account: String) {
        val now = clock()
        if (keys(address, account).any { (key, max) -> recent(key, now) >= max }) {
            throw ApiError.tooMany("Too many wrong passwords. Try again in a few minutes.")
        }
    }

    fun failed(address: String, account: String) {
        val now = clock()
        keys(address, account).forEach { (key, _) -> failures.compute(key) { _, list -> list.orEmpty().filter { now - it <= windowMs } + now } }
        if (failures.size > 30_000) failures.entries.removeIf { (_, list) -> list.all { now - it > windowMs } }
    }

    /** The right password: this address's count for the account starts again. */
    fun succeeded(address: String, account: String) {
        failures.remove("pair:$address|$account")
    }

    private fun recent(key: String, now: Long): Int = failures[key]?.count { now - it <= windowMs } ?: 0
}
