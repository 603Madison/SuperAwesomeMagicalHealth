package com.health.app.orchestrator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * How one of the family's Android apps talks to SuperAwesomeMagicalOrchestrator on the same
 * phone: to sign in as whoever is signed in there, and to say what the person is doing so their
 * other devices can carry on with it.
 *
 * This file is the same in every app (only the package line differs): it's kept in
 * SAM-DeviceOrchestrator, `kits/android/`; change it there and copy. It needs nothing but
 * Android itself, and this in the app's manifest so the orchestrator can be seen:
 *
 *     <queries><package android:name="app.superawesomemagical.orchestrator" /></queries>
 *
 * Everything here may take a moment (it asks another app, which may ask its server): call it
 * off the main thread.
 */
object OrchestratorLink {
    const val PACKAGE = "app.superawesomemagical.orchestrator"
    private val PROVIDER: Uri = Uri.parse("content://$PACKAGE.link")

    /** The orchestrator's page asking the person whether this app may sign in as them. */
    const val ACTION_APPROVE = "$PACKAGE.APPROVE"

    sealed interface SignIn {
        /** Hand [ticket] to the app's server at [url]: `POST <url>/api/auth/orchestrator`. */
        data class Ticket(val url: String, val ticket: String) : SignIn

        /** The person hasn't said this app may sign in as them yet: start [intent] for a result, and ask again on RESULT_OK. */
        data class NeedsApproval(val intent: Intent) : SignIn

        /** Not installed, nobody signed in, the app isn't listed on the orchestrator, or it couldn't be reached. [reason] is for a log. */
        data class Unavailable(val reason: String) : SignIn
    }

    /** Who is signed in to the orchestrator on this phone. */
    data class Status(val signedIn: Boolean, val server: String, val username: String, val displayName: String)

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    private fun call(context: Context, method: String, arg: String?, extras: Bundle? = null): Bundle? = try {
        context.contentResolver.call(PROVIDER, method, arg, extras)
    } catch (e: IllegalArgumentException) {
        null // not installed, or an older one without this
    } catch (e: SecurityException) {
        null
    }

    fun status(context: Context): Status? = call(context, "status", null)?.let {
        Status(it.getBoolean("signedIn"), it.getString("server").orEmpty(), it.getString("username").orEmpty(), it.getString("displayName").orEmpty())
    }

    /** A ticket to sign in to this app's server ([app]: "music", "health", "video", "maps", "email"). */
    fun signIn(context: Context, app: String): SignIn {
        val answer = call(context, "signIn", app) ?: return SignIn.Unavailable("SuperAwesomeMagicalOrchestrator isn't installed")
        val url = answer.getString("url")
        val ticket = answer.getString("ticket")
        return when {
            answer.getBoolean("ok") && !url.isNullOrBlank() && !ticket.isNullOrBlank() -> SignIn.Ticket(url.trimEnd('/'), ticket)
            answer.getBoolean("needsApproval") -> SignIn.NeedsApproval(Intent(ACTION_APPROVE).setPackage(PACKAGE).putExtra("app", app))
            else -> SignIn.Unavailable(answer.getString("error") ?: "No answer")
        }
    }

    /**
     * Says what the person is doing here ("Now playing", a video, a route), so another device can
     * offer to carry on: [url] is what opens it there. A null [title] says they stopped. Quietly
     * does nothing when there's no orchestrator, nobody is signed in, or the person hasn't let
     * this app sign in through it.
     */
    fun publishActivity(context: Context, app: String, title: String?, detail: String = "", url: String = "") {
        val extras = Bundle().apply {
            putString("app", app)
            title?.let { putString("title", it) }
            putString("detail", detail)
            putString("url", url)
        }
        call(context, "publishActivity", app, extras)
    }
}
