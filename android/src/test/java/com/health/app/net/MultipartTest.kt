package com.health.app.net

import com.health.api.Photo
import com.health.api.Pose
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The multipart helper, as progress photos use it. */
class MultipartTest {
    private val server = MockWebServer()
    private lateinit var api: ServerApi

    @Before
    fun start() {
        server.start()
        api = ServerApi(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { "app-token" })
    }

    @After
    fun stop() = server.shutdown()

    private val photo = Photo(album = "Transition", pose = Pose.SIDE, at = 1_763_000_000_000, note = "Morning light", tags = listOf("same light"))

    @Test
    fun `the body has the photo's details as JSON, then the picture as a file`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val body = api.multipartBody(photoParts(jpeg, "image/jpeg", photo))
        assertEquals(2, body.size)
        assertEquals("multipart", body.type.type)
        assertEquals("form-data", body.type.subtype)
        val details = body.part(0)
        assertTrue(details.headers!!["Content-Disposition"]!!.contains("name=\"photo\""))
        val json = Buffer().also { details.body.writeTo(it) }.readUtf8()
        assertTrue(json, json.contains("\"album\":\"Transition\"") && json.contains("\"pose\":\"SIDE\"") && json.contains("\"same light\""))
        val file = body.part(1)
        val disposition = file.headers!!["Content-Disposition"]!!
        assertTrue(disposition, disposition.contains("name=\"file\"") && disposition.contains("filename=\"photo.jpg\""))
        assertEquals("image/jpeg", file.body.contentType().toString())
        assertTrue(jpeg.contentEquals(Buffer().also { file.body.writeTo(it) }.readByteArray()))
    }

    @Test
    fun `an upload posts multipart with the app's token and reads the photo back`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"album":"Transition","pose":"SIDE","at":1763000000000,"id":"p1","date":"2025-11-13","width":3,"height":4}"""))
        val saved = api.uploadPhoto(byteArrayOf(1, 2, 3), "image/png", photo)
        assertEquals("p1", saved.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/photos", request.path)
        assertEquals("Bearer app-token", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val text = request.body.readUtf8()
        assertTrue(text.contains("name=\"photo\"") && text.contains("filename=\"photo.png\"") && text.contains("Content-Type: image/png"))
    }

    @Test
    fun `nothing is uploaded while viewing someone's share`() = runBlocking {
        val viewer = ServerApi(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { "app-token" }, viewAs = { "owner-1" })
        try {
            viewer.uploadPhoto(byteArrayOf(1), "image/jpeg", photo)
            fail("A viewer can't upload")
        } catch (e: ServerException) {
            assertEquals(403, e.code)
        }
        assertEquals(0, server.requestCount)
    }
}
