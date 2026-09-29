package app.cablegram.phone

import app.cablegram.telegram.FakeTelegramApi
import app.cablegram.telegram.TgFile
import app.cablegram.telegram.TgVideo
import app.cablegram.telegram.TgVideoPage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramRemovalTest {
    private val chat = -1004481882279L

    private fun video(message: Long, unique: String) = TgVideo(
        chatId = chat, messageId = message, file = TgFile(message.toInt(), 100, unique, 0, 0, 0, false),
        fileName = "f.mkv", caption = "", mimeType = "video/x-matroska", durationSeconds = 60,
        supportsStreaming = false, date = 0,
    )

    @Test
    fun `a finished upload that never registered is found again by name and exact size`() = runTest {
        val api = FakeTelegramApi()
        val sent = video(44, "up").copy(fileName = "abcd1234-Holiday.mkv", file = TgFile(44, 500, "up", 0, 0, 0, false))
        api.videoPages[0L] = TgVideoPage(listOf(sent, video(40, "other")), nextFromMessageId = 0)
        assertEquals(44L, TelegramRemoval.findUpload(api, chat, 500, setOf("abcd1234-Holiday.mkv"))?.messageId)
        assertEquals(null, TelegramRemoval.findUpload(api, chat, 501, setOf("abcd1234-Holiday.mkv")))
        assertEquals(null, TelegramRemoval.findUpload(api, chat, 500, setOf("ffff0000-Holiday.mkv")))
        // Uploaded under the file's own name (no link possible): still found, so it isn't uploaded twice.
        val original = sent.copy(fileName = "abcd1234-5678.mp4", messageId = 45)
        api.videoPages[0L] = TgVideoPage(listOf(original), nextFromMessageId = 0)
        assertEquals(45L, TelegramRemoval.findUpload(api, chat, 500, setOf("abcd1234-Holiday.mkv", "abcd1234-5678.mp4"))?.messageId)
    }

    @Test
    fun `every message with the file is found, other files are left alone`() = runTest {
        val api = FakeTelegramApi()
        api.videoPages[0L] = TgVideoPage(listOf(video(30, "a"), video(20, "b")), nextFromMessageId = 20)
        api.videoPages[20L] = TgVideoPage(listOf(video(10, "a")), nextFromMessageId = 0)
        assertEquals(
            mapOf("tgfile:a" to setOf(30L, 10L), "tgfile:c" to emptySet<Long>()),
            TelegramRemoval.channelMessageIds(api, chat, setOf("tgfile:a", "tgfile:c")),
        )
        assertEquals("one walk of the channel for the whole batch", 2, api.videoPageRequests)
    }

    @Test
    fun `the queue survives a restart, records failures and forgets finished deletions`() {
        var stored: String? = null
        val queue = TelegramDeletionQueue({ stored }, { stored = it })
        assertEquals(emptyList<PendingTelegramDeletion>(), queue.all())
        queue.add(PendingTelegramDeletion("tgfile:a", chat, listOf(1), "A", createdAt = 5))
        queue.add(PendingTelegramDeletion("tgfile:b", chat, listOf(2), "B", createdAt = 6))
        queue.failed("tgfile:a", "offline")

        val reopened = TelegramDeletionQueue({ stored }, { stored = it })
        assertEquals(listOf("tgfile:a" to 1, "tgfile:b" to 0), reopened.all().map { it.stableSourceKey to it.attempts })
        reopened.remove("tgfile:a")
        assertEquals(listOf("tgfile:b"), reopened.all().map { it.stableSourceKey })
    }

    @Test
    fun `a deletion Telegram confirmed stays queued until the control plane is told`() {
        var text: String? = null
        val queue = TelegramDeletionQueue({ text }, { text = it })
        queue.add(PendingTelegramDeletion("tgfile:abc", -100L, listOf(5L), "Film", createdAt = 0))
        queue.markDeletedInTelegram("tgfile:abc")
        assertEquals(true, queue.all().single().deletedInTelegram)
        // Survives a restart: the flag is part of the persisted entry.
        assertEquals(true, TelegramDeletionQueue({ text }, { text = it }).all().single().deletedInTelegram)
        queue.remove("tgfile:abc")
        assertEquals(emptyList<PendingTelegramDeletion>(), queue.all())
    }
}

