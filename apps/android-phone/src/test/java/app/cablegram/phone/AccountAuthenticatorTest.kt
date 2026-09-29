package app.cablegram.phone

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AccountAuthenticatorTest {
    private lateinit var server: MockWebServer

    private class Tokens(override var accountToken: String?, override var refreshToken: String?) : AccountTokens

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun call(tokens: AccountTokens): Int {
        val base = server.url("/").toString()
        val client = OkHttpClient.Builder().authenticator(AccountAuthenticator(base, tokens)).build()
        val request = Request.Builder().url(server.url("/api/me")).header("Authorization", "Bearer ${tokens.accountToken}").build()
        return client.newCall(request).execute().use { it.code }
    }

    @Test
    fun `expired access token is renewed once and the request retried`() {
        val tokens = Tokens(accountToken = "expired", refreshToken = "refresh-1")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"access_token":"fresh"}"""))
        server.enqueue(MockResponse().setBody("{}"))

        assertEquals(200, call(tokens))

        assertEquals("Bearer expired", server.takeRequest().getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/api/auth/refresh", refresh.path)
        assertEquals("""{"refresh_token":"refresh-1"}""", refresh.body.readUtf8())
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        assertEquals("fresh", tokens.accountToken)
    }

    @Test
    fun `a rotated refresh token replaces the used one`() {
        val tokens = Tokens(accountToken = "expired", refreshToken = "refresh-1")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"access_token":"fresh","refresh_token":"refresh-2"}"""))
        server.enqueue(MockResponse().setBody("{}"))

        assertEquals(200, call(tokens))
        assertEquals("fresh", tokens.accountToken)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `rejected refresh clears the dead session so the app asks to sign in`() {
        val tokens = Tokens(accountToken = "expired", refreshToken = "revoked")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(401, call(tokens))
        assertNull(tokens.accountToken)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `older installs without a refresh token are sent back to sign in`() {
        val tokens = Tokens(accountToken = "expired", refreshToken = null)
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(401, call(tokens))
        assertNull(tokens.accountToken)
        assertEquals(1, server.requestCount)
    }
}
