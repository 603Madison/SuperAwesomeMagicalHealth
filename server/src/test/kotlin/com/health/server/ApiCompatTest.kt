package com.health.server

import com.health.api.AiConsent
import com.health.api.ApiJson
import com.health.api.Module
import com.health.api.Profile
import org.junit.Assert.assertEquals
import org.junit.Test

/** An older app and a newer server (or the other way round) still understand each other. */
class ApiCompatTest {
    @Test
    fun `a feature this version doesn't know is skipped, not a failure`() {
        val profile = ApiJson.decodeFromString(Profile.serializer(), """{"modules":["SLEEP","SOMETHING_NEW","MIND"]}""")
        assertEquals(setOf(Module.SLEEP, Module.MIND), profile.modules)
        val consent = ApiJson.decodeFromString(AiConsent.serializer(), """{"modules":["NUTRITION","LATER"],"acknowledged":true}""")
        assertEquals(setOf(Module.NUTRITION), consent.modules)
        // The same list as before goes out.
        val out = ApiJson.encodeToString(Profile.serializer(), Profile(modules = setOf(Module.MIND, Module.SLEEP)))
        assertEquals(true, out.contains(""""modules":["MIND","SLEEP"]"""))
    }
}
