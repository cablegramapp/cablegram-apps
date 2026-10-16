package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramTvApprovalsTest {
    private fun request(id: String, link: String = "tg://login?token=AAAAAAAAAAAAAAAA") =
        PendingTvLogin(requestId = id, tvDeviceId = "tv-$id", tvName = "Living room", loginLink = link)

    @Test
    fun `allow approves the request that was shown, with its latest link`() {
        val fresher = request("a", "tg://login?token=BBBBBBBBBBBBBBBB")
        val chosen = TelegramTvApprovals.requestToApprove(listOf(request("other"), fresher), "a")
        assertEquals(fresher, chosen)
    }

    @Test
    fun `allow never falls back to another request the server lists`() {
        assertNull(TelegramTvApprovals.requestToApprove(listOf(request("other")), "a"))
        assertNull(TelegramTvApprovals.requestToApprove(emptyList(), "a"))
        assertNull(TelegramTvApprovals.requestToApprove(null, "a"))
    }
}

class RecentTvPasswordsTest {
    @Test
    fun `a TV the password was just sent to signs in without asking again, only that TV and only for a while`() {
        RecentTvPasswords.sent("tv-1", now = 1_000L)
        org.junit.Assert.assertTrue(RecentTvPasswords.covers("tv-1", now = 1_000L + 60_000L))
        org.junit.Assert.assertFalse(RecentTvPasswords.covers("tv-2", now = 1_000L))
        org.junit.Assert.assertFalse(RecentTvPasswords.covers("tv-1", now = 1_000L + RecentTvPasswords.WINDOW_MS))
    }

    @Test
    fun `the allowance is used up by the approval`() {
        RecentTvPasswords.sent("tv-3", now = 1_000L)
        RecentTvPasswords.used("tv-3")
        org.junit.Assert.assertFalse(RecentTvPasswords.covers("tv-3", now = 1_000L))
    }
}
