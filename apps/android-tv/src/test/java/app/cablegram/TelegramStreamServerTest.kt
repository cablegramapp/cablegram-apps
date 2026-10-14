package app.cablegram

import app.cablegram.telegram.FakeTelegramApi
import app.cablegram.telegram.TgFile
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelegramStreamServerTest {
    private val api = FakeTelegramApi()
    private val bytes = ByteArray(100) { it.toByte() }
    private lateinit var server: TelegramStreamServer
    private val client = OkHttpClient()

    @Before fun start() {
        api.files[7] = TgFile(7, bytes.size.toLong(), "AgADvideo", 0, bytes.size.toLong(), bytes.size.toLong(), true)
        api.content[7] = bytes
        // A file from another chat that TDLib also knows.
        api.files[8] = TgFile(8, bytes.size.toLong(), "AgADother", 0, bytes.size.toLong(), bytes.size.toLong(), true)
        api.content[8] = bytes
        server = TelegramStreamServer(api).also { it.start(5_000, true) }
    }

    @After fun stop() = server.stop()

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun `only registered streams are served, never a file by its TDLib id`() {
        val base = "http://127.0.0.1:${server.listeningPort}"
        get("$base/tg/7").use { assertEquals(404, it.code) }
        get("$base/tg/8").use { assertEquals(404, it.code) }
        get("$base/tg/00000000000000000000000000000000").use { assertEquals(404, it.code) }

        val url = server.register(7)
        assertTrue(url, Regex("""http://127\.0\.0\.1:\d+/tg/[0-9a-f]{32}""").matches(url))
        get(url).use {
            assertEquals(200, it.code)
            assertArrayEquals(bytes, it.body!!.bytes())
        }
        // Each registration is a fresh, unguessable id.
        assertNotEquals(url, server.register(7))
    }
}
