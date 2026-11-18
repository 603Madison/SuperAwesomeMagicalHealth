package com.health.server.records

import com.health.api.ApiJson
import com.health.api.DataPath
import com.health.api.HealthRecords
import com.health.api.LabResult
import com.health.api.RecordAuthorize
import com.health.api.RecordConnect
import com.health.api.RecordConnection
import com.health.api.RecordItem
import com.health.api.RecordKind
import com.health.api.RecordOrganization
import com.health.api.RecordProvider
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.auth.OidcClient
import com.health.server.modules.EntryKind
import com.health.server.platform.PrivacyStore
import com.health.server.records.Fhir.arr
import com.health.server.records.Fhir.num
import com.health.server.records.Fhir.obj
import com.health.server.records.Fhir.str
import com.health.server.util.OutboundDns
import com.health.server.util.Random
import com.health.server.util.call
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Medical records from patient portals on Epic and athenahealth (shared Records.kt, /api/records):
 * connecting with SMART on FHIR (R4, a standalone patient launch with PKCE), reading the record,
 * and keeping it up to date. Read-only: nothing is ever written to the portal. Access is sealed
 * and never leaves the server. Every request out asks `privacy.allows(userId, DataPath.HEALTH_RECORDS)`
 * first, and goes only to an organization on Epic's public list or athenahealth's one address (and
 * the sign-in addresses they give), never redirected, never to private addresses.
 */
class RecordsService(private val services: Services, private val urls: Urls = Urls()) {
    /** Where the organizations are; tests point these at a stand-in, over plain http. */
    data class Urls(
        val epicDirectory: String = EPIC_DIRECTORY,
        val athenaBase: String = ATHENA_BASE,
        /** Only https addresses, everywhere (off in tests alone). */
        val httpsOnly: Boolean = true,
    )

    private val log = LoggerFactory.getLogger("samh.records")
    private val clock = services.clock
    private val store = RecordStore(services.db, services.box, clock)
    private val loopback get() = services.config.outboundLoopback

    // Hospitals are on the internet: never a private address (but this machine in tests), never a redirect.
    private val client = services.http.newBuilder()
        .dns(OutboundDns({ loopback }, { loopback }))
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** First imports after a sign-in and the daily round run here, apart from any request. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val round = Mutex()

    /** How long the portal's redirect back waits for the first import before showing the page. */
    @Volatile
    var firstImportWaitMs = 5_000L

    @Volatile
    private var epicCache: Pair<Long, List<RecordOrganization>>? = null
    private val discoveries = ConcurrentHashMap<String, Pair<Long, Smart>>()

    // ---- What's connected

    suspend fun state(userId: String): HealthRecords =
        HealthRecords(services.settings.get().records.providers, paused = !allowed(userId), connections = connections(userId))

    /** The person's connected portals (with one disconnected but kept, too). */
    suspend fun connections(userId: String): List<RecordConnection> {
        val counts = store.counts(userId)
        return store.connections(userId).map { view(it, counts[it.id].orEmpty()) }
    }

    /** The connections that can still reach their portal, for Where your data goes. */
    suspend fun linked(userId: String): List<RecordConnection> =
        store.connections(userId).filter { it.access.accessToken != null || it.access.refreshToken != null }.map { view(it, emptyMap()) }

    /** The hosts their records are read from, for Where your data goes. */
    suspend fun hosts(userId: String): List<String> = linked(userId).mapNotNull { it.fhirBaseUrl.toHttpUrlOrNull()?.host }.distinct()

    suspend fun items(userId: String, kind: RecordKind?, connectionId: String?): List<RecordItem> = store.items(userId, kind, connectionId)

    private fun view(c: RecordStore.Connection, counts: Map<RecordKind, Int>) = RecordConnection(
        id = c.id, organization = c.access.organization, fhirBaseUrl = c.access.fhirBaseUrl, provider = c.provider,
        connectedAt = c.createdAt, lastSyncAt = c.lastSyncAt, problem = c.access.problem,
        keepsUpdating = c.access.refreshToken != null, counts = counts,
    )

    private suspend fun allowed(userId: String) = services.privacy.allows(userId, DataPath.HEALTH_RECORDS)

