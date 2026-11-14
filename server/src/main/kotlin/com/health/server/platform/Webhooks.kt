package com.health.server.platform

import com.health.api.ApiJson
import com.health.api.Webhook
import com.health.api.WebhookDelivery
import com.health.api.WebhookEvent
import com.health.api.WebhookPayload
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.db.Row
import com.health.server.util.Random
import com.health.server.util.SecretBox
import com.health.server.util.call
import com.health.server.util.hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Webhooks: addresses the server calls when something happens, signed with each hook's secret. */
class WebhookService(
    private val db: Database,
    private val box: SecretBox,
    http: OkHttpClient,
    private val allowPrivate: suspend () -> Boolean,
    private val clock: () -> Long,
    /** Tests only: let hooks reach this machine. */
    private val loopback: () -> Boolean = { false },
    /** How long to wait before try [n] (2, 3): a second, then four. Tests make it instant. */
    var backoff: (Int) -> Long = { n -> if (n == 2) 1_000L else 4_000L },
) {
    private val log = LoggerFactory.getLogger("samh.webhooks")

    // Redirects aren't followed: a hook goes where it says, and only there.
    private val client = http.newBuilder()
        .dns(com.health.server.util.OutboundDns({ kotlinx.coroutines.runBlocking { allowPrivate() } }, loopback))
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun context(userId: String, id: String) = "webhook:$userId:$id"

    private fun toWebhook(row: Row, last: WebhookDelivery?) = Webhook(
        name = row.string("name"),
        url = row.string("url"),
        events = eventsOf(row.string("events")),
        enabled = row.bool("enabled"),
        includeDetails = row.bool("include_details"),
        id = row.string("id"),
        createdAt = row.long("created_at"),
        lastDelivery = last,
    )

    suspend fun list(userId: String): List<Webhook> {
        val last = lastDeliveries(userId)
        return db.tx { query("SELECT * FROM webhooks WHERE user_id = ? ORDER BY created_at", userId) { toWebhook(it, null) } }
            .map { it.copy(lastDelivery = last[it.id]) }
    }

    suspend fun get(userId: String, id: String): Webhook =
        db.tx { one("SELECT * FROM webhooks WHERE id = ? AND user_id = ?", id, userId) { toWebhook(it, null) } } ?: throw ApiError.notFound("No such webhook")

    suspend fun create(userId: String, hook: Webhook): Webhook {
        val clean = validated(hook)
        val id = Random.id()
        val secret = "whsec_" + Random.token(24)
        val now = clock()
        db.tx {
            if (long("SELECT COUNT(*) FROM webhooks WHERE user_id = ?", userId) >= 20) throw ApiError.badRequest("That's 20 webhooks already: remove one first")
            update(
                "INSERT INTO webhooks (id, user_id, name, url, secret, events, enabled, include_details, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, userId, clean.name, clean.url, box.seal(secret, context(userId, id)), eventsText(clean.events), clean.enabled, clean.includeDetails, now, now,
            )
        }
        return clean.copy(id = id, createdAt = now, secret = secret)
    }

    suspend fun update(userId: String, id: String, hook: Webhook): Webhook {
        val clean = validated(hook)
        val changed = db.tx {
            update(
                "UPDATE webhooks SET name = ?, url = ?, events = ?, enabled = ?, include_details = ?, updated_at = ? WHERE id = ? AND user_id = ?",
                clean.name, clean.url, eventsText(clean.events), clean.enabled, clean.includeDetails, clock(), id, userId,
            )
        }
        if (changed == 0) throw ApiError.notFound("No such webhook")
        return get(userId, id).copy(lastDelivery = lastDeliveries(userId)[id])
    }

    suspend fun delete(userId: String, id: String) {
        if (db.tx { update("DELETE FROM webhooks WHERE id = ? AND user_id = ?", id, userId) } == 0) throw ApiError.notFound("No such webhook")
    }

    suspend fun enabled(userId: String): List<Webhook> = list(userId).filter { it.enabled }

    suspend fun deliveries(userId: String, limit: Int = 50): List<WebhookDelivery> = db.tx {
        query("SELECT * FROM webhook_deliveries WHERE user_id = ? ORDER BY at DESC, id DESC LIMIT ?", userId, limit) { delivery(it) }
    }

    private fun delivery(row: Row) = WebhookDelivery(
        at = row.long("at"),
        event = WebhookEvent.entries.firstOrNull { it.name == row.string("event") } ?: WebhookEvent.TEST,
        ok = row.bool("ok"),
        status = row.intOrNull("status"),
        error = row.stringOrNull("error"),
        webhookId = row.string("webhook_id"),
    )

    private suspend fun lastDeliveries(userId: String): Map<String, WebhookDelivery> = db.tx {
        query(
            """SELECT d.* FROM webhook_deliveries d JOIN (SELECT webhook_id, MAX(id) AS last FROM webhook_deliveries WHERE user_id = ? GROUP BY webhook_id) l
               ON d.id = l.last""",
            userId,
        ) { delivery(it) }
    }.associateBy { it.webhookId!! }

    private suspend fun secret(userId: String, id: String): String = db.tx {
        one("SELECT secret FROM webhooks WHERE id = ? AND user_id = ?", id, userId) { it.string("secret") }
    }?.let { box.open(it, context(userId, id)) } ?: throw ApiError.notFound("No such webhook")

    /**
     * Calls [hook] with [payload]: up to three tries, each one logged. Returns the last try.
     * Never throws: a hook that's down is the hook's business, not the caller's.
     */
    suspend fun deliver(userId: String, hook: Webhook, event: WebhookEvent, payload: WebhookPayload): WebhookDelivery {
        val body = ApiJson.encodeToString(WebhookPayload.serializer(), payload)
        val secret = runCatching { secret(userId, hook.id!!) }.getOrNull() ?: return record(userId, hook.id!!, event, false, null, "No secret")
        var last: WebhookDelivery? = null
        for (attempt in 1..3) {
            if (attempt > 1) delay(backoff(attempt))
            last = attempt(userId, hook, event, body, secret)
            // Only a server error, a timeout or no answer is worth another try.
            if (last.ok || (last.status != null && last.status!! < 500 && last.status != 429)) break
            if (last.error?.startsWith("Refused") == true) break
        }
        prune(hook.id!!)
        return last!!
    }

    private suspend fun attempt(userId: String, hook: Webhook, event: WebhookEvent, body: String, secret: String): WebhookDelivery {
        val url = hook.url.toHttpUrlOrNull() ?: return record(userId, hook.id!!, event, false, null, "Refused: not a URL")
        refusal(url)?.let { return record(userId, hook.id!!, event, false, null, "Refused: $it") }
        val request = Request.Builder().url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "SuperAwesomeMagicalHealth")
            .header("X-SAMH-Event", event.key)
            .header("X-SAMH-Signature", "sha256=" + signature(secret, body))
            .build()
        return try {
            val status = client.call(request) { it.code }
            record(userId, hook.id!!, event, status in 200..299, status, if (status in 200..299) null else "HTTP $status")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.warn("Webhook {} failed: {}", hook.name, e.message ?: e.javaClass.simpleName)
            // What kind of failure, never the network's own words (they'd make the test button a port scanner).
            val said = when (e) {
                is com.health.server.util.OutboundDns.Refused -> "Refused: ${e.message}"
                is java.net.UnknownHostException -> "Couldn't find that address"
                is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "No answer in time"
                is javax.net.ssl.SSLException -> "Its secure connection didn't check out"
                else -> "Couldn't connect"
            }
            record(userId, hook.id!!, event, false, null, said)
        }
    }

    /** Why the server won't call [url], or null if it will. */
    private suspend fun refusal(url: HttpUrl): String? {
        val addresses = withContext(Dispatchers.IO) { runCatching { InetAddress.getAllByName(url.host).toList() }.getOrNull() }
            ?: return "can't find ${url.host}"
        val private = allowPrivate()
        return addresses.firstNotNullOfOrNull { com.health.server.util.OutboundDns.refusal(it, private, loopback()) }?.replaceFirstChar { it.lowercase() }
    }

    private suspend fun record(userId: String, webhookId: String, event: WebhookEvent, ok: Boolean, status: Int?, error: String?): WebhookDelivery {
        val at = clock()
        db.tx {
            update(
                "INSERT INTO webhook_deliveries (webhook_id, user_id, at, event, ok, status, error) VALUES (?, ?, ?, ?, ?, ?, ?)",
                webhookId, userId, at, event, ok, status, error,
            )
        }
        return WebhookDelivery(at, event, ok, status, error, webhookId)
    }

    private suspend fun prune(webhookId: String) = db.tx {
        update(
            "DELETE FROM webhook_deliveries WHERE webhook_id = ? AND id NOT IN (SELECT id FROM webhook_deliveries WHERE webhook_id = ? ORDER BY id DESC LIMIT 50)",
            webhookId, webhookId,
        )
    }

    companion object {
        fun signature(secret: String, body: String): String = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            doFinal(body.toByteArray())
        }.hex()

        fun isPrivate(address: InetAddress): Boolean = com.health.server.util.OutboundDns.isPrivate(address)

        fun eventsText(events: Set<WebhookEvent>) = events.joinToString(",") { it.name }

        fun eventsOf(text: String): Set<WebhookEvent> = text.split(',').mapNotNull { name -> WebhookEvent.entries.firstOrNull { it.name == name } }.toSet()

        fun validated(hook: Webhook): Webhook {
            val url = hook.url.trim().toHttpUrlOrNull()
            if (url == null || (url.scheme != "http" && url.scheme != "https")) throw ApiError.badRequest("A webhook needs an http:// or https:// address")
            if (hook.url.length > 2000) throw ApiError.badRequest("That address is too long")
            val events = hook.events - WebhookEvent.TEST
            if (events.isEmpty()) throw ApiError.badRequest("Pick at least one thing to send")
            return hook.copy(
                name = hook.name.trim().take(80).ifBlank { url.host },
                url = url.toString(),
                events = events,
                id = null, createdAt = null, lastDelivery = null, secret = null,
            )
        }

        fun payload(event: WebhookEvent, at: Long, username: String, title: String, data: JsonObject) =
            WebhookPayload(event.key, at, username, title, data)
    }
}
