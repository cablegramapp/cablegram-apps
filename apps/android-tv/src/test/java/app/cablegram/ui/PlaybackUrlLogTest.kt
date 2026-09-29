package app.cablegram.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackUrlLogTest {
    @Test
    fun `redacts signature and upstream token from playback URLs`() {
        val logged = playbackUrlForLog(
            "https://cdn.cablegram.app/v/11111111-1111-4111-8111-111111111111" +
                "?uid=22222222-2222-4222-8222-222222222222&exp=1770000000&fuid=tg-file&k=videos/a/b/source" +
                "&sig=super-secret-hmac&u=encrypted-upstream",
        )
        assertEquals(
            "https://cdn.cablegram.app/v/11111111-1111-4111-8111-111111111111" +
                "?uid=22222222-2222-4222-8222-222222222222&exp=1770000000&fuid=tg-file&k=videos/a/b/source" +
                "&sig=redacted&u=redacted",
            logged,
        )
        assertFalse(logged.contains("super-secret-hmac"))
        assertFalse(logged.contains("encrypted-upstream"))
    }
}
