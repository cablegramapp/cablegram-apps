package app.cablegram.phone

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CatalogLookupTest {
    @Test fun `editor distinguishes no match from authentication and provider failure`() {
        MockWebServer().use { server ->
            server.start()
            val client = CatalogClient(server.url("/").toString())
            server.enqueue(MockResponse().setBody("""{"found":false}"""))
            assertFalse(runBlocking { client.resolveTitle("title", "token", strict = true).found })
            for (code in listOf(401, 503)) {
                server.enqueue(MockResponse().setResponseCode(code))
                try {
                    runBlocking { client.resolveTitle("title", "token", strict = true) }
                    fail("Expected a typed error")
                } catch (error: CatalogLookupException) { assertEquals(code, error.status) }
            }
            server.enqueue(MockResponse().setResponseCode(503))
            try {
                runBlocking { client.enrich("title", "token", null, strict = true) }
                fail("Preview failure must not become no-match")
            } catch (error: CatalogLookupException) { assertEquals(503, error.status) }
        }
    }

    @Test fun `editor search names its household item so a Telegram caption stays away from the AI`() {
        MockWebServer().use { server ->
            server.start()
            val client = CatalogClient(server.url("/").toString())
            server.enqueue(MockResponse().setBody("""{"found":false}"""))
            server.enqueue(MockResponse().setBody("""{"found":false}"""))
            runBlocking { client.resolveTitle("Gary", "token", strict = true, itemId = "item-1") }
            runBlocking { client.resolveTitle("Gary", "token", strict = true) }
            assertEquals("""{"query":"Gary","item_id":"item-1"}""", server.takeRequest().body.readUtf8())
            assertEquals("""{"query":"Gary"}""", server.takeRequest().body.readUtf8())
        }
    }
}
