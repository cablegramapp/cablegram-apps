package app.cablegram.ui

import app.cablegram.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateThumbnailTest {
    @Test
    fun `a private title always gets the same stand-in`() {
        assertEquals(privateThumbnail("item-1"), privateThumbnail("item-1"))
    }

    @Test
    fun `stand-ins vary across titles`() {
        val pictures = (1..60).map { privateThumbnail("item-$it") }.toSet()
        assertTrue(pictures.size > 1)
    }

    @Test
    fun `a private title draws the stand-in, never a poster URL`() {
        val video = Video(id = "item-1", posterUrl = "https://cdn.example/og-image.jpg", isPrivate = true)
        assertEquals(privateThumbnail("item-1"), video.posterModel())
    }

    @Test
    fun `other titles draw their poster, and nothing for a blank one`() {
        assertEquals("https://cdn.example/p.jpg", Video(id = "a", posterUrl = "https://cdn.example/p.jpg").posterModel())
        assertNull(Video(id = "b", posterUrl = " ").posterModel())
    }
}
