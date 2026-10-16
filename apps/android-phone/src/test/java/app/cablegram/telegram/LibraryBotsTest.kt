package app.cablegram.telegram

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryBotsTest {
    private val first = RecommendedLibraryBot(71, "FirstBot", "First bot")
    private val second = RecommendedLibraryBot(72, "SecondBot", "Second bot")
    private val library = -10042L

    @Test
    fun `parses handles deep links and embedded button URLs without duplicates`() {
        assertEquals(setOf("firstbot", "secondbot", "thirdbot", "fourthbot", "fifthbot"), BotHubLinks.usernames(
            "Try @FirstBot, https://t.me/FirstBot?start=hello and (t.me/SecondBot). tg://resolve?domain=ThirdBot",
            listOf("https://telegram.me/FourthBot?startchannel&admin=delete_messages", "https://t.me/FifthBot"),
        ))
    }

    @Test
    fun `rejects offsite links invite links post links and email addresses`() {
        assertTrue(BotHubLinks.usernames(
            "person@FirstBot.com https://t.me.evil.test/FirstBot https://evil.test/t.me/FirstBot " +
                "https://t.me/+invite https://t.me/SomeChannel/123 https://t.me/Name@evil.test/FirstBot " +
                "https://t.me:444/FirstBot https://t.me@evil.test/FirstBot tg://join?invite=FirstBot",
        ).isEmpty())
    }

    @Test
    fun `discovery and unaccepted add never grant bot access`() = runTest {
        val api = FakeTelegramApi().also { it.recommendedBots = listOf(first, second) }
        val bots = LibraryBotRecommendations(api, library, onDecision = {}, onCompleted = {})
        bots.addCurrent()
        bots.load()
        bots.addCurrent()
        assertEquals(listOf("recommendedLibraryBots"), api.calls)
        assertFalse(bots.state.value.disclaimerAccepted)
        assertEquals(first, bots.state.value.current)
    }

    @Test
    fun `each bot requires fresh acceptance and targets the library only`() = runTest {
        val api = FakeTelegramApi().also { it.recommendedBots = listOf(first, second) }
        val decisions = mutableListOf<Long>()
        var completed = 0
        val bots = LibraryBotRecommendations(api, library, onDecision = { decisions += it }, onCompleted = { completed++ })
        bots.load()
        bots.acceptDisclaimer(true)
        bots.addCurrent()
        assertEquals(second, bots.state.value.current)
        assertFalse(bots.state.value.disclaimerAccepted)
        bots.addCurrent()
        assertEquals(listOf("recommendedLibraryBots", "addRecommendedBot:$library:71"), api.calls)
        bots.acceptDisclaimer(true)
        bots.addCurrent()
        assertEquals(listOf(71L, 72L), decisions)
        assertEquals(1, completed)
        assertTrue(bots.state.value.completed)
        assertEquals(2, bots.state.value.added)
    }

    @Test
    fun `skip individual bots or all without accepting disclaimer`() = runTest {
        val api = FakeTelegramApi().also { it.recommendedBots = listOf(first, second) }
        val decisions = mutableListOf<Long>()
        var completed = false
        val bots = LibraryBotRecommendations(api, library, onDecision = { decisions += it }, onCompleted = { completed = true })
        bots.load()
        bots.skipCurrent()
        assertEquals(second, bots.state.value.current)
        bots.finish()
        bots.finish()
        assertEquals(listOf(71L), decisions)
        assertTrue(completed)
        assertEquals(listOf("recommendedLibraryBots"), api.calls)
    }

    @Test
    fun `saved decisions and repeated recommendations are filtered on resume`() = runTest {
        val api = FakeTelegramApi().also { it.recommendedBots = listOf(first, second, first, second) }
        val bots = LibraryBotRecommendations(api, library, setOf(first.id), onDecision = {}, onCompleted = {})
        bots.load()
        bots.load()
        assertEquals(listOf(second), bots.state.value.bots)
        assertEquals(listOf("recommendedLibraryBots"), api.calls)
    }

    @Test
    fun `failed addition stays on current bot and can be retried or skipped`() = runTest {
        val api = FakeTelegramApi().also { it.recommendedBots = listOf(first, second) }
        val decisions = mutableListOf<Long>()
        val bots = LibraryBotRecommendations(api, library, onDecision = { decisions += it }, onCompleted = {})
        bots.load()
        bots.acceptDisclaimer(true)
        api.failNext = TgException(400, "CHAT_ADMIN_REQUIRED")
        bots.addCurrent()
        assertEquals(first, bots.state.value.current)
        assertNotNull(bots.state.value.error)
        assertTrue(decisions.isEmpty())
        bots.addCurrent()
        assertEquals(second, bots.state.value.current)
        assertEquals(listOf(71L), decisions)
        assertNull(bots.state.value.error)
    }

    @Test
    fun `failed discovery is retryable and empty feed is optional`() = runTest {
        val api = FakeTelegramApi()
        var completed = false
        val bots = LibraryBotRecommendations(api, library, onDecision = {}, onCompleted = { completed = true })
        api.failNext = TgException(500, "INTERNAL")
        bots.load()
        assertFalse(bots.state.value.loaded)
        assertNotNull(bots.state.value.error)
        bots.load()
        assertTrue(bots.state.value.loaded)
        assertNull(bots.state.value.current)
        assertFalse(completed)
        bots.finish()
        assertTrue(completed)
    }

    @Test
    fun `concurrent add calls and skip cannot change bot during addition`() = runTest {
        val api = FakeTelegramApi().also { it.recommendedBots = listOf(first, second) }
        val gate = CompletableDeferred<Unit>()
        var additions = 0
        val slow = object : TelegramApi by api {
            override suspend fun addRecommendedBotToLibrary(libraryChatId: Long, botUserId: Long) {
                additions++
                gate.await()
            }
        }
        val bots = LibraryBotRecommendations(slow, library, onDecision = {}, onCompleted = {})
        bots.load()
        bots.acceptDisclaimer(true)
        val job = launch { bots.addCurrent() }
        runCurrent()
        bots.skipCurrent()
        bots.finish()
        bots.acceptDisclaimer(false)
        bots.addCurrent()
        assertEquals(1, additions)
        assertEquals(first, bots.state.value.current)
        gate.complete(Unit)
        job.join()
        assertEquals(second, bots.state.value.current)
        assertFalse(bots.state.value.disclaimerAccepted)
    }

    @Test
    fun `cancellation stops adding and does not record a decision`() = runTest {
        val api = FakeTelegramApi().also { it.recommendedBots = listOf(first) }
        val slow = object : TelegramApi by api {
            override suspend fun addRecommendedBotToLibrary(libraryChatId: Long, botUserId: Long) { awaitCancellation() }
        }
        val bots = LibraryBotRecommendations(slow, library, onDecision = { error("Not added") }, onCompleted = { error("Not completed") })
        bots.load()
        bots.acceptDisclaimer(true)
        val job = launch { bots.addCurrent() }
        runCurrent()
        job.cancel()
        job.join()
        assertFalse(bots.state.value.busy)
        assertEquals(first, bots.state.value.current)
        assertFalse(bots.state.value.completed)
    }

    @Test
    fun `addition timeout keeps the current recommendation retryable`() = runTest {
        val api = FakeTelegramApi().also { it.recommendedBots = listOf(first) }
        val slow = object : TelegramApi by api {
            override suspend fun addRecommendedBotToLibrary(libraryChatId: Long, botUserId: Long) { awaitCancellation() }
        }
        val bots = LibraryBotRecommendations(slow, library, onDecision = { error("Not added") }, onCompleted = { error("Not completed") })
        bots.load()
        bots.acceptDisclaimer(true)
        bots.addCurrent()
        assertNotNull(bots.state.value.error)
        assertFalse(bots.state.value.busy)
        assertEquals(first, bots.state.value.current)
    }

    @Test
    fun `new bot rights cannot delete edit invite or promote`() {
        val rights = libraryBotPostingRights()
        val enabled = rights.javaClass.declaredMethods.filter { it.name.startsWith("get") && it.returnType == Boolean::class.javaPrimitiveType }
            .filter { it.invoke(rights) == true }.map { it.name }.toSet()
        assertEquals(setOf("getCanManageChat", "getCanPostMessages"), enabled)
    }
}
