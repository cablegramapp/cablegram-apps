package app.cablegram.phone

import java.io.File
import java.io.IOException
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpClientsTest {
    private lateinit var server: MockWebServer

    /** Every name resolves to the mock server, so a "public" host is reached without the internet. */
    private val client = cablegramHttpClient().dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }).build()

    @Before fun setUp() { server = MockWebServer().apply { start(InetAddress.getLoopbackAddress(), 0) } }
    @After fun tearDown() { server.shutdown() }

    private fun get(host: String) = client.newCall(Request.Builder().url("http://$host:${server.port}/").build()).execute().use { it.code }

    @Test
    fun `plain HTTP to a home-network host is allowed`() {
        server.enqueue(MockResponse().setBody("ok"))
        assertEquals(200, get("nas.local"))
    }

    @Test
    fun `plain HTTP to a public host is refused`() {
        server.enqueue(MockResponse().setBody("would be served"))
        assertRefused { get("example.com") }
    }

    @Test
    fun `a redirect from a home-network host to plain HTTP on a public host is refused`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.com:${server.port}/"))
        server.enqueue(MockResponse().setBody("would be served"))
        assertRefused { get("nas.local") }
    }

    /** The guard's own refusal, not a timeout or a connection error that is also an IOException. */
    private fun assertRefused(call: () -> Unit) {
        val error = assertThrows(IOException::class.java) { call() }
        assertTrue(error.message, error.message.orEmpty().startsWith("Plain HTTP is allowed only to home-network addresses"))
    }

    @Test
    fun `private hosts are parsed, not matched by prefix`() {
        listOf("10.0.2.2", "192.168.0.5", "172.16.0.1", "172.31.255.255", "127.0.0.1", "localhost", "nas.local", "media.lan")
            .forEach { assertTrue(it, isPrivateLanHost(it)) }
        listOf("10.example.com", "192.168.1.1.example.com", "172.32.0.1", "8.8.8.8", "example.com")
            .forEach { assertFalse(it, isPrivateLanHost(it)) }
    }

    @Test
    fun `every OkHttp client is built by cablegramHttpClient`() {
        val direct = Regex("""OkHttpClient(\.Builder)?\(\)""")
        val offenders = File("src/main/java").walk()
            .filter { it.extension == "kt" && it.name != "HttpClients.kt" && direct.containsMatchIn(it.readText()) }
            .map { it.path }.toList()
        assertTrue("Build clients with cablegramHttpClient(): $offenders", offenders.isEmpty())
    }
}
