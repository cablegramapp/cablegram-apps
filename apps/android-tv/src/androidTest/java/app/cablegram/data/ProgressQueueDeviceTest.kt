package app.cablegram.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CAB-14 on a real Android runtime: the real SharedPreferences-backed [ProgressQueue], and the real
 * [CablegramApi] client (with its clock-learning interceptor) talking to a local server over HTTP. The control
 * plane itself is a MockWebServer; nothing leaves the device.
 */
@RunWith(AndroidJUnit4::class)
class ProgressQueueDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        context.getSharedPreferences("cablegram_progress", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
        context.getSharedPreferences("cablegram_progress", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun api() = CablegramApi(baseUrl = server.url("/").toString())

    private fun isPermanent(t: Throwable): Boolean {
        val code = (t as? ApiException)?.statusCode ?: return false
        return code in 400..499 && code != 401 && code != 408 && code != 429
    }

    private fun httpDate(epochMs: Long) =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC))

    private fun uploadVia(api: CablegramApi): suspend (QueuedProgress) -> Unit = { e ->
        api.updateProgress(
            e.videoId, e.positionSeconds, "token", e.profileId,
            state = e.state, durationSeconds = e.durationSeconds, clientUpdatedAt = e.clientUpdatedAt,
        )
    }

    @Test fun queuedEntriesAndVersionsSurviveRecreatingTheQueue() {
        val queue = ProgressQueue(context)
        queue.enqueue("v", "p", 100, "paused", 7200, observedAt = 5_000)
        queue.enqueue("v", "p", 120, "paused", 7200, observedAt = 5_000) // equal timestamp: version +1
        val restored = ProgressQueue(context) // what a process restart sees
        assertEquals(queue.pending(), restored.pending())
        assertEquals(5_001L, restored.pending().single().clientUpdatedAt)
        assertEquals(120, restored.pending().single().positionSeconds)
    }

    @Test fun anOlderUploadFinishingOverRealHttpDoesNotDeleteTheNewerUpdate() {
        val firstPutArrived = CountDownLatch(1)
        val releaseFirstPut = CountDownLatch(1)
        val bodies = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "PUT") {
                    bodies += JSONObject(request.body.readUtf8())
                    if (bodies.size == 1) {
                        firstPutArrived.countDown()
                        releaseFirstPut.await(15, TimeUnit.SECONDS)
                    }
                }
                return MockResponse().setResponseCode(204)
            }
        }
        val queue = ProgressQueue(context)
        queue.enqueue("v", "p", 100, "paused", 7200, observedAt = 1_000) // A
        val flusher = thread { runBlocking { flushProgress(queue, uploadVia(api()), ::isPermanent) } }
        assertTrue("the first upload never reached the server", firstPutArrived.await(15, TimeUnit.SECONDS))
        queue.enqueue("v", "p", 500, "paused", 7200, observedAt = 2_000) // B, while A is in flight
        releaseFirstPut.countDown()
        flusher.join(20_000)
        assertEquals(100, bodies.first().getInt("position_seconds"))
        assertEquals(listOf(500), queue.pending().map { it.positionSeconds })
        // The next round uploads B with its own timestamp.
        runBlocking { flushProgress(queue, uploadVia(api()), ::isPermanent) }
        assertTrue(queue.pending().isEmpty())
        assertEquals(500, bodies.last().getInt("position_seconds"))
        assertEquals(Instant.ofEpochMilli(2_000).toString(), bodies.last().getString("client_updated_at"))
    }

    @Test fun aDroppedConnectionKeepsTheEntryAndTheNextRoundReplaysItWithItsOriginalTime() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(204))
        val queue = ProgressQueue(context)
        queue.enqueue("v", "p", 300, "paused", 7200, observedAt = 3_000)
        runBlocking { flushProgress(queue, uploadVia(api()), ::isPermanent) }
        assertEquals(1, queue.pending().size)
        runBlocking { flushProgress(queue, uploadVia(api()), ::isPermanent) }
        assertTrue(queue.pending().isEmpty())
        server.takeRequest(5, TimeUnit.SECONDS) // the dropped attempt
        val replay = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals(300, replay.getInt("position_seconds"))
        assertEquals(Instant.ofEpochMilli(3_000).toString(), replay.getString("client_updated_at"))
    }

    @Test fun aRejectedTitleIsDroppedOverRealHttpAndTheNextEntryStillUploads() {
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"media_deleted"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        val queue = ProgressQueue(context)
        queue.enqueue("gone", "p", 10, "paused", 7200, observedAt = 1_000)
        queue.enqueue("ok", "p", 20, "paused", 7200, observedAt = 2_000)
        runBlocking { flushProgress(queue, uploadVia(api()), ::isPermanent) }
        assertTrue(queue.pending().isEmpty())
        assertEquals(2, server.requestCount)
    }

    private fun learnClockFromServerThatIs(offsetMs: Long) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(404).setHeader("Date", httpDate(System.currentTimeMillis() + offsetMs))
        }
        runBlocking { api().getLibraryAds() } // any API response teaches the clock; this one swallows the 404
    }

    @Test fun queuedProgressIsStampedInServerTimeWhenTheServerIsTenMinutesAhead() {
        val ahead = 10 * 60_000L
        learnClockFromServerThatIs(ahead)
        val queue = ProgressQueue(context)
        queue.enqueue("v", "p", 100, "paused", 7200) // default stamp: the learned server clock
        val stamp = queue.pending().single().clientUpdatedAt
        val expected = System.currentTimeMillis() + ahead
        assertTrue("stamp $stamp is ${stamp - expected} ms from server time", kotlin.math.abs(stamp - expected) < 3_000)
        learnClockFromServerThatIs(0) // leave the shared clock as we found it
    }

    @Test fun queuedProgressIsStampedInServerTimeWhenTheServerIsTenMinutesBehind() {
        val behind = -10 * 60_000L
        learnClockFromServerThatIs(behind)
        val queue = ProgressQueue(context)
        queue.enqueue("v", "p", 100, "paused", 7200)
        val stamp = queue.pending().single().clientUpdatedAt
        val expected = System.currentTimeMillis() + behind
        assertTrue("stamp $stamp is ${stamp - expected} ms from server time", kotlin.math.abs(stamp - expected) < 3_000)
        learnClockFromServerThatIs(0)
    }
}
