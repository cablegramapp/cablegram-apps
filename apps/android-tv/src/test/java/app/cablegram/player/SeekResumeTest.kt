package app.cablegram.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekResumeTest {
    @Test
    fun `a second seek during the hold keeps a playing video playing`() {
        // The hold paused the player itself; it was going to resume.
        assertTrue(resumeAfterUserSeek(settling = true, settleResume = true, playing = false))
    }

    @Test
    fun `a paused video stays paused, during the hold or not`() {
        assertFalse(resumeAfterUserSeek(settling = true, settleResume = false, playing = false))
        assertFalse(resumeAfterUserSeek(settling = false, settleResume = true, playing = false))
    }

    @Test
    fun `outside the hold a seek follows the player`() {
        assertTrue(resumeAfterUserSeek(settling = false, settleResume = false, playing = true))
    }
}
