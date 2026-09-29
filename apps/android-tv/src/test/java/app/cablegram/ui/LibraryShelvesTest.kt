package app.cablegram.ui

import app.cablegram.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryShelvesTest {
    @Test
    fun `orders continue watching recently added my list then genres`() {
        val videos = listOf(
            video("inception", "Inception", genres = listOf("Science Fiction", "Action"), resume = 40, added = "2026-09-01T12:00:00Z", inMyList = true),
            video("up", "Up", genres = listOf("Animation"), added = "2026-09-08T12:00:00Z"),
        )
        val shelves = summarizeLibraryShelves(videos)
        assertEquals(
            listOf("continue", "recent", "mylist", "genre:Action", "genre:Animation", "genre:Science Fiction"),
            shelves.map { it.id },
        )
        assertEquals(
            listOf("Continue Watching", "Recently Added", "My List", "Action", "Animation", "Science Fiction"),
            shelves.map { it.title },
        )
    }

    @Test
    fun `same poster can appear on several rows`() {
        val videos = listOf(
            video("inception", "Inception", genres = listOf("Action", "Drama"), resume = 12, added = "2026-09-01T12:00:00Z", inMyList = true),
        )
        val shelves = summarizeLibraryShelves(videos)
        val rowsWithInception = shelves.filter { "inception" in it.entryIds }.map { it.id }
        assertEquals(listOf("continue", "recent", "mylist", "genre:Action", "genre:Drama"), rowsWithInception)
    }

    @Test
    fun `continue watching only includes in-progress titles`() {
        val videos = listOf(
            video("done", "Finished", genres = listOf("Drama"), resume = 0),
            video("mid", "Halfway", genres = listOf("Drama"), resume = 80),
        )
        val continueRow = summarizeLibraryShelves(videos).single { it.id == "continue" }
        assertEquals(listOf("mid"), continueRow.entryIds)
        assertTrue(summarizeLibraryShelves(videos).none { it.id == "mylist" })
    }

    @Test
    fun `my list row only includes saved titles`() {
        val videos = listOf(
            video("kept", "Kept", genres = listOf("Drama"), inMyList = true),
            video("other", "Other", genres = listOf("Drama")),
        )
        val myList = summarizeLibraryShelves(videos).single { it.id == "mylist" }
        assertEquals(listOf("kept"), myList.entryIds)
    }

    @Test
    fun `my list destination excludes unsaved titles`() {
        val videos = listOf(
            video("kept", "Kept", genres = listOf("Drama"), inMyList = true),
            video("other", "Other", genres = listOf("Drama")),
        )

        val visibleIds = summarizeLibraryShelves(videos, selectedType = "mylist")
            .flatMap { it.entryIds }
            .distinct()

        assertEquals(listOf("kept"), visibleIds)
    }

    @Test
    fun `continue and my list destinations are single named shelves`() {
        val videos = listOf(
            video("kept", "Kept", genres = listOf("Drama"), resume = 30, inMyList = true),
            video("other", "Other", genres = listOf("Drama"), resume = 10),
        )

        assertEquals(listOf("mylist"), summarizeLibraryShelves(videos, selectedType = "mylist").map { it.id })
        val continueShelves = summarizeLibraryShelves(videos, selectedType = "watchlist")
        assertEquals(listOf("continue"), continueShelves.map { it.id })
        assertEquals(listOf("kept", "other"), continueShelves.single().entryIds)
    }

    private fun video(
        id: String,
        title: String,
        genres: List<String>,
        resume: Int = 0,
        added: String = "2026-09-01T12:00:00Z",
        inMyList: Boolean = false,
    ) = Video(
        id = id,
        title = title,
        addedAtTimestamp = added,
        resumePositionSeconds = resume,
        genres = genres,
        mediaType = "movie",
        inMyList = inMyList,
    )
}
