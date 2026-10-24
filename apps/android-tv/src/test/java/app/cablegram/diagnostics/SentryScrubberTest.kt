package app.cablegram.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryScrubberTest {

    @Test
    fun `scrubUrl redacts sensitive query parameters like token, password, and code`() {
        val url = "https://api.cablegram.app/auth/callback?token=supersecret&code=123456&session=safeSession"
        val scrubbed = SentryScrubber.scrubUrl(url)

        assertTrue(scrubbed.contains("token=REDACTED"))
        assertTrue(scrubbed.contains("code=REDACTED"))
        assertTrue(scrubbed.contains("session=safeSession"))
        assertFalse(scrubbed.contains("supersecret"))
        assertFalse(scrubbed.contains("123456"))
    }

    @Test
    fun `scrubUrl redacts tg login links`() {
        val tgLink = "tg://login?token=AQAA_some_very_secret_token_value"
        val scrubbed = SentryScrubber.scrubUrl(tgLink)
        assertEquals("tg://login?token=REDACTED", scrubbed)
    }

    @Test
    fun `scrubMessage removes media filenames and personal paths`() {
        val msg = "VLC Error loading /storage/emulated/0/Movies/Shogun.S01E01.1080p.mkv"
        val scrubbed = SentryScrubber.scrubMessage(msg)

        assertFalse(scrubbed.contains("Shogun.S01E01.1080p.mkv"))
        assertFalse(scrubbed.contains("/storage/emulated/0/Movies/"))
        assertTrue(scrubbed.contains("[PATH_REDACTED]"))
    }
}
