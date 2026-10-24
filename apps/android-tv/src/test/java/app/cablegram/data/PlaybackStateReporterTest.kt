package app.cablegram.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStateReporterTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val sent = mutableListOf<PlaybackReport>()
    private var now = 0L
    private val reporter = PlaybackStateReporter(scope, { sent += it }, nowMs = { now }, seq = 100)

    @Test
    fun `changes are sent at once with a rising sequence`() {
        reporter.playing("v1", "o1", 1_000)
        reporter.paused("v1", "o1", 2_000)
        reporter.stopped()
        scope.runCurrent()
        assertEquals(listOf("playing", "paused", "stopped"), sent.map { it.state })
        assertEquals(listOf(101L, 102L, 103L), sent.map { it.seq })
        assertEquals("o1", sent[0].originId)
        assertEquals(null, sent[2].videoId)
    }

    @Test
    fun `the player volume travels with playing and paused reports`() {
        reporter.playing("v1", null, 1_000, volume = 35)
        reporter.paused("v1", null, 2_000, volume = 40)
        reporter.stopped()
        scope.runCurrent()
        assertEquals(listOf<Int?>(35, 40, null), sent.map { it.volume })
    }

    @Test
    fun `a lasting state is refreshed only now and then`() {
        reporter.playing("v1", null, 1_000)
        now = 5_000; reporter.playing("v1", null, 6_000)
        now = PlaybackStateReporter.REFRESH_MS + 1; reporter.playing("v1", null, 16_000)
        scope.runCurrent()
        assertEquals(listOf(1_000L, 16_000L), sent.map { it.positionMs })
    }

    @Test
    fun `another title is a change`() {
        reporter.playing("v1", null, 1_000)
        reporter.playing("v2", null, 0)
        scope.runCurrent()
        assertEquals(listOf("v1", "v2"), sent.map { it.videoId })
    }

    @Test
    fun `stopped is sent once`() {
        reporter.stopped()
        now = 60_000; reporter.stopped()
        scope.runCurrent()
        assertEquals(1, sent.size)
    }

    @Test
    fun `a failed send does not stop later ones`() {
        val reports = mutableListOf<String>()
        val failing = PlaybackStateReporter(scope, {
            reports += it.state
            if (it.state == "playing") error("offline")
        }, nowMs = { now })
        failing.playing("v1", null, 0)
        scope.runCurrent()
        failing.stopped()
        scope.runCurrent()
        assertEquals(listOf("playing", "stopped"), reports)
    }
}
