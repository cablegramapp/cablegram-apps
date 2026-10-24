package app.cablegram.phone.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider
import android.content.Context

@RunWith(RobolectricTestRunner::class)
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
        val msg = "Failed to load /sdcard/Movies/Inception.2010.1080p.mkv while playing"
        val scrubbed = SentryScrubber.scrubMessage(msg)

        assertFalse(scrubbed.contains("Inception.2010.1080p.mkv"))
        assertFalse(scrubbed.contains("/sdcard/Movies/"))
        assertTrue(scrubbed.contains("[PATH_REDACTED]"))
    }

    @Test
    fun `diagnostics preference defaults to true and updates cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(DiagnosticsPreferences.isCrashReportingEnabled(context))

        DiagnosticsPreferences.setCrashReportingEnabled(context, false)
        assertFalse(DiagnosticsPreferences.isCrashReportingEnabled(context))

        DiagnosticsPreferences.setCrashReportingEnabled(context, true)
        assertTrue(DiagnosticsPreferences.isCrashReportingEnabled(context))
    }
}
