package app.cablegram

import app.cablegram.data.PlaybackResponse
import app.cablegram.data.Video
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPollingStateTest {
    private val video = Video(id = "video-1", addedAtTimestamp = "2026-09-01T12:00:00Z")

    @Test
    fun `keeps phone commands alive while playback is preparing`() {
        assertTrue(ScreenState.Resolving(video).acceptsCommandPolling())
        assertTrue(ScreenState.Player(video, PlaybackResponse(status = "ready")).acceptsCommandPolling())
    }

    @Test
    fun `stops phone commands on blocking screens`() {
        assertFalse(ScreenState.Loading.acceptsCommandPolling())
        assertFalse(ScreenState.Error("Unavailable", "Try again").acceptsCommandPolling())
    }
}
