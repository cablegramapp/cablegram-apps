package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class TvPlaybackFollowTest {
    private fun report(state: String, ageMs: Long? = 1_000, videoId: String? = "v1", originId: String? = null, online: Boolean = true) =
        TvPlayback(state, videoId, originId, positionMs = 0, ageMs = ageMs, online = online)

    private val longAgo = Long.MAX_VALUE

    @Test
    fun `a title that ended or was stopped on the TV clears the remote`() {
        assertEquals(TvFollow.Stopped, followTv(report("stopped", videoId = null), "v1", longAgo))
    }

    @Test
    fun `pause and play on the TV are followed`() {
        assertEquals(TvFollow.Playing(paused = true), followTv(report("paused"), "v1", longAgo))
        assertEquals(TvFollow.Playing(paused = false), followTv(report("playing"), "v1", longAgo))
    }

    @Test
    fun `the phone's id may be the TV's origin id`() {
        assertEquals(TvFollow.Playing(paused = true), followTv(report("paused", videoId = "server-1", originId = "v1"), "v1", longAgo))
    }

    @Test
    fun `a report made before the phone's own command is ignored`() {
        // The phone paused 2 s ago; the TV's "playing" is 3 s old.
        assertEquals(TvFollow.Keep, followTv(report("playing", ageMs = 3_000), "v1", sinceOwnCommandMs = 2_000))
        assertEquals(TvFollow.Playing(paused = true), followTv(report("paused", ageMs = 500), "v1", sinceOwnCommandMs = 2_000))
    }

    @Test
    fun `nothing reported yet keeps what the phone shows`() {
        assertEquals(TvFollow.Keep, followTv(report("unknown", ageMs = null), "v1", longAgo))
    }

    @Test
    fun `a TV that went away or stopped reporting is no longer playing`() {
        assertEquals(TvFollow.Stopped, followTv(report("playing", ageMs = TV_GONE_MS + 1, online = false), "v1", longAgo))
        assertEquals(TvFollow.Stopped, followTv(report("playing", ageMs = TV_PLAYING_STALE_MS + 1), "v1", longAgo))
        // A paused TV does not refresh its report, so an old pause still stands while the TV is on.
        assertEquals(TvFollow.Playing(paused = true), followTv(report("paused", ageMs = 600_000), "v1", longAgo))
        // A short reconnect does not end playback on the phone.
        assertEquals(TvFollow.Playing(paused = false), followTv(report("playing", ageMs = 5_000, online = false), "v1", longAgo))
    }

    @Test
    fun `another title started on the TV is reported as such`() {
        assertEquals(TvFollow.Other("v2", "o2", paused = false), followTv(report("playing", videoId = "v2", originId = "o2"), "v1", longAgo))
    }
}
