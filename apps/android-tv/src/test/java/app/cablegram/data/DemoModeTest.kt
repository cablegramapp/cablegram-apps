package app.cablegram.data

import app.cablegram.ReviewerBypassTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoModeTest {
    @Test
    fun `five center clicks in the window enter demo mode`() {
        var now = 1_000L
        val tracker = ReviewerBypassTracker(nowMs = { now })
        repeat(4) { assertFalse(tracker.onSelect()) }
        assertTrue(tracker.onSelect())
    }

    @Test
    fun `slow center clicks do not enter demo mode`() {
        var now = 1_000L
        val tracker = ReviewerBypassTracker(nowMs = { now })
        repeat(4) { assertFalse(tracker.onSelect()) }
        now += 9_000L
        assertFalse(tracker.onSelect())
    }

    @Test
    fun `a single select reaches the focused button but a rapid burst does not`() {
        var now = 1_000L
        val tracker = ReviewerBypassTracker(nowMs = { now })
        assertTrue(tracker.isDeliberateSelect())
        tracker.onSelect()
        now += 300
        assertFalse(tracker.isDeliberateSelect())
        tracker.onSelect()
        now += 2_000
        assertTrue(tracker.isDeliberateSelect())
    }

    @Test
    fun `pin 9999 enters demo mode`() {
        val tracker = ReviewerBypassTracker()
        assertFalse(tracker.onDigit('9'))
        assertFalse(tracker.onDigit('9'))
        assertFalse(tracker.onDigit('9'))
        assertTrue(tracker.onDigit('9'))
    }

    @Test
    fun `demo catalog is local https samples without legacy ids`() {
        val videos = demoLibraryVideos()
        assertEquals(7, videos.size)
        assertTrue(videos.all { isDemoVideoId(it.id) })
        assertTrue(videos.all { demoPlaybackUrl(it.id)!!.startsWith("https://") })
        assertTrue(videos.all { it.posterUrl!!.startsWith("https://") })
        assertTrue(videos.none { it.id.contains("t.me") })
        assertTrue(videos.none { demoPlaybackUrl(it.id)!!.contains("commondatastorage.googleapis.com") })
    }
}
