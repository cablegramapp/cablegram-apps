package app.cablegram.phone

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CatalogClientCommandTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client() = CatalogClient(server.url("/").toString(), null)

    private fun idOf(body: String) = Json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.content

    @Test
    fun `an accepted send returns the id it posted`() {
        server.enqueue(MockResponse().setResponseCode(201))
        val sent = runBlocking { client().postCommand("t", "pause", targetDeviceId = "tv-1") }
        val posted = idOf(server.takeRequest().body.readUtf8())
        assertEquals(CommandSend.Accepted(posted!!), sent)
    }

    @Test
    fun `a retry after a network error reuses the same id`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(200))
        val sent = runBlocking { client().postCommand("t", "pause", targetDeviceId = "tv-1") }
        val first = idOf(server.takeRequest().body.readUtf8())
        val second = idOf(server.takeRequest().body.readUtf8())
        assertEquals(first, second)
        assertEquals(CommandSend.Accepted(first!!), sent)
    }

    @Test
    fun `a server error is not retried and fails`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(CommandSend.Failed, runBlocking { client().postCommand("t", "pause", targetDeviceId = "tv-1") })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a revoked or removed target is reported as gone, not as unreachable`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"unknown_target_device"}"""))
        assertEquals(CommandSend.TargetGone, runBlocking { client().postCommand("t", "pause", targetDeviceId = "tv-1") })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `another bad request stays a plain failure`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"id_requires_target_device"}"""))
        assertEquals(CommandSend.Failed, runBlocking { client().postCommand("t", "pause", targetDeviceId = "tv-1") })
    }

    @Test
    fun `a status 404 from an older server maps to Unsupported`() {
        // Fastify's route-not-found body, as an older server answers.
        server.enqueue(MockResponse().setResponseCode(404)
            .setBody("""{"message":"Route GET:/api/control/commands/cmd-1 not found","error":"Not Found","statusCode":404}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html>Not Found</html>"))
        runBlocking {
            assertEquals(CommandStatus.Unsupported, client().commandStatus("t", "cmd-1"))
            assertEquals(CommandStatus.Unsupported, client().commandStatus("t", "cmd-1"))
        }
        assertEquals("/api/control/commands/cmd-1", server.takeRequest().path)
    }

    @Test
    fun `the endpoint's own not_found is not read as an older server`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
        assertEquals(null, runBlocking { client().commandStatus("t", "cmd-1") })
    }

    @Test
    fun `a send whose responses are lost counts as accepted when the server has it`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("""{"status":"delivered","expires_in_ms":29000}"""))
        val sent = runBlocking { client().postCommand("t", "pause", targetDeviceId = "tv-1") }
        val id = idOf(server.takeRequest().body.readUtf8())
        server.takeRequest()
        assertEquals("/api/control/commands/$id", server.takeRequest().path)
        assertEquals(CommandSend.Accepted(id!!), sent)
    }

    @Test
    fun `a send whose responses are lost fails when the server does not have it`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
        assertEquals(CommandSend.Failed, runBlocking { client().postCommand("t", "pause", targetDeviceId = "tv-1") })
    }

    @Test
    fun `a status response is parsed and other errors read as unknown`() {
        server.enqueue(MockResponse().setBody("""{"id":"c","status":"rejected","reason":"expired","expires_at_ms":1234,"expires_in_ms":0}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        runBlocking {
            assertEquals(CommandStatus.Known("rejected", "expired", 0L), client().commandStatus("t", "c"))
            assertEquals(null, client().commandStatus("t", "c"))
        }
        assertTrue(server.requestCount == 2)
    }
}
