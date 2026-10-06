package app.cablegram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSubtitlesTest {
    private fun response(vararg tracks: SubtitleTrack) = PlaybackResponse(status = "ready", subtitles = tracks.toList())

    @Test fun aSavedSubtitleGetsAReadableTitleAndKeepsItsId() {
        val smart = response(SubtitleTrack("row-1", language = "fa", label = "Persian", isDefault = true, url = "/x", content = "WEBVTT\n")).smartSubtitles().single()
        assertEquals("row-1", smart.id); assertEquals("Persian · Cablegram", smart.label)
    }

    @Test fun theLanguageCodeIsTheFallbackTitleAndAnEmptyDocumentIsSkipped() {
        val list = response(
            SubtitleTrack("a", language = "de", label = null, url = "/x", content = "WEBVTT\n"),
            SubtitleTrack("b", language = "fa", label = "Persian", url = "/y", content = " "),
            SubtitleTrack("c", language = null, label = null, url = "/z", content = null),
        ).smartSubtitles()
        assertEquals(listOf("de · Cablegram"), list.map { it.label }); assertTrue(list.none { it.content.isBlank() })
    }
}
