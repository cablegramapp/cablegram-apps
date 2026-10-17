package app.cablegram.telegram

import dev.g000sha256.tdl.dto.ChatFolder
import dev.g000sha256.tdl.dto.ChatFolderIcon
import dev.g000sha256.tdl.dto.ChatFolderInfo
import dev.g000sha256.tdl.dto.ChatFolderName
import dev.g000sha256.tdl.dto.FormattedText
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class CablegramFolderTest {
    private fun folder(name: String = "Cablegram", included: LongArray = longArrayOf(), pinned: LongArray = longArrayOf(), excluded: LongArray = longArrayOf()) = ChatFolder(
        ChatFolderName(FormattedText(name, emptyArray()), false), ChatFolderIcon("Custom"), -1, false,
        pinned, included, excluded, false, false, false, false, false, false, false, false,
    )

    private class Store : CablegramFolderStore {
        val saved = linkedMapOf<Int, ChatFolder>()
        var creates = 0
        var edits = 0
        var fail = false
        override suspend fun folders(): List<ChatFolderInfo> = saved.map { (id, f) ->
            ChatFolderInfo(id, f.name, f.icon ?: ChatFolderIcon("Custom"), f.colorId, f.isShareable, false)
        }
        override suspend fun folder(id: Int): ChatFolder = saved.getValue(id)
        override suspend fun create(folder: ChatFolder) {
            yield()
            if (fail) error("Telegram folder limit")
            creates++
            saved[creates] = folder
        }
        override suspend fun edit(id: Int, folder: ChatFolder) {
            if (fail) error("Telegram folder limit")
            edits++
            saved[id] = folder
        }
    }

    @Test
    fun `creates one explicit Cablegram folder without broad chat filters`() = runTest {
        val store = Store()
        val organizer = CablegramFolderOrganizer(store)
        organizer.include(longArrayOf(-1001, -1002, 42, 42, 0))
        val created = store.saved.values.single()
        assertEquals("Cablegram", created.name.text.text)
        assertArrayEquals(longArrayOf(-1001, -1002, 42), created.includedChatIds)
        assertTrue(created.pinnedChatIds.isEmpty())
        assertFalse(created.includeContacts || created.includeNonContacts || created.includeBots || created.includeGroups || created.includeChannels)
        assertFalse(created.isShareable)
    }

    @Test
    fun `reuses existing named folder and preserves pins filters and unrelated entries`() = runTest {
        val store = Store()
        val old = ChatFolder(
            ChatFolderName(FormattedText(" cablegram ", emptyArray()), true), ChatFolderIcon("Favorite"), 3, false,
            longArrayOf(91), longArrayOf(92), longArrayOf(93, 94), true, true, true, true, true, true, true, true,
        )
        store.saved[17] = old
        store.saved[18] = folder("Personal", longArrayOf(500))
        CablegramFolderOrganizer(store).include(longArrayOf(91, 93, 95))
        val merged = store.saved.getValue(17)
        assertEquals(0, store.creates)
        assertEquals(1, store.edits)
        assertEquals(old.name, merged.name)
        assertEquals(old.icon, merged.icon)
        assertEquals(old.colorId, merged.colorId)
        assertArrayEquals(longArrayOf(91), merged.pinnedChatIds)
        assertArrayEquals(longArrayOf(92, 93, 95), merged.includedChatIds)
        assertArrayEquals(longArrayOf(94), merged.excludedChatIds)
        assertTrue(merged.excludeMuted && merged.excludeRead && merged.excludeArchived)
        assertTrue(merged.includeContacts && merged.includeNonContacts && merged.includeBots && merged.includeGroups && merged.includeChannels)
        assertArrayEquals(longArrayOf(500), store.saved.getValue(18).includedChatIds)
    }

    @Test
    fun `repeat organization is idempotent and does not edit an unchanged folder`() = runTest {
        val store = Store()
        val organizer = CablegramFolderOrganizer(store)
        organizer.include(longArrayOf(11, 12))
        organizer.include(longArrayOf(11, 12, 12))
        assertEquals(1, store.creates)
        assertEquals(0, store.edits)
        assertArrayEquals(longArrayOf(11, 12), store.saved.values.single().includedChatIds)
    }

    @Test
    fun `concurrent merges create only one folder and keep both additions`() = runTest {
        val store = Store()
        val organizer = CablegramFolderOrganizer(store)
        val first = async { organizer.include(longArrayOf(11)) }
        val second = async { organizer.include(longArrayOf(12)) }
        first.await(); second.await()
        assertEquals(1, store.creates)
        assertArrayEquals(longArrayOf(11, 12), store.saved.values.single().includedChatIds)
    }

    @Test
    fun `folder limit failure leaves existing folder untouched and can be retried`() = runTest {
        val store = Store()
        val old = folder(included = longArrayOf(11))
        store.saved[7] = old
        store.fail = true
        val organizer = CablegramFolderOrganizer(store)
        try {
            organizer.include(longArrayOf(12))
            fail("Expected folder failure")
        } catch (_: IllegalStateException) { }
        assertEquals(old, store.saved.getValue(7))
        store.fail = false
        organizer.include(longArrayOf(12))
        assertArrayEquals(longArrayOf(11, 12), store.saved.getValue(7).includedChatIds)
    }

    @Test
    fun `empty inclusion does not create a folder`() = runTest {
        val store = Store()
        CablegramFolderOrganizer(store).include(longArrayOf(0))
        assertTrue(store.saved.isEmpty())
    }
}
