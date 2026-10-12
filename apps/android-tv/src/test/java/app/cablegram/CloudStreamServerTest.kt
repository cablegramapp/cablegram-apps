package app.cablegram

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudStreamServerTest {
    private lateinit var drive: MockWebServer
    private lateinit var proxy: CloudStreamServer
    private val client = OkHttpClient()
    private val file = ByteArray(10_000) { (it * 7 + 3).toByte() }
    private val seen = mutableListOf<RecordedRequest>()
    private var answer: (RecordedRequest) -> MockResponse = { request ->
        // A fake Drive: needs the bearer header, answers Range with 206.
        if (request.getHeader("Authorization") != "Bearer ya29.live") MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}""")
        else {
            val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range").orEmpty())
            if (m == null) MockResponse().setBody(Buffer().write(file)).setHeader("Accept-Ranges", "bytes")
            else {
                val from = m.groupValues[1].toInt()
                val to = m.groupValues[2].ifEmpty { "${file.size - 1}" }.toInt()
                MockResponse().setResponseCode(206).setHeader("Content-Type", "video/x-matroska")
                    .setHeader("Content-Range", "bytes $from-$to/${file.size}").setBody(Buffer().write(file, from, to - from + 1))
            }
        }
    }

    @Before fun start() {
        drive = MockWebServer()
        drive.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(seen) { seen += request; answer(request) }
        }
        drive.start()
        proxy = CloudStreamServer(allowed = { true })
        proxy.start(5_000, true)
    }

    @After fun stop() {
        proxy.stop()
        drive.shutdown()
    }

    private fun upstream() = drive.url("/drive/v3/files/f1?alt=media").toString()
    private fun get(url: String, range: String? = null) = client.newCall(
        Request.Builder().url(url).apply { range?.let { header("Range", it) } }.build(),
    ).execute()

    @Test fun `the player gets the file with the header added, and a Range read answers 206 with the right bytes`() {
        val local = proxy.register(upstream(), mapOf("Authorization" to "Bearer ya29.live"))!!
        assertTrue(local.startsWith("http://127.0.0.1:"))
        assertFalse("the token is in no URL", local.contains("ya29"))

        get(local, "bytes=1000-1999").use { r ->
            assertEquals(206, r.code)
            assertEquals("bytes 1000-1999/10000", r.header("Content-Range"))
            assertEquals("video/x-matroska", r.header("Content-Type"))
            assertTrue(file.copyOfRange(1000, 2000).contentEquals(r.body!!.bytes()))
        }
        assertEquals("Bearer ya29.live", seen.last().getHeader("Authorization"))
        assertEquals("bytes=1000-1999", seen.last().getHeader("Range"))
    }

    @Test fun `an open-ended Range, as a player seeks to the end, is passed through`() {
        val local = proxy.register(upstream(), mapOf("Authorization" to "Bearer ya29.live"))!!
        get(local, "bytes=9500-").use { r ->
            assertEquals(206, r.code)
            assertTrue(file.copyOfRange(9500, 10_000).contentEquals(r.body!!.bytes()))
        }
    }

    @Test fun `a request with no Range gets the whole file`() {
        val local = proxy.register(upstream(), mapOf("Authorization" to "Bearer ya29.live"))!!
        get(local).use { r ->
            assertEquals(200, r.code)
            assertTrue(file.contentEquals(r.body!!.bytes()))
        }
    }

    @Test fun `a renewal changes the header behind the same local URL, with no new URL for the player`() {
        val first = proxy.register(upstream(), mapOf("Authorization" to "Bearer ya29.old"))!!
        assertEquals("the old token is refused", 403, get(first, "bytes=0-9").use { it.code })

        val renewed = proxy.register(upstream(), mapOf("Authorization" to "Bearer ya29.live"))!!
        assertEquals("the same local URL", first, renewed)
        assertEquals(206, get(first, "bytes=0-9").use { it.code })
    }

    @Test fun `Drive's own error reaches the player as it is`() {
        val local = proxy.register(upstream(), mapOf("Authorization" to "Bearer wrong"))!!
        get(local, "bytes=0-9").use { r ->
            assertEquals(403, r.code)
            assertFalse(r.body!!.string().contains("wrong"))
        }
        answer = { MockResponse().setResponseCode(404) }
        assertEquals(404, get(local, "bytes=0-9").use { it.code })
        answer = { MockResponse().setResponseCode(429) }
        assertEquals(429, get(local, "bytes=0-9").use { it.code })
    }

    @Test fun `an unknown or guessed path is not served, and only GET and HEAD are`() {
        proxy.register(upstream(), mapOf("Authorization" to "Bearer ya29.live"))
        val base = "http://127.0.0.1:${proxy.listeningPort}"
        assertEquals(404, get("$base/cloud/00000000000000000000000000000000").use { it.code })
        assertEquals(404, get("$base/tg/1").use { it.code })
        assertEquals(404, get("$base/").use { it.code })
        val local = proxy.register(upstream(), emptyMap())!!
        val post = client.newCall(Request.Builder().url(local).post(okhttp3.RequestBody.create(null, "x")).build()).execute()
        assertEquals(405, post.use { it.code })
    }

    @Test fun `only HTTPS addresses are fetched by default, and odd header names are dropped`() {
        val strict = CloudStreamServer()
        try {
            assertNull(strict.register("http://example.com/a", mapOf("Authorization" to "Bearer x")))
            assertNull(strict.register("file:///etc/passwd", emptyMap()))
            assertNull(strict.register("not a url", emptyMap()))
            assertNotNull(strict.register("https://www.googleapis.com/drive/v3/files/f1?alt=media", mapOf("Authorization" to "Bearer x")))
        } finally {
            strict.stop()
        }
        proxy.register(upstream(), mapOf("Authorization" to "Bearer ya29.live", "bad name" to "v", "X-Inject" to "a\r\nEvil: 1"))
        get("http://127.0.0.1:${proxy.listeningPort}/cloud/${"00".repeat(16)}").close()
        val local = proxy.register(upstream(), mapOf("Authorization" to "Bearer ya29.live", "bad name" to "v", "X-Inject" to "a\r\nEvil: 1"))!!
        get(local, "bytes=0-9").close()
        assertNull(seen.last().getHeader("Evil"))
        assertNull(seen.last().getHeader("bad name"))
    }

    @Test fun `an upstream that cannot be reached answers 502 and nothing leaks`() {
        val local = proxy.register(upstream(), mapOf("Authorization" to "Bearer ya29.live"))!!
        drive.shutdown()
        get(local, "bytes=0-9").use { r ->
            assertEquals(502, r.code)
            assertFalse(r.body!!.string().contains("ya29"))
        }
    }
}