    private suspend fun requireAllowed(userId: String) {
        if (!allowed(userId)) throw ApiError.conflict(PAUSED)
    }

    private suspend fun setUp(provider: RecordProvider) = provider in services.settings.get().records.providers

    private suspend fun requireSetUp(provider: RecordProvider) {
        if (!setUp(provider)) throw ApiError.conflict(notSetUp(provider))
    }

    // ---- Organizations

    /** Organizations to connect to, matching [query] by words, for the systems set up here. */
    suspend fun organizations(userId: String, query: String?): List<RecordOrganization> {
        val providers = services.settings.get().records.providers
        if (providers.isEmpty()) return emptyList()
        requireAllowed(userId)
        val epic = if (RecordProvider.EPIC in providers) search(epicDirectory(), query.orEmpty()) else emptyList()
        val athena = if (RecordProvider.ATHENA in providers) listOf(athena()) else emptyList()
        return epic + athena
    }

    private fun athena() = RecordOrganization(ATHENA_NAME, normalize(urls.athenaBase), RecordProvider.ATHENA)

    /** Epic's organizations, from its public list: kept a week (an older copy if it can't be reached). */
    private suspend fun epicDirectory(): List<RecordOrganization> {
        val now = clock()
        epicCache?.takeIf { now - it.first < WEEK_MS }?.let { return it.second }
        val saved = store.directory(RecordProvider.EPIC)
        val savedList = saved?.let { (data, _) -> runCatching { ApiJson.decodeFromString(ListSerializer(RecordOrganization.serializer()), data) }.getOrNull() }
        if (saved != null && savedList != null && now - saved.second < WEEK_MS) {
            epicCache = saved.second to savedList
            return savedList
        }
        val fresh = try {
            val answer = send(Request.Builder().url(urls.epicDirectory).header("Accept", "application/fhir+json, application/json").get().build(), MAX_DIRECTORY)
            if (answer.code != 200) throw IOException("HTTP ${answer.code}")
            parseDirectory(answer.body, urls.httpsOnly).takeIf { it.isNotEmpty() } ?: throw IOException("An empty list")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Epic's list of organizations couldn't be fetched: {}", e.javaClass.simpleName)
            if (savedList != null) return savedList.also { epicCache = now to it }
            throw ApiError.upstream("Epic's list of organizations couldn't be reached. Try again in a moment")
        }
        store.saveDirectory(RecordProvider.EPIC, ApiJson.encodeToString(ListSerializer(RecordOrganization.serializer()), fresh))
        epicCache = now to fresh
        return fresh
    }

    /** The organization at [base] on [provider]'s list, or null when it isn't one: nothing else is ever asked. */
    private suspend fun known(provider: RecordProvider, base: String): RecordOrganization? {
        if (base.toHttpUrlOrNull() == null || (urls.httpsOnly && !base.startsWith("https://"))) return null
        return when (provider) {
            RecordProvider.EPIC -> epicDirectory().firstOrNull { it.fhirBaseUrl == base }
            RecordProvider.ATHENA -> athena().takeIf { it.fhirBaseUrl == base }
        }
    }

    // ---- Connecting

