package app.cablegram.phone

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ManualMetadataTest {
    private fun item() = LibraryItem("local", "1917", "1917.mkv", year = 2019, overview = "Old summary", importedAt = "2026-10-03", posterPath = "/poster.jpg", catalogItemId = "shared")

    @Test fun `manual edits preserve artwork and source and survive serialization`() {
        val original = item()
        val edited = commitManualMetadata(original, " My title ", null, "tv", " My summary ")
        assertEquals("My title", edited.title)
        assertNull(edited.year)
        assertEquals("My summary", edited.overview)
        assertEquals(setOf("title", "year", "mediaType", "overview"), edited.pendingMetadataFields)
        assertEquals(original.posterPath, edited.posterPath)
        assertEquals(original.filename, edited.filename)
        assertEquals(edited, Json.decodeFromString<LibraryItem>(Json.encodeToString(edited)))
    }

    @Test fun `changing only year does not claim title or summary`() {
        val edited = commitManualMetadata(item(), "1917", 2020, "movie", "Old summary")
        assertEquals(setOf("year"), edited.pendingMetadataFields)
        assertEquals(setOf("year"), edited.userMetadataFields)
        assertEquals(item(), commitManualMetadata(item(), "1917", 2019, "movie", "Old summary"))
    }

    @Test fun `catalog refresh cannot erase pending edits or an intentional clear`() {
        val edited = commitManualMetadata(item(), "Personal video", null, "movie", "")
        val remote = RemoteCatalogItem("shared", title = "Wrong", year = 2024, overview = "Wrong", metadataRevision = 3)
        val merged = mergeManualMetadata(edited, remote)
        assertEquals("Personal video", merged.title)
        assertNull(merged.year)
        assertNull(merged.overview)
        assertEquals(0, merged.metadataRevision)
        assertEquals(edited.pendingMetadataFields, merged.pendingMetadataFields)
    }

    @Test fun `a later shared correction updates acknowledged manual fields`() {
        val original = item().copy(userMetadataFields = setOf("title", "year"), metadataRevision = 1)
        val remote = RemoteCatalogItem("shared", title = "New shared title", year = null, userMetadataFields = setOf("title", "year"), metadataRevision = 2)
        val merged = mergeManualMetadata(original, remote)
        assertEquals("New shared title", merged.title)
        assertNull(merged.year)
        assertEquals(2, merged.metadataRevision)
    }

    @Test fun `an acknowledgement cannot clear a newer edit made during sync`() {
        val sent = commitManualMetadata(item(), "First", 2019, "movie", "Old summary")
        val latest = commitManualMetadata(sent, "Second", 2019, "movie", "New summary")
        val remote = RemoteCatalogItem("shared", title = "First", year = 2019, overview = "Old summary", metadataRevision = 1, userMetadataFields = setOf("title"))
        val kept = acknowledgeManualMetadata(latest, sent, remote)
        assertEquals("Second", kept.title)
        assertEquals("New summary", kept.overview)
        assertEquals(latest.pendingMetadataFields, kept.pendingMetadataFields)
        assertEquals(1, kept.metadataRevision)
        val acknowledged = acknowledgeManualMetadata(sent, sent, remote)
        assertTrue(acknowledged.pendingMetadataFields.isEmpty())
        assertEquals(1, acknowledged.metadataRevision)
    }

    @Test fun `reviewing a conflict creates a new edit against the current revision`() {
        val conflicted = commitManualMetadata(item(), "My version", 2019, "movie", "Old summary").copy(metadataConflict = true, metadataRevision = 4)
        val reviewed = commitManualMetadata(conflicted, conflicted.title, conflicted.year, conflicted.mediaType, conflicted.overview)
        assertFalse(reviewed.metadataConflict)
        assertEquals(4, reviewed.metadataRevision)
        assertNotEquals(conflicted.metadataEditId, reviewed.metadataEditId)
        assertEquals(conflicted.pendingMetadataFields, reviewed.pendingMetadataFields)
    }

    @Test fun `older catalogs without year or summary do not clear legacy local metadata`() {
        val merged = mergeManualMetadata(item(), RemoteCatalogItem("shared", title = "1917"))
        assertEquals(2019, merged.year)
        assertEquals("Old summary", merged.overview)
    }

    @Test fun `a draft opened before a shared correction preserves untouched fields and checks its original revision`() {
        val base = item().copy(metadataRevision = 1)
        val current = base.copy(title = "Shared correction", year = 2020, overview = "Shared summary", metadataRevision = 2)
        val edited = commitManualMetadataDraft(current, base, "My title", base.year, base.mediaType, base.overview)
        assertEquals("My title", edited.title)
        assertEquals(2020, edited.year)
        assertEquals("Shared summary", edited.overview)
        assertEquals(setOf("title"), edited.pendingMetadataFields)
        assertEquals(1, edited.metadataRevision)
        assertEquals(current, commitManualMetadataDraft(current, base, base.title, base.year, base.mediaType, base.overview))
    }
}
