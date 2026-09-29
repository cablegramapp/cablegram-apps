package app.cablegram.phone

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * How much the owner trusts a paired TV (spec 004 US8). A temporary TV (hotel, friend, rental) is
 * unpaired at [expiresAt] and holds no Telegram session unless [telegramDirect] was opted into.
 */
data class TvTrust(val temporary: Boolean = false, val expiresAt: Instant? = null, val telegramDirect: Boolean = false) {
    companion object {
        val Home = TvTrust()
    }
}

object TvTrustRules {
    /** Telegram will not let an account end a session created less than this long ago. */
    val FRESH_LINK: Duration = Duration.ofHours(24)
    val REMINDER_BEFORE: Duration = Duration.ofHours(1)
    val EXTEND_BY: Duration = Duration.ofDays(1)

    /** "Checkout": the next 12:00 at least a few hours away, so tomorrow at 12:00 unless it is early morning. */
    fun defaultCheckout(now: ZonedDateTime): Instant = checkoutAfterDays(now, 1)

    fun checkoutAfterDays(now: ZonedDateTime, days: Long): Instant =
        now.toLocalDate().plusDays(days).atTime(12, 0).atZone(now.zone).toInstant()

    /** Choices offered next to the default, each at 12:00 local time. */
    fun checkoutChoices(now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): List<Pair<String, Instant>> = listOf(
        "Tomorrow at 12:00" to checkoutAfterDays(now, 1),
        "In 2 days at 12:00" to checkoutAfterDays(now, 2),
        "In 3 days at 12:00" to checkoutAfterDays(now, 3),
        "In a week at 12:00" to checkoutAfterDays(now, 7),
    )

    /** False while the household's Telegram link is under 24 h old (`FRESH_RESET_AUTHORISATION_FORBIDDEN`). */
    fun directAllowed(linkedAt: Instant?, now: Instant): Boolean =
        linkedAt != null && Duration.between(linkedAt, now) >= FRESH_LINK

    /** A reminder is due in the hour before the end time, once; expired TVs are already unpaired. */
    fun reminderDue(trust: TvTrust, now: Instant): Boolean {
        val end = trust.expiresAt ?: return false
        return trust.temporary && now.isBefore(end) && !now.isBefore(end.minus(REMINDER_BEFORE))
    }
}
