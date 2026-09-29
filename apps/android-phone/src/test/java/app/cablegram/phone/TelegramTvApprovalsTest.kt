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
