package app.cablegram.phone

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review fix 6: a hidden or deleted Telegram title is never served to a TV through the phone. */
class RemovedTelegramSourcesTest {
    private class Tokens(override var accountToken: String?, override var refreshToken: String? = null) : AccountTokens

    @Test
    fun `hidden sources are refused, others are served`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"tombstones":[{"stable_source_key":"tgfile:hidden1","state":"hidden"}]}"""))
            val removed = RemovedTelegramSources(CatalogClient(server.url("/").toString()), Tokens("jwt"))
            assertTrue(removed.contains("tgfile:hidden1"))
            assertFalse(removed.contains("tgfile:visible"))
        }
    }

    @Test
    fun `nothing is served before the list could be loaded once`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val removed = RemovedTelegramSources(CatalogClient(server.url("/").toString()), Tokens("jwt"))
            assertTrue("fails closed", removed.contains("tgfile:anything"))
        }
    }
}
