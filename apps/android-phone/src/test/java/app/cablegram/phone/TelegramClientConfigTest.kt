package app.cablegram.phone

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TelegramClientConfigTest {
    private class Tokens(override var accountToken: String?, override var refreshToken: String?) : AccountTokens

    @Test fun `client config renews the phone token before reading identity`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"access_token":"new-access","refresh_token":"new-refresh"}"""))
            server.enqueue(MockResponse().setBody("""{"api_id":123,"api_hash":"00000000000000000000000000000000"}"""))
            val tokens = Tokens("old-access", "old-refresh")
            val credentials = CatalogClient(server.url("/").toString(), tokens).telegramClientCredentials("old-access")
            assertEquals(123, credentials.apiId)
            assertEquals("Bearer old-access", server.takeRequest().getHeader("Authorization"))
            assertEquals("/api/auth/refresh", server.takeRequest().path)
            val retried = server.takeRequest()
            assertEquals("/api/telegram/client-config", retried.path)
            assertEquals("Bearer new-access", retried.getHeader("Authorization"))
            assertEquals("new-access", tokens.accountToken)
            assertEquals("new-refresh", tokens.refreshToken)
        }
    }
}
