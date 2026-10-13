package app.cablegram.telegram

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Shared by apps/android-phone and apps/android-tv tests (spec 004). Keep both copies identical.

class TelegramLibrarySyncTest {
    private val chat = -1004481882279L

    private fun video(message: Long, unique: String, caption: String = "", chatId: Long = chat) = TgVideo(
        chatId = chatId, messageId = message,
        file = TgFile(message.toInt(), 100, unique, 0, 0, 0, false),
        fileName = "file-$message.mkv", caption = caption, mimeType = "video/x-matroska",
        durationSeconds = 60, supportsStreaming = false, date = 0,
    )

    @Test
    fun `a full scan registers every video across pages and reconciles the rest away`() = runTest {
        val api = FakeTelegramApi()
        api.videoPages[0L] = TgVideoPage(listOf(video(30, "c"), video(20, "b")), nextFromMessageId = 20)
        api.videoPages[20L] = TgVideoPage(listOf(video(10, "a"), video(5, "a")), nextFromMessageId = 5) // "a" forwarded twice
        api.videoPages[5L] = TgVideoPage(emptyList(), nextFromMessageId = 0)
        val registered = mutableListOf<Long>()
        var reconciled: Set<String>? = null
        var changes = 0
        val sync = TelegramLibrarySync(api, chat, { registered += it.messageId }, { reconciled = it }, { changes++ })

        assertEquals(3, sync.fullScan())
        assertEquals("one registration per file", listOf(30L, 20L, 10L), registered)
        assertEquals(setOf("tgfile:c", "tgfile:b", "tgfile:a"), reconciled)
        assertEquals(1, changes)
    }

    @Test
    fun `new videos in the channel register at once, other chats are ignored`() = runTest {
        val api = FakeTelegramApi()
        val registered = mutableListOf<Long>()
        val sync = TelegramLibrarySync(api, chat, { registered += it.messageId }, {})
        sync.watch(backgroundScope); runCurrent()
        api.newMessages.emit(TgMessage(chat, 40, video(40, "d")))
        api.newMessages.emit(TgMessage(-1009999L, 41, video(41, "e", chatId = -1009999L)))
        api.newMessages.emit(TgMessage(chat, 42, null)) // a text message
        runCurrent()
        assertEquals(listOf(40L), registered)
    }

    @Test
    fun `a deletion in the channel rescans so the gone video becomes unavailable`() = runTest {
        val api = FakeTelegramApi()
        api.videoPages[0L] = TgVideoPage(listOf(video(30, "c")), nextFromMessageId = 0)
        var reconciled: Set<String>? = null
        val sync = TelegramLibrarySync(api, chat, {}, { reconciled = it })
        sync.watch(backgroundScope); runCurrent()
        api.deletedMessages.emit(TgDeletedMessages(chat, longArrayOf(20)))
        runCurrent()
        assertEquals(setOf("tgfile:c"), reconciled)
    }

    @Test
    fun `a caption names the video, otherwise the file name does`() {
        assertEquals("Gary", TelegramLibrarySync.title(video(1, "x", caption = "  Gary \nThe Bear special")))
        assertNull(TelegramLibrarySync.title(video(1, "x")))
        assertEquals("tg:$chat:7", TelegramLibrarySync.originIdentity(video(7, "x")))
        assertEquals("tgfile:x", TelegramLibrarySync.stableKey(video(7, "x")))
    }

    @Test
    fun `a full scan sends the newest TMDB reference for each library video`() = runTest {
        val api = FakeTelegramApi()
        api.videoPages[0L] = TgVideoPage(
            listOf(video(30, "cccccccc")), nextFromMessageId = 0,
            notes = listOf(
                TgMessage(chat, 33, null, "video_id: #cccccccc #movie_1313826"),
                TgMessage(chat, 32, null, "video_id: #not_a_library_video #movie_7"), // never sent
                TgMessage(chat, 31, null, "video_id: #cccccccc #movie_99"), // older: the newer one wins
            ),
        )
        val sent = mutableListOf<TgTmdbHint>()
        val sync = TelegramLibrarySync(api, chat, {}, {}, hints = { sent += it })
        sync.fullScan()
        assertEquals(listOf(TgTmdbHint("cccccccc", "movie", 1313826)), sent)
    }

    @Test
    fun `a reference that comes later, or by an edit, is still picked up`() = runTest {
        val api = FakeTelegramApi()
        val sent = mutableListOf<TgTmdbHint>()
        val sync = TelegramLibrarySync(api, chat, {}, {}, hints = { sent += it }, hintRetryDelaysMs = listOf(5_000L, 15_000L))
        sync.watch(backgroundScope); runCurrent()
        api.newMessages.emit(TgMessage(chat, 40, video(40, "dddddddd"))); runCurrent()
        // The bot's placeholder, then its edit.
        api.newMessages.emit(TgMessage(chat, 41, null, "⏳ Processing video… video_id: #dddddddd")); runCurrent()
        assertEquals(emptyList<TgTmdbHint>(), sent)
        api.editedMessages.emit(TgMessage(chat, 41, null, "✅ video_id: #dddddddd #movie_1313826")); runCurrent()
        assertEquals(listOf(TgTmdbHint("dddddddd", "movie", 1313826)), sent)

        // Another video whose reference only shows up when the channel is read again.
        sent.clear()
        api.newMessages.emit(TgMessage(chat, 50, video(50, "eeeeeeee"))); runCurrent()
        api.videoPages[0L] = TgVideoPage(emptyList(), 0, notes = listOf(TgMessage(chat, 51, null, "#eeeeeeee #tv_42_s01e02")))
        testScheduler.advanceTimeBy(5_001); runCurrent()
        assertEquals(listOf(TgTmdbHint("eeeeeeee", "tv", 42, 1, 2)), sent)
    }
}
