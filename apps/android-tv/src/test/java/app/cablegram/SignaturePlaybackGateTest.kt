package app.cablegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignaturePlaybackGateTest {
    @Test fun readyMovieWaitsForOneClip() {
        val gate = SignaturePlaybackGate<String>()
        gate.begin("a")
        assertNull(gate.ready("a", "movie"))
        assertEquals("movie", gate.complete("a"))
        assertNull(gate.complete("a"))
    }

    @Test fun longerPreparationDoesNotRequireAnotherFullLoop() {
        val gate = SignaturePlaybackGate<String>()
        gate.begin("a")
        assertNull(gate.complete("a"))
        assertEquals("movie", gate.ready("a", "movie"))
    }

    @Test fun oldClipAndOldMovieCannotReleaseANewAttempt() {
        val gate = SignaturePlaybackGate<String>()
        gate.begin("a")
        gate.ready("a", "old")
        gate.begin("b")
        assertNull(gate.complete("a"))
        assertNull(gate.ready("a", "old"))
        assertNull(gate.ready("b", "new"))
        assertEquals("new", gate.complete("b"))
    }

    @Test fun cancellingDiscardsReadyMovieAndLateCompletion() {
        val gate = SignaturePlaybackGate<String>()
        gate.begin("a")
        gate.ready("a", "movie")
        gate.cancel()
        assertNull(gate.complete("a"))
        assertNull(gate.ready("a", "movie"))
    }
}
