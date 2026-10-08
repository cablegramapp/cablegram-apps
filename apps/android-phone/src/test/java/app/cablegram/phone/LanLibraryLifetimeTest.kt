package app.cablegram.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanLibraryLifetimeTest {
    private var now = 0L
    private val lifetime = LanLibraryLifetime(30 * 60_000L) { now }

    @Test fun foregroundNeverExpiresAndBackgroundGetsFullIdlePeriod() {
        lifetime.appForeground(true)
        now = 60 * 60_000L
        assertFalse(lifetime.shouldStop(false))
        lifetime.appForeground(false)
        now += 30 * 60_000L - 1
        assertFalse(lifetime.shouldStop(false))
        now++
        assertTrue(lifetime.shouldStop(false))
    }

    @Test fun tvRequestsResetBackgroundIdleDeadline() {
        now = 29 * 60_000L
        lifetime.tvRequest()
        now = 30 * 60_000L
        assertFalse(lifetime.shouldStop(false))
        now = 59 * 60_000L
        assertTrue(lifetime.shouldStop(false))
    }

    @Test fun simultaneousStreamsHoldServiceUntilLastStreamCloses() {
        lifetime.streamStarted()
        lifetime.streamStarted()
        now = 90 * 60_000L
        assertFalse(lifetime.shouldStop(false))
        lifetime.streamFinished()
        now += 30 * 60_000L
        assertFalse(lifetime.shouldStop(false))
        lifetime.streamFinished()
        assertFalse(lifetime.shouldStop(false))
        now += 30 * 60_000L
        assertTrue(lifetime.shouldStop(false))
    }

    @Test fun castSessionHoldsServicePastIdleDeadline() {
        now = 30 * 60_000L
        assertFalse(lifetime.shouldStop(true))
        assertTrue(lifetime.shouldStop(false))
    }

    @Test fun reopeningResetsBackgroundDeadline() {
        now = 30 * 60_000L
        assertTrue(lifetime.shouldStop(false))
        lifetime.appForeground(true)
        assertFalse(lifetime.shouldStop(false))
        lifetime.appForeground(false)
        now += 29 * 60_000L
        assertFalse(lifetime.shouldStop(false))
    }
}
