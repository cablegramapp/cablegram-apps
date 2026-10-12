package app.cablegram.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TelegramClientConfigTest {
    @Test fun `TV requests identity using its paired device token`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"api_id":123,"api_hash":"00000000000000000000000000000000"}"""))
            val credentials = CablegramApi(server.url("/").toString()).telegramClientCredentials("tv-access")
            assertEquals(123, credentials.apiId)
            val request = server.takeRequest()
            assertEquals("/api/telegram/client-config", request.path)
            assertEquals("Bearer tv-access", request.getHeader("Authorization"))
        }
    }
}
