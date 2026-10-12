package app.cablegram.data

import app.cablegram.ui.preflightMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRenewalTest {
    private val drive = PlaybackResponse(
        status = "ready", url = "https://www.googleapis.com/drive/v3/files/f1?alt=media",
        headers = mapOf("Authorization" to "Bearer old"), expiresAt = "2026-10-02T12:50:00Z",
    )

    @Test fun `a new bearer token on the same Drive URL reloads the player`() {
        assertTrue(playbackNeedsReload(drive, drive.copy(headers = mapOf("Authorization" to "Bearer new"), expiresAt = "2026-10-02T13:40:00Z")))
    }

    @Test fun `a new signed URL reloads the player`() {
        val r2 = PlaybackResponse(status = "ready", url = "https://acct.r2.cloudflarestorage.com/a?X-Amz-Signature=1")
        assertTrue(playbackNeedsReload(r2, r2.copy(url = "https://acct.r2.cloudflarestorage.com/a?X-Amz-Signature=2")))
    }

    @Test fun `the same URL and header, only a later expiry, does not reload`() {
        assertFalse(playbackNeedsReload(drive, drive.copy(expiresAt = "2026-10-02T13:40:00Z")))
    }

    @Test fun `an answer with no URL never reloads`() {
        assertFalse(playbackNeedsReload(drive, PlaybackResponse(status = "preparing")))
    }

    @Test fun `a cloud error is explained as cloud, not as a phone`() {
        assertTrue(preflightMessage(404, cloud = true).contains("cloud storage"))
        assertTrue(preflightMessage(401, cloud = true).contains("sign in again"))
        assertTrue(preflightMessage(403, cloud = true).contains("limiting downloads"))
        assertTrue(preflightMessage(429, cloud = true).contains("limiting downloads"))
        assertFalse(preflightMessage(404, cloud = true).contains("phone"))
        assertEquals("This video isn't on your phone anymore. Import it again on the phone, or remove it from the library.", preflightMessage(404))
        assertTrue(preflightMessage(403).contains("Your phone didn't allow"))
    }
}
