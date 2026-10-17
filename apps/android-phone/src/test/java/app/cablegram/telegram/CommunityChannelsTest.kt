package app.cablegram.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommunityChannelsTest {
    @Test
    fun `all selected initially but nothing happens before save`() = runTest {
        val api = FakeTelegramApi()
        val channels = CommunityChannels(api) { error("Not saved") }
        assertEquals(CablegramChannel.entries.toSet(), channels.state.value.selected)
        channels.select(CablegramChannel.News, false)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `save joins only selected channels and completes once`() = runTest {
        val api = FakeTelegramApi()
        var completed = 0
        val channels = CommunityChannels(api) { completed++ }
        channels.select(CablegramChannel.Community, false)
        channels.save()
        channels.save()
        assertEquals(listOf("joinCommunityChannel:CablegramNews", "joinCommunityChannel:CablegramBotHubs"), api.calls)
        assertEquals(1, completed)
        assertTrue(channels.state.value.completed)
    }

    @Test
    fun `empty selection completes without joining anything`() = runTest {
        val api = FakeTelegramApi()
        var completed = false
        val channels = CommunityChannels(api) { completed = true }
        CablegramChannel.entries.forEach { channels.select(it, false) }
        channels.save()
        assertTrue(completed)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `partial failure attempts every selection and retries only failures`() = runTest {
        val api = FakeTelegramApi()
        var completed = false
        val channels = CommunityChannels(api) { completed = true }
        api.failNext = TgException(429, "FLOOD_WAIT_30")
        channels.save()
        assertEquals(3, api.calls.size)
        assertEquals(setOf(CablegramChannel.News), channels.state.value.failed)
        assertEquals(setOf(CablegramChannel.Community, CablegramChannel.BotHubs), channels.state.value.joined)
        assertFalse(completed)
        channels.save()
        assertEquals(4, api.calls.size)
        assertEquals("joinCommunityChannel:CablegramNews", api.calls.last())
        assertTrue(completed)
    }

    @Test
    fun `failed channel can be unchecked to finish`() = runTest {
        val api = FakeTelegramApi()
        val channels = CommunityChannels(api) {}
        api.failNext = TgException(400, "USERNAME_NOT_OCCUPIED")
        channels.save()
        channels.select(CablegramChannel.News, false)
        channels.save()
        assertEquals(3, api.calls.size)
        assertTrue(channels.state.value.completed)
    }

    @Test
    fun `duplicate save and selection changes are ignored while saving`() = runTest {
        val api = FakeTelegramApi()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val slow = object : TelegramApi by api {
            override suspend fun joinCommunityChannel(channel: CablegramChannel) {
                calls++
                gate.await()
            }
        }
        val channels = CommunityChannels(slow) {}
        val first = launch { channels.save() }
        runCurrent()
        channels.select(CablegramChannel.News, false)
        channels.save()
        assertEquals(1, calls)
        assertEquals(3, channels.state.value.selected.size)
        gate.complete(Unit)
        first.join()
        assertEquals(3, calls)
    }

    @Test
    fun `timeout leaves failed channel available to retry`() = runTest {
        val api = FakeTelegramApi()
        val slow = object : TelegramApi by api {
            override suspend fun joinCommunityChannel(channel: CablegramChannel) { awaitCancellation() }
        }
        val channels = CommunityChannels(slow) {}
        channels.save()
        assertEquals(CablegramChannel.entries.toSet(), channels.state.value.failed)
        assertFalse(channels.state.value.saving)
        assertFalse(channels.state.value.completed)
    }

    @Test
    fun `cancellation stops further joins and clears busy state`() = runTest {
        val api = FakeTelegramApi()
        var calls = 0
        val slow = object : TelegramApi by api {
            override suspend fun joinCommunityChannel(channel: CablegramChannel) {
                calls++
                awaitCancellation()
            }
        }
        val channels = CommunityChannels(slow) {}
        val job = launch { channels.save() }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(1, calls)
        assertFalse(channels.state.value.saving)
        assertFalse(channels.state.value.completed)
    }
}
