package app.cablegram.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CablegramApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CablegramApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = CablegramApi(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a TV links the household with its own token and the account it signed in with`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"linked":true,"telegram_user_id":777000111,"display_name":"Masoud","library_chat_id":-1004296878113,"linked_at":"2026-10-01T10:00:00Z"}"""))
        val link = api.putTelegramLink("tv-token", 777000111L, "Masoud", -1004296878113L)
        assertTrue(link.linked)
        assertEquals(-1004296878113L, link.chatId)
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("PUT", request.method)
        assertEquals("/api/telegram/link", request.path)
        assertEquals("Bearer tv-token", request.getHeader("Authorization"))
        // Exactly the fields the control plane accepts; it rejects any other, so no phone_device_id from a TV.
        assertEquals("""{"telegram_user_id":777000111,"display_name":"Masoud","library_chat_id":-1004296878113}""", request.body.readUtf8())
    }

    @Test
    fun `a household that is already linked refuses the TV's link`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))
        val error = runCatching { runBlocking { api.putTelegramLink("tv-token", 1L, "x", -100L) } }.exceptionOrNull() as ApiException
        assertEquals(403, error.statusCode)
    }

    @Test
    fun `real websocket carries authenticated command and receipt frames`() {
        val events = LinkedBlockingQueue<String>()
        val results = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"ready"}""")
                webSocket.send("""{"type":"command","command":{"id":"abc","command":"pause","payload":{},"expires_at_ms":9999999999999}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                results.offer(text)
                webSocket.send("""{"type":"receipt","id":"abc","status":"completed"}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val socket = api.openCommandSocket("tv-token",
            onCommand = { events.offer("command:${it.id}:${it.expiresAtMs}") },
            onClosed = {},
            onReady = { events.offer("ready") },
            onReceipt = { events.offer("receipt:$it") },
        )
        try {
            assertEquals("ready", events.poll(3, TimeUnit.SECONDS))
            assertEquals("command:abc:9999999999999", events.poll(3, TimeUnit.SECONDS))
            assertTrue(socket.send("""{"type":"complete","id":"abc"}"""))
            assertEquals("""{"type":"complete","id":"abc"}""", results.poll(3, TimeUnit.SECONDS))
            assertEquals("receipt:abc", events.poll(3, TimeUnit.SECONDS))
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("/api/control/commands/socket", request.path)
            assertEquals("Bearer tv-token", request.getHeader("Authorization"))
        } finally { socket.cancel() }
    }

    @Test
    fun `HTTP result failure is surfaced for durable retry and reliable polling is requested`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { api.completeCommand("id", "token") }.exceptionOrNull() is ApiException)
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { api.rejectCommand("id", "unsupported_command", "token") }.exceptionOrNull() is ApiException)
        server.takeRequest()
        server.enqueue(MockResponse().setBody("""{"commands":[]}"""))
        api.getCommands("token")
        assertEquals("/api/control/commands?delivery=ack", server.takeRequest().path)
    }

    @Test
    fun `creates device session using backend contract`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"session_id":"session-1","pairing_token":"secret","pin":"123456","expires_at":"2026-09-01T12:00:00Z"}""",
            ),
        )

        val session = api.createDeviceSession("Living Room")

        assertEquals("123456", session.pin)
        assertEquals("/api/auth/device", server.takeRequest().path)
    }

    @Test
    fun `decodes snake case video metadata`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"id":"item-1","title":"Inception","duration_seconds":42,"poster_url":"https://image.test/poster.jpg","genres":["Science Fiction"],"director":"Christopher Nolan","sources":[{"kind":"phone_local","origin_identity":"video-1"}]}]}""",
            ),
        )

        val library = api.getVideos("jwt")

        assertEquals(42, library.videos.single().durationSeconds)
        assertEquals(listOf("Science Fiction"), library.videos.single().genres)
        assertEquals("Christopher Nolan", library.videos.single().director)
        assertEquals("item-1", library.videos.single().id)
        assertEquals("Bearer jwt", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `decodes signed playback and subtitle tracks`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Inception","sources":[{"origin_identity":"video-1"}]}]}"""))
        server.enqueue(MockResponse().setBody("""{"devices":[{"kind":"phone","last_lan_host":"192.168.1.20","last_lan_port":8765}]}"""))

        val playback = api.getPlayback("item-1", "jwt")

        assertEquals("ready", playback.status)
        assertEquals("http://192.168.1.20:8765/media/video-1", playback.url)
        assertEquals("/api/catalog/items", server.takeRequest().path)
        assertEquals("/api/me", server.takeRequest().path)
    }

    @Test
    fun `LAN playback carries the paired TV capability`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Clip","sources":[{"origin_identity":"phone-video"}]}]}"""))
        server.enqueue(MockResponse().setBody("""{"devices":[{"kind":"phone","last_lan_host":"192.168.1.20","last_lan_port":8765}]}"""))

        val playback = api.getPlayback("item-1", "jwt", "capability+/=")

        assertEquals("http://192.168.1.20:8765/media/phone-video?token=capability%2B%2F%3D", playback.url)
    }

    @Test
    fun `private phone artwork uses authenticated LAN poster URL`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"items":[{"id":"item-1","title":"Clip","poster_url":"https://api.test/private-poster","sources":[{"kind":"phone_local","origin_identity":"phone-video","private":true}]}]}""",
        ))
        server.enqueue(MockResponse().setBody("""{"devices":[{"kind":"phone","last_lan_host":"192.168.1.20","last_lan_port":8765}]}"""))

        val video = api.getVideos("jwt", "capability+/=").videos.single()

        assertEquals("http://192.168.1.20:8765/poster/phone-video?token=capability%2B%2F%3D", video.posterUrl)
    }

    @Test
    fun `does not report progress without a selected profile`() = runBlocking {
        api.updateProgress("video-1", 21, "jwt")
        assertTrue(server.requestCount == 0)
    }

    @Test
    fun `does not delete household catalog items`() = runBlocking {
        api.deleteFailedVideo("video-1", "jwt")
        assertTrue(server.requestCount == 0)
    }

    @Test
    fun `surfaces unavailable playback message`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"error":"video_unavailable","message":"Video is unavailable."}""",
            ),
        )

        val error = runCatching { api.getPlayback("video-1", "jwt") }.exceptionOrNull()

        assertTrue(error is ApiException)
        assertEquals(409, (error as ApiException).statusCode)
        assertEquals("Video is unavailable.", error.message)
    }

    @Test
    fun `returns preparing playback when phone is not available`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Inception","sources":[{"origin_identity":"video-1"}]}]}"""))
        server.enqueue(MockResponse().setBody("""{"devices":[]}"""))

        val playback = api.getPlayback("item-1", "jwt")

        assertEquals("preparing", playback.status)
        assertEquals("Waiting for your phone on this Wi‑Fi", playback.prepareLabel)
    }

    @Test
    fun `marks ingest complete once playback is ready`() {
        val ready = Video(
            id = "video-1",
            addedAtTimestamp = "2026-09-01T12:00:00Z",
            tier = "hot+r2",
            ingestProgress = 100,
            ingestStage = "ready",
        )
        assertTrue(ready.hasCompletedIngest())
        val preparing = ready.copy(tier = "ingesting", ingestProgress = 54, ingestStage = "copying")
        assertTrue(!preparing.hasCompletedIngest())
        val evicted = ready.copy(tier = "evicted", ingestProgress = 100, ingestStage = "ready")
        assertTrue(evicted.hasCompletedIngest())
    }

    @Test
    fun `fetches loading videos list`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"videos":["https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/Italy.mp4","https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/Japan.mp4"]}""",
            ),
        )

        val videos = api.getLoadingVideos("jwt")

        assertEquals(2, videos.size)
        assertEquals("https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/Italy.mp4", videos[0])
        assertEquals("/api/videos/loading-videos", server.takeRequest().path)
    }

    @Test
    fun `extracts user id from JWT token`() {
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyLXV1aWQtMTIzNCIsInNjb3BlIjoidHYiLCJzZXNzaW9uSWQiOiJzZXNzaW9uLXV1aWQtNTY3OCJ9.dummy"
        val userId = extractUserIdFromToken(token)
        assertEquals("user-uuid-1234", userId)

        val credential = AccountCredential(sessionId = "session-uuid-5678", token = token)
        assertEquals("user-uuid-1234", credential.effectiveUserId)
    }

    private val libraryBody = """{"items":[{"id":"v1","title":"First","sources":[]}]}"""

    @Test
    fun `a library answered 200 gives the list and its ETag`() = runBlocking {
        server.enqueue(MockResponse().setHeader("ETag", "\"abc\"").setBody(libraryBody))
        val fetch = api.getVideosIfChanged("tv-token", null, "p1", null)
        val changed = fetch as CablegramApi.LibraryFetch.Changed
        assertEquals("\"abc\"", changed.etag)
        assertEquals(listOf("v1"), changed.library.videos.map { it.id })
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals(null, request.getHeader("If-None-Match"))
        assertEquals("/api/catalog/items?profile_id=p1", request.path)
    }

    @Test
    fun `a 304 keeps the list the TV already has`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"abc\""))
        val fetch = api.getVideosIfChanged("tv-token", null, "p1", "\"abc\"")
        assertEquals(CablegramApi.LibraryFetch.NotModified, fetch)
        assertEquals("\"abc\"", server.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("If-None-Match"))
        // A 304 must not cost the device lookup either.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an error answer is still an error`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        val error = runCatching { runBlocking { api.getVideosIfChanged("tv-token", null, null, "\"abc\"") } }.exceptionOrNull() as ApiException
        assertEquals(401, error.statusCode)
    }
}
