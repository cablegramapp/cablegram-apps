package app.cablegram.data

import app.cablegram.CloudStreamServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTlsTest {
    private val phone = TestLanCert.phone()
    private val client = LanTls.pinned(OkHttpClient())
    private fun url(path: String = "/library") = "https://127.0.0.1:${phone.port}$path"

    @After
    fun tearDown() {
        LanTls.clear()
        phone.shutdown()
    }

    private fun get(url: String, http: OkHttpClient = client) = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { it.code }
    }

    @Test
    fun `the paired phone's certificate is accepted at its address`() {
        LanTls.remember("127.0.0.1", phone.port, TestLanCert.sha256)
        phone.enqueue(MockResponse().setBody("ok"))
        assertEquals(200, get(url()).getOrNull())
    }

    @Test
    fun `another certificate at that address is refused before anything is sent`() {
        LanTls.remember("127.0.0.1", phone.port, "f".repeat(64))
        assertTrue(get(url()).isFailure)
        assertEquals(0, phone.requestCount)
    }

    @Test
    fun `a self-signed server with no pin gets the system's trust, which refuses it`() {
        assertTrue(get(url()).isFailure)
        assertEquals(0, phone.requestCount)
    }

    @Test
    fun `a pin holds for its own address only`() {
        LanTls.remember("127.0.0.1", phone.port + 1, TestLanCert.sha256)
        assertTrue(get(url()).isFailure)
    }

    @Test
    fun `the player's local proxy reaches the phone over pinned TLS and keeps the path`() {
        LanTls.remember("127.0.0.1", phone.port, TestLanCert.sha256)
        val proxy = CloudStreamServer(
            http = LanTls.pinned(OkHttpClient.Builder().followRedirects(false).build()),
            allowed = { it.isHttps && LanTls.isPinned(it.host, it.port) },
            prefix = "lan",
            keepPath = true,
        ).apply { start(5_000, true) }
        try {
            phone.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-1/10").setBody("ab"))
            val local = proxy.register(url("/telegram/AgADabcdEFGH?link=l1"), emptyMap())!!
            assertTrue(local, local.matches(Regex("http://127\\.0\\.0\\.1:\\d+/lan/[0-9a-f]+/telegram/AgADabcdEFGH")))
            val plain = OkHttpClient()
            plain.newCall(Request.Builder().url(local).header("Range", "bytes=0-1").build()).execute().use {
                assertEquals(206, it.code)
                assertEquals("ab", it.body!!.string())
            }
            val upstream = phone.takeRequest()
            assertEquals("/telegram/AgADabcdEFGH?link=l1", upstream.path)
            assertEquals("bytes=0-1", upstream.getHeader("Range"))
            // An address with no pin is never fetched, whatever the URL.
            assertNull(proxy.register("https://192.168.1.50:8765/media/x?link=l", emptyMap()))
        } finally {
            proxy.stop()
        }
    }
}
