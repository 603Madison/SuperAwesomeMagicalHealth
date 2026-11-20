package com.health.app.orchestrator

import android.content.Context
import android.content.Intent
import com.health.app.attempt
import com.health.app.data.Prefs
import com.health.app.net.ServerApi
import com.health.app.net.ServerException
import com.health.app.net.SignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What to do with what SuperAwesomeMagicalOrchestrator's agent on this phone says: the decisions,
 * apart from the phone, so they can be tested.
 */
object OrchestratorSteps {
    /** Why the agent is being asked. */
    enum class Why {
        /** The app opened, not signed in: quietly. */
        START,

        /** The person tapped the button. */
        TAPPED,

        /** The person just said yes on the orchestrator's page. */
        APPROVED,
    }

    sealed interface Step {
        /** Hand the ticket to the app's server at [url], and carry on into the app. */
        data class HandIn(val url: String, val ticket: String) : Step

        /** Open the orchestrator's page asking whether this app may sign in as them, now. */
        data class AskApproval(val intent: Intent) : Step

        /** Show the sign-in screen with "Continue with SuperAwesomeMagicalOrchestrator" on it. */
        data class OfferApproval(val intent: Intent) : Step

        /** The usual sign-in, unchanged, and no error. */
        data object Usual : Step
    }

    /**
     * Whether to ask the agent by itself when the app opens: only when nobody is signed in, the
     * person didn't sign out on purpose (they chose to be signed out: it stays that way until they
     * sign in again), and it hasn't been tried since the app started.
     */
    fun asksAtStart(signedIn: Boolean, signedOutOnPurpose: Boolean, triedAlready: Boolean): Boolean =
        !signedIn && !signedOutOnPurpose && !triedAlready

    fun next(answer: OrchestratorLink.SignIn, why: Why): Step = when (answer) {
        is OrchestratorLink.SignIn.Ticket -> Step.HandIn(answer.url, answer.ticket)
        // Its page only opens when the person asked for it; never by itself, and never twice in a row.
        is OrchestratorLink.SignIn.NeedsApproval -> if (why == Why.TAPPED) Step.AskApproval(answer.intent) else Step.OfferApproval(answer.intent)
        is OrchestratorLink.SignIn.Unavailable -> Step.Usual
    }

    /** The button's words on the sign-in screen, or null for no button. */
    fun label(approvalWaiting: Boolean, status: OrchestratorLink.Status?): String? {
        val name = status?.takeIf { it.signedIn }?.let { it.displayName.ifBlank { it.username } }.orEmpty().trim()
        return when {
            approvalWaiting -> WITH_ORCHESTRATOR
            status?.signedIn != true -> null
            name.isEmpty() -> WITH_ORCHESTRATOR
            else -> "Continue as $name"
        }
    }

    const val WITH_ORCHESTRATOR = "Continue with SuperAwesomeMagicalOrchestrator"
}

/** How the agent is reached (the phone's, or a stand-in in tests). */
interface OrchestratorAgent {
    fun status(): OrchestratorLink.Status?

    fun signIn(): OrchestratorLink.SignIn
}

/**
 * Signing in through SuperAwesomeMagicalOrchestrator: where someone is signed in to it on this
 * phone, the app asks its agent for a short-lived ticket, hands that to its own server, and is
 * signed in as the same person, exactly as if they had signed in by hand.
 *
 * Any failure falls back to the usual sign-in, with no error shown: this is only ever a shortcut.
 */
