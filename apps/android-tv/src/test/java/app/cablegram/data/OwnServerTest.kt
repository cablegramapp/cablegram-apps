package app.cablegram.data

import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OwnServerTest {
    private lateinit var server: MockWebServer
    private val address get() = "http://127.0.0.1:${server.port}"

    @Before fun setUp() { server = MockWebServer().apply { start(InetAddress.getLoopbackAddress(), 0) } }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun `a typed address gets a scheme that fits it`() {
        assertEquals("http://192.168.1.20:3000", ownServerUrl("192.168.1.20:3000"))
        assertEquals("http://nas.local:3000", ownServerUrl(" nas.local:3000/ "))
        assertEquals("https://cablegram.example.com", ownServerUrl("cablegram.example.com"))
        assertEquals("https://cablegram.example.com", ownServerUrl("https://cablegram.example.com/"))
        assertNull("plain HTTP to a public host", ownServerUrl("http://cablegram.example.com"))
        assertNull("prefix is not private", ownServerUrl("http://10.example.com"))
        assertNull(ownServerUrl("  "))
    }

    @Test
    fun `a Cablegram server is found`() = runTest {
        server.enqueue(MockResponse().setBody("""{"api_version":1,"edition":"community","auth":{"mode":"device"}}"""))
        assertEquals(ServerProbe.Found(address, "community"), probeServer(address))
        assertEquals("/api/server", server.takeRequest().path)
    }

    @Test
    fun `the check sends none of this TV's credentials`() = runTest {
        server.enqueue(MockResponse().setBody("""{"api_version":1,"edition":"community"}"""))
        probeServer(address)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `other web servers are not Cablegram`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ServerProbe.NotCablegram, probeServer(address))
        server.enqueue(MockResponse().setBody("<html>router login</html>"))
        assertEquals(ServerProbe.NotCablegram, probeServer(address))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/"))
        assertEquals(ServerProbe.NotCablegram, probeServer(address))
    }

    @Test
    fun `nothing listening is unreachable`() = runTest {
        val closed = address
        server.shutdown()
        assertEquals(ServerProbe.Unreachable, probeServer(closed))
    }

    @Test
    fun `plain HTTP to a public host is refused before any request`() = runTest {
        assertEquals(ServerProbe.InvalidAddress, probeServer("http://cablegram.example.com"))
    }
}
