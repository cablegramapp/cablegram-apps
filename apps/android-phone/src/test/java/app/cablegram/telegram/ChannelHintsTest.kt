package app.cablegram.telegram

import org.junit.Assert.assertEquals
import org.junit.Test

// Shared by apps/android-phone and apps/android-tv tests (CAB-60). Keep both copies identical.

class ChannelHintsTest {
    private val chat = -1004481882279L
    private val nickels = "AgADZR4AAh5DeFI"

    private fun note(text: String) = TgMessage(chat, 50, null, text)

    private fun video(unique: String, caption: String) = TgVideo(
        chatId = chat, messageId = 40, file = TgFile(1, 100, unique, 0, 0, 0, false), fileName = "N.2026.mkv",
        caption = caption, mimeType = "video/x-matroska", durationSeconds = 0, supportsStreaming = false, date = 0,
    )

    @Test
    fun `a reply naming a library video and a movie describes that video`() {
        val reply = note("✅ Video enrichment completed.\nvideo_id: #$nickels\n#movie_1313826")
        assertEquals(listOf(TgTmdbHint(nickels, "movie", 1313826)), ChannelHints.from(reply, setOf(nickels)))
    }

    @Test
    fun `a card with the same title as a tag and a link is one reference`() {
        val card = note("Nickels (2026)\n#movie_1313826\nvideo_id: #$nickels\nhttps://www.themoviedb.org/movie/1313826-nickels")
        assertEquals(listOf(TgTmdbHint(nickels, "movie", 1313826)), ChannelHints.from(card, setOf(nickels)))
    }

    @Test
    fun `episodes keep their season and episode, from a tag or a link`() {
        assertEquals(
            listOf(TgTmdbHint(nickels, "tv", 42, 2, 3)),
            ChannelHints.from(note("#$nickels #tv_42_s02e03"), setOf(nickels)),
        )
        assertEquals(
            listOf(TgTmdbHint(nickels, "tv", 1399, 1, 9)),
            ChannelHints.from(note("#$nickels https://www.themoviedb.org/tv/1399-game-of-thrones/season/1/episode/9"), setOf(nickels)),
        )
    }

    @Test
    fun `a TMDB reference in a video's own caption describes it`() {
        val captioned = TgMessage(chat, 40, video(nickels, "Nickels (2026) #movie_1313826"), "Nickels (2026) #movie_1313826")
        assertEquals(listOf(TgTmdbHint(nickels, "movie", 1313826)), ChannelHints.from(captioned, emptySet()))
    }

    @Test
    fun `nothing without a library video, a reference, or with two different titles`() {
        assertEquals(emptyList<TgTmdbHint>(), ChannelHints.from(note("#AgADunknown1 #movie_1"), setOf(nickels)))
        assertEquals(emptyList<TgTmdbHint>(), ChannelHints.from(note("⏳ Processing video… video_id: #$nickels"), setOf(nickels)))
        assertEquals(emptyList<TgTmdbHint>(), ChannelHints.from(note("#$nickels #movie_1 #movie_2"), setOf(nickels)))
    }
}
