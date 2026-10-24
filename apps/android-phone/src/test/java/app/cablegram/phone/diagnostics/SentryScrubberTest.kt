package app.cablegram.phone.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.SentryStackFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SentryScrubberTest {

    @Test
    fun `scrubUrl redacts sensitive parameters but preserves harmless ones like monkey and zipcode`() {
        val url = "https://api.cablegram.app/auth/callback?token=supersecret&code=123456&monkey=banana&zipcode=90210&api_key=mykey"
        val scrubbed = SentryScrubber.scrubUrl(url)

        assertTrue(scrubbed.contains("token=REDACTED"))
        assertTrue(scrubbed.contains("code=REDACTED"))
        assertTrue(scrubbed.contains("api_key=REDACTED"))
        assertTrue(scrubbed.contains("monkey=banana"))
        assertTrue(scrubbed.contains("zipcode=90210"))
        assertFalse(scrubbed.contains("supersecret"))
        assertFalse(scrubbed.contains("mykey"))
    }

    @Test
    fun `scrubUrl redacts embedded userinfo`() {
        val url = "https://admin:superSecretPassword@relay.cablegram.app/stream"
        val scrubbed = SentryScrubber.scrubUrl(url)

        assertFalse(scrubbed.contains("admin:superSecretPassword"))
        assertTrue(scrubbed.contains("[REDACTED]@relay.cablegram.app/stream"))
    }

    @Test
    fun `scrubUrl redacts tg deep links anywhere in message`() {
        val tgLink = "tg://login?token=AQAA_some_very_secret_token_value"
        assertEquals("tg://[REDACTED]", SentryScrubber.scrubUrl(tgLink))

        val midMessage = "User paired via tg://login?token=abc123secret on TV"
        val scrubbed = SentryScrubber.scrubMessage(midMessage)
        assertFalse(scrubbed.contains("abc123secret"))
        assertTrue(scrubbed.contains("tg://[REDACTED]"))
    }

    @Test
    fun `scrubMessage removes media filenames with spaces and various formats while preserving surrounding prose`() {
        val msgWithQuotes = "Failed to play \"My Holiday 2024.mp4\" due to decoder error"
        val scrubbedQuotes = SentryScrubber.scrubMessage(msgWithQuotes)
        assertEquals("Failed to play [MEDIA_FILE_REDACTED] due to decoder error", scrubbedQuotes)

        val msgSingle = "Failed to play holiday.mp4 due to decoder error"
        val scrubbedSingle = SentryScrubber.scrubMessage(msgSingle)
        assertEquals("Failed to play [MEDIA_FILE_REDACTED] due to decoder error", scrubbedSingle)

        val audioMsg = "Playing audio track soundtrack.flac and commentary.m4a"
        val scrubbedAudio = SentryScrubber.scrubMessage(audioMsg)
        assertFalse(scrubbedAudio.contains("soundtrack.flac"))
        assertFalse(scrubbedAudio.contains("commentary.m4a"))
        assertEquals("Playing audio track [MEDIA_FILE_REDACTED] and [MEDIA_FILE_REDACTED]", scrubbedAudio)

        val subMsg = "Subtitles loaded from custom_subs.ass and movie.sub"
        val scrubbedSubs = SentryScrubber.scrubMessage(subMsg)
        assertFalse(scrubbedSubs.contains("custom_subs.ass"))
        assertFalse(scrubbedSubs.contains("movie.sub"))
        assertEquals("Subtitles loaded from [MEDIA_FILE_REDACTED] and [MEDIA_FILE_REDACTED]", scrubbedSubs)
    }

    @Test
    fun `scrubMessage preserves typescript code references without treating index dot ts as media`() {
        val codeMsg = "Crash occurred at index.ts:42 in render()"
        val scrubbed = SentryScrubber.scrubMessage(codeMsg)
        assertTrue(scrubbed.contains("index.ts:42"))
    }

    @Test
    fun `scrubMessage removes storage paths with spaces`() {
        val pathMsg = "File error: /sdcard/Movies/My Film 2024.mkv could not be opened"
        val scrubbed = SentryScrubber.scrubMessage(pathMsg)
        assertFalse(scrubbed.contains("/sdcard/Movies"))
        assertFalse(scrubbed.contains("My Film 2024.mkv"))
        assertTrue(scrubbed.contains("[PATH_REDACTED]"))
    }

    @Test
    fun `scrubMessage redacts free text secrets and bearer tokens`() {
        val secretMsg = "Connection failed; password: superSecretPassword123, token=xyz789"
        val scrubbedSecret = SentryScrubber.scrubMessage(secretMsg)
        assertFalse(scrubbedSecret.contains("superSecretPassword123"))
        assertFalse(scrubbedSecret.contains("xyz789"))
        assertTrue(scrubbedSecret.contains("password: [REDACTED]"))
        assertTrue(scrubbedSecret.contains("token: [REDACTED]"))

        val authHeaderMsg = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        val scrubbedAuth = SentryScrubber.scrubMessage(authHeaderMsg)
        assertFalse(scrubbedAuth.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertTrue(scrubbedAuth.contains("Authorization: [REDACTED]"))

        val bearerMsg = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        val scrubbedBearer = SentryScrubber.scrubMessage(bearerMsg)
        assertFalse(scrubbedBearer.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertTrue(scrubbedBearer.contains("Bearer [REDACTED]"))
    }

    @Test
    fun `scrubMessage does not mangle arbitrary prose`() {
        val prose = "User reported: The TV was on standby and nothing appeared. Please help?"
        val scrubbed = SentryScrubber.scrubMessage(prose)
        assertEquals(prose, scrubbed)
    }

    @Test
    fun `scrubEvent sanitizes event message, exceptions, and breadcrumbs`() {
        val event = SentryEvent().apply {
            message = Message().apply {
                formatted = "Crash while playing My Video.mp4 at /storage/emulated/0/Movies/My Video.mp4"
            }
            exceptions = listOf(
                SentryException().apply {
                    value = "Decoder error for /sdcard/Movies/Holiday.mkv with token=secret123"
                    stacktrace = SentryStackTrace().apply {
                        frames = listOf(
                            SentryStackFrame().apply {
                                absPath = "/Users/developer/project/file.kt"
                            }
                        )
                    }
                }
            )
            breadcrumbs = listOf(
                Breadcrumb().apply {
                    message = "User opened /storage/emulated/0/DCIM/Family.mp4"
                    setData("file_path", "/sdcard/Downloads/Personal.mp4")
                    setData("status_code", 200)
                }
            )
        }

        val scrubbed = PhoneDiagnostics.scrubEvent(event)
        val msg = scrubbed.message?.formatted.orEmpty()
        assertFalse(msg.contains("My Video.mp4"))
        assertFalse(msg.contains("/storage/emulated/0/"))

        val exValue = scrubbed.exceptions?.firstOrNull()?.value.orEmpty()
        assertFalse(exValue.contains("Holiday.mkv"))
        assertFalse(exValue.contains("secret123"))

        val crumb = scrubbed.breadcrumbs?.firstOrNull()
        assertFalse(crumb?.message.orEmpty().contains("Family.mp4"))
        assertFalse((crumb?.getData("file_path") as? String).orEmpty().contains("Personal.mp4"))
        assertEquals(200, crumb?.getData("status_code"))
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

    @Test
    fun `sendProblemReport respects opt-out and does not leave Sentry enabled when crash reporting is disabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DiagnosticsPreferences.setCrashReportingEnabled(context, false)
        assertFalse(DiagnosticsPreferences.isCrashReportingEnabled(context))

        PhoneDiagnostics.sendProblemReport(context, "Test bug report description") { _ -> }

        assertFalse(io.sentry.Sentry.isEnabled())
    }

    @Test
    fun `scrubEvent scrubs tags and extras alongside message and exceptions`() {
        val event = SentryEvent().apply {
            setTag("server_url", "https://api.cablegram.app/endpoint?token=secret123")
            setTag("custom_tag", "Error playing \"My Video.mp4\"")
            setExtra("report_json", "{\"file\":\"/sdcard/Movies/secret.mp4\",\"token\":\"secret456\"}")
        }

        val scrubbed = PhoneDiagnostics.scrubEvent(event)

        val serverTag = scrubbed.getTag("server_url").orEmpty()
        assertTrue(serverTag.contains("token=REDACTED"))
        assertFalse(serverTag.contains("secret123"))

        val customTag = scrubbed.getTag("custom_tag").orEmpty()
        assertEquals("Error playing [MEDIA_FILE_REDACTED]", customTag)

        val reportJson = (scrubbed.getExtra("report_json") as? String).orEmpty()
        assertFalse(reportJson.contains("secret.mp4"))
        assertFalse(reportJson.contains("secret456"))
        assertTrue(reportJson.contains("[PATH_REDACTED]"))
        assertTrue(reportJson.contains("[REDACTED]"))
    }
}
