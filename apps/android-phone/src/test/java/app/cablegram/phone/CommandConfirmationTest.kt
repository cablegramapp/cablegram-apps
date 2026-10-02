package app.cablegram.phone

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandConfirmationTest {
    private fun known(state: String, reason: String? = null, expiresAtMs: Long = 0) = CommandStatus.Known(state, reason, expiresAtMs)

    @Test
    fun `confirmed once the TV completes it`() = runTest {
        val states = ArrayDeque(listOf(known("pending"), known("delivered"), known("completed")))
        val outcome = awaitOutcome("c", ConfirmationWait.Control(), { states.removeFirst() }, { currentTime })
        assertEquals(Outcome.Confirmed, outcome)
        assertEquals(1000L, currentTime) // two 500 ms polls
    }

    @Test
    fun `rejected carries the TV's reason`() = runTest {
        val outcome = awaitOutcome("c", ConfirmationWait.Control(), { known("rejected", "profile_required") }, { currentTime })
        assertEquals(Outcome.Rejected("profile_required"), outcome)
    }

    @Test
    fun `a control times out at 8 seconds and slows polling after 2`() = runTest {
        var calls = 0
        val outcome = awaitOutcome("c", ConfirmationWait.Control(), { calls++; known("delivered") }, { currentTime })
        assertEquals(Outcome.TimedOut, outcome)
        assertEquals(8_000L, currentTime)
        assertEquals(4 + 7, calls) // 500 ms reads at 0-1.5 s, then 1 s reads at 2-8 s
    }

    @Test
    fun `transient read failures keep polling`() = runTest {
        val states = ArrayDeque<CommandStatus?>(listOf(null, null, known("completed")))
        assertEquals(Outcome.Confirmed, awaitOutcome("c", ConfirmationWait.Control(), { states.removeFirst() }, { currentTime }))
    }

    @Test
    fun `a title start waits until the command expires`() = runTest {
        val expires = 120_000L
        val outcome = awaitOutcome("c", ConfirmationWait.TitleStart, { known("delivered", expiresAtMs = expires) }, { currentTime })
        assertEquals(Outcome.TimedOut, outcome)
        assertTrue(currentTime >= expires)
        assertTrue(currentTime < expires + 3_000)
    }

    @Test
    fun `an old server is unconfirmable`() = runTest {
        assertEquals(Outcome.Unconfirmable, awaitOutcome("c", ConfirmationWait.Control(), { CommandStatus.Unsupported }, { currentTime }))
        assertEquals(0L, currentTime)
    }
}
