package com.health.app.net

import kotlinx.serialization.Serializable

/** `GET /api/health`: whether the server is up, and its version ("0.3.0"). */
@Serializable
data class ServerHealth(val ok: Boolean = false, val version: String? = null)

suspend fun ServerApi.serverHealth(): ServerHealth = get("${base()}/api/health", ServerHealth.serializer(), auth = false)

/**
 * The server and this app go together (one version for both). An older server doesn't know what
 * the app has added since (turning water off, arranging screens, meal ideas): the app says so
 * gently, and a 404 or 400 from something new reads as "the server needs updating", not an error.
 */
object ServerVersion {
    /** Where the README says how to update the server. */
    const val HELP_URL = "https://github.com/603Madison/SuperAwesomeMagicalHealth#updating"

    /** "0.3.0", "v0.3.0" or "0.3.0-beta" as (0, 3); null for anything else ("dev"). */
    fun majorMinor(version: String?): Pair<Int, Int>? {
        val match = Regex("""^v?(\d+)\.(\d+)""").find(version?.trim().orEmpty()) ?: return null
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    /** Whether [server]'s major.minor is older than [app]'s; unknown versions never are. */
    fun isOlder(server: String?, app: String): Boolean {
        val (sMajor, sMinor) = majorMinor(server) ?: return false
        val (aMajor, aMinor) = majorMinor(app) ?: return false
        return sMajor < aMajor || (sMajor == aMajor && sMinor < aMinor)
    }

    /** What the notice on Today and in Settings says. */
    fun notice(server: String, app: String): String =
        "Your server is on $server and this app is $app: some things (like turning water off and arranging screens) need the server updated."

    /** Which notice was put away: one per pair of versions, so a newer app asks again. */
    fun key(server: String, app: String): String = "$server>$app"

    /** Whether [e] is an older server not knowing something new: a 404 or 405, or a 400 for fields it doesn't know. */
    fun missing(e: Throwable, badRequestToo: Boolean = false): Boolean =
        e is ServerException && (e.code == 404 || e.code == 405 || (e.code == 400 && (badRequestToo || e.message.orEmpty().startsWith("Unknown field"))))

    /** "Your server needs updating to arrange screens." */
    fun needsUpdate(what: String): String = "Your server needs updating $what."

    /** Runs [block]; an older server not knowing it becomes a plain [ServerException] saying so. */
    internal suspend fun <T> plain(what: String, badRequestToo: Boolean = false, block: suspend () -> T): T = try {
        block()
    } catch (e: ServerException) {
        if (missing(e, badRequestToo)) throw ServerException(e.code, needsUpdate(what)) else throw e
    }
}
