package com.health.server.platform

import com.health.api.ApiJson
import com.health.api.PushMessage
import com.health.api.PushSubscriptionInfo
import com.health.api.PushSubscriptionInput
import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.util.Random
import com.health.server.util.SecretBox
import com.health.server.util.base64Url
import com.health.server.util.call
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Web Push's message encryption (RFC 8291, aes128gcm from RFC 8188) and the server's VAPID
 * signature (RFC 8292), with the JDK's own crypto.
 */
object WebPushCrypto {
    private val curve: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** The 65-byte uncompressed point: 0x04, x, y. */
    fun publicBytes(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)

    fun privateBytes(key: ECPrivateKey): ByteArray = fixed(key.s)

    fun publicKey(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 65 && bytes[0] == 4.toByte()) { "Not an uncompressed P-256 point" }
        val point = ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, curve)) as ECPublicKey
    }

    fun privateKey(bytes: ByteArray): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, bytes), curve)) as ECPrivateKey

    fun keyPair(privateBytes: ByteArray, publicBytes: ByteArray) = KeyPair(publicKey(publicBytes), privateKey(privateBytes))

    private fun fixed(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim().trimEnd('=').replace('+', '-').replace('/', '_'))

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    private fun ecdh(private: ECPrivateKey, public: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(private); doPhase(public, true); generateSecret() }

    private class Keys(val cek: ByteArray, val nonce: ByteArray)

    private fun keys(ecdhSecret: ByteArray, auth: ByteArray, uaPublic: ByteArray, asPublic: ByteArray, salt: ByteArray): Keys {
        val prkKey = hmac(auth, ecdhSecret)
        val keyInfo = "WebPush: info".toByteArray() + 0 + uaPublic + asPublic
        val ikm = hmac(prkKey, keyInfo + 1)
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)
        return Keys(cek, nonce)
    }

    private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())

    /** Encrypts [plain] for a browser's [p256dh] key and [auth] secret: the whole request body. */
    fun encrypt(
        plain: ByteArray,
        p256dh: ByteArray,
        auth: ByteArray,
        server: KeyPair = newKeyPair(),
        salt: ByteArray = Random.bytes(16),
        recordSize: Int = 4096,
    ): ByteArray {
        val asPublic = publicBytes(server.public as ECPublicKey)
        val secret = ecdh(server.private as ECPrivateKey, publicKey(p256dh))
        val keys = keys(secret, auth, p256dh, asPublic, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.cek, "AES"), GCMParameterSpec(128, keys.nonce))
        }
        // One record: the message, then the last-record delimiter.
        val sealed = cipher.doFinal(plain + 2)
        require(sealed.size + 86 <= recordSize) { "A notification can carry about 3 KB" }
        return ByteBuffer.allocate(16 + 4 + 1 + asPublic.size + sealed.size)
            .put(salt).putInt(recordSize).put(asPublic.size.toByte()).put(asPublic).put(sealed).array()
    }

    /** What a browser does: for tests, and to check [encrypt] against RFC 8291's example. */
    fun decrypt(body: ByteArray, uaPrivate: ByteArray, uaPublic: ByteArray, auth: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(body)
        val salt = ByteArray(16).also(buffer::get)
        buffer.int
        val idLength = buffer.get().toInt() and 0xFF
        val asPublic = ByteArray(idLength).also(buffer::get)
        val sealed = ByteArray(buffer.remaining()).also(buffer::get)
        val secret = ecdh(privateKey(uaPrivate), publicKey(asPublic))
        val keys = keys(secret, auth, uaPublic, asPublic, salt)
        val padded = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.cek, "AES"), GCMParameterSpec(128, keys.nonce))
            doFinal(sealed)
        }
        val end = padded.indexOfLast { it != 0.toByte() }
        require(end >= 0 && padded[end] == 2.toByte()) { "No last-record delimiter" }
        return padded.copyOf(end)
    }

    /** `Authorization: vapid t=…, k=…` for a push service at [endpoint]. */
    fun vapid(endpoint: String, keys: KeyPair, subject: String, now: Long, ttlSeconds: Long = 12 * 3600): String {
        val url = endpoint.toHttpUrlOrNull() ?: throw IllegalArgumentException("Not a URL")
        val audience = "${url.scheme}://${url.host}${if (url.port == okhttp3.HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"}"
        val claims = JWTClaimsSet.Builder().audience(audience).expirationTime(Date(now + ttlSeconds * 1000)).subject(subject).build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build(), claims)
        jwt.sign(ECDSASigner(keys.private as ECPrivateKey))
        return "vapid t=${jwt.serialize()}, k=${publicBytes(keys.public as ECPublicKey).base64Url()}"
    }
}

