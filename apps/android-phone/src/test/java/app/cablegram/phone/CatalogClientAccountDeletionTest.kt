package app.cablegram.phone

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class CatalogClientAccountDeletionTest {
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

    private fun client(tokens: AccountTokens? = null) = CatalogClient(server.url("/").toString(), tokens)

    private fun delete(response: MockResponse, password: String = "hunter2-pass"): AccountDeletionResult {
        server.enqueue(response)
        return runBlocking { client().deleteAccount("access-1", password) }
    }

    @Test
    fun `204 deletes the account and sends the bearer token and password`() {
        assertEquals(AccountDeletionResult.Deleted, delete(MockResponse().setResponseCode(204)))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/account/delete", request.path)
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
        assertEquals("""{"password":"hunter2-pass"}""", request.body.readUtf8())
    }

    @Test
    fun `wrong password is reported and sent only once`() {
        val result = delete(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_credentials"}"""))
        assertEquals(AccountDeletionResult.WrongPassword, result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a wrong password is not resent even when a renewing authenticator is installed`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_credentials"}"""))
        val tokens = Tokens("access-1", "refresh-1")
        val result = runBlocking { client(tokens).deleteAccount("access-1", "wrong") }

        assertEquals(AccountDeletionResult.WrongPassword, result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `rate limit is reported`() {
        val result = delete(MockResponse().setResponseCode(429).setBody("""{"error":"too_many_login_attempts"}"""))
        assertEquals(AccountDeletionResult.RateLimited, result)
    }

    @Test
    fun `an invalid session without a refresh token is reported`() {
        val result = delete(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        assertEquals(AccountDeletionResult.SessionExpired, result)
    }

    @Test
    fun `an expired access token is renewed once and the deletion repeated`() {
        val tokens = Tokens("access-1", "refresh-1")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(401)) // GET /api/me, which makes the authenticator renew
        server.enqueue(MockResponse().setBody("""{"access_token":"access-2","refresh_token":"refresh-2"}"""))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(AccountDeletionResult.Deleted, runBlocking { client(tokens).deleteAccount("access-1", "pw-12345") })

        assertEquals("/api/account/delete", server.takeRequest().path)
        assertEquals("/api/me", server.takeRequest().path)
        assertEquals("/api/auth/refresh", server.takeRequest().path)
        assertEquals("/api/me", server.takeRequest().path)
        val retry = server.takeRequest()
        assertEquals("/api/account/delete", retry.path)
        assertEquals("Bearer access-2", retry.getHeader("Authorization"))
        assertEquals("""{"password":"pw-12345"}""", retry.body.readUtf8())
    }

    @Test
    fun `a session that cannot be renewed stays expired`() {
        val tokens = Tokens("access-1", "refresh-1")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401)) // the refresh token is rejected too

        assertEquals(AccountDeletionResult.SessionExpired, runBlocking { client(tokens).deleteAccount("access-1", "pw-12345") })
        assertNull(tokens.accountToken)
    }

    @Test
    fun `any other status is a generic failure`() {
        assertEquals(AccountDeletionResult.Failed, delete(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""")))
        assertEquals(AccountDeletionResult.Failed, delete(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}""")))
        assertEquals(AccountDeletionResult.Failed, delete(MockResponse().setResponseCode(400).setBody("not json")))
    }

    @Test
    fun `network failure is reported as offline`() {
        server.shutdown()
        assertEquals(AccountDeletionResult.Offline, runBlocking { client().deleteAccount("access-1", "pw-12345") })
    }

    @Test
    fun `a dropped connection is offline too`() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(AccountDeletionResult.Offline, runBlocking { client().deleteAccount("access-1", "pw-12345") })
        server.takeRequest(1, TimeUnit.SECONDS)
    }
}
