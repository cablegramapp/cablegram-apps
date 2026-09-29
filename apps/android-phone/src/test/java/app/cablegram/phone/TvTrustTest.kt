package app.cablegram.phone

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvTrustTest {
    private val zone = ZoneId.of("Europe/Paris")

    @Test
    fun `checkout defaults to tomorrow at noon local time`() {
        val now = ZonedDateTime.of(2026, 10, 1, 22, 30, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 10, 2, 12, 0, 0, 0, zone).toInstant(), TvTrustRules.defaultCheckout(now))
        assertEquals(4, TvTrustRules.checkoutChoices(now).size)
        assertEquals(ZonedDateTime.of(2026, 10, 8, 12, 0, 0, 0, zone).toInstant(), TvTrustRules.checkoutChoices(now).last().second)
    }

    @Test
    fun `direct Telegram needs a link older than 24 hours`() {
        val now = Instant.parse("2026-10-02T10:00:00Z")
        assertFalse(TvTrustRules.directAllowed(null, now))
        assertFalse(TvTrustRules.directAllowed(Instant.parse("2026-10-01T10:00:01Z"), now))
        assertTrue(TvTrustRules.directAllowed(Instant.parse("2026-10-01T10:00:00Z"), now))
    }

    @Test
    fun `the reminder is due only in the last hour of a temporary TV`() {
        val end = Instant.parse("2026-10-02T10:00:00Z")
        val trust = TvTrust(temporary = true, expiresAt = end)
        assertFalse(TvTrustRules.reminderDue(trust, end.minusSeconds(3601)))
        assertTrue(TvTrustRules.reminderDue(trust, end.minusSeconds(3600)))
        assertTrue(TvTrustRules.reminderDue(trust, end.minusSeconds(1)))
        assertFalse(TvTrustRules.reminderDue(trust, end))
        assertFalse(TvTrustRules.reminderDue(TvTrust.Home, end.minusSeconds(60)))
    }
}
