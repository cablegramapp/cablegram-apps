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

class R2FormChecksTest {
    @org.junit.Test fun `nothing typed yet shows no complaint`() {
        for (check in listOf(::r2AccountIdProblem, ::r2BucketProblem, ::r2KeyIdProblem, ::r2SecretProblem)) org.junit.Assert.assertNull(check(""))
    }

    @org.junit.Test fun `the account ID is taken from the Endpoint address Cloudflare shows`() {
        val id = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        org.junit.Assert.assertEquals(id, r2AccountIdFrom(id))
        org.junit.Assert.assertEquals(id, r2AccountIdFrom("  ${id.uppercase()} "))
        org.junit.Assert.assertEquals(id, r2AccountIdFrom("https://$id.r2.cloudflarestorage.com"))
        org.junit.Assert.assertEquals(id, r2AccountIdFrom("https://$id.r2.cloudflarestorage.com/my-bucket"))
        org.junit.Assert.assertEquals(id, r2AccountIdFrom("$id.r2.cloudflarestorage.com"))
        org.junit.Assert.assertNull(r2AccountIdFrom("https://$id.evil.example"))
        org.junit.Assert.assertNull(r2AccountIdFrom("https://x$id.r2.cloudflarestorage.com"))
        org.junit.Assert.assertNull(r2AccountIdFrom("not an id"))
        org.junit.Assert.assertNull(r2AccountIdProblem("https://$id.r2.cloudflarestorage.com"))
        org.junit.Assert.assertNotNull(r2AccountIdProblem("https://example.com"))
    }

    @org.junit.Test fun `good values pass, with spaces around them ignored`() {
        org.junit.Assert.assertNull(r2AccountIdProblem(" " + "a1b2c3d4e5f60718293a4b5c6d7e8f90" + " "))
        org.junit.Assert.assertNull(r2BucketProblem("my-cablegram-videos"))
        org.junit.Assert.assertNull(r2KeyIdProblem("AKIAKEY0123456789"))
        org.junit.Assert.assertNull(r2SecretProblem("wJalrXUtnFEMI-K7MDENG_bPxRfiCYTESTSECRETKEY"))
    }

    @org.junit.Test fun `typical mistakes are caught before anything is sent`() {
        org.junit.Assert.assertNotNull(r2AccountIdProblem("12345"))
        org.junit.Assert.assertNotNull(r2AccountIdProblem("zzzz".repeat(8)))
        org.junit.Assert.assertNotNull(r2BucketProblem("My Videos"))
        org.junit.Assert.assertNotNull(r2BucketProblem("ab"))
        org.junit.Assert.assertNotNull(r2KeyIdProblem("short"))
        org.junit.Assert.assertNotNull(r2SecretProblem("short"))
        // The API URL or a token value pasted into the wrong field.
        org.junit.Assert.assertNotNull(r2BucketProblem("https://x.r2.cloudflarestorage.com"))
    }
}
