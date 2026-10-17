package app.cablegram.phone

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class UiLanguageTest {
    @Test fun `first supported device preference wins with English fallback`() {
        val supported = setOf("en", "de", "ar", "fa", "ru", "es", "it")
        assertEquals("de", phoneUiLanguage(listOf("de-AT", "en-US"), supported))
        assertEquals("fa", phoneUiLanguage(listOf("ja-JP", "fa-IR", "de-DE"), supported))
        assertEquals("en", phoneUiLanguage(listOf("ja-JP"), supported))
        assertEquals("en", phoneUiLanguage(emptyList(), supported))
        for ((tag, expected) in listOf("ar-SA" to "ar", "fa-IR" to "fa", "ru-RU" to "ru", "es-MX" to "es", "it-IT" to "it")) {
            assertEquals(expected, phoneUiLanguage(listOf(tag), supported))
        }
    }

    @Test fun `reported tag keeps the region of the shown language and unsupported first choices`() {
        val supported = setOf("en", "de", "ar", "fa", "ru", "es", "it")
        assertEquals("de-AT", phoneLanguageTag(listOf("de-AT", "en-US"), supported))
        assertEquals("de-DE", phoneLanguageTag(listOf("ja-JP", "de-DE"), supported))
        assertEquals("fr-FR", phoneLanguageTag(listOf("fr-FR"), supported))
        assertEquals("en", phoneLanguageTag(emptyList(), supported))
        assertEquals("en", phoneLanguageTag(listOf("und"), supported))
    }

    @Test fun `profile override and inheritance use separate authenticated updates`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = CatalogClient(server.url("/").toString())
            for (language in listOf("ar", null)) {
                server.enqueue(MockResponse().setBody("{}"))
                assertTrue(client.setProfileLanguage("phone-token", "profile-id", language))
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("PATCH", request.method)
                assertEquals("/api/profiles/profile-id/language", request.path)
                assertEquals("Bearer phone-token", request.getHeader("Authorization"))
                assertEquals(if (language == null) """{"ui_language":null}""" else """{"ui_language":"ar"}""", request.body.readUtf8())
            }
        }
    }

    @Test fun `sync sends authenticated language and reports server failures for retry`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = CatalogClient(server.url("/").toString())
            server.enqueue(MockResponse().setBody("""{"ui_language":"de"}"""))
            assertTrue(client.syncDeviceLanguage("phone-token", "de"))
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("PUT", request.method)
            assertEquals("/api/me/language", request.path)
            assertEquals("Bearer phone-token", request.getHeader("Authorization"))
            assertEquals("""{"ui_language":"de"}""", request.body.readUtf8())
            server.enqueue(MockResponse().setResponseCode(503))
            assertFalse(client.syncDeviceLanguage("phone-token", "de"))
        }
    }
}
