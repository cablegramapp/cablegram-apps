package app.cablegram.phone

import android.content.Context
import app.cablegram.telegram.TelegramApi
import app.cablegram.telegram.TelegramLibrarySync
import app.cablegram.telegram.TgVideo
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A channel deletion the control plane asked for, kept until Telegram confirms it (spec 004 US5). */
@Serializable
data class PendingTelegramDeletion(
    val stableSourceKey: String,
    val chatId: Long,
    val messageIds: List<Long>,
    val title: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    /** Telegram already deleted the messages; only telling the control plane is left (review fix 3). */
    val deletedInTelegram: Boolean = false,
)

/**
 * The deletions still to do, persisted so a failed or offline deletion is retried after a restart.
 * [load] and [save] hold the JSON text (SharedPreferences on the phone, a variable in tests).
 */
class TelegramDeletionQueue(private val load: () -> String?, private val save: (String) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PendingTelegramDeletion.serializer())

    @Synchronized
    fun all(): List<PendingTelegramDeletion> =
        load()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    @Synchronized
    fun add(item: PendingTelegramDeletion) {
        save(json.encodeToString(serializer, all().filterNot { it.stableSourceKey == item.stableSourceKey } + item))
    }

    @Synchronized
    fun remove(key: String) {
        save(json.encodeToString(serializer, all().filterNot { it.stableSourceKey == key }))
    }

    @Synchronized
    fun markDeletedInTelegram(key: String) {
        save(json.encodeToString(serializer, all().map { if (it.stableSourceKey == key) it.copy(deletedInTelegram = true) else it }))
    }

    @Synchronized
    fun failed(key: String, error: String) {
        save(json.encodeToString(serializer, all().map {
            if (it.stableSourceKey == key) it.copy(attempts = it.attempts + 1, lastError = error) else it
        }))
    }

    companion object {
        /** A deletion that still fails after this long is reported to the user. */
        const val GIVE_UP_AFTER_MS = 24L * 60 * 60 * 1000

        fun forContext(context: Context): TelegramDeletionQueue {
            val prefs = context.applicationContext.getSharedPreferences("cablegram_telegram_deletions", Context.MODE_PRIVATE)
            return TelegramDeletionQueue({ prefs.getString("pending", null) }, { prefs.edit().putString("pending", it).apply() })
        }
    }
}

object TelegramRemoval {
    /**
     * For each file in [keys], every message in the channel that carries it (a file forwarded twice is two
     * messages; deleting one would leave the copy), found in one walk of the channel for the whole batch.
     */
    suspend fun channelMessageIds(api: TelegramApi, chatId: Long, keys: Set<String>): Map<String, Set<Long>> {
        val found = keys.associateWith { mutableSetOf<Long>() }
        TelegramLibrarySync.forEachVideo(api, chatId) { video ->
            found[TelegramLibrarySync.stableKey(video)]?.add(video.messageId)
            false
        }
        return found
    }

    /**
     * A video already in the channel from an earlier "Save to Telegram" of the same title that did not finish
     * registering: one of the names it could have been uploaded under ([uploadNames], see
     * LibraryStore.telegramUploadNames) and exactly the same size. Saving again then reuses it instead of adding
     * a second copy of a large file.
     */
    suspend fun findUpload(api: TelegramApi, chatId: Long, sizeBytes: Long, uploadNames: Set<String>): TgVideo? {
        var match: TgVideo? = null
        TelegramLibrarySync.forEachVideo(api, chatId) { video ->
            (video.fileName in uploadNames && video.file.size == sizeBytes).also { if (it) match = video }
        }
        return match
    }
}
