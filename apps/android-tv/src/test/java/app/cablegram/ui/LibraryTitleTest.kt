package app.cablegram.ui

import app.cablegram.data.Video
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTitleTest {
    @Test
    fun `hides noisy caption until catalog trim finishes`() {
        val pending = Video(
            id = "video-1",
            title = "WEBRip x265 noisy caption 1080p",
            addedAtTimestamp = "2026-09-01T12:00:00Z",
            tier = "ingesting",
            matchStatus = "pending",
            ingestProgress = 6,
            ingestStage = "catalog",
        )
        assertEquals("Receiving...", pending.displayTitle())
    }

    @Test
    fun `shows cleaned title after catalog`() {
        val ready = Video(
            id = "video-1",
            title = "Inception",
            addedAtTimestamp = "2026-09-01T12:00:00Z",
            tier = "ingesting",
            matchStatus = "matched",
            ingestProgress = 20,
            ingestStage = "downloading",
        )
        assertEquals("Inception", ready.displayTitle())
    }

    @Test
    fun `hides storage identifiers from the living room UI`() {
        val video = Video(
            id = "video-1",
            title = "4_5854897790114603224",
            addedAtTimestamp = "2026-09-01T12:00:00Z",
            tier = "hot",
            matchStatus = "unmatched",
        )

        assertEquals("Personal video", video.displayTitle())
    }
}
