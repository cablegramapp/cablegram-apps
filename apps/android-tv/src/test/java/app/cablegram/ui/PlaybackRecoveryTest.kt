package app.cablegram.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryTest {
    private fun NoProgressTimer.run(seconds: Int, waiting: Boolean = true) = (1..seconds).count { tick(waiting) }

    @Test fun `fires once at the limit and not before`() {
        val timer = NoProgressTimer(30)
        assertEquals(0, timer.run(29))
        assertTrue(timer.tick(true))
        assertFalse(timer.tick(true)) // counting again from zero
    }

    @Test fun `keeps firing every limit seconds while still stuck`() {
        assertEquals(3, NoProgressTimer(10).run(30))
    }

    @Test fun `a tick that is not waiting starts the count over`() {
        val timer = NoProgressTimer(10)
        timer.run(9)
        timer.tick(false)
        assertEquals(0, timer.run(9))
        assertTrue(timer.tick(true))
    }

    @Test fun `reset starts the count over`() {
        val timer = NoProgressTimer(5)
        timer.run(4)
        timer.reset()
        assertEquals(0, timer.run(4))
    }

    @Test fun `startup waits only after the source loaded and before anything played`() {
        assertTrue(startupWaiting(loaded = true, hasStarted = false, hasError = false, switching = false))
        assertFalse(startupWaiting(loaded = false, hasStarted = false, hasError = false, switching = false)) // still probing
        assertFalse(startupWaiting(loaded = true, hasStarted = true, hasError = false, switching = false))
        assertFalse(startupWaiting(loaded = true, hasStarted = false, hasError = true, switching = false))
        assertFalse(startupWaiting(loaded = true, hasStarted = false, hasError = false, switching = true))
    }

    private fun stalled(
        hasStarted: Boolean = true, settling: Boolean = false, wants: Boolean = true, error: Boolean = false,
        switching: Boolean = false, position: Long = 5_000, last: Long = 5_000,
    ) = streamStalled(hasStarted, settling, wants, error, switching, position, last)

    @Test fun `a started stream whose position does not move is stalled`() {
        assertTrue(stalled())
        assertFalse(stalled(position = 6_000)) // advancing
    }

    @Test fun `a viewer pause, a seek hold, an error or a source switch are not stalls`() {
        assertFalse(stalled(wants = false))
        assertFalse(stalled(settling = true))
        assertFalse(stalled(error = true))
        assertFalse(stalled(switching = true))
        assertFalse(stalled(hasStarted = false))
    }

    @Test fun `a stalled seek is detected after the hold ends even when the decoder reports not playing`() {
        val timer = NoProgressTimer(SEEK_STALL_SECONDS)
        // 17 s of hold: never counted.
        repeat(17) { assertFalse(timer.tick(stalled(settling = true))) }
        // The hold ends on a frozen picture: the screen still wants playing, the position is the seek target.
        val fired = (1..SEEK_STALL_SECONDS).map { timer.tick(stalled()) }
        assertEquals(listOf(false), fired.dropLast(1).distinct())
        assertTrue(fired.last())
    }

    @Test fun `recovery focus alternates between Retry and Back`() {
        assertEquals(RecoveryChoice.BACK, RecoveryChoice.RETRY.toggled())
        assertEquals(RecoveryChoice.RETRY, RecoveryChoice.BACK.toggled())
    }
}