    /** The portal's sign-in address for [input], with a sign-in kept ten minutes for its way back. */
    suspend fun connect(userId: String, input: RecordConnect, redirectUri: String): RecordAuthorize {
        requireAllowed(userId)
        if (!setUp(input.provider)) throw ApiError.badRequest(notSetUp(input.provider))
        val base = normalize(input.fhirBaseUrl)
        val organization = known(input.provider, base) ?: throw ApiError.badRequest("That isn't an organization this server knows. Choose one from the list")
        val smart = discover(base)
        val app = services.settings.get().records.of(input.provider)
        val verifier = Random.token(48)
        val state = Random.token(32)
        store.startPending(
            userId, state,
            RecordStore.Pending(input.provider, organization.name, base, smart.token, smart.revoke, verifier, redirectUri, input.app),
            PENDING_MS,
        )
        val url = smart.authorize.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", app.clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", SCOPES.getValue(input.provider))
            .addQueryParameter("state", state)
            .addQueryParameter("aud", base)
            .addQueryParameter("code_challenge", OidcClient.challengeOf(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .build()
        return RecordAuthorize(url.toString())
    }

    /** Where a portal says to sign in, get tokens and give them back. */
    data class Smart(val authorize: String, val token: String, val revoke: String?)

    private suspend fun discover(base: String): Smart {
        val now = clock()
        discoveries[base]?.takeIf { now - it.first < HOUR_MS }?.let { return it.second }
        val smart = try {
            wellKnown(base) ?: metadata(base)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("A portal's sign-in details couldn't be fetched: {}", e.javaClass.simpleName)
            null
        } ?: throw ApiError.upstream("That portal didn't say how to sign in to it. Try again in a moment")
        discoveries[base] = now to smart
        return smart
    }

    private suspend fun wellKnown(base: String): Smart? {
        val answer = send(Request.Builder().url("$base/.well-known/smart-configuration").header("Accept", "application/json").get().build())
        if (answer.code != 200) return null
        val json = runCatching { ApiJson.parseToJsonElement(answer.body) as? JsonObject }.getOrNull() ?: return null
        return smart(json.str("authorization_endpoint"), json.str("token_endpoint"), json.str("revocation_endpoint"))
    }

    /** Older servers say it in their CapabilityStatement's security extension (SMART's oauth-uris). */
    private suspend fun metadata(base: String): Smart? {
        val answer = send(Request.Builder().url("$base/metadata").header("Accept", FHIR_JSON).get().build())
        if (answer.code != 200) return null
        val json = runCatching { ApiJson.parseToJsonElement(answer.body) as? JsonObject }.getOrNull() ?: return null
        val uris = json.arr("rest").orEmpty().mapNotNull { (it as? JsonObject)?.obj("security") }
            .flatMap { it.arr("extension").orEmpty() }.mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("url") == OAUTH_URIS }?.arr("extension").orEmpty().mapNotNull { it as? JsonObject }
        fun uri(name: String) = uris.firstOrNull { it.str("url") == name }?.str("valueUri")
        return smart(uri("authorize"), uri("token"), uri("revoke"))
    }

    private fun smart(authorize: String?, token: String?, revoke: String?): Smart? {
        fun ok(url: String?) = url?.toHttpUrlOrNull()?.takeIf { !urls.httpsOnly || it.isHttps }?.toString()
        return Smart(ok(authorize) ?: return null, ok(token) ?: return null, ok(revoke))
    }

    /**
     * The portal sending the browser back with [params] (`code` and `state`, or `error`): returns
     * where to send it next. The person is known by the sign-in's single-use `state` alone (the
     * session cookie doesn't come on a redirect from another site); when one does come, it must be theirs.
     */
    /**
     * Where the portal sends the browser back. Nothing is connected yet: the portal's code is kept,
     * sealed under a new single-use token, and the browser goes on to the app, which confirms it as
     * the signed-in person who started it ([confirm]). So someone else on this server can't start a
     * sign-in, get another person to allow it at their portal, and have that record land with them.
     */
    suspend fun callback(params: Map<String, String?>, sessionUserId: String?): String {
        val state = params["state"]?.takeIf { it.length in 16..256 }
        val found = state?.let { store.finishPending(it) }?.takeIf { it.second.code == null } ?: return back(false, error = EXPIRED)
        val (userId, p) = found
        if (sessionUserId != null && sessionUserId != userId) return back(p.app, error = SOMEONE_ELSE)
        params["error"]?.let { return back(p.app, error = if (it == "access_denied") DENIED else PORTAL_REFUSED) }
        val code = params["code"]?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: return back(p.app, error = PORTAL_REFUSED)
        val confirm = Random.token(32)
        store.startPending(userId, confirm, p.copy(code = code), CONFIRM_MS)
        return back(p.app, confirm = confirm)
    }

