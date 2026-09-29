package app.cablegram.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackPreferencesTest {
    private val tracks = listOf(
        PlayerTrack(3, "English"),
        PlayerTrack(7, "Deutsch"),
        PlayerTrack(11, "فارسی"),
    )

    @Test
    fun `off preference disables subtitles`() {
        assertNull(matchSubtitleTrack(SubtitlePreference(off = true, label = "English"), tracks))
    }

    @Test
    fun `matches saved label even when vlc ids change`() {
        val preference = SubtitlePreference(trackId = "99", label = "Deutsch")
        assertEquals("7", matchSubtitleTrack(preference, tracks))
    }

    @Test
    fun `falls back to saved id when labels differ`() {
        val preference = SubtitlePreference(trackId = "11", label = "Persian")
        assertEquals("11", matchSubtitleTrack(preference, tracks))
    }

    @Test
    fun `returns null when nothing matches`() {
        assertNull(matchSubtitleTrack(SubtitlePreference(trackId = "4", label = "Francais"), tracks))
    }
}
