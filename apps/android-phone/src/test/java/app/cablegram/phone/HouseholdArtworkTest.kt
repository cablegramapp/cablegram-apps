package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CAB-29: a cover made on the phone reaches the server only when the user saved that exact cover. */
class HouseholdArtworkTest {
    private val frame = LibraryItem(
        id = "local-1", title = "Birthday", filename = "birthday.mp4", fileName = "local-1.mp4",
        importedAt = "2026-10-07T00:00:00Z", copied = false,
        posterPath = "/posters/local-1.jpg", posterVersion = 2, artworkOrigin = ARTWORK_FRAME,
        catalogItemId = "server-1",
    )

    @Test fun `a frame stays on the phone by default`() {
        assertTrue(canSaveArtworkToHousehold(frame))
        assertFalse(artworkSavedToHousehold(frame))
        assertEquals(HouseholdArtworkStep.None, householdArtworkStep(frame))
    }

    @Test fun `a saved cover is uploaded once`() {
        val saved = frame.copy(householdArtworkVersion = 2)
        assertEquals(HouseholdArtworkStep.Upload, householdArtworkStep(saved))
        assertEquals(HouseholdArtworkStep.None, householdArtworkStep(saved.copy(householdArtworkUploadedVersion = 2)))
    }

    @Test fun `changing the cover after saving removes the old upload`() {
        val changed = frame.copy(householdArtworkVersion = 2, householdArtworkUploadedVersion = 2, posterVersion = 3)
        assertFalse(artworkSavedToHousehold(changed))
        assertEquals(HouseholdArtworkStep.Remove, householdArtworkStep(changed))
    }

    @Test fun `withdrawing or making the video private removes the upload`() {
        val uploaded = frame.copy(householdArtworkVersion = 2, householdArtworkUploadedVersion = 2)
        assertEquals(HouseholdArtworkStep.Remove, householdArtworkStep(uploaded.copy(householdArtworkVersion = null)))
        assertEquals(HouseholdArtworkStep.Remove, householdArtworkStep(uploaded.copy(isPrivate = true)))
    }

    @Test fun `catalog covers and placeholders are not offered`() {
        assertFalse(canSaveArtworkToHousehold(frame.copy(posterUrl = "https://image.tmdb.org/t/p/w500/x.jpg", artworkOrigin = ARTWORK_CATALOG)))
        assertFalse(canSaveArtworkToHousehold(frame.copy(posterPath = null)))
        assertFalse(canSaveArtworkToHousehold(frame.copy(isPrivate = true)))
    }

    @Test fun `the server echoing the saved cover back keeps it saved`() {
        val echoed = frame.copy(householdArtworkVersion = 2, householdArtworkUploadedVersion = 2,
            posterUrl = "https://api.cablegram.app/api/catalog/items/server-1/poster")
        assertTrue(isHouseholdPosterUrl(echoed.posterUrl))
        assertTrue(artworkSavedToHousehold(echoed))
        assertEquals(HouseholdArtworkStep.None, householdArtworkStep(echoed))
        assertFalse(isHouseholdPosterUrl("https://image.tmdb.org/t/p/w500/poster.jpg"))
    }
}
