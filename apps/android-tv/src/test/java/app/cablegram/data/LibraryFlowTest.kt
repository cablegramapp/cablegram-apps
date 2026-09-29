package app.cablegram.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryFlowTest {
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
    fun `library requests profile progress and drops resume for completed titles`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"items":[
              {"id":"mid","title":"Mid","created_at":"2026-09-02T10:00:00Z","sources":[],"progress":{"position_seconds":40,"state":"paused"}},
              {"id":"done","title":"Done","sources":[],"progress":{"position_seconds":98,"state":"completed"}}
            ]}""",
        ))

        val videos = api.getVideos("tv-token", profileId = "profile-1").videos

        assertEquals("/api/catalog/items?profile_id=profile-1", server.takeRequest().path)
        assertEquals(40, videos.single { it.id == "mid" }.resumePositionSeconds)
        assertEquals("2026-09-02T10:00:00Z", videos.single { it.id == "mid" }.addedAtTimestamp)
        assertNull(videos.single { it.id == "done" }.resumePositionSeconds)
    }

    @Test
    fun `playback streams from the serving phone not the first phone record`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"items":[{"id":"v1","title":"Movie","sources":[{"kind":"phone_local","origin_identity":"file-1","serving_device_id":"phone-new"}]}]}""",
        ))
        server.enqueue(MockResponse().setBody(
            """{"devices":[
              {"id":"phone-old","kind":"phone","last_lan_host":"10.0.0.5","last_lan_port":8765},
              {"id":"phone-new","kind":"phone","last_lan_host":"10.0.0.9","last_lan_port":8765}
            ]}""",
        ))

        val playback = api.getPlayback("v1", "tv-token", lanPin = "cap")

        assertEquals("ready", playback.status)
        assertEquals("http://10.0.0.9:8765/media/file-1?token=cap", playback.url)
    }

    @Test
    fun `approved private title starts once and streams with the LAN pass`() = runBlocking {
        val item = """{"items":[{"id":"p1","title":"Private","sources":[{"kind":"phone_local","origin_identity":"file-p","private":true,"serving_device_id":"phone-1"}]}]}"""
        server.enqueue(MockResponse().setBody(item))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"attempt_id":"att-1","expires_at":"2026-09-25T18:00:00Z"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"approved"}"""))
        server.enqueue(MockResponse().setBody(item))
        server.enqueue(MockResponse().setBody("""{"devices":[{"id":"phone-1","kind":"phone","last_lan_host":"10.0.0.9"}]}"""))
        server.enqueue(MockResponse().setBody("""{"lan_pass":"pass-123","expires_at":"2026-09-26T00:00:00Z"}"""))

        val playback = api.getPlayback("p1", "tv-token", lanPin = "cap")

        assertEquals("http://10.0.0.9:8765/media/file-p?token=cap&pass=pass-123", playback.url)
        repeat(5) { server.takeRequest() }
        assertEquals("/api/playback/private-approvals/att-1/start", server.takeRequest().path)
    }

    @Test
    fun `phone play commands match titles by the phone's own id`() {
        val videos = listOf(Video(id = "catalog-1", title = "A", originIdentity = "phone-local-1"))
        assertEquals("catalog-1", findRemoteTitle(videos, "catalog-1")?.id)
        assertEquals("catalog-1", findRemoteTitle(videos, "phone-local-1")?.id)
        assertNull(findRemoteTitle(videos, "unknown"))
    }

    @Test
    fun `serving phone selection skips revoked phones and waits for an unannounced owner`() {
        val revoked = DeviceHintDto(id = "a", kind = "phone", revokedAt = "2026-09-01", lastLanHost = "10.0.0.1")
        val live = DeviceHintDto(id = "b", kind = "phone", lastLanHost = "10.0.0.2")
        val quiet = DeviceHintDto(id = "c", kind = "phone")

        assertEquals("b", servingPhone(listOf(revoked, live), servingDeviceId = null)?.id)
        assertEquals("b", servingPhone(listOf(revoked, live), servingDeviceId = "a")?.id)
        assertNull(servingPhone(listOf(live, quiet), servingDeviceId = "c"))
        assertTrue(servingPhone(listOf(revoked), servingDeviceId = null) == null)
    }
}
