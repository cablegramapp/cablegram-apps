package app.cablegram.phone

import org.junit.Assert.*
import org.junit.Test

class ArtworkDraftTest {
    private val base = LibraryItem("video", "Personal title", "file.mp4", importedAt = "2026-10-03", year = 2020,
        overview = "Personal summary", posterPath = "/current.jpg", artworkUserSelected = true, metadataRevision = 4)

    @Test fun `opening and cancelling has no changes including artwork ownership`() {
        val draft = ArtworkDraft(base, 1)
        assertFalse(draft.changed)
        assertEquals(CoverChoice.Keep, draft.cover)
        assertEquals(base, commitArtworkDraft(base, draft))
    }
    @Test fun `title only preserves cover and concurrent summary`() {
        val concurrent = base.copy(overview = "Updated on TV", metadataRevision = 5)
        val saved = commitArtworkDraft(concurrent, ArtworkDraft(base, 1, title = "New title"))
        assertEquals("New title", saved.title)
        assertEquals("Updated on TV", saved.overview)
        assertEquals(base.posterPath, saved.posterPath)
        assertTrue(saved.artworkUserSelected)
        assertEquals(4, saved.metadataRevision) // draft revision retained for server conflict detection
        assertEquals(setOf("title"), saved.pendingMetadataFields)
    }
    @Test fun `cover only does not claim details or rewind metadata revision`() {
        val current = base.copy(title = "Shared correction", metadataRevision = 6)
        val saved = commitArtworkDraft(current, ArtworkDraft(base, 1, cover = CoverChoice.Frame("/frame.jpg")))
        assertEquals(current, saved) // artwork file commit is independent
    }
    @Test fun `empty upstream summary is not an edit and cover only preserves concurrent summary`() {
        val upstream = base.copy(overview = "")
        val draft = ArtworkDraft(upstream, 1)
        assertFalse(draft.changed)
        assertEquals(upstream, commitArtworkDraft(upstream, draft))
        val current = upstream.copy(overview = "Updated elsewhere", metadataRevision = 6)
        val saved = commitArtworkDraft(current, draft.copy(cover = CoverChoice.Frame("/frame.jpg")))
        assertEquals(current, saved)
    }
    @Test fun `accepted details override reviewed owned fields but search candidate does not`() {
        val match = CatalogMetadata("Dune", year = 2021, tmdbId = 438631, overview = "Catalog summary", matchStatus = "matched")
        val owned = base.copy(userMetadataFields = setOf("title", "overview"))
        assertEquals(owned, commitArtworkDraft(owned, ArtworkDraft(owned, 1, candidate = match)))
        val chosen = ArtworkDraft(owned, 1, candidate = match, acceptedMatch = match, useCatalogDetails = true,
            title = "Custom Dune title", year = "2021", overview = "Catalog summary")
        val saved = commitArtworkDraft(owned, chosen)
        assertEquals("Custom Dune title", saved.title)
        assertEquals(438631, saved.tmdbId)
        assertEquals("Catalog summary", saved.overview)
        assertEquals(owned.posterPath, saved.posterPath)
    }
    @Test fun `new search never changes the accepted identity`() {
        val chosen = CatalogMetadata("Dune", tmdbId = 1)
        val result = CatalogMetadata("Another film", tmdbId = 2)
        val saved = commitArtworkDraft(base, ArtworkDraft(base, 1, candidate = result, acceptedMatch = chosen, useCatalogDetails = true))
        assertEquals(1, saved.tmdbId)
    }
    @Test fun `normalized episode fields identify duplicate and specials`() {
        val match = CatalogMetadata("Series", mediaType = "tv", tmdbId = 42)
        val draft = ArtworkDraft(base, 1, candidate = match, acceptedMatch = match, useCatalogDetails = true,
            mediaType = "tv", season = "0", episode = "1")
        val existing = base.copy(id = "other", title = "Unrelated display text", mediaType = "tv", tmdbId = 42, seasonNumber = 0, episodeNumber = 1)
        assertTrue(hasDuplicateEpisode(listOf(existing), draft))
        assertFalse(draft.copy(duplicate = true).valid)
        assertTrue(draft.copy(duplicate = true, keepBoth = true).valid)
        assertFalse(hasDuplicateEpisode(listOf(existing.copy(tmdbId = 43)), draft))
    }
    @Test fun `invalid year or incomplete episode cannot save`() {
        assertFalse(ArtworkDraft(base, 1, year = "0").valid)
        assertFalse(ArtworkDraft(base, 1, mediaType = "tv", useCatalogDetails = true).valid)
        assertTrue(ArtworkDraft(base, 1, year = "").valid)
    }
}
