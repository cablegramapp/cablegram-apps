package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashSafeImportTest {
    private val now = 1_000_000L
    private val old = now - 10 * 60_000L

    private fun swept(files: List<VideoFileInfo>, referenced: Set<String> = emptySet(), importing: Set<String> = emptySet()) =
        orphanedVideoFiles(files, referenced, importing, now)

    @Test
    fun `a part file is deleted`() {
        assertEquals(listOf("a.mp4.part"), swept(listOf(VideoFileInfo("a.mp4.part", old)), referenced = setOf("a.mp4")))
    }

    @Test
    fun `a file no library row points to is deleted`() {
        assertEquals(listOf("orphan.mp4"), swept(listOf(VideoFileInfo("orphan.mp4", old), VideoFileInfo("kept.mp4", old)), referenced = setOf("kept.mp4")))
    }

    @Test
    fun `a referenced file is kept`() {
        assertEquals(emptyList<String>(), swept(listOf(VideoFileInfo("kept.mp4", old)), referenced = setOf("kept.mp4")))
    }

    @Test
    fun `a file being imported or just written is left alone`() {
        val files = listOf(VideoFileInfo("live.mp4.part", old), VideoFileInfo("live.mp4", old), VideoFileInfo("fresh.mp4", now - 5_000))
        assertEquals(emptyList<String>(), swept(files, importing = setOf("live.mp4")))
    }

    @Test
    fun `resume finishes a registered import, copies a readable one and drops an unreadable one`() {
        assertEquals(SharedImportResume.Finish, sharedImportResume(alreadyRegistered = true, sourceReadable = false))
        assertEquals(SharedImportResume.Finish, sharedImportResume(alreadyRegistered = true, sourceReadable = true))
        assertEquals(SharedImportResume.Copy, sharedImportResume(alreadyRegistered = false, sourceReadable = true))
        assertEquals(SharedImportResume.Drop, sharedImportResume(alreadyRegistered = false, sourceReadable = false))
    }
}
