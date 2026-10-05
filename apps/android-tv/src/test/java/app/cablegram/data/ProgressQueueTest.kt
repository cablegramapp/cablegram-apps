package app.cablegram.data

import java.io.IOException
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressQueueTest {
    private fun queue(initial: String? = null, onSave: (String) -> Unit = {}, clock: () -> Long = { 0L }) =
        ProgressQueue(initial, onSave, clock)

    private fun ProgressQueue.add(video: String, position: Int, at: Long, profile: String? = "p", state: String = "paused") =
        enqueue(video, profile, position, state, durationSeconds = 7200, observedAt = at)

    private val offline = IOException("offline")

    @Test fun `a newer update enqueued during an upload survives the older upload finishing`() = runTest {
        val queue = queue()
        queue.add("v", 100, at = 1_000)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val uploaded = mutableListOf<Int>()
        val job = launch {
            flushProgress(queue, upload = { uploaded += it.positionSeconds; started.complete(Unit); release.await() }, isPermanentRejection = { false })
        }
        runCurrent()
        assertTrue(started.isCompleted)
        queue.add("v", 500, at = 2_000) // B replaces A while A is still uploading
        release.complete(Unit)
        job.join()
        assertEquals(listOf(100), uploaded)
        assertEquals(listOf(500), queue.pending().map { it.positionSeconds })
    }

    @Test fun `acknowledging removes only the exact version and leaves other profiles and videos alone`() {
        val queue = queue()
        queue.add("v", 100, at = 1_000, profile = "p")
        queue.add("v", 200, at = 1_100, profile = "other")
        queue.add("w", 300, at = 1_200, profile = "p")
        val a = queue.pending().first { it.videoId == "v" && it.profileId == "p" }
        queue.add("v", 150, at = 1_300, profile = "p") // newer version of the same key
        assertFalse(queue.acknowledge(a))
        assertEquals(3, queue.pending().size)
        val b = queue.pending().first { it.videoId == "v" && it.profileId == "p" }
        assertTrue(queue.acknowledge(b))
        assertEquals(setOf("v/other", "w/p"), queue.pending().map { "${it.videoId}/${it.profileId}" }.toSet())
    }

    @Test fun `a transient failure keeps every entry and stops the round, the next round finishes it`() = runTest {
        val queue = queue()
        queue.add("v", 100, at = 1_000)
        queue.add("w", 200, at = 2_000)
        var online = false
        val uploaded = mutableListOf<String>()
        val upload: suspend (QueuedProgress) -> Unit = { if (!online) throw offline else uploaded += it.videoId }
        flushProgress(queue, upload, isPermanentRejection = { false })
        assertEquals(2, queue.pending().size)
        assertTrue(uploaded.isEmpty())
        online = true
        flushProgress(queue, upload, isPermanentRejection = { false })
        assertEquals(listOf("v", "w"), uploaded)
        assertTrue(queue.pending().isEmpty())
    }

    @Test fun `an entry the server will never accept is dropped and does not block the rest`() = runTest {
        val queue = queue()
        queue.add("gone", 100, at = 1_000)
        queue.add("ok", 200, at = 2_000)
        val uploaded = mutableListOf<String>()
        flushProgress(
            queue,
            upload = { if (it.videoId == "gone") throw IllegalStateException("410") else uploaded += it.videoId },
            isPermanentRejection = { it is IllegalStateException },
        )
        assertEquals(listOf("ok"), uploaded)
        assertTrue(queue.pending().isEmpty())
    }

    @Test fun `equal timestamps keep the order the updates happened in`() {
        val queue = queue()
        queue.add("v", 100, at = 5_000)
        queue.add("v", 200, at = 5_000)
        val only = queue.pending().single()
        assertEquals(200, only.positionSeconds)
        assertEquals(5_001, only.clientUpdatedAt)
        queue.add("v", 300, at = 4_000) // observed "earlier" by a skewed reading, but it happened later
        assertEquals(300, queue.pending().single().positionSeconds)
        assertEquals(5_002, queue.pending().single().clientUpdatedAt)
    }

    @Test fun `a rewind is a newer update and wins over the later position it replaces`() {
        val queue = queue()
        queue.add("v", 5_000, at = 1_000)
        queue.add("v", 60, at = 2_000) // the viewer started the film over
        assertEquals(60, queue.pending().single().positionSeconds)
    }

    @Test fun `entries are replayed oldest first`() {
        val queue = queue()
        queue.add("c", 3, at = 3_000)
        queue.add("a", 1, at = 1_000)
        queue.add("b", 2, at = 2_000)
        assertEquals(listOf("a", "b", "c"), queue.pending().map { it.videoId })
    }

    @Test fun `profile and video isolation holds`() {
        val queue = queue()
        queue.add("v", 100, at = 1_000, profile = "one")
        queue.add("v", 200, at = 1_000, profile = "two")
        queue.add("v", 300, at = 1_000, profile = null)
        assertEquals(setOf(100, 200, 300), queue.pending().map { it.positionSeconds }.toSet())
    }

    @Test fun `entries and versions survive a restart and corrupt storage starts empty`() {
        var disk: String? = null
        val queue = queue(onSave = { disk = it })
        queue.add("v", 100, at = 1_000)
        queue.add("v", 120, at = 1_000)
        val restored = queue(initial = disk)
        assertEquals(queue.pending(), restored.pending())
        assertTrue(queue(initial = "not json").pending().isEmpty())
    }

    @Test fun `the queue is capped and drops the oldest entries first`() {
        val queue = queue()
        repeat(205) { queue.add("v$it", it, at = it.toLong()) }
        val pending = queue.pending()
        assertEquals(200, pending.size)
        assertEquals("v5", pending.first().videoId)
        assertEquals("v204", pending.last().videoId)
    }

    // --- ordering against the server's rule: the latest timestamp wins, an equal one is accepted ---

    /** The control plane's progress rule (`progress/service.ts`): replace unless the stored time is later. */
    private class FakeServer {
        var position: Int? = null
        var updatedAt: Long = Long.MIN_VALUE
        fun put(positionSeconds: Int, at: Long) {
            if (updatedAt <= at) { position = positionSeconds; updatedAt = at }
        }
    }

    @Test fun `a delayed replay never overwrites a later update`() = runTest {
        val server = FakeServer()
        val queue = queue()
        queue.add("v", 100, at = 3_000) // made offline
        server.put(900, at = 5_000) // the viewer carried on, online, later
        flushProgress(queue, upload = { server.put(it.positionSeconds, it.clientUpdatedAt) }, isPermanentRejection = { false })
        assertEquals(900, server.position)
        assertTrue(queue.pending().isEmpty())
    }

    @Test fun `a TV whose clock is ten minutes fast stamps queued progress in server time`() = runTest {
        val serverNow = 1_700_000_000_000L
        val tenMinutes = 600_000L
        var deviceNow = serverNow + tenMinutes
        val clock = ServerClock { deviceNow }
        clock.observe(Date(serverNow)) // a successful response while still online
        val queue = queue(clock = clock::now)
        deviceNow += 5_000
        queue.enqueue("v", "p", 100, "paused", 7200) // offline from here on
        assertEquals(serverNow + 5_000, queue.pending().single().clientUpdatedAt) // server time, not the fast device time
        val server = FakeServer()
        deviceNow += 60_000
        server.put(900, at = serverNow + 5_000 + 60_000 + 1_000) // another TV, online, later
        flushProgress(queue, upload = { server.put(it.positionSeconds, it.clientUpdatedAt) }, isPermanentRejection = { false })
        assertEquals(900, server.position) // without the correction the replay would be ten minutes in the future and win
    }

    @Test fun `a TV whose clock is ten minutes slow still wins over an earlier update from another TV`() = runTest {
        val serverNow = 1_700_000_000_000L
        var deviceNow = serverNow - 600_000L
        val clock = ServerClock { deviceNow }
        clock.observe(Date(serverNow))
        val server = FakeServer()
        server.put(100, at = serverNow + 1_000) // another TV, a moment ago
        val queue = queue(clock = clock::now)
        deviceNow += 10_000
        queue.enqueue("v", "p", 700, "paused", 7200) // this TV, later, offline
        flushProgress(queue, upload = { server.put(it.positionSeconds, it.clientUpdatedAt) }, isPermanentRejection = { false })
        assertEquals(700, server.position)
    }

    @Test fun `before any response the device clock is used and a missing Date header changes nothing`() {
        var deviceNow = 10_000L
        val clock = ServerClock { deviceNow }
        assertEquals(10_000L, clock.now())
        clock.observe(null)
        assertEquals(10_000L, clock.now())
        clock.observe(Date(70_000L))
        deviceNow = 11_000L
        assertEquals(71_000L, clock.now())
    }
}
