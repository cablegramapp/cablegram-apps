package app.cablegram.telegram

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

// Shared by apps/android-phone and apps/android-tv (spec 004). Keep both copies identical.

/**
 * Keeps the catalog in step with the household's library channel (spec 004 US3): every video in the
 * channel is registered as a `telegram` source, deleted ones are marked unavailable. Reads only that
 * one channel (FR-005). [register] and [reconcile] call the control plane; [onChanged] refreshes the UI.
 * [hints] sends TMDB references found in the channel for its videos (CAB-60, see [ChannelHints]).
 */
class TelegramLibrarySync(
    private val api: TelegramApi,
    private val chatId: Long,
    private val register: suspend (TgVideo) -> Unit,
    private val reconcile: suspend (Set<String>) -> Unit,
    private val onChanged: suspend () -> Unit = {},
    private val hints: suspend (List<TgTmdbHint>) -> Unit = {},
    /** After a new video: when to look again for a reference posted a little later (by a bot, say). */
    private val hintRetryDelaysMs: List<Long> = HINT_RETRY_DELAYS_MS,
) {
    /** File ids of the channel's videos: only references to these are sent. */
    private val known: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Registers every video in the channel, then marks the rest unavailable. Returns how many there are. */
    suspend fun fullScan(): Int {
        val keys = linkedSetOf<String>()
        val texts = mutableListOf<TgMessage>()
        forEachPage(api, chatId) { page ->
            for (video in page.videos) {
                known += video.file.uniqueId
                if (keys.add(stableKey(video))) runCatching { register(video) }
                if (video.caption.isNotBlank()) texts += TgMessage(video.chatId, video.messageId, video, video.caption)
            }
            texts += page.notes
            false
        }
        reconcile(keys)
        // Newest first: the newest reference for a video wins.
        sendHints(texts.flatMap { ChannelHints.from(it, known) }.distinctBy { it.uniqueId })
        onChanged()
        return keys.size
    }

    /**
     * New videos register at once; deletions trigger a rescan (TDLib reports ids, not files). New and edited messages
     * are read for TMDB references, and after a new video the channel is looked at again a few times, since a bot's
     * reply often comes seconds later, or first as a placeholder that is edited afterwards.
     */
    fun watch(scope: CoroutineScope): Job = scope.launch {
        launch {
            api.newMessages.filter { it.chatId == chatId }.collect { message ->
                val video = message.video
                if (video != null) {
                    known += video.file.uniqueId
                    runCatching { register(video) }
                    onChanged()
                }
                val found = ChannelHints.from(message, known)
                sendHints(found)
                if (video != null && found.none { it.uniqueId == video.file.uniqueId }) launch { lookAgain(video.file.uniqueId) }
            }
        }
        launch {
            api.editedMessages.filter { it.chatId == chatId }.collect { sendHints(ChannelHints.from(it, known)) }
        }
        launch {
            api.deletedMessages.filter { it.chatId == chatId }.collect { runCatching { fullScan() } }
        }
    }

    /** Reads the newest messages again until one names a TMDB title for [uniqueId], or the delays run out. */
    private suspend fun lookAgain(uniqueId: String) {
        for (wait in hintRetryDelaysMs) {
            delay(wait)
            val page = runCatching { api.videos(chatId, 0, RECENT_MESSAGES) }.getOrNull() ?: continue
            val messages = page.notes + page.videos.map { TgMessage(it.chatId, it.messageId, it, it.caption) }
            val found = messages.sortedByDescending { it.messageId }.flatMap { ChannelHints.from(it, known) }.firstOrNull { it.uniqueId == uniqueId }
            if (found != null) {
                sendHints(listOf(found))
                return
            }
        }
    }

    private suspend fun sendHints(found: List<TgTmdbHint>) {
        if (found.isEmpty()) return
        found.chunked(HINTS_PER_REQUEST).forEach { runCatching { hints(it) } }
        onChanged()
    }

    companion object {
        private const val PAGE_SIZE = 100
        private const val RECENT_MESSAGES = 50
        private const val HINTS_PER_REQUEST = 500
        val HINT_RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L)
        /** Enough for 10 000 messages; a library channel is far smaller. */
        private const val MAX_PAGES = 100

        /**
         * Every video in the library channel, newest first, page by page, until [visit] returns true or the
         * channel ends. The one channel walk shared by the sync, deletions, uploads and the phone's streaming.
         */
        suspend fun forEachVideo(api: TelegramApi, chatId: Long, visit: suspend (TgVideo) -> Boolean) {
            forEachPage(api, chatId) { page -> page.videos.any { visit(it) } }
        }

        /** The channel page by page, newest first, until [visit] returns true or the channel ends. */
        private suspend fun forEachPage(api: TelegramApi, chatId: Long, visit: suspend (TgVideoPage) -> Boolean) {
            var from = 0L
            for (page in 0 until MAX_PAGES) {
                val result = api.videos(chatId, from, PAGE_SIZE)
                if (visit(result)) return
                val next = result.nextFromMessageId
                if (next == 0L || next == from) return
                from = next
            }
        }

        fun stableKey(video: TgVideo) = "tgfile:${video.file.uniqueId}"
        fun originIdentity(video: TgVideo) = "tg:${video.chatId}:${video.messageId}"

        /** A caption's first line is the owner's title for the video; otherwise the file name is used. */
        fun title(video: TgVideo): String? = video.caption.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
    }
}