class OrchestratorSignIn(
    private val agent: OrchestratorAgent,
    private val api: ServerApi,
    private val prefs: Prefs,
    private val deviceName: () -> String = SignIn::deviceName,
) {
    /** What the sign-in screen shows: the button's words (null: no button) and whether a ticket is being handed in. */
    data class Offer(val label: String? = null, val working: Boolean = false)

    private val _offer = MutableStateFlow(Offer())
    val offer: StateFlow<Offer> = _offer.asStateFlow()

    private val _approvals = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    /** The orchestrator's page to open for a result (the sign-in screen does; RESULT_OK: [approved]). */
    val approvals: SharedFlow<Intent> = _approvals.asSharedFlow()

    private val triedAtStart = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    @Volatile private var approval: Intent? = null
    @Volatile private var status: OrchestratorLink.Status? = null
    @Volatile private var gaveUp = false

    /** The app came to the front: who is signed in to the orchestrator, and (once) a quiet try at signing in. */
    fun opened(scope: CoroutineScope) {
        scope.launch { attempt { open() } }
    }

    internal suspend fun open() {
        val settings = prefs.current()
        if (settings.signedIn) return
        status = attempt { asked { agent.status() } }.getOrNull()
        show()
        if (OrchestratorSteps.asksAtStart(settings.signedIn, settings.signedOutOnPurpose, triedAtStart.getAndSet(true))) run(OrchestratorSteps.Why.START)
    }

    /** The person tapped the button. */
    fun tapped(scope: CoroutineScope) {
        scope.launch { attempt { run(OrchestratorSteps.Why.TAPPED) } }
    }

    /** The person said yes on the orchestrator's page. */
    fun approved(scope: CoroutineScope) {
        scope.launch { attempt { run(OrchestratorSteps.Why.APPROVED) } }
    }

    /** The orchestrator's page couldn't be opened: the usual sign-in. (Saying no there leaves the button, to change their mind.) */
    fun unavailable() {
        fellBack()
        show()
    }

    internal suspend fun run(why: OrchestratorSteps.Why) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val answer = attempt { asked { agent.signIn() } }.getOrElse { OrchestratorLink.SignIn.Unavailable(it.message ?: "No answer") }
            when (val step = OrchestratorSteps.next(answer, why)) {
                is OrchestratorSteps.Step.HandIn -> {
                    _offer.value = _offer.value.copy(working = true)
                    val worked = attempt { handIn(step.url, step.ticket) }.isSuccess
                    // Didn't work here (no account, an older server, no network): the usual sign-in, and no button to try in vain.
                    if (!worked) fellBack()
                }
                is OrchestratorSteps.Step.AskApproval -> {
                    approval = step.intent
                    _approvals.tryEmit(step.intent)
                }
                is OrchestratorSteps.Step.OfferApproval -> approval = step.intent
                // Asked for by hand and nothing came of it: no button to try in vain.
                OrchestratorSteps.Step.Usual -> if (why != OrchestratorSteps.Why.START) fellBack()
            }
        } finally {
            busy.set(false)
            show(working = false)
        }
    }

    /** It didn't work: the button goes, and stays away until the app next starts, rather than fail again at every tap. */
    private fun fellBack() {
        gaveUp = true
        approval = null
    }

    /** Hands [ticket] to the server at [url] and keeps the sign-in as every other way of signing in does. */
    internal suspend fun handIn(url: String, ticket: String) {
        val server = ServerApi.normalize(url) ?: throw ServerException(0, "The orchestrator gave no server address")
        val signedIn = api.orchestratorSignIn(server, ticket, deviceName())
        val token = signedIn.token ?: throw ServerException(0, "The server sent no sign-in")
        // Signed in already; the server's name is only for show.
        val name = attempt { api.authConfig(server).serverName }.getOrDefault("")
        prefs.signIn(server, token, signedIn.user, name)
    }

    private fun show(working: Boolean = _offer.value.working) {
        _offer.value = Offer(if (gaveUp) null else OrchestratorSteps.label(approval != null, status), working)
    }

    /** The agent is another app, which may ask its server: never on the main thread. */
    private suspend fun <T> asked(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** This app's name in the orchestrator's list of the family's apps. */
        const val APP = "health"

        /**
         * The orchestrator on this phone, through the family's kit ([OrchestratorLink]).
         *
         * Only signing in goes through it. Health never tells the orchestrator what someone is
         * doing here (`OrchestratorLink.publishActivity` is never called): this is health data, and
         * nothing about what a person is logging or looking at leaves the phone this way, so there
         * is nothing for their other devices to "carry on" with.
         */
        fun onThisPhone(context: Context): OrchestratorAgent = object : OrchestratorAgent {
            // The kit asks whoever answers at the orchestrator's address: only the app of that name counts.
            private fun there(): Boolean =
                context.packageManager.resolveContentProvider("${OrchestratorLink.PACKAGE}.link", 0)?.packageName == OrchestratorLink.PACKAGE

            override fun status(): OrchestratorLink.Status? = if (there()) OrchestratorLink.status(context) else null

            override fun signIn(): OrchestratorLink.SignIn =
                if (there()) OrchestratorLink.signIn(context, APP) else OrchestratorLink.SignIn.Unavailable("SuperAwesomeMagicalOrchestrator isn't installed")
        }
    }
}