/** Browsers that asked for notifications, and sending to them. */
class PushService(
    private val db: Database,
    private val box: SecretBox,
    private val http: OkHttpClient,
    private val subject: suspend () -> String,
    private val clock: () -> Long,
    /** Tests only: let pushes reach this machine. */
    private val loopback: () -> Boolean = { false },
) {
    private val log = LoggerFactory.getLogger("samh.push")

    // Browsers' push services are on the internet: never a private address, never a redirect.
    private val client = http.newBuilder()
        .dns(com.health.server.util.OutboundDns(loopback, loopback))
        .followRedirects(false).followSslRedirects(false)
        .build()

    @Volatile
    private var cachedKeys: KeyPair? = null

    /** The server's VAPID keys: made the first time they're needed, then kept (the private one sealed). */
    suspend fun keys(): KeyPair = cachedKeys ?: db.tx {
        val public = one("SELECT value FROM settings WHERE key = 'vapid.public'") { it.string("value") }
        val private = one("SELECT value FROM settings WHERE key = 'vapid.private'") { it.string("value") }
        val existing = if (public != null && private != null) {
            runCatching { WebPushCrypto.keyPair(WebPushCrypto.decode(box.open(private, "vapid.private")), WebPushCrypto.decode(public)) }.getOrNull()
        } else null
        existing ?: WebPushCrypto.newKeyPair().also { pair ->
            update("INSERT OR REPLACE INTO settings (key, value) VALUES ('vapid.public', ?)", WebPushCrypto.publicBytes(pair.public as ECPublicKey).base64Url())
            update(
                "INSERT OR REPLACE INTO settings (key, value) VALUES ('vapid.private', ?)",
                box.seal(WebPushCrypto.privateBytes(pair.private as ECPrivateKey).base64Url(), "vapid.private"),
            )
        }
    }.also { cachedKeys = it }

    suspend fun publicKey(): String = WebPushCrypto.publicBytes(keys().public as ECPublicKey).base64Url()

    suspend fun subscribe(userId: String, input: PushSubscriptionInput): PushSubscriptionInfo {
        val url = input.endpoint.trim().toHttpUrlOrNull()
        if (url == null || (url.scheme != "https" && !loopback()) || url.username.isNotEmpty() || input.endpoint.length > 2000) {
            throw ApiError.badRequest("That isn't a push address a browser would give")
        }
        val p256dh = runCatching { WebPushCrypto.decode(input.p256dh) }.getOrNull()
        val auth = runCatching { WebPushCrypto.decode(input.auth) }.getOrNull()
        if (p256dh == null || p256dh.size != 65 || p256dh[0] != 4.toByte() || runCatching { WebPushCrypto.publicKey(p256dh) }.isFailure) {
            throw ApiError.badRequest("The browser's key isn't right")
        }
        if (auth == null || auth.size != 16) throw ApiError.badRequest("The browser's secret isn't right")
        val name = input.name.trim().take(80).ifBlank { "Browser" }
        val now = clock()
        val id = db.tx {
            // The same browser subscribing again (or for another account) replaces its old subscription.
            update("DELETE FROM push_subscriptions WHERE endpoint = ?", url.toString())
            Random.id().also {
                update(
                    "INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    it, userId, url.toString(), p256dh.base64Url(), auth.base64Url(), name, now,
                )
            }
        }
        return PushSubscriptionInfo(id, name, now)
    }

    suspend fun list(userId: String): List<PushSubscriptionInfo> = db.tx {
        query("SELECT id, name, created_at, last_ok_at FROM push_subscriptions WHERE user_id = ? ORDER BY created_at", userId) {
            PushSubscriptionInfo(it.string("id"), it.string("name"), it.long("created_at"), it.longOrNull("last_ok_at"))
        }
    }

    suspend fun failures(userId: String): Map<String, Int> = db.tx {
        query("SELECT id, failures FROM push_subscriptions WHERE user_id = ?", userId) { it.string("id") to it.int("failures") }.toMap()
    }

    suspend fun count(userId: String): Int = db.tx { long("SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?", userId).toInt() }

    suspend fun delete(userId: String, id: String): Boolean = db.tx { update("DELETE FROM push_subscriptions WHERE id = ? AND user_id = ?", id, userId) } > 0

    private class Target(val id: String, val endpoint: String, val p256dh: String, val auth: String)

    /** Sends [message] to each of the person's browsers; returns how many took it. */
    suspend fun send(userId: String, message: PushMessage, urgent: Boolean = false, ttlSeconds: Int = 4 * 3600): Int {
        val targets = db.tx {
            query("SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ?", userId) {
                Target(it.string("id"), it.string("endpoint"), it.string("p256dh"), it.string("auth"))
            }
        }
        if (targets.isEmpty()) return 0
        val keys = keys()
        val payload = ApiJson.encodeToString(PushMessage.serializer(), message).toByteArray()
        val subject = subject()
        var delivered = 0
        for (target in targets) {
            val status = runCatching {
                val body = WebPushCrypto.encrypt(payload, WebPushCrypto.decode(target.p256dh), WebPushCrypto.decode(target.auth))
                val request = Request.Builder().url(target.endpoint)
                    .post(body.toRequestBody("application/octet-stream".toMediaType()))
                    .header("Content-Encoding", "aes128gcm")
                    .header("TTL", ttlSeconds.toString())
                    .header("Urgency", if (urgent) "high" else "normal")
                    .header("Authorization", WebPushCrypto.vapid(target.endpoint, keys, subject, clock()))
                    .build()
                client.call(request) { it.code }
            }.getOrElse { e ->
                log.warn("Push to a browser failed: {}", e.message)
                -1
            }
            when {
                status in 200..299 -> {
                    delivered++
                    db.tx { update("UPDATE push_subscriptions SET last_ok_at = ?, failures = 0 WHERE id = ?", clock(), target.id) }
                }
                status == 404 || status == 410 -> {
                    // The browser unsubscribed, or the subscription expired.
                    log.info("A browser's notifications ended (HTTP {}); forgetting it", status)
                    db.tx { update("DELETE FROM push_subscriptions WHERE id = ?", target.id) }
                }
                else -> {
                    if (status > 0) log.warn("A push service answered HTTP {}", status)
                    db.tx {
                        update("UPDATE push_subscriptions SET failures = failures + 1 WHERE id = ?", target.id)
                        // A subscription that has failed for days is gone in all but name.
                        update("DELETE FROM push_subscriptions WHERE id = ? AND failures >= 50", target.id)
                    }
                }
            }
        }
        return delivered
    }
}
