package app.cablegram

import app.cablegram.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibrarySyncSignatureTest {
    private val original = Video(id = "film", title = "Same title", releaseYear = 2019, overview = "Original")

    @Test fun metadataOnlyCorrectionsRefreshTheLibrary() {
        val previous = librarySyncSignature(listOf(original))
        for (correction in listOf(
            original.copy(releaseYear = 2021),
            original.copy(overview = "Corrected"),
            original.copy(mediaType = "tv"),
            original.copy(releaseYear = null),
            original.copy(overview = null),
        )) {
            assertNotEquals(previous, librarySyncSignature(listOf(correction)))
        }
    }

    @Test fun unchangedResponseKeepsTheSameSignature() {
        assertEquals(librarySyncSignature(listOf(original)), librarySyncSignature(listOf(original.copy())))
    }
}
