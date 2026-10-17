package app.cablegram.telegram

import dev.g000sha256.tdl.dto.ChatFolder
import dev.g000sha256.tdl.dto.ChatFolderInfo
import dev.g000sha256.tdl.dto.ChatFolderName
import dev.g000sha256.tdl.dto.FormattedText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val CABLEGRAM_FOLDER_NAME = "Cablegram"
internal const val CABLEGRAM_SUPPORT_BOT = "CablegramSupportBot"

/** Only folder metadata: no chat histories or contacts are read to organize the folder. */
internal interface CablegramFolderStore {
    suspend fun folders(): List<ChatFolderInfo>
    suspend fun folder(id: Int): ChatFolder
    suspend fun create(folder: ChatFolder)
    suspend fun edit(id: Int, folder: ChatFolder)
}

/** Merge explicit chats, preserving the owner's pins, filters, style, and unrelated entries. */
internal class CablegramFolderOrganizer(private val store: CablegramFolderStore) {
    private val mutex = Mutex()

    suspend fun include(chatIds: LongArray) = mutex.withLock {
        val desired = chatIds.filter { it != 0L }.toSet()
        if (desired.isEmpty()) return@withLock
        val info = store.folders().firstOrNull { it.name.text.text.trim().equals(CABLEGRAM_FOLDER_NAME, ignoreCase = true) }
        val old = info?.let { store.folder(it.id) }
        val pinned = old?.pinnedChatIds ?: longArrayOf()
        val included = ((old?.includedChatIds ?: longArrayOf()).toList() + desired).distinct().filter { it !in pinned }.toLongArray()
        val excluded = (old?.excludedChatIds ?: longArrayOf()).filter { it !in desired }.toLongArray()
        val merged = ChatFolder(
            name = old?.name ?: ChatFolderName(FormattedText(CABLEGRAM_FOLDER_NAME, emptyArray()), false),
            icon = old?.icon,
            colorId = old?.colorId ?: -1,
            isShareable = old?.isShareable ?: false,
            pinnedChatIds = pinned,
            includedChatIds = included,
            excludedChatIds = excluded,
            excludeMuted = old?.excludeMuted ?: false,
            excludeRead = old?.excludeRead ?: false,
            excludeArchived = old?.excludeArchived ?: false,
            includeContacts = old?.includeContacts ?: false,
            includeNonContacts = old?.includeNonContacts ?: false,
            includeBots = old?.includeBots ?: false,
            includeGroups = old?.includeGroups ?: false,
            includeChannels = old?.includeChannels ?: false,
        )
        if (info == null) store.create(merged)
        else if (old != merged) store.edit(info.id, merged)
    }
}

/** Unstarted bot dialogs cannot be included in a Telegram folder without user interaction. */
data class TgFolderResult(val unlistedBots: List<String> = emptyList())
