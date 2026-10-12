package app.cablegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignaturePlaybackGateTest {
    private var time = 0L
    private val gate = SignaturePlaybackGate(now = { time })

    @Test fun quickTitleStartsAtOnce() {
        gate.begin("a")
        gate.clipStarted("a")
        time = 1_200
        assertEquals(0L, gate.delayFor("a"))
    }

    @Test fun slowerTitleGetsTheShortMinimumNotAWholeClip() {
        gate.begin("a")
        time = 1_000; gate.clipStarted("a")
        time = 2_000
        assertEquals(1_000L, gate.delayFor("a"))
        time = 30_000
        assertEquals(0L, gate.delayFor("a"))
    }

    @Test fun clipThatNeverAppearedHoldsNothing() {
        gate.begin("a")
        time = 5_000
        assertEquals(0L, gate.delayFor("a"))
    }

    @Test fun castsAndRetriesAreNeverHeld() {
        gate.begin("a", holdForClip = false)
        time = 1_600; gate.clipStarted("a")
        time = 1_700
        assertEquals(0L, gate.delayFor("a"))
    }

    @Test fun olderAttemptsCannotReleaseATitle() {
        gate.begin("old")
        gate.begin("new")
        assertNull(gate.delayFor("old"))
        gate.cancel()
        assertNull(gate.delayFor("new"))
    }
}
