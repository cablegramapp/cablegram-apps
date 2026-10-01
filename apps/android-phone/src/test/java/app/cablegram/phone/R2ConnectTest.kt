package app.cablegram.phone

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class R2ConnectTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun connect(response: MockResponse): R2ConnectResult {
        server.enqueue(response)
        return runBlocking { CatalogClient(server.url("/").toString()).connectR2("phone-token", "a".repeat(32), "my-movies", "AKIAKEY0123456789", "s3cret-0123456789-0123456789-0123456789") }
    }

    @Test fun `the keys go once, as JSON, with the phone token, to the connect route`() {
        assertNull(connect(MockResponse().setResponseCode(201).setBody("""{"connection":{"status":"active"}}""")).error)
        val request = server.takeRequest()
        assertEquals("/api/storage/connect/r2", request.path)
        assertEquals("Bearer phone-token", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"account_id\":\"${"a".repeat(32)}\""))
        assertTrue(body.contains("\"bucket\":\"my-movies\""))
        assertTrue(body.contains("\"secret_access_key\""))
    }

    @Test fun `the server's stable error string is kept, anything else is not`() {
        assertEquals("credentials_rejected", connect(MockResponse().setResponseCode(422).setBody("""{"error":"credentials_rejected"}""")).error)
        assertEquals("bucket_not_found", connect(MockResponse().setResponseCode(422).setBody("""{"error":"bucket_not_found"}""")).error)
        assertEquals("invalid_request", connect(MockResponse().setResponseCode(400).setBody("""<html>proxy page with secret-detail</html>""")).error)
        // A free-text "error" (not a stable code) is dropped for a status-based one.
        assertEquals("http_502", connect(MockResponse().setResponseCode(502).setBody("""{"error":"The bucket said: secret-detail!"}""")).error)
    }

    @Test fun `an unreachable server reads as offline`() {
        server.shutdown()
        val result = runBlocking { CatalogClient("http://127.0.0.1:1/").connectR2("t", "a".repeat(32), "bucket-1", "AKIAKEY0123456789", "s".repeat(40)) }
        assertEquals("offline", result.error)
    }

    @Test fun `every failure is explained in words and none repeats the server`() {
        for (code in listOf("credentials_rejected", "bucket_not_found", "invalid_request", "storage_already_connected", "storage_not_configured", "storage_unavailable", "offline")) {
            val message = r2ConnectMessage(code)
            assertTrue(code, message.length > 20)
            assertFalse(code, message.contains(code))
        }
        assertEquals("Couldn't connect (http_500).", r2ConnectMessage("http_500"))
    }
}
