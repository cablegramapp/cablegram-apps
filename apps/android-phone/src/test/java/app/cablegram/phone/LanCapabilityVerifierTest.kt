package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanCapabilityVerifierTest {
    private var now = 0L
    private var calls = 0
    private val verifier = LanCapabilityVerifier(
        verify = { capability -> calls++; if (capability == "paired-by-other-phone") "tv-2" else null },
        nowMs = { now },
        acceptMs = 1_000,
        rejectMs = 100,
    )

    @Test
    fun `a TV paired by another household phone is accepted and cached`() {
        repeat(10) { assertEquals("tv-2", verifier.tvDeviceId("paired-by-other-phone")) }
        assertEquals(1, calls)
        now = 1_001
        verifier.tvDeviceId("paired-by-other-phone")
        assertEquals(2, calls)
    }

    @Test
    fun `unknown capabilities are rejected without hammering the server`() {
        repeat(10) { assertNull(verifier.tvDeviceId("forged")) }
        assertEquals(1, calls)
        now = 101
        assertNull(verifier.tvDeviceId("forged"))
        assertEquals(2, calls)
    }

    @Test
    fun `forgetting a TV drops its cached answer so the server is asked again`() {
        verifier.tvDeviceId("paired-by-other-phone")
        verifier.forgetDevice("tv-2")
        verifier.tvDeviceId("paired-by-other-phone")
        assertEquals(2, calls)
    }
}
