package app.cablegram.ui

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A relay that takes the request but never answers is a silent phone, not a TV without internet (CAB-15 run). */
class ProbeNoResponseTest {
    private val client = OkHttpClient()

    @Test fun `a server that accepts the request but never answers reports no response`() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); start() }
        try {
            val (code, error) = probeSource(client, server.url("/relay/v1/p/a/media/b").toString(), emptyMap(), 1_000)
            assertNull(code)
            assertEquals(NO_RESPONSE, error)
            assertTrue(relayMessage(code, error).contains("phone didn't respond"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `nothing listening is still this TV not reaching Cablegram`() = runBlocking {
        val gone = MockWebServer().apply { start() }
        val url = gone.url("/relay/v1/p/a/media/b").toString()
        gone.shutdown()
        val (code, error) = probeSource(client, url, emptyMap(), 1_000)
        assertNull(code)
        assertNull(error)
        assertTrue(relayMessage(code, error).contains("Check the TV's internet"))
    }
}
