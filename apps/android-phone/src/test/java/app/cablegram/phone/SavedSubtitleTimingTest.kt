package app.cablegram.phone

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSubtitleTimingTest {
    @Test
    fun `a saved subtitle reopens with the timing it plays with now`() {
        val body = """{"id":"s1","language":"nl","offset":1.5,"scale":1.001,"cues":[{"start":10.0,"end":12.0,"text":"Hallo"}],"extra":true}"""
        val timing = Json { ignoreUnknownKeys = true }.decodeFromString<SavedSubtitleTiming>(body)
        assertEquals(1.5, timing.offset, 0.0)
        assertEquals(1.001, timing.scale, 0.0)
        assertEquals(listOf(SubtitleCue(10.0, 12.0, "Hallo")), timing.cues)
    }

    @Test
    fun `a changed video or a removed subtitle says what to do`() {
        assertTrue(savedTimingError("source_identity_changed").contains("attach the subtitle again"))
        assertTrue(savedTimingError("subtitle_not_found").contains("isn't saved"))
        assertTrue(savedTimingError(null).contains("try again"))
    }
}
