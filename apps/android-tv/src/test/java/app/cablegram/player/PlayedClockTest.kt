package app.cablegram.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayedClockTest {
    @Test
    fun `a clock frozen near the end still reaches the end (CAB-56)`() {
        // Seen on a Chromecast: a 163.6 s file, VLC's time stops at 158.5 s while the last 5 s play out.
        val clock = PlayedClock()
        clock.onPlaying(0, nowMs = 1_000)
        clock.onTime(158_500, nowMs = 159_500)
        clock.onTime(158_500, nowMs = 162_000)
        clock.onEnd(158_500, nowMs = 164_600, rate = 1f)
        assertEquals(163_600, clock.playedThroughMs(158_500, nowMs = 170_000, rate = 1f))
    }

    @Test
    fun `a clock that keeps up adds nothing`() {
        val clock = PlayedClock()
        clock.onPlaying(0, nowMs = 0)
        clock.onTime(60_000, nowMs = 60_000)
        clock.onEnd(60_000, nowMs = 60_000, rate = 1f)
        assertEquals(60_000, clock.playedThroughMs(60_000, nowMs = 61_000, rate = 1f))
    }

    @Test
    fun `a stream lost mid-film still ends short`() {
        // The connection drops at 40 s; VLC plays its 1.5 s read-ahead and reports the end.
        val clock = PlayedClock()
        clock.onPlaying(0, nowMs = 0)
        clock.onTime(40_000, nowMs = 40_000)
        clock.onEnd(40_000, nowMs = 41_500, rate = 1f)
        assertEquals(41_500, clock.playedThroughMs(40_000, nowMs = 50_000, rate = 1f))
    }

    @Test
    fun `the estimate is capped`() {
        val clock = PlayedClock()
        clock.onPlaying(0, nowMs = 0)
        clock.onTime(30_000, nowMs = 30_000)
        assertEquals(30_000 + PlayedClock.MAX_CLOCK_LAG_MS, clock.playedThroughMs(30_000, nowMs = 300_000, rate = 1f))
    }

    @Test
    fun `paused time does not count`() {
        val clock = PlayedClock()
        clock.onPlaying(0, nowMs = 0)
        clock.onTime(20_000, nowMs = 20_000)
        clock.onHold()
        assertEquals(20_000, clock.playedThroughMs(20_000, nowMs = 25_000, rate = 1f))
        clock.onPlaying(20_000, nowMs = 100_000)
        assertEquals(22_000, clock.playedThroughMs(20_000, nowMs = 102_000, rate = 1f))
    }

    @Test
    fun `buffering time does not count`() {
        val clock = PlayedClock()
        clock.onPlaying(0, nowMs = 0)
        clock.onTime(10_000, nowMs = 10_000)
        clock.onStall(nowMs = 18_000)
        assertEquals(11_000, clock.playedThroughMs(10_000, nowMs = 19_000, rate = 1f))
    }

    @Test
    fun `playback speed scales the estimate`() {
        val clock = PlayedClock()
        clock.onPlaying(0, nowMs = 0)
        clock.onTime(10_000, nowMs = 10_000)
        assertEquals(14_000, clock.playedThroughMs(10_000, nowMs = 12_000, rate = 2f))
    }

    @Test
    fun `a seek or a new source forgets the end`() {
        val clock = PlayedClock()
        clock.onPlaying(0, nowMs = 0)
        clock.onEnd(100_000, nowMs = 104_000, rate = 1f)
        clock.onSeek(30_000, nowMs = 110_000)
        assertEquals(30_000, clock.playedThroughMs(30_000, nowMs = 110_000, rate = 1f))
        clock.onEnd(30_000, nowMs = 110_000, rate = 1f)
        clock.reset()
        assertEquals(5_000, clock.playedThroughMs(5_000, nowMs = 200_000, rate = 1f))
    }
}
