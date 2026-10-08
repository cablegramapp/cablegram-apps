package app.cablegram.telegram

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

// Shared by apps/android-phone and apps/android-tv tests. Keep both copies identical.
class TelegramClientCredentialsTest {
    private val identity = """{"api_id":123,"api_hash":"00000000000000000000000000000000"}"""

    @Test fun `authenticated request reads identity without exposing it through toString`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(identity))
            val credentials = fetchTelegramClientCredentials(OkHttpClient(), server.url("/").toString(), "access", true)
            assertEquals(123, credentials.apiId)
            assertEquals("00000000000000000000000000000000", credentials.apiHash)
            assertFalse(credentials.toString().contains(credentials.apiHash))
            val request = server.takeRequest()
            assertEquals("/api/telegram/client-config", request.path)
            assertEquals("Bearer access", request.getHeader("Authorization"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
        }
    }

    @Test fun `production rejects plaintext transport before sending a token`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val failure = runCatching { fetchTelegramClientCredentials(OkHttpClient(), server.url("/").toString(), "access") }.exceptionOrNull()
            assertTrue(failure is TelegramClientUnavailable)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `redirects errors and malformed identities have fixed redacted failures`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val responses = listOf(
                MockResponse().setResponseCode(302).setHeader("Location", server.url("/stolen")),
                MockResponse().setResponseCode(503).setBody(identity),
                MockResponse().setBody("""{"api_id":0,"api_hash":"00000000000000000000000000000000"}"""),
                MockResponse().setBody("""{"api_id":123,"api_hash":"short"}"""),
                MockResponse().setBody("secret malformed payload"),
                MockResponse().setBody("""{"api_id":2147483648,"api_hash":"00000000000000000000000000000000"}"""),
            )
            for (response in responses) {
                server.enqueue(response)
                val failure = runCatching { fetchTelegramClientCredentials(OkHttpClient(), server.url("/").toString(), "access", true) }.exceptionOrNull()
                assertTrue(failure is TelegramClientUnavailable)
                assertEquals(TelegramClientUnavailable().message, failure!!.message)
                // Coroutine stack recovery may wrap the same sanitized exception.
                failure.cause?.let { assertEquals(TelegramClientUnavailable().message, it.message) }
                assertEquals("/api/telegram/client-config", server.takeRequest().path)
            }
            assertEquals(responses.size, server.requestCount)
        }
    }
}