    /** Connects the portal the person just allowed: only the one who started it, signed in, within a few minutes. */
    suspend fun confirm(userId: String, token: String): RecordConnection {
        val found = token.takeIf { it.length in 16..256 }?.let { store.finishPending(it) }?.takeIf { it.second.code != null }
            ?: throw ApiError.badRequest(EXPIRED)
        val (startedBy, p) = found
        if (startedBy != userId) throw ApiError.forbidden(SOMEONE_ELSE)
        requireAllowed(userId)
        if (!setUp(p.provider)) throw ApiError.badRequest(notSetUp(p.provider))
        val tokens = try {
            token(p.provider, p.tokenEndpoint, FormBody.Builder().add("grant_type", "authorization_code").add("code", p.code!!).add("redirect_uri", p.redirectUri).add("code_verifier", p.verifier))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("A portal didn't give access for a sign-in: {}", (e as? Refused)?.let { "HTTP ${it.status}" } ?: e.javaClass.simpleName)
            throw ApiError.badRequest(EXCHANGE_FAILED)
        }
        val patient = tokens.patient ?: throw ApiError.badRequest("The portal didn't say whose record it is, so nothing was connected")
        val access = RecordStore.Access(
            organization = p.organization, fhirBaseUrl = p.fhirBaseUrl, tokenEndpoint = p.tokenEndpoint, revokeEndpoint = p.revokeEndpoint,
            accessToken = tokens.access, refreshToken = tokens.refresh, expiresAt = tokens.expiresAt, patient = patient, scope = tokens.scope,
        )
        // The same portal and person again (signing in again, or after disconnecting and keeping it): that connection.
        val existing = store.connections(userId).firstOrNull { it.access.fhirBaseUrl == p.fhirBaseUrl && it.access.patient == patient }
        val id = if (existing != null) {
            store.save(existing, access.copy(organization = existing.access.organization))
            existing.id
        } else {
            store.insert(userId, p.provider, access).id
        }
        log.info("Medical records: a portal was connected ({})", id)
        val first = scope.launch { runCatching { syncNow(userId, id) }.onFailure { log.warn("A first import failed: {}", it.javaClass.simpleName) } }
        withTimeoutOrNull(firstImportWaitMs) { first.join() }
        return connections(userId).first { it.id == id }
    }

    /**
     * The address the browser goes on to. An error goes as a short code the apps turn into its
     * sentence ([RETURN_ERRORS]), so a link made up elsewhere can't put words of its own on the page.
     */
    private fun back(app: Boolean, confirm: String? = null, error: String? = null): String {
        val query = confirm?.let { "confirm=${encode(it)}" } ?: "error=${encode(RETURN_ERRORS.entries.firstOrNull { it.value == error }?.key ?: "failed")}"
        return if (app) "$APP_RETURN?$query" else "$WEB_RETURN?$query"
    }

    // ---- Tokens

    private class Tokens(val access: String, val refresh: String?, val expiresAt: Long?, val patient: String?, val scope: String?)

    /** The portal said no ([status]); its words are never passed on. */
    private class Refused(val status: Int) : IOException("Refused")

    private suspend fun token(provider: RecordProvider, endpoint: String, form: FormBody.Builder): Tokens {
        val clientId = services.settings.get().records.of(provider).clientId
        val secret = services.settings.recordSecret(provider)
        if (secret.isEmpty()) form.add("client_id", clientId)
        val request = Request.Builder().url(endpoint).header("Accept", "application/json")
            .apply { if (secret.isNotEmpty()) header("Authorization", Credentials.basic(clientId, secret)) }
            .post(form.build()).build()
        val answer = send(request)
        if (answer.code !in 200..299) throw Refused(answer.code)
        val json = runCatching { ApiJson.parseToJsonElement(answer.body) as? JsonObject }.getOrNull() ?: throw Refused(answer.code)
        val access = json.str("access_token")?.takeIf { it.isNotBlank() } ?: throw Refused(answer.code)
        val expires = json.num("expires_in")?.toLong()?.takeIf { it > 0 }
        return Tokens(
            access = access,
            refresh = json.str("refresh_token")?.takeIf { it.isNotBlank() },
            expiresAt = expires?.let { clock() + it * 1000 },
            patient = json.str("patient")?.takeIf { it.isNotBlank() },
            scope = json.str("scope"),
        )
    }

