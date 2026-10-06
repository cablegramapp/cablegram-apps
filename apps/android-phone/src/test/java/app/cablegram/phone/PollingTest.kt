package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollingTest {
    @Test
    fun `the approvals poll is quick in the app and slow in the background`() {
        assertEquals(6_000L, approvalPollDelayMs(appVisible = true))
        assertEquals(15_000L, approvalPollDelayMs(appVisible = false))
    }

    @Test
    fun `a web import the server rejected for good is not retried`() {
        listOf("unsupported_site", "no_video", "unsafe_url", "invalid_url").forEach { assertTrue(it, isPermanentWebImportError(it)) }
    }

    @Test
    fun `a transient web import failure stays queued`() {
        listOf(null, "Request failed (503)", "timeout", "rate_limited", "web_import_failed").forEach { assertFalse(it ?: "null", isPermanentWebImportError(it)) }
    }
}
