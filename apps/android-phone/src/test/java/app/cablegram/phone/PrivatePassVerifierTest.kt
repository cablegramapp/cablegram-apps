package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivatePassVerifierTest {
    private var now = 1_000L
    private var calls = 0
    private val verifier = PrivatePassVerifier(
        verify = { pass ->
            calls++
            if (pass == "good") VerifiedPass(setOf("file-p"), tvDeviceId = "tv-1", expiresAtMs = 10_000L) else null
        },
        nowMs = { now },
    )

    @Test
    fun `private media needs a pass for this file and this TV`() {
        assertFalse(verifier.allows(null, "file-p", "tv-1"))
        assertFalse(verifier.allows("forged", "file-p", "tv-1"))
        assertFalse(verifier.allows("good", "other-file", "tv-1"))
        assertFalse(verifier.allows("good", "file-p", "tv-2"))
        assertTrue(verifier.allows("good", "file-p", "tv-1"))
    }

    @Test
    fun `range requests reuse the verified pass until it expires`() {
        repeat(20) { assertTrue(verifier.allows("good", "file-p", "tv-1")) }
        assertEquals(1, calls)
        now = 10_001L
        assertFalse(verifier.allows("good", "file-p", "tv-1"))
    }
}