    /** Access that works now: refreshed when it's within a minute of running out (keeping a new refresh token if the portal gives one). */
    private suspend fun fresh(c: RecordStore.Connection): RecordStore.Access {
        val a = c.access
        val now = clock()
        if (a.accessToken != null && (a.expiresAt == null || a.expiresAt - REFRESH_EARLY_MS > now)) return a
        val refresh = a.refreshToken ?: throw Refused(401)
        val tokens = token(c.provider, a.tokenEndpoint, FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh))
        val next = a.copy(accessToken = tokens.access, refreshToken = tokens.refresh ?: refresh, expiresAt = tokens.expiresAt, scope = tokens.scope ?: a.scope)
        store.save(c, next)
        return next
    }

    // ---- Bringing a record up to date

    /** Brings connection [id] up to date now, as the person asked. */
    suspend fun sync(userId: String, id: String): RecordConnection {
        val c = store.connection(userId, id) ?: throw ApiError.notFound("No such connection")
        requireAllowed(userId)
        requireSetUp(c.provider)
        syncNow(userId, id)
        return connections(userId).firstOrNull { it.id == id } ?: throw ApiError.notFound("No such connection")
    }

    private suspend fun syncNow(userId: String, id: String) = locks.computeIfAbsent(id) { Mutex() }.withLock { read(userId, id) }

    private suspend fun read(userId: String, id: String) {
        val c = store.connection(userId, id) ?: return
        val now = clock()
        if (!allowed(userId) || !setUp(c.provider)) return
        if (c.access.accessToken == null && c.access.refreshToken == null) {
            store.save(c, c.access, attempted = now)
            return
        }
        fun problem(text: String, gone: Boolean = false): RecordStore.Access =
            if (gone) c.access.copy(accessToken = null, refreshToken = null, expiresAt = null, problem = text) else c.access.copy(problem = text)
        var access = try {
            fresh(c)
        } catch (e: Refused) {
            // Refused for good (400 invalid_grant, 401): access is gone; anything else may pass.
            val gone = e.status in 400..401
            log.info("Medical records: a connection's access couldn't be renewed ({}: HTTP {})", id, e.status)
            store.save(c, problem(if (gone) SIGN_IN_AGAIN else UNREACHABLE, gone), attempted = now)
            return
        } catch (e: IOException) {
            log.info("Medical records: a portal didn't answer ({}: {})", id, e.javaClass.simpleName)
            store.save(c, problem(UNREACHABLE), attempted = now)
            return
        }
        val current = c.copy(access = access)
        val zone = services.profiles.days(userId)
        val base = access.fhirBaseUrl
        val token = access.accessToken!!
        try {
            // Whose record: it must be the one the access was given for.
            val patient = send(fhirGet("$base/Patient/${encodePath(access.patient)}", token))
            when (patient.code) {
                200 -> {
                    val json = runCatching { ApiJson.parseToJsonElement(patient.body) as? JsonObject }.getOrNull()
                    if (json == null || json.str("resourceType") != "Patient" || json.str("id") != access.patient) {
                        log.warn("Medical records: a portal sent a record that wasn't the person's own ({})", id)
                        store.save(current, access.copy(problem = NOT_YOURS), attempted = now)
                        return
                    }
                    // athenahealth's one address serves every practice: the record names hers.
                    if (c.provider == RecordProvider.ATHENA && access.organization == ATHENA_NAME) {
                        json.obj("managingOrganization")?.str("display")?.trim()?.takeIf { it.isNotEmpty() }?.let { access = access.copy(organization = it.take(120)) }
                    }
                }
                401 -> throw Refused(401)
                400, 403, 404 -> Unit
                else -> throw IOException("HTTP ${patient.code}")
            }
            val mapped = mutableListOf<Fhir.Mapped>()
            val complete = mutableSetOf<RecordKind>()
            for (search in Fhir.SEARCHES) {
                val result = searchAll(base, token, search, access.patient) ?: continue
                result.resources.mapNotNullTo(mapped) { Fhir.map(it, search.kind, access.organization, zone, access.patient, result.included) }
                if (result.complete) complete += search.kind
            }
            val all = mapped.distinctBy { it.key + "|" + it.item.kind }
            store.replace(userId, id, all, complete)
            keepLabs(userId, id, access.organization, all.filter { it.item.kind == RecordKind.LAB }, RecordKind.LAB in complete)
            store.save(current, access.copy(problem = null), synced = now, attempted = now)
            log.info("Medical records: a connection was brought up to date ({})", id)
        } catch (e: Refused) {
            log.info("Medical records: a portal no longer accepts a connection's access ({})", id)
            // A refresh token may still renew it next time; until then, it says so.
            store.save(current, access.copy(accessToken = null, expiresAt = null, problem = SIGN_IN_AGAIN), attempted = now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info("Medical records: a portal didn't answer ({}: {})", id, e.javaClass.simpleName)
            store.save(current, access.copy(problem = UNREACHABLE), attempted = now)
        }
    }

    private class Found(val resources: List<JsonObject>, val included: Map<String, JsonObject>, val complete: Boolean)

    /**
     * Every page of a search for the patient's [search] (following `next` on the same server, up to
     * [MAX_PAGES]); null when the portal doesn't offer it or allow it. A 401 means access is gone.
     */
    private suspend fun searchAll(base: String, token: String, search: Fhir.Search, patient: String): Found? {
        val origin = base.toHttpUrl()
        var url: HttpUrl? = origin.newBuilder().addPathSegment(search.type).addQueryParameter("patient", patient)
            .apply { search.params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val resources = mutableListOf<JsonObject>()
        val included = mutableMapOf<String, JsonObject>()
        var pages = 0
        while (url != null && pages < MAX_PAGES) {
            val answer = send(fhirGet(url.toString(), token))
            when (answer.code) {
                200 -> Unit
                401 -> throw Refused(401)
                400, 403, 404 -> return if (pages == 0) null else Found(resources, included, false)
                else -> throw IOException("HTTP ${answer.code}")
            }
            val bundle = runCatching { ApiJson.parseToJsonElement(answer.body) as? JsonObject }.getOrNull()
                ?.takeIf { it.str("resourceType") == "Bundle" } ?: throw IOException("Not a bundle")
            for (entry in bundle.arr("entry").orEmpty().mapNotNull { it as? JsonObject }) {
                val resource = entry.obj("resource") ?: continue
                val type = resource.str("resourceType")
                val mode = entry.obj("search")?.str("mode")
                when {
                    type == search.type && mode != "include" && mode != "outcome" -> resources += resource
                    type == "Medication" -> resource.str("id")?.let { included["Medication/$it"] = resource }
                }
            }
            pages++
            // Only further pages on the same server: the access never goes anywhere else.
            url = bundle.arr("link").orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.str("relation") == "next" }?.str("url")
                ?.toHttpUrlOrNull()?.takeIf { it.scheme == origin.scheme && it.host == origin.host && it.port == origin.port }
        }
        return Found(resources, included, url == null)
    }

    private fun fhirGet(url: String, token: String) = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", FHIR_JSON).get().build()

    /**
     * Lab results with a number into Medications › Labs, each marked with the organization and
     * `record:{connection}:{id}`: never added twice, changed when the record changes it, and (after
     * a full read) gone when the record no longer has it.
     */
    private suspend fun keepLabs(userId: String, connectionId: String, organization: String, labs: List<Fhir.Mapped>, complete: Boolean) {
        val prefix = "$REF_PREFIX$connectionId:"
        val existing = services.modules.meds.labs(userId, 36500).filter { it.ref?.startsWith(prefix) == true }.associateBy { it.ref!! }
        val zone = services.profiles.days(userId)
        val seen = mutableSetOf<String>()
        for (m in labs) {
            val v = m.lab ?: continue
            val ref = prefix + m.key.substringAfter('/')
            if (!seen.add(ref)) continue
            val lab = LabResult(
                name = m.item.title.take(80), value = v.value, unit = v.unit.take(30), at = v.at, low = v.low, high = v.high,
                source = organization, ref = ref,
            )
            val old = existing[ref]
            if (old == null) {
                services.entries.put(userId, EntryKind.LAB, null, zone.dateOf(v.at), v.at, lab, LabResult.serializer())
            } else if (old.copy(id = null, date = null, note = null) != lab) {
                services.entries.put(userId, EntryKind.LAB, old.id, zone.dateOf(v.at), v.at, lab.copy(note = old.note), LabResult.serializer())
            }
        }
        if (complete) existing.filterKeys { it !in seen }.values.forEach { old -> old.id?.let { runCatching { services.entries.delete(userId, EntryKind.LAB, it) } } }
    }

    private suspend fun deleteLabs(userId: String, connectionId: String) {
        val prefix = "$REF_PREFIX$connectionId:"
        services.modules.meds.labs(userId, 36500).filter { it.ref?.startsWith(prefix) == true }
            .forEach { lab -> lab.id?.let { runCatching { services.entries.delete(userId, EntryKind.LAB, it) } } }
    }

    // ---- Disconnecting

    /**
     * Forgets connection [id]'s access (asking the portal to end it, where it can). With [keep],
     * what it brought in stays (the connection stays listed, without access, until it's removed or
     * connected again); without, everything it brought in goes, labs too.
     */
    suspend fun disconnect(userId: String, id: String, keep: Boolean) {
        val c = store.connection(userId, id) ?: throw ApiError.notFound("No such connection")
        locks.computeIfAbsent(id) { Mutex() }.withLock {
            val now = store.connection(userId, id) ?: return
            if (allowed(userId) && setUp(now.provider)) withTimeoutOrNull(REVOKE_WAIT_MS) { revoke(now) }
            if (keep) {
                store.save(now, now.access.copy(accessToken = null, refreshToken = null, expiresAt = null, problem = DISCONNECTED))
            } else {
                deleteLabs(userId, id)
                store.delete(userId, id)
            }
        }
        locks.remove(c.id)
    }

    /** Best effort (RFC 7009): a portal that offers revoking is asked to end the access. */
    private suspend fun revoke(c: RecordStore.Connection) {
        val endpoint = c.access.revokeEndpoint ?: return
        val (token, hint) = c.access.refreshToken?.let { it to "refresh_token" } ?: c.access.accessToken?.let { it to "access_token" } ?: return
        try {
            val clientId = services.settings.get().records.of(c.provider).clientId
            val secret = services.settings.recordSecret(c.provider)
            val form = FormBody.Builder().add("token", token).add("token_type_hint", hint).apply { if (secret.isEmpty()) add("client_id", clientId) }.build()
            val request = Request.Builder().url(endpoint).apply { if (secret.isNotEmpty()) header("Authorization", Credentials.basic(clientId, secret)) }.post(form).build()
            send(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info("Medical records: a portal couldn't be asked to end access ({})", e.javaClass.simpleName)
        }
    }

    // ---- Each day

    /** Starts the daily round in the background, unless one is still going (the scheduler's each minute). */
    fun startDailyRound(now: Long) {
        if (round.isLocked) return
        scope.launch { runCatching { syncDue(now) }.onFailure { log.warn("The medical records round failed: {}", it.javaClass.simpleName) } }
    }

    /** Brings each connection with lasting access up to date, once a day. */
    suspend fun syncDue(now: Long) {
        if (!round.tryLock()) return
        try {
            for (c in store.due(now - DAY_MS)) {
                val user = services.users.byId(c.userId) ?: continue
                if (user.disabled || !setUp(c.provider) || !allowed(c.userId)) continue
                try {
                    syncNow(c.userId, c.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Medical records: a daily update failed ({}: {})", c.id, e.javaClass.simpleName)
                }
            }
        } finally {
            round.unlock()
        }
    }

    // ---- Talking to portals

    private class Answer(val code: Int, val body: String)

    private suspend fun send(request: Request, max: Int = MAX_BODY): Answer = client.call(request) { response ->
        val bytes = response.body?.byteStream()?.use { it.readNBytes(max + 1) } ?: ByteArray(0)
        if (bytes.size > max) throw IOException("Too much")
        Answer(response.code, String(bytes, Charsets.UTF_8))
    }

    companion object {
        /** Where the portal sends the person back after they allow it: registered with each system as the redirect URI. */
        const val CALLBACK_PATH = "/api/records/callback"
        const val EPIC_DIRECTORY = "https://open.epic.com/Endpoints/R4"
        const val ATHENA_BASE = "https://api.platform.athenahealth.com/fhir/r4"
        const val ATHENA_NAME = "A practice on athenahealth"
        const val REF_PREFIX = "record:"
        const val WEB_RETURN = "/records"
        const val APP_RETURN = "samhealth://records"
        private const val FHIR_JSON = "application/fhir+json"
        private const val OAUTH_URIS = "http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris"

        /**
         * What's asked for. Epic grants what the app was registered for and ignores most of the
         * rest; athenahealth checks each resource, in SMART v2's form, so it gets exactly the ones read.
         */
        val SCOPES: Map<RecordProvider, String> = mapOf(
            RecordProvider.EPIC to "launch/patient openid fhirUser offline_access patient/*.read",
            RecordProvider.ATHENA to "launch/patient openid fhirUser offline_access " +
                (listOf("patient/Patient.r") + Fhir.SEARCHES.map { it.type }.distinct().map { "patient/$it.rs" }).joinToString(" "),
        )

        const val PENDING_MS = 10 * 60_000L
        /** How long a sign-in waits for the app to confirm it: portals' codes don't last much longer. */
        const val CONFIRM_MS = 5 * 60_000L
        private const val WEEK_MS = 7 * 86_400_000L
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 23 * 3_600_000L
        private const val REFRESH_EARLY_MS = 60_000L
        private const val REVOKE_WAIT_MS = 3_000L
        private const val MAX_PAGES = 50
        private const val MAX_BODY = 32 * 1024 * 1024
        private const val MAX_DIRECTORY = 16 * 1024 * 1024

        val PAUSED = PrivacyStore.pausedText("Medical records")
        const val SIGN_IN_AGAIN = "Sign in again to bring it up to date"
        const val UNREACHABLE = "The portal didn't answer. It'll try again later"
        const val DISCONNECTED = "Disconnected: what it brought in is kept here. Connect again to bring it up to date"
        const val NOT_YOURS = "The portal sent a record that isn't yours, so nothing was brought in"
        /** The codes the portal's return can carry, and what the apps say for each (any other code: "failed"). */
        val RETURN_ERRORS: Map<String, String> by lazy {
            mapOf("expired" to EXPIRED, "someone-else" to SOMEONE_ELSE, "denied" to DENIED, "refused" to PORTAL_REFUSED)
        }

        const val EXPIRED = "That sign-in expired or was already used. Try connecting again"
        const val SOMEONE_ELSE = "That sign-in was started by someone else on this server, so nothing was connected"
        const val DENIED = "You didn't allow it, so nothing was connected"
        const val PORTAL_REFUSED = "The portal didn't connect it. Try again in a moment"
        const val EXCHANGE_FAILED = "The portal didn't give access, so nothing was connected. Try again in a moment"

        fun notSetUp(provider: RecordProvider) = "Medical records from ${provider.label} aren't set up on this server"

        /** An address as compared and kept: no spaces, no slash at the end. */
        fun normalize(url: String) = url.trim().trimEnd('/')

        /** Epic's organizations from its public list (a FHIR Bundle of Endpoints), by name. */
        fun parseDirectory(text: String, httpsOnly: Boolean = true): List<RecordOrganization> {
            val bundle = ApiJson.parseToJsonElement(text) as? JsonObject ?: return emptyList()
            return bundle.arr("entry").orEmpty().mapNotNull { (it as? JsonObject)?.obj("resource") }
                .filter { it.str("resourceType") == "Endpoint" && (it.str("status") ?: "active") == "active" }
                .mapNotNull { e ->
                    val address = e.str("address")?.let(::normalize)?.takeIf { a -> a.toHttpUrlOrNull() != null && (!httpsOnly || a.startsWith("https://")) } ?: return@mapNotNull null
                    val name = e.str("name")?.trim()?.ifBlank { null }
                        ?: e.arr("contained").orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.str("resourceType") == "Organization" }?.str("name")?.trim()?.ifBlank { null }
                        ?: return@mapNotNull null
                    RecordOrganization(name.take(200), address, RecordProvider.EPIC)
                }
                .distinctBy { it.fhirBaseUrl }.sortedBy { it.name.lowercase() }
        }

        /** [all] whose names have every word of [query] (each the start of one of the name's words), best first, at most 25. */
        fun search(all: List<RecordOrganization>, query: String): List<RecordOrganization> {
            val words = words(query)
            if (words.isEmpty()) return emptyList()
            return all.mapNotNull { o ->
                val name = words(o.name)
                if (words.all { w -> name.any { it.startsWith(w) } }) o to (if (name.firstOrNull()?.startsWith(words[0]) == true) 0 else 1) else null
            }.sortedWith(compareBy({ it.second }, { it.first.name.lowercase() })).map { it.first }.take(25)
        }

        private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

        private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

        private fun encodePath(text: String) = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
    }
}
