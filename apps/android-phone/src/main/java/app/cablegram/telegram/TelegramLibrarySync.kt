package app.cablegram.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

// Shared by apps/android-phone and apps/android-tv (spec 004). Keep both copies identical.

/**
 * Keeps the catalog in step with the household's library channel (spec 004 US3): every video in the
 * channel is registered as a `telegram` source, deleted ones are marked unavailable. Reads only that
 * one channel (FR-005). [register] and [reconcile] call the control plane; [onChanged] refreshes the UI.
 */
class TelegramLibrarySync(
    private val api: TelegramApi,
    private val chatId: Long,
    private val register: suspend (TgVideo) -> Unit,
    private val reconcile: suspend (Set<String>) -> Unit,
    private val onChanged: suspend () -> Unit = {},
) {
    /** Registers every video in the channel, then marks the rest unavailable. Returns how many there are. */
    suspend fun fullScan(): Int {
        val keys = linkedSetOf<String>()
        forEachVideo(api, chatId) { video ->
            if (keys.add(stableKey(video))) runCatching { register(video) }
            false
        }
        reconcile(keys)
        onChanged()
        return keys.size
    }

    /** New videos register at once; deletions trigger a rescan (TDLib reports ids, not files). */
    fun watch(scope: CoroutineScope): Job = scope.launch {
        launch {
            api.newMessages.filter { it.chatId == chatId && it.video != null }.collect { message ->
                runCatching { register(message.video!!) }
                onChanged()
            }
        }
        launch {
            api.deletedMessages.filter { it.chatId == chatId }.collect { runCatching { fullScan() } }
        }
    }

    companion object {
        private const val PAGE_SIZE = 100
        /** Enough for 10 000 messages; a library channel is far smaller. */
        private const val MAX_PAGES = 100

        /**
         * Every video in the library channel, newest first, page by page, until [visit] returns true or the
         * channel ends. The one channel walk shared by the sync, deletions, uploads and the phone's streaming.
         */
        suspend fun forEachVideo(api: TelegramApi, chatId: Long, visit: suspend (TgVideo) -> Boolean) {
            var from = 0L
            for (page in 0 until MAX_PAGES) {
                val result = api.videos(chatId, from, PAGE_SIZE)
                for (video in result.videos) if (visit(video)) return
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
