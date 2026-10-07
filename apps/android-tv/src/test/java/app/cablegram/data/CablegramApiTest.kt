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
import org.junit.Assert.assertFalse
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
    fun `completing a command over HTTP sends a JSON body, not an empty one`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        api.completeCommand("cmd-1", "tv-token")
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/control/commands/cmd-1/complete", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        // The control plane's JSON parser rejects an empty body sent as application/json with a 400.
        assertEquals("{}", request.body.readUtf8())
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
    fun `shared manual metadata and cleared fields appear on the next catalog refresh`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"My film","year":2013,"overview":"My summary","media_type":"movie","metadata_revision":1,"user_metadata_fields":["title","year","overview"],"sources":[]}]}"""))
        val first = api.getVideos("jwt").videos.single()
        assertEquals("My film", first.title)
        assertEquals(2013, first.releaseYear)
        assertEquals("My summary", first.overview)
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Corrected film","year":null,"overview":null,"media_type":"movie","metadata_revision":2,"user_metadata_fields":["title","year","overview"],"sources":[]}]}"""))
        val next = api.getVideos("jwt").videos.single()
        assertEquals("Corrected film", next.title)
        assertEquals(null, next.releaseYear)
        assertEquals(null, next.overview)
    }

    @Test
    fun `decodes signed playback and subtitle tracks`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Inception","sources":[{"origin_identity":"video-1"}]}]}"""))
        server.enqueue(MockResponse().setBody("""{"devices":[{"kind":"phone","last_lan_host":"192.168.1.20","last_lan_port":8765,"lan_cert_sha256":"${TestLanCert.sha256}"}]}"""))

        val playback = api.getPlayback("item-1", "jwt")

        assertEquals("ready", playback.status)
        assertEquals("https://192.168.1.20:8765/media/video-1", playback.url)
        assertEquals("/api/catalog/items", server.takeRequest().path)
        assertEquals("/api/me", server.takeRequest().path)
    }

    @Test
    fun `a phone that published no certificate is never played over plain HTTP`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Clip","sources":[{"origin_identity":"video-1"}]}]}"""))
        server.enqueue(MockResponse().setBody("""{"devices":[{"kind":"phone","last_lan_host":"192.168.1.20","last_lan_port":8765}]}"""))

        val playback = api.getPlayback("item-1", "jwt", "capability")

        assertEquals("preparing", playback.status)
        assertEquals(null, playback.url)
    }

    @Test
    fun `a LAN server whose certificate is not the published one is not played`() = runBlocking {
        val phone = TestLanCert.phone()
        try {
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Clip","sources":[{"origin_identity":"phone-video"}]}]}"""))
            server.enqueue(MockResponse().setBody("""{"devices":[{"kind":"phone","last_lan_host":"127.0.0.1","last_lan_port":${phone.port},"lan_cert_sha256":"${"0".repeat(64)}"}]}"""))
            phone.enqueue(MockResponse().setBody("""{"link":"one-title-link","expires_in_seconds":900}"""))

            val playback = api.getPlayback("item-1", "jwt", "capability")

            assertEquals(null, playback.url)
            // The handshake failed before any request: the capability never reached the impostor.
            assertEquals(0, phone.requestCount)
        } finally {
            phone.shutdown()
        }
    }

    @Test
    fun `LAN playback plays a short-lived link and sends the capability only in a header`() = runBlocking {
        val phone = TestLanCert.phone()
        try {
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Clip","sources":[{"origin_identity":"phone-video"}]}]}"""))
            server.enqueue(MockResponse().setBody("""{"devices":[{"kind":"phone","last_lan_host":"127.0.0.1","last_lan_port":${phone.port},"lan_cert_sha256":"${TestLanCert.sha256}"}]}"""))
            phone.enqueue(MockResponse().setBody("""{"link":"one-title-link","expires_in_seconds":900}"""))

            val playback = api.getPlayback("item-1", "jwt", "capability+/=")

            assertEquals("https://127.0.0.1:${phone.port}/media/phone-video?link=one-title-link", playback.url)
            assertFalse(playback.url!!.contains("capability"))
            val asked = phone.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("/links/media/phone-video", asked.path)
            assertEquals("Bearer capability+/=", asked.getHeader("Authorization"))
        } finally {
            phone.shutdown()
        }
    }

    @Test
    fun `a phone that refuses the link is not played over the LAN`() = runBlocking {
        val phone = TestLanCert.phone()
        try {
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Clip","sources":[{"origin_identity":"phone-video"}]}]}"""))
            server.enqueue(MockResponse().setBody("""{"devices":[{"kind":"phone","last_lan_host":"127.0.0.1","last_lan_port":${phone.port},"lan_cert_sha256":"${TestLanCert.sha256}"}]}"""))
            phone.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_lan_token"}"""))

            val playback = api.getPlayback("item-1", "jwt", "capability+/=")

            // No relay ticket for a phone without an id either: the TV waits for the phone, with no URL at all.
            assertEquals("preparing", playback.status)
            assertEquals(null, playback.url)
        } finally {
            phone.shutdown()
        }
    }

    @Test
    fun `private phone artwork leaves the capability out of the poster URL`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"items":[{"id":"item-1","title":"Clip","poster_url":"https://api.test/private-poster","sources":[{"kind":"phone_local","origin_identity":"phone-video","private":true}]}]}""",
        ))
        server.enqueue(MockResponse().setBody("""{"devices":[{"kind":"phone","last_lan_host":"192.168.1.20","last_lan_port":8765,"lan_cert_sha256":"${TestLanCert.sha256}"}]}"""))

        val video = api.getVideos("jwt", "capability+/=").videos.single()

        assertEquals("https://192.168.1.20:8765/poster/phone-video", video.posterUrl)
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

    private val r2Item = """{"id":"item-1","title":"Film","sources":[{"kind":"phone_local","origin_identity":"phone-video","serving_device_id":"p1"},{"kind":"own_cloud","origin_identity":"r2:abc","availability":"available"}]}"""
    private val r2Resolve = """{"status":"ready","url":"https://acct.r2.cloudflarestorage.com/h/u/film.mkv?X-Amz-Signature=sig"}"""

    @Test
    fun `a title that only lives in the household R2 bucket plays from it without asking a phone`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Film","sources":[{"kind":"own_cloud","origin_identity":"r2:abc","availability":"available"}]}]}"""))
        server.enqueue(MockResponse().setBody(r2Resolve))
        server.enqueue(MockResponse().setBody(noSubtitles)) // the saved-subtitle lookup once playback is ready

        val playback = api.getPlayback("item-1", "jwt")

        assertEquals("ready", playback.status)
        assertEquals("https://acct.r2.cloudflarestorage.com/h/u/film.mkv?X-Amz-Signature=sig", playback.url)
        server.takeRequest()
        assertEquals("/api/playback/resolve", server.takeRequest().path)
        assertEquals("no phone is asked", 3, server.requestCount)
    }

    @Test
    fun `R2 is used before the relay when the phone is not on this Wi-Fi`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[$r2Item]}"""))
        server.enqueue(MockResponse().setBody("""{"devices":[{"id":"p1","kind":"phone"}]}"""))
        server.enqueue(MockResponse().setBody(r2Resolve))

        val playback = api.getPlayback("item-1", "jwt")

        assertTrue(playback.url!!.startsWith("https://acct.r2.cloudflarestorage.com/"))
    }

    @Test
    fun `LAN stays first and R2 is looked up only if the LAN stalls`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[$r2Item]}"""))
        server.enqueue(MockResponse().setBody("""{"devices":[{"id":"p1","kind":"phone","last_lan_host":"192.168.1.20","last_lan_port":8765,"lan_cert_sha256":"${TestLanCert.sha256}"}]}"""))
        server.enqueue(MockResponse().setResponseCode(404)) // the relay ticket: not available in this test
        server.enqueue(MockResponse().setBody(noSubtitles)) // the saved-subtitle lookup once playback is ready
        server.enqueue(MockResponse().setBody(r2Resolve))

        val playback = api.getPlayback("item-1", "jwt")

        assertTrue(playback.url!!.startsWith("https://192.168.1.20:8765/media/phone-video"))
        val before = server.requestCount
        val fallback = playback.fallbackResolver!!.invoke()
        assertTrue(fallback!!.startsWith("https://acct.r2.cloudflarestorage.com/"))
        assertEquals(before + 1, server.requestCount)
        playback.fallbackResolver!!.invoke()
        assertEquals("the R2 lookup is made once", before + 1, server.requestCount)
    }

    private val noSubtitles = """{"subtitles":[]}"""

    private val driveResolve = """{"status":"ready","url":"https://www.googleapis.com/drive/v3/files/f1?alt=media","headers":{"Authorization":"Bearer ya29.test"},"mime_type":"video/x-matroska","expiresAt":"2026-10-02T12:50:00.000Z","title":"Film"}"""

    @Test
    fun `a Google Drive copy plays with its bearer header and its short expiry`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Film","sources":[{"kind":"own_cloud","origin_identity":"gdrive:abc","availability":"available"}]}]}"""))
        server.enqueue(MockResponse().setBody(driveResolve))

        val playback = api.getPlayback("item-1", "jwt")

        assertEquals("ready", playback.status)
        assertEquals("https://www.googleapis.com/drive/v3/files/f1?alt=media", playback.url)
        assertEquals(mapOf("Authorization" to "Bearer ya29.test"), playback.headers)
        assertEquals("video/x-matroska", playback.mimeType)
        assertEquals("2026-10-02T12:50:00.000Z", playback.expiresAt)
        assertEquals("Film", playback.title)
    }

    @Test
    fun `a fallback never carries the cloud token, and the cloud copy is not a bare-URL fallback`() = runBlocking {
        // Phone off this Wi-Fi: Drive first, the relay behind it; the relay is looked up without any header.
        server.enqueue(MockResponse().setBody("""{"items":[$r2Item]}"""))
        server.enqueue(MockResponse().setBody("""{"devices":[{"id":"p1","kind":"phone"}]}"""))
        server.enqueue(MockResponse().setBody(driveResolve))
        server.enqueue(MockResponse().setBody(noSubtitles)) // the saved-subtitle lookup once playback is ready
        val cloudFirst = api.getPlayback("item-1", "jwt")
        assertEquals(mapOf("Authorization" to "Bearer ya29.test"), cloudFirst.headers)
        assertTrue("the fallback has its own, empty, headers", cloudFirst.fallbackHeaders.isEmpty())

        // LAN first: when it stalls, a copy that needs a header is not offered as a bare URL.
        server.enqueue(MockResponse().setBody("""{"items":[$r2Item]}"""))
        server.enqueue(MockResponse().setBody("""{"devices":[{"id":"p1","kind":"phone","last_lan_host":"192.168.1.20","last_lan_port":8765,"lan_cert_sha256":"${TestLanCert.sha256}"}]}"""))
        server.enqueue(MockResponse().setResponseCode(404)) // the relay ticket
        server.enqueue(MockResponse().setBody(noSubtitles)) // the saved-subtitle lookup once playback is ready
        server.enqueue(MockResponse().setBody(driveResolve))
        val lan = api.getPlayback("item-1", "jwt")
        val fallback = lan.fallbackResolver!!.invoke()
        assertTrue("no Drive URL as a fallback: $fallback", fallback == null || !fallback.contains("googleapis"))
    }

    @Test
    fun `when the TV can add the header itself the player gets a plain local URL with no token`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Film","sources":[{"kind":"own_cloud","origin_identity":"gdrive:abc","availability":"available"}]}]}"""))
        server.enqueue(MockResponse().setBody(driveResolve))
        var proxied: Pair<String, Map<String, String>>? = null

        val playback = api.getPlayback("item-1", "jwt", cloudStream = { url, headers ->
            proxied = url to headers
            "http://127.0.0.1:5555/cloud/abc"
        })

        assertEquals("http://127.0.0.1:5555/cloud/abc", playback.url)
        assertTrue("the header travels to the local stream, not to the player", playback.headers.isEmpty())
        assertEquals("https://www.googleapis.com/drive/v3/files/f1?alt=media", proxied!!.first)
        assertEquals(mapOf("Authorization" to "Bearer ya29.test"), proxied!!.second)
        assertEquals("2026-10-02T12:50:00.000Z", playback.expiresAt)
    }

    @Test
    fun `a presigned R2 URL is played directly, with no local stream`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Film","sources":[{"kind":"own_cloud","origin_identity":"r2:abc","availability":"available"}]}]}"""))
        server.enqueue(MockResponse().setBody(r2Resolve))
        var asked = false

        val playback = api.getPlayback("item-1", "jwt", cloudStream = { _, _ -> asked = true; "http://127.0.0.1:1/x" })

        assertTrue(playback.url!!.startsWith("https://acct.r2.cloudflarestorage.com/"))
        assertFalse(asked)
    }

    @Test
    fun `a private Drive title, once approved, also plays through the local stream`() = runBlocking {
        val privateDrive = """{"items":[{"id":"item-1","title":"Film","sources":[{"kind":"own_cloud","origin_identity":"gdrive:abc","availability":"available","private":true}]}]}"""
        server.enqueue(MockResponse().setBody(privateDrive))                    // catalog
        server.enqueue(MockResponse().setBody("""{"attempt_id":"att-1","expires_at":"2026-10-02T13:00:00Z"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"approved"}"""))     // the owner tapped Allow
        server.enqueue(MockResponse().setBody(privateDrive))                    // catalog again after approval
        server.enqueue(MockResponse().setBody(driveResolve))

        val playback = api.getPlayback("item-1", "jwt", cloudStream = { _, _ -> "http://127.0.0.1:5555/cloud/abc" })

        assertEquals("ready", playback.status)
        assertEquals("http://127.0.0.1:5555/cloud/abc", playback.url)
        assertTrue("no bearer reaches the player", playback.headers.isEmpty())
    }

    private val privateOnPhoneAndDrive = """{"items":[{"id":"item-1","title":"Film","sources":[{"kind":"phone_local","origin_identity":"phone-video","private":true},{"kind":"own_cloud","origin_identity":"gdrive:abc","availability":"available","private":true}]}]}"""

    @Test
    fun `an approved private title also in Drive plays from the phone when it answers on this Wi-Fi`() = runBlocking {
        val phone = TestLanCert.phone().apply { enqueue(MockResponse().setResponseCode(401)) }
        try {
            server.enqueue(MockResponse().setBody(privateOnPhoneAndDrive))          // catalog
            server.enqueue(MockResponse().setBody("""{"attempt_id":"att-1","expires_at":"2026-10-02T13:00:00Z"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"approved"}"""))
            server.enqueue(MockResponse().setBody(privateOnPhoneAndDrive))          // catalog again after approval
            server.enqueue(MockResponse().setBody("""{"devices":[{"kind":"phone","last_lan_host":"127.0.0.1","last_lan_port":${phone.port},"lan_cert_sha256":"${TestLanCert.sha256}"}]}"""))
            server.enqueue(MockResponse().setBody("""{"lan_pass":"pass-1"}"""))      // the approval is spent on the phone path
            var cloud = false

            val playback = api.getPlayback("item-1", "jwt", cloudStream = { _, _ -> cloud = true; "http://127.0.0.1:5555/cloud/abc" })

            assertEquals("ready", playback.status)
            assertTrue(playback.url!!.startsWith("https://127.0.0.1:${phone.port}/media/phone-video"))
            assertTrue(playback.url!!.contains("pass=pass-1"))
            assertFalse("Drive is not asked while the phone answers", cloud)
        } finally {
            phone.shutdown()
        }
    }

    @Test
    fun `an approved private title also in Drive plays from Drive when the phone does not answer`() = runBlocking {
        val gone = MockWebServer().apply { start() }
        val port = gone.port
        gone.shutdown()
        server.enqueue(MockResponse().setBody(privateOnPhoneAndDrive))
        server.enqueue(MockResponse().setBody("""{"attempt_id":"att-1","expires_at":"2026-10-02T13:00:00Z"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"approved"}"""))
        server.enqueue(MockResponse().setBody(privateOnPhoneAndDrive))
        server.enqueue(MockResponse().setBody("""{"devices":[{"kind":"phone","last_lan_host":"127.0.0.1","last_lan_port":$port}]}"""))
        server.enqueue(MockResponse().setBody(driveResolve))

        val playback = api.getPlayback("item-1", "jwt", cloudStream = { _, _ -> "http://127.0.0.1:5555/cloud/abc" })

        assertEquals("ready", playback.status)
        assertEquals("http://127.0.0.1:5555/cloud/abc", playback.url)
        assertTrue(playback.headers.isEmpty())
    }

    @Test
    fun `a web title's own headers are left for the player, not sent through the local stream`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Clip","sources":[{"kind":"web","availability":"available"}]}]}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ready","url":"https://cdn.example/clip.mp4","headers":{"Referer":"https://site.example/"}}"""))
        var asked = false

        val playback = api.getPlayback("item-1", "jwt", cloudStream = { _, _ -> asked = true; "http://127.0.0.1:1/x" })

        assertEquals("https://cdn.example/clip.mp4", playback.url)
        assertEquals(mapOf("Referer" to "https://site.example/"), playback.headers)
        assertFalse(asked)
    }

    @Test
    fun `Drive limiting downloads is explained, not shown as a phone that is away`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Film","sources":[{"kind":"own_cloud","origin_identity":"gdrive:abc","availability":"available"}]}]}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"provider_rate_limited"}"""))

        val playback = api.getPlayback("item-1", "jwt")

        assertEquals("denied", playback.status)
        assertTrue(playback.prepareLabel!!.contains("limiting downloads"))
    }

    @Test
    fun `a title whose cloud source went away does not wait for a phone that has nothing`() = runBlocking {
        // The control plane answers 404 source_unavailable once it finds the file gone.
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Film","sources":[{"kind":"own_cloud","origin_identity":"gdrive:abc","availability":"available"}]}]}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"source_unavailable"}"""))

        val playback = api.getPlayback("item-1", "jwt")

        assertTrue(playback.status != "ready")
        assertTrue(playback.url == null)
    }

    @Test
    fun `a cloud copy is never mistaken for a phone to stream from`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[$r2Item]}"""))
        server.enqueue(MockResponse().setBody("""{"devices":[]}"""))
        server.enqueue(MockResponse().setBody(r2Resolve))

        val playback = api.getPlayback("item-1", "jwt")

        assertTrue(playback.url!!.startsWith("https://acct.r2.cloudflarestorage.com/"))
        assertTrue(!playback.url!!.contains("r2:abc"))
    }

    @Test
    fun `an unavailable R2 copy is not tried`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"item-1","title":"Film","sources":[{"kind":"own_cloud","origin_identity":"r2:abc","availability":"unavailable"}]}]}"""))

        val playback = api.getPlayback("item-1", "jwt")

        assertEquals("denied", playback.status)
        assertEquals(1, server.requestCount)
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
