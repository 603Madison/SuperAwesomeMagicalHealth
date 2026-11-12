package com.health.server.platform

import com.health.server.ApiError
import com.health.server.db.Database
import com.health.server.util.SecretBox
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.Properties

@Serializable
enum class SmtpSecurity { NONE, STARTTLS, TLS }

/** Mail for the weekly digest, set by the admin. The password is kept apart, sealed. */
@Serializable
data class SmtpSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 587,
    val security: SmtpSecurity = SmtpSecurity.STARTTLS,
    val username: String = "",
    /** The sender: "SuperAwesomeMagicalHealth <health@example.com>". */
    val from: String = "",
) {
    val ready: Boolean get() = enabled && host.isNotBlank() && from.isNotBlank()
}

@Serializable
data class SmtpAdmin(val settings: SmtpSettings, val passwordSet: Boolean)

@Serializable
data class SmtpChange(val settings: SmtpSettings, val password: String? = null)

@Serializable
data class SmtpTest(val to: String)

/** Sends one email: plain text, with an HTML version. */
fun interface Mailer {
    suspend fun send(to: String, subject: String, text: String, html: String)
}

/** The SMTP password, sealed in the settings table like the sign-in provider's secret. */
class SmtpPassword(private val db: Database, private val box: SecretBox) {
    suspend fun get(): String = db.tx {
        one("SELECT value FROM settings WHERE key = 'smtp.password'") { it.string("value") }
    }?.let { runCatching { box.open(it, "smtp.password") }.getOrNull() }.orEmpty()

    suspend fun set(password: String) = db.tx {
        if (password.isEmpty()) update("DELETE FROM settings WHERE key = 'smtp.password'")
        else update("INSERT OR REPLACE INTO settings (key, value) VALUES ('smtp.password', ?)", box.seal(password, "smtp.password"))
    }
}

/** Sends through the admin's mail server with Jakarta Mail (Eclipse Angus). */
class SmtpMailer(private val settings: suspend () -> SmtpSettings, private val password: suspend () -> String) : Mailer {
    override suspend fun send(to: String, subject: String, text: String, html: String) {
        val smtp = settings()
        if (!smtp.ready) throw ApiError.badRequest("Email isn't set up on this server yet: an admin can add a mail server in Admin › Email")
        val secret = password()
        val props = Properties().apply {
            put("mail.smtp.host", smtp.host)
            put("mail.smtp.port", smtp.port.toString())
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
            put("mail.smtp.writetimeout", "15000")
            put("mail.smtp.auth", (smtp.username.isNotBlank()).toString())
            when (smtp.security) {
                SmtpSecurity.STARTTLS -> {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                }
                SmtpSecurity.TLS -> put("mail.smtp.ssl.enable", "true")
                SmtpSecurity.NONE -> Unit
            }
        }
        val message = message(Session.getInstance(props), smtp.from, to, subject, text, html)
        withContext(Dispatchers.IO) {
            if (smtp.username.isNotBlank()) Transport.send(message, smtp.username, secret) else Transport.send(message)
        }
    }

    companion object {
        fun message(session: Session, from: String, to: String, subject: String, text: String, html: String): MimeMessage =
            MimeMessage(session).apply {
                setFrom(InternetAddress(from))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject(subject, "UTF-8")
                val alternative = MimeMultipart("alternative")
                alternative.addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8") })
                alternative.addBodyPart(MimeBodyPart().apply { setContent(html, "text/html; charset=UTF-8") })
                setContent(alternative)
            }

        /** A plausible address: something@somewhere, one of them. */
        fun validAddress(address: String): Boolean =
            address.length in 3..254 && runCatching { InternetAddress(address, true); address.contains('@') && !address.contains(',') }.getOrDefault(false)
    }
}
