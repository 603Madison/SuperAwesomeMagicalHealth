package com.health.server.platform

import com.health.api.IntegrationKey
import com.health.api.PushKey
import com.health.api.PushMessage
import com.health.api.PushSubscriptionInput
import com.health.api.ReportSectionKey
import com.health.api.SessionKind
import com.health.api.SmtpStatus
import com.health.api.User
import com.health.api.Webhook
import com.health.api.WebhookEvent
import com.health.api.WatchLog
import com.health.server.ApiError
import com.health.server.Services
import com.health.server.auth.principal
import com.health.server.auth.publicBase
import com.health.server.auth.requireAdmin
import com.health.server.auth.requireUser
import com.health.server.util.Days
import com.health.server.util.parseDate
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.time.LocalDate

@Serializable
data class KeyRequest(val name: String = "")

@Serializable
data class Sent(val sent: Int)

/** Notifications, reminders, the digest, webhooks, Home Assistant, reports, exports and the watch. */
fun Route.platformRoutes(services: Services) {
    val platform by lazy { services.platform }

    route("/api/push") {
        get("/key") {
            call.requireUser(services)
            call.respond(PushKey(platform.push.publicKey()))
        }
        route("/subscriptions") {
            get { call.respond(platform.push.list(call.requireUser(services).id)) }
            post { call.respond(platform.push.subscribe(call.requireUser(services).id, call.receive<PushSubscriptionInput>())) }
            delete("/{id}") {
                if (!platform.push.delete(call.requireUser(services).id, call.parameters["id"].orEmpty())) throw ApiError.notFound("No such browser")
                call.respond(mapOf("ok" to true))
            }
        }
        post("/test") {
            val user = call.requireUser(services)
            if (platform.push.count(user.id) == 0) throw ApiError.badRequest("Turn on notifications in this browser first")
            val sent = platform.push.send(user.id, PushMessage("Notifications are on", "Reminders will come here.", tag = "test", url = "/settings"))
            call.respond(Sent(sent))
        }
    }

    get("/api/reminders/upcoming") {
        val user = call.requireUser(services)
        val profile = services.profiles.get(user.id)
        val hours = call.request.queryParameters["hours"]?.toIntOrNull()?.coerceIn(1, 72) ?: 24
        val now = services.clock()
        val upcoming = platform.scheduler.collect(user.id, profile, now, now + hours * 3_600_000L)
            .filter { it.push }.distinctBy { it.key }.sortedBy { it.at }
            .map { Scheduler.message(it, profile.reminders) }
        call.respond(upcoming)
    }

    route("/api/digest") {
        get {
            val user = call.requireUser(services)
            val week = call.request.queryParameters["week"]?.let(::parseDate) ?: throw ApiError.badRequest("Which week? ?week=YYYY-MM-DD")
            call.respond(platform.digests.forWeek(user.id, week))
        }
        get("/latest") { call.respond(platform.digests.latestOrLastWeek(call.requireUser(services).id)) }
        get("/smtp") {
            call.requireUser(services)
            call.respond(SmtpStatus(services.settings.get().smtp.ready))
        }
        post("/email-test") {
            val user = call.requireUser(services)
            val profile = services.profiles.get(user.id)
            val to = profile.digest.email?.takeIf { SmtpMailer.validAddress(it) } ?: throw ApiError.badRequest("Add your email address in Settings › Weekly digest first")
            if (!services.settings.get().smtp.ready) throw ApiError.badRequest("Email isn't set up on this server yet: an admin can add a mail server in Admin › Email")
            val digest = platform.digests.latestOrLastWeek(user.id)
            try {
                platform.digests.email(user, profile, digest, to)
            } catch (e: ApiError) {
                throw e
            } catch (e: Exception) {
                throw ApiError.upstream("The mail server didn't take it: ${e.message ?: e.javaClass.simpleName}")
            }
            call.respond(mapOf("ok" to true))
        }
    }

    route("/api/webhooks") {
        get { call.respond(platform.webhooks.list(call.requireUser(services).id)) }
        post { call.respond(platform.webhooks.create(call.requireUser(services).id, call.receive<Webhook>())) }
        get("/deliveries") { call.respond(platform.webhooks.deliveries(call.requireUser(services).id)) }
        put("/{id}") { call.respond(platform.webhooks.update(call.requireUser(services).id, call.parameters["id"].orEmpty(), call.receive<Webhook>())) }
        delete("/{id}") {
            platform.webhooks.delete(call.requireUser(services).id, call.parameters["id"].orEmpty())
            call.respond(mapOf("ok" to true))
        }
        post("/{id}/test") {
            val user = call.requireUser(services)
            val hook = platform.webhooks.get(user.id, call.parameters["id"].orEmpty())
            val payload = WebhookService.payload(
                WebhookEvent.TEST, services.clock(), user.username, "A test from SuperAwesomeMagicalHealth",
                json("message" to "If you can read this, the webhook works."),
            )
            call.respond(platform.webhooks.deliver(user.id, hook, WebhookEvent.TEST, payload))
        }
    }

    route("/api/integrations") {
        route("/keys") {
            get {
                val user = call.requireUser(services)
                val url = "${publicBase(services, call)}/api/integrations/state"
                call.respond(services.sessions.list(user.id).filter { it.kind == SessionKind.INTEGRATION }.map { IntegrationKey(it.id, it.name, "", url) })
            }
            post {
                val user = call.requireUser(services)
                val name = runCatching { call.receive<KeyRequest>() }.getOrDefault(KeyRequest()).name.trim().take(80).ifBlank { "Home Assistant" }
                val token = services.sessions.create(user.id, SessionKind.INTEGRATION, name)
                call.respond(IntegrationKey(com.health.server.auth.SessionStore.idOf(token), name, token, "${publicBase(services, call)}/api/integrations/state"))
            }
            delete("/{id}") {
                val user = call.requireUser(services)
                val id = call.parameters["id"].orEmpty()
                if (services.sessions.list(user.id).none { it.id == id && it.kind == SessionKind.INTEGRATION }) throw ApiError.notFound("No such key")
                services.sessions.revoke(user.id, id)
                call.respond(mapOf("ok" to true))
            }
        }
        get("/state") {
            val user = call.keyUser(services, SessionKind.INTEGRATION) ?: throw ApiError.unauthorized("Use a Home Assistant key from Settings › Integrations")
            call.respond(platform.state(user.id))
        }
    }

    // ---- Reports and exports ----

    get("/api/report.pdf") {
        val user = call.requireUser(services)
        val (from, to) = call.period(services, user, defaultDays = 90)
        val sections = call.request.queryParameters["sections"]?.split(',')?.mapNotNull { s -> ReportSectionKey.entries.firstOrNull { it.name.equals(s.trim(), true) } }?.toSet()
        val pdf = platform.reports.pdf(user.id, from, to, sections)
        call.attachment("health-report-$from-to-$to.pdf")
        call.respondBytes(pdf, ContentType.Application.Pdf)
    }

    get("/api/export.zip") {
        val user = call.requireUser(services)
        val (from, to) = call.period(services, user, defaultDays = 365)
        val zip = platform.exports.zip(user.id, from, to)
        call.attachment("health-export-$from-to-$to.zip")
        call.respondBytes(zip, ContentType.Application.Zip)
    }

    get("/api/export/fhir") {
        val user = call.requireUser(services)
        val (from, to) = call.period(services, user, defaultDays = 365)
        val bundle = platform.exports.fhir(user.id, from, to)
        call.respondText(platform.exports.fhirText(bundle), ContentType("application", "fhir+json"))
    }

    // ---- The watch ----

    get("/pebble/config") {
        val returnTo = call.request.queryParameters["return_to"].orEmpty()
        if (!returnTo.lowercase().startsWith("pebblejs://close")) throw ApiError.badRequest("This page is opened by the watch app's settings")
        call.respondRedirect("/watch-setup?return_to=" + URLEncoder.encode(returnTo, Charsets.UTF_8))
    }

    route("/api/watch") {
        post("/keys") {
            val user = call.requireUser(services)
            val name = runCatching { call.receive<KeyRequest>() }.getOrDefault(KeyRequest()).name.trim().take(80).ifBlank { "Watch" }
            val token = services.sessions.create(user.id, SessionKind.WATCH, name)
            call.respond(IntegrationKey(com.health.server.auth.SessionStore.idOf(token), name, token, publicBase(services, call)))
        }
        get("/today") { call.respond(platform.watch.today(call.watchUser(services).id)) }
        post("/log") {
            platform.watch.log(call.watchUser(services).id, call.receive<WatchLog>())
            call.respond(mapOf("ok" to true))
        }
    }

    // ---- Admin: mail ----

    route("/api/admin/smtp") {
        get {
            call.requireAdmin(services)
            call.respond(SmtpAdmin(services.settings.get().smtp, platform.smtpPassword.get().isNotEmpty()))
        }
        put {
            call.requireAdmin(services)
            val change = call.receive<SmtpChange>()
            val s = change.settings
            val clean = s.copy(
                host = s.host.trim().take(200),
                username = s.username.trim().take(200),
                from = s.from.trim().take(200),
                port = s.port.takeIf { it in 1..65535 } ?: throw ApiError.badRequest("A port is 1 to 65535"),
            )
            if (clean.enabled && (clean.host.isBlank() || !SmtpMailer.validAddress(clean.from.substringAfter('<').substringBefore('>')))) {
                throw ApiError.badRequest("Mail needs a server and a sender address")
            }
            // The saved password only goes to the mail server it was given for: a new one needs it again.
            val before = services.settings.get().smtp
            val moved = before.host.isNotBlank() && (!before.host.equals(clean.host, ignoreCase = true) || before.port != clean.port)
            if (moved && change.password == null && platform.smtpPassword.get().isNotEmpty()) {
                throw ApiError.badRequest("Enter the password again for the new mail server")
            }
            services.settings.update { it.copy(smtp = clean) }
            change.password?.let { platform.smtpPassword.set(it) }
            call.respond(SmtpAdmin(clean, platform.smtpPassword.get().isNotEmpty()))
        }
        post("/test") {
            call.requireAdmin(services)
            val to = call.receive<SmtpTest>().to.trim()
            if (!SmtpMailer.validAddress(to)) throw ApiError.badRequest("That isn't an email address")
            try {
                platform.mailer.send(
                    to, "A test from SuperAwesomeMagicalHealth", "If you can read this, email works.",
                    "<p style=\"font-family:Lexend,Helvetica,Arial,sans-serif\">If you can read this, email works.</p>",
                )
            } catch (e: ApiError) {
                throw e
            } catch (e: Exception) {
                throw ApiError.upstream("The mail server didn't take it: ${e.message ?: e.javaClass.simpleName}")
            }
            call.respond(mapOf("ok" to true))
        }
    }
}

/** The watch app's key, or a signed-in browser or phone. */
private suspend fun ApplicationCall.watchUser(services: Services): User =
    keyUser(services, SessionKind.WATCH) ?: principal(services)?.user ?: throw ApiError.unauthorized()

private suspend fun ApplicationCall.period(services: Services, user: User, defaultDays: Long): Pair<LocalDate, LocalDate> {
    val today = Days(Days.zoneOf(services.profiles.get(user.id).timezone)).today(services.clock())
    val to = request.queryParameters["to"]?.takeIf { it.isNotBlank() }?.let(::parseDate) ?: today
    val from = request.queryParameters["from"]?.takeIf { it.isNotBlank() }?.let(::parseDate) ?: to.minusDays(defaultDays - 1)
    return from to to
}

private fun ApplicationCall.attachment(name: String) =
    response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString())
