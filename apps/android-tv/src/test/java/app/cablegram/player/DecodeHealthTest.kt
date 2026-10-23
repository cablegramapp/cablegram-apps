package app.cablegram.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeHealthTest {
    /** Feeds one sample a second for [seconds], with [shown] and [dropped] pictures per second. */
    private fun DecodeHealth.play(fromS: Int, seconds: Int, shown: Int, dropped: Int, start: Pair<Int, Int>): Pair<Int, Int> {
        var (displayed, lost) = start
        for (s in fromS until fromS + seconds) {
            sample(s * 1_000L, displayed, lost)
            displayed += shown; lost += dropped
        }
        return displayed to lost
    }

    @Test
    fun `a film at 24 pictures a second is healthy`() {
        val health = DecodeHealth()
        health.play(0, 20, shown = 24, dropped = 0, start = 0 to 0)
        assertFalse(health.struggling)
    }

    @Test
    fun `pictures dropped as too late while the sound plays is struggling`() {
        // The Chromecast case: VLC logged "picture is too late to be displayed" for nearly every picture.
        val health = DecodeHealth()
        health.play(0, 7, shown = 0, dropped = 24, start = 0 to 0)
        assertTrue(health.struggling)
    }

    @Test
    fun `it clears once pictures flow again`() {
        val health = DecodeHealth()
        val after = health.play(0, 7, shown = 1, dropped = 20, start = 0 to 0)
        assertTrue(health.struggling)
        health.play(7, 12, shown = 23, dropped = 1, start = after)
        assertFalse(health.struggling)
    }

    @Test
    fun `a still or slide-show video with few pictures and no drops is not struggling`() {
        val health = DecodeHealth()
        health.play(0, 20, shown = 1, dropped = 0, start = 0 to 0)
        assertFalse(health.struggling)
    }

    @Test
    fun `a short hiccup inside a healthy window is not struggling`() {
        val health = DecodeHealth()
        var counters = health.play(0, 3, shown = 24, dropped = 0, start = 0 to 0)
        counters = health.play(3, 2, shown = 0, dropped = 24, start = counters)
        health.play(5, 10, shown = 24, dropped = 0, start = counters)
        assertFalse(health.struggling)
    }

    @Test
    fun `pausing starts a new window instead of counting the pause`() {
        val health = DecodeHealth()
        health.play(0, 3, shown = 24, dropped = 0, start = 0 to 0)
        health.pause()
        // Paused for a minute: the counters don't move, which must not read as zero pictures a second.
        health.play(63, 10, shown = 24, dropped = 0, start = 72 to 0)
        assertFalse(health.struggling)
    }

    @Test
    fun `counters that start over with a new video start a new window`() {
        val health = DecodeHealth()
        health.play(0, 10, shown = 24, dropped = 0, start = 0 to 0)
        health.play(10, 10, shown = 24, dropped = 0, start = 0 to 0)
        assertFalse(health.struggling)
    }

    @Test
    fun `reset forgets a struggle`() {
        val health = DecodeHealth()
        health.play(0, 7, shown = 0, dropped = 24, start = 0 to 0)
        health.reset()
        assertFalse(health.struggling)
    }
}
