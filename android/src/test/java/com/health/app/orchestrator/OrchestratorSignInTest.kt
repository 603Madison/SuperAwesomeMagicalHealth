package com.health.app.orchestrator

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.health.api.Role
import com.health.api.User
import com.health.app.data.Prefs
import com.health.app.net.ServerApi
import com.health.app.orchestrator.OrchestratorSteps.Step
import com.health.app.orchestrator.OrchestratorSteps.Why
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Signing in through SuperAwesomeMagicalOrchestrator: what the app decides, and what it sends its server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrchestratorSignInTest {
    private val server = MockWebServer()
    private val prefs = Prefs(ApplicationProvider.getApplicationContext())
    private val maddie = OrchestratorLink.Status(signedIn = true, server = "https://orchestrator.example.com", username = "maddie", displayName = "Maddie")
    private val approvalPage = Intent(OrchestratorLink.ACTION_APPROVE)

    /** What the server at [server] answers a ticket with, and what it was sent. */
    private var answer = 200
    private val sent = mutableListOf<RecordedRequest>()

    /** The agent on the phone, played here. */
    private var status: OrchestratorLink.Status? = maddie
    private var answers = ArrayDeque<OrchestratorLink.SignIn>()
    private var asked = 0
    private val agent = object : OrchestratorAgent {
        override fun status() = status
        override fun signIn(): OrchestratorLink.SignIn {
            asked++
            return answers.removeFirstOrNull() ?: OrchestratorLink.SignIn.Unavailable("Nobody is signed in")
        }
    }

    private lateinit var base: String
    private lateinit var signIn: OrchestratorSignIn

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/orchestrator" -> {
                    sent += request
                    if (answer == 200) json("""{"user":{"id":"u1","username":"maddie","displayName":"Maddie","role":"USER","createdAt":1},"token":"t0k3n"}""")
                    else json("""{"error":"That sign-in didn't work here. Sign in the usual way."}""", answer)
                }
                "/api/auth/config" -> json("""{"serverName":"Health at home","version":"0.12.0","localLogin":true,"orchestrator":true}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
        val api = ServerApi(OkHttpClient(), { "" }, { null }, { })
        signIn = OrchestratorSignIn(agent, api, prefs, deviceName = { "Pixel 9" })
        // The phone's settings outlast a test: each starts signed out, and not on purpose.
        runBlocking { signedOut(onPurpose = false) }
    }

    @After
    fun stop() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private suspend fun signedOut(onPurpose: Boolean) {
        prefs.signIn("https://old.example.com", "old-token", User("u0", "old", "Old", Role.USER, createdAt = 1), "Old")
        prefs.signOut(onPurpose)
    }

    private fun ticket() = OrchestratorLink.SignIn.Ticket(base, "the.ticket.jwt")

    // ---- The decisions ----

    @Test
    fun `the agent is asked at start only when nobody is signed in and nobody chose to sign out`() {
        assertTrue(OrchestratorSteps.asksAtStart(signedIn = false, signedOutOnPurpose = false, triedAlready = false))
        assertFalse(OrchestratorSteps.asksAtStart(signedIn = true, signedOutOnPurpose = false, triedAlready = false))
        assertFalse(OrchestratorSteps.asksAtStart(signedIn = false, signedOutOnPurpose = true, triedAlready = false))
        assertFalse(OrchestratorSteps.asksAtStart(signedIn = false, signedOutOnPurpose = false, triedAlready = true))
    }

    @Test
    fun `a ticket is handed in, approval is only opened when asked for, and anything else is the usual sign-in`() {
        Why.entries.forEach { why ->
            assertEquals(Step.HandIn("https://health.example.com", "t"), OrchestratorSteps.next(OrchestratorLink.SignIn.Ticket("https://health.example.com", "t"), why))
            assertEquals(Step.Usual, OrchestratorSteps.next(OrchestratorLink.SignIn.Unavailable("not installed"), why))
        }
        val needs = OrchestratorLink.SignIn.NeedsApproval(approvalPage)
        assertEquals(Step.OfferApproval(approvalPage), OrchestratorSteps.next(needs, Why.START))
        assertEquals(Step.AskApproval(approvalPage), OrchestratorSteps.next(needs, Why.TAPPED))
        // Said yes and it still asks: the button again, not the page in a loop.
        assertEquals(Step.OfferApproval(approvalPage), OrchestratorSteps.next(needs, Why.APPROVED))
    }

    @Test
    fun `the button's words`() {
        assertNull(OrchestratorSteps.label(approvalWaiting = false, status = null))
        assertNull(OrchestratorSteps.label(approvalWaiting = false, status = maddie.copy(signedIn = false)))
        assertEquals("Continue as Maddie", OrchestratorSteps.label(approvalWaiting = false, status = maddie))
        assertEquals("Continue as maddie", OrchestratorSteps.label(approvalWaiting = false, status = maddie.copy(displayName = " ")))
        assertEquals("Continue with SuperAwesomeMagicalOrchestrator", OrchestratorSteps.label(approvalWaiting = true, status = maddie))
        assertEquals("Continue with SuperAwesomeMagicalOrchestrator", OrchestratorSteps.label(approvalWaiting = true, status = null))
    }

    // ---- On the phone ----

    @Test
    fun `at start, a ticket signs the app in as any other sign-in does`() = runBlocking {
        answers += ticket()
        signIn.open()

        val request = sent.single()
        assertEquals("POST", request.method)
        assertNull(request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"ticket\":\"the.ticket.jwt\"") && body.contains("\"deviceName\":\"Pixel 9\""))

        val settings = prefs.current()
        assertTrue(settings.signedIn)
        assertEquals(base, settings.server)
        assertEquals("t0k3n", settings.token)
        assertEquals("maddie", settings.user?.username)
        assertEquals("Health at home", settings.serverName)
        assertFalse(signIn.offer.value.working)
    }

    @Test
    fun `a server that says no leaves the usual sign-in, with no button and no error`() = runBlocking {
        answer = 401
        answers += ticket()
        signIn.open()
        assertEquals(1, sent.size)
        assertFalse(prefs.current().signedIn)
        assertEquals(OrchestratorSignIn.Offer(), signIn.offer.value)
        // Coming back to the app doesn't try again by itself, nor bring the button back.
        answers += ticket()
        signIn.open()
        assertEquals(1, sent.size)
        assertNull(signIn.offer.value.label)
    }

    @Test
    fun `no orchestrator, or nobody signed in there - the usual screen, unchanged`() = runBlocking {
        status = null
        signIn.open()
        assertEquals(1, asked)
        assertTrue(sent.isEmpty())
        assertEquals(OrchestratorSignIn.Offer(), signIn.offer.value)
    }

    @Test
    fun `after signing out on purpose, nothing happens by itself, and the button still works`() = runBlocking {
        signedOut(onPurpose = true)
        assertTrue(prefs.current().signedOutOnPurpose)
        answers += ticket()
        signIn.open()
        assertEquals(0, asked)
        assertFalse(prefs.current().signedIn)
        assertEquals("Continue as Maddie", signIn.offer.value.label)

        signIn.run(Why.TAPPED)
        assertTrue(prefs.current().signedIn)
        // Signed in again, by any means: the choice to stay signed out is over.
        assertFalse(prefs.current().signedOutOnPurpose)
    }

    @Test
    fun `a sign-in the server ended isn't a choice to stay signed out`() = runBlocking {
        signedOut(onPurpose = false)
        assertFalse(prefs.current().signedOutOnPurpose)
    }

    @Test
    fun `the first time, the person is asked on the orchestrator's page - only once they tap`() = runBlocking {
        answers += OrchestratorLink.SignIn.NeedsApproval(approvalPage)
        signIn.open()
        assertEquals("Continue with SuperAwesomeMagicalOrchestrator", signIn.offer.value.label)
        assertTrue(sent.isEmpty())

        // The tap opens the page…
        answers += OrchestratorLink.SignIn.NeedsApproval(approvalPage)
        val page = async { withTimeout(5_000) { signIn.approvals.first() } }
        kotlinx.coroutines.yield()
        signIn.run(Why.TAPPED)
        assertEquals(OrchestratorLink.ACTION_APPROVE, page.await().action)
        assertFalse(prefs.current().signedIn)

        // …and saying yes there signs in.
        answers += ticket()
        signIn.run(Why.APPROVED)
        assertTrue(prefs.current().signedIn)
    }

    @Test
    fun `a tap that comes to nothing takes the button away`() = runBlocking {
        signedOut(onPurpose = true)
        signIn.open()
        assertEquals("Continue as Maddie", signIn.offer.value.label)
        signIn.run(Why.TAPPED) // the agent has nothing to give
        assertNull(signIn.offer.value.label)
        assertFalse(prefs.current().signedIn)
    }
}
