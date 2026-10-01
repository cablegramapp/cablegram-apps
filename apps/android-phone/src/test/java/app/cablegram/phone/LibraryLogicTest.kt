package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {
    @Test fun `every imported filename requires explicit title confirmation`() {
        assertEquals(ImportMetadataAction.PromptForCorrection, importMetadataAction(item(title = "38", filename = "38.mp4")))
        assertEquals(ImportMetadataAction.PromptForCorrection, importMetadataAction(item(title = "a1b2c3d4e5f6", filename = "a1b2c3d4e5f6.mp4")))
        assertEquals(
            ImportMetadataAction.PromptForCorrection,
            importMetadataAction(
                item(title = "Dune", filename = "Dune.2021.mkv").copy(
                    posterPath = "/posters/dune.jpg",
                    artworkOrigin = ARTWORK_FRAME,
                ),
            ),
        )
    }

    @Test fun `readable titles still open frame chooser when thumbnail extraction failed`() {
        val withoutThumbnail = item(title = "Dune", filename = "Dune.2021.mkv").copy(
            posterPath = null,
            posterUrl = null,
            artworkOrigin = ARTWORK_PLACEHOLDER,
        )

        assertTrue(needsArtworkChoice(withoutThumbnail))
        assertEquals(ImportMetadataAction.PromptForCorrection, importMetadataAction(withoutThumbnail))
        assertFalse(needsArtworkChoice(withoutThumbnail.copy(posterUrl = "https://image.tmdb.org/dune.jpg")))
    }

    @Test fun `catalog artwork cannot replace a frame or user image`() {
        assertFalse(catalogMayReplaceArtwork(item().copy(artworkOrigin = ARTWORK_FRAME, artworkUserSelected = true)))
        assertFalse(catalogMayReplaceArtwork(item().copy(artworkOrigin = ARTWORK_USER_IMAGE, artworkUserSelected = true)))
        assertTrue(catalogMayReplaceArtwork(item().copy(artworkOrigin = ARTWORK_FRAME, artworkUserSelected = false)))
    }
    @Test
    fun `unreachable sources remain visible with an honest availability label`() {
        val unavailable = item().copy(sourceAvailable = false)
        assertEquals(listOf(unavailable), movies(listOf(unavailable)))
        assertEquals(StorageBadge.Unavailable, storageBadge(unavailable))
        assertEquals("Source unavailable · Title kept in library", storageStatusLine(unavailable))
        assertEquals(StorageBadge.Cloud, storageBadge(unavailable.copy(cloudObjectPresent = true)))
        assertEquals(StorageBadge.Local, storageBadge(unavailable.copy(sourceAvailable = true)))
        assertEquals(StorageBadge.NotOnPhone, storageBadge(unavailable.copy(householdOnly = true)))
        assertFalse(canSaveToCloud(unavailable))
        assertFalse(canRemoveLocalCopy(unavailable.copy(cloudObjectPresent = true)))
    }

    @Test
    fun `an unmatched Telegram title is offered once, after the server had time to match it`() {
        val now = java.time.Instant.parse("2026-10-01T12:00:00Z")
        val old = now.minusSeconds(120)
        assertTrue(needsTelegramMatchPrompt("unmatched", old, now, alreadyPrompted = false))
        assertFalse(needsTelegramMatchPrompt("unmatched", now.minusSeconds(30), now, false))
        assertFalse(needsTelegramMatchPrompt("matched", old, now, false))
        assertFalse(needsTelegramMatchPrompt("user_edited", old, now, false))
        assertFalse(needsTelegramMatchPrompt("unmatched", old, now, alreadyPrompted = true))
        assertFalse(needsTelegramMatchPrompt("unmatched", null, now, false))
    }

    @Test
    fun `save to Telegram is refused for private, unavailable and oversize videos before any upload`() {
        val mb = 1024L * 1024
        val ok = item()
        assertTrue(canSaveToTelegram(ok))
        assertFalse(canSaveToTelegram(ok.copy(isPrivate = true)))
        assertFalse(canSaveToTelegram(ok.copy(sourceAvailable = false)))
        assertFalse(canSaveToTelegram(ok.copy(telegramCopy = true)))
        assertFalse(canSaveToTelegram(ok.copy(transferStatus = TRANSFER_SAVING)))
        assertTrue("a failed save can be retried", canSaveToTelegram(ok.copy(transferStatus = TRANSFER_FAILED)))
        assertEquals(null, telegramUploadRefusal(1500 * mb, 2000 * mb))
        assertTrue(telegramUploadRefusal(2500 * mb, 2000 * mb)!!.contains("4 GB with Telegram Premium"))
        assertEquals(null, telegramUploadRefusal(2500 * mb, 4000 * mb))
        assertTrue(telegramUploadRefusal(0, 2000 * mb) != null)
    }

    @Test
    fun `free up space needs a verified Telegram copy, and the badge follows it`() {
        val saved = item().copy(telegramCopy = true, copied = true)
        assertTrue(canRemoveLocalCopy(saved))
        assertFalse(canRemoveLocalCopy(item().copy(copied = true)))
        assertEquals(StorageBadge.Both, storageBadge(saved))
        assertEquals(StorageBadge.Telegram, storageBadge(saved.copy(copied = false, sourceAvailable = false)))
    }

    @Test
    fun `free up space needs a verified R2 copy, the badge follows it, and it stops counting after a disconnect`() {
        val saved = item().copy(r2Copy = true, copied = true)
        assertTrue(canRemoveLocalCopy(saved))
        assertEquals(StorageBadge.Both, storageBadge(saved))
        assertEquals(StorageBadge.Cloud, storageBadge(saved.copy(copied = false, sourceAvailable = false)))
        assertFalse("already saved there", canSaveToCloud(saved))
        assertTrue(saved in cloudFiles(listOf(saved)))
        // The catalog clears the flag when the bucket is disconnected: the phone file is then the only copy.
        val disconnected = saved.copy(r2Copy = false)
        assertFalse(canRemoveLocalCopy(disconnected))
        assertTrue(canSaveToCloud(disconnected))
        // R2 bytes are the user's own storage and never count against Cablegram Cloud's 5 GB.
        assertEquals(0L, cloudMediaBytes(listOf(saved)))
    }

    @Test
    fun `telegram titles show a cloud badge and cannot be saved to cloud again`() {
        val telegram = item().copy(sourceKind = "telegram", copied = false, sourceAvailable = true)
        assertEquals(StorageBadge.Telegram, storageBadge(telegram))
        assertEquals("Cloud · Telegram", storageBadgeLabel(storageBadge(telegram)))
        assertFalse(canSaveToCloud(telegram))
        assertEquals(StorageBadge.Unavailable, storageBadge(telegram.copy(sourceAvailable = false)))
    }

    private fun item(
        title: String = "Dune",
        filename: String = "Dune.mkv",
        mediaType: String = "movie",
        year: Int? = 2021,
        position: Int = 0,
        duration: Int? = 9_000,
        copied: Boolean = true,
        cloud: Boolean = false,
        lastWatched: String? = null,
        importedAt: String = "2026-01-01T00:00:00Z",
        size: Long? = 100,
        status: String = TRANSFER_IDLE,
    ) = LibraryItem(
        id = title,
        title = title,
        filename = filename,
        durationSeconds = duration,
        fileSizeBytes = size,
        year = year,
        mediaType = mediaType,
        importedAt = importedAt,
        copied = copied,
        storageState = when {
            copied && cloud -> STORAGE_BOTH
            cloud -> STORAGE_CLOUD
            else -> STORAGE_LOCAL
        },
        positionSeconds = position,
        lastWatchedAt = lastWatched,
        cloudObjectPresent = cloud,
        transferStatus = status,
        genres = listOf("Sci-Fi"),
    )

    @Test
    fun `storage badges follow local cloud and both`() {
        assertEquals(StorageBadge.Local, storageBadge(item(copied = true, cloud = false)))
        assertEquals(StorageBadge.Cloud, storageBadge(item(copied = false, cloud = true)))
        assertEquals(StorageBadge.Both, storageBadge(item(copied = true, cloud = true)))
    }

    @Test
    fun `continue watching skips finished and untouched titles`() {
        val watching = item(title = "Dune", position = 1000, lastWatched = "2026-02-01")
        val done = item(title = "Done", position = 8900, duration = 9000)
        val fresh = item(title = "New", position = 0)
        assertEquals(listOf(watching), continueWatching(listOf(watching, done, fresh)))
    }

    @Test
    fun `search matches title filename year and genre`() {
        val dune = item()
        val other = item(title = "Arrival", filename = "arrival.mkv", year = 2016)
        assertEquals(listOf(dune), searchLibrary(listOf(dune, other), "dune"))
        assertEquals(listOf(dune), searchLibrary(listOf(dune, other), "2021"))
        assertEquals(listOf(dune, other), searchLibrary(listOf(dune, other), "sci"))
    }

    @Test
    fun `cannot remove local copy until a cloud object exists`() {
        assertFalse(canRemoveLocalCopy(item(copied = true, cloud = false)))
        assertTrue(canRemoveLocalCopy(item(copied = true, cloud = true)))
    }

    @Test
    fun `media byte totals ignore indexed-only files`() {
        val copied = item(copied = true, size = 80)
        val indexed = item(title = "Clip", copied = false, cloud = false, size = 40)
        assertEquals(80L, libraryMediaBytes(listOf(copied, indexed)))
    }

    @Test
    fun `save and free-up language follows three states`() {
        val local = item()
        val saving = item(title = "Saving", status = TRANSFER_SAVING)
        val both = item(copied = true, cloud = true)
        val failed = item(title = "Fail", status = TRANSFER_FAILED)
        assertEquals("On phone", storageStatusLine(local))
        assertEquals("On phone · Saving…", storageStatusLine(saving))
        assertEquals("Available on phone & cloud", storageStatusLine(both))
        assertEquals("Cloud save failed · Open to retry", storageStatusLine(failed))
        assertTrue(canSaveToCloud(local))
        assertFalse(canSaveToCloud(saving))
    }

    @Test
    fun `cablegram cloud quota rejects files that do not fit`() {
        val used = item(title = "Big", cloud = true, copied = false, size = 4L * 1024 * 1024 * 1024)
        val next = item(title = "Next", size = 2L * 1024 * 1024 * 1024)
        val available = cloudAvailableBytes(listOf(used))
        assertFalse(fitsInCloud(next, available))
        assertTrue(fitsInCloud(item(size = 100), available))
    }

    @Test
    fun `free up only offers titles already in the cloud`() {
        val local = item()
        val both = item(title = "Keep", copied = true, cloud = true, size = 50)
        assertEquals(50L, freeUpBytes(listOf(local, both)))
        assertEquals(listOf(both), freeUpCandidates(listOf(local, both)))
    }

    @Test
    fun `tv vs movie shelves`() {
        val movie = item()
        val show = item(title = "Better Call Saul", mediaType = "tv")
        assertEquals(listOf(movie), movies(listOf(movie, show)))
        assertEquals(listOf(show), tvShows(listOf(movie, show)))
    }

    @Test
    fun `browse filter keeps folders and videos only`() {
        val folder = BrowseEntry("d", "Movies", isDirectory = true, isVideo = false)
        val video = BrowseEntry("v", "Dune.mkv", isDirectory = false, isVideo = true)
        val image = BrowseEntry("i", "photo.jpg", isDirectory = false, isVideo = false, mime = "image/jpeg")
        val other = BrowseEntry("n", "notes.txt", isDirectory = false, isVideo = false)
        // Flow 2: browse always lists videos only — no "All files" mode.
        val videosOnly = visibleBrowseEntries(listOf(other, video, folder), videosOnly = true)
        assertEquals(listOf("Movies", "Dune.mkv"), videosOnly.map { it.name })
        val all = visibleBrowseEntries(listOf(other, video, folder, image), videosOnly = false)
        assertEquals(listOf("Movies", "Dune.mkv"), all.map { it.name })
        val hidden = BrowseEntry("a", "Android", isDirectory = true, isVideo = false)
        assertTrue(visibleBrowseEntries(listOf(hidden, folder), videosOnly = false).none { it.name == "Android" })
    }

    @Test
    fun `hash filenames need a title from the user`() {
        assertTrue(needsTitleInput("a1b2c3d4e5f6.mp4"))
        assertTrue(needsTitleInput("38.mp4"))
        assertTrue(needsTitleInput("1_4981178877662464.mp4"))
        assertTrue(needsTitleInput("2_5327978772606.mp4"))
        assertTrue(needsTitleInput("video_2024-02-28_12-00-00.mp4"))
        assertTrue(needsTitleInput("PXL_20260911_093532232.mp4"))
        assertTrue(needsTitleInput("PXL_20260911_093532232.COVER.mp4"))
        assertFalse(needsTitleInput("Dune.mkv"))
        assertFalse(needsTitleInput("[For PC].The.Bear.S01E01.mkv"))
    }

    @Test
    fun `preview frames sit inside the video`() {
        val times = previewFrameTimesUs(durationMs = 120_000, count = 4, random = kotlin.random.Random(1))
        assertEquals(4, times.size)
        times.forEach { us ->
            assertTrue(us >= 14_400_000L)
            assertTrue(us <= 105_600_000L)
        }
    }
}
