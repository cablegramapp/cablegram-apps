package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGroupingTest {
    private fun item(
        id: String,
        title: String,
        type: String = "tv",
        tmdb: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        position: Int = 0,
        poster: String? = null,
        added: String = "2026-01-01T00:00:00Z",
    ) = LibraryItem(
        id = id, title = title, filename = "$id.mkv", mediaType = type, tmdbId = tmdb, seasonNumber = season,
        episodeNumber = episode, positionSeconds = position, posterUrl = poster, importedAt = added,
    )

    @Test
    fun `episodes of one series share one poster, films stay single`() {
        val entries = groupIntoEntries(
            listOf(
                item("a", "The Bear", tmdb = 136315, season = 1, episode = 2),
                item("m", "Dune", type = "movie"),
                item("b", "The Bear", tmdb = 136315, season = 1, episode = 1),
                item("c", "The Bear", tmdb = 136315, season = 2, episode = 1),
            ),
        )
        assertEquals(listOf("tmdb:136315", "m"), entries.map { it.key })
        val series = entries[0] as LibraryEntry.Series
        assertEquals("ordered by season then episode", listOf("b", "a", "c"), series.episodes.map { it.id })
        assertEquals("2 seasons · 3 episodes", series.summary())
        assertTrue(entries[1] is LibraryEntry.Film)
    }

    @Test
    fun `a series is placed where its first episode was, so shelf order is kept`() {
        val entries = groupIntoEntries(
            listOf(
                item("1", "Alpha", type = "movie"),
                item("2", "Show", tmdb = 7, episode = 1),
                item("3", "Beta", type = "movie"),
                item("4", "Show", tmdb = 7, episode = 2),
            ),
        )
        assertEquals(listOf("1", "tmdb:7", "3"), entries.map { it.key })
    }

    @Test
    fun `without a TMDB id the title decides, ignoring case and spaces`() {
        val entries = groupIntoEntries(
            listOf(item("a", "Gary", episode = 1), item("b", " gary ", episode = 2), item("c", "Other", episode = 1)),
        )
        assertEquals(2, entries.size)
        assertEquals(2, (entries.first() as LibraryEntry.Series).episodeCount)
    }

    @Test
    fun `the card leads with the episode in progress, else one that has a poster`() {
        val watching = groupIntoEntries(listOf(item("a", "S", tmdb = 1, episode = 1, poster = "p"), item("b", "S", tmdb = 1, episode = 2, position = 300)))
        assertEquals("b", (watching.single() as LibraryEntry.Series).lead.id)
        val poster = groupIntoEntries(listOf(item("a", "S", tmdb = 1, episode = 1), item("b", "S", tmdb = 1, episode = 2, poster = "p")))
        assertEquals("b", (poster.single() as LibraryEntry.Series).lead.id)
    }

    @Test
    fun `episode badges show season and episode when known`() {
        assertEquals("S2 · E5", episodeBadge(item("a", "S", season = 2, episode = 5)))
        assertEquals("E5", episodeBadge(item("a", "S", episode = 5)))
        assertEquals(null, episodeBadge(item("a", "S")))
    }
}
