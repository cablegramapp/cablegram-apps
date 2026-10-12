package app.cablegram.telegram

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Shared by apps/android-phone and apps/android-tv tests (spec 004). Keep both copies identical.

class TelegramSessionTest {
    private val params = TgParameters("db", "files", ByteArray(32), 1, "hash", "Model", "Android", "test")
    private val link = "tg://login?token=AQAB_c2VjcmV0LXRva2Vu"
    private val OWN_API = 100

    private fun TestScope.session(api: FakeTelegramApi, role: TelegramRole) =
        TelegramSession(api, role, { params }, backgroundScope).also { it.start(); runCurrent() }

    @Test
    fun `unavailable configuration can be retried without replacing the Telegram session`() = runTest {
        val api = FakeTelegramApi()
        var requests = 0
        val s = TelegramSession(api, TelegramRole.Phone, {
            requests++
            if (requests == 1) throw TelegramClientUnavailable()
            params
        }, backgroundScope)
        s.start(); runCurrent()
        api.authStates.emit(TgAuthState.WaitParameters); runCurrent()
        assertTrue(s.state.value is TelegramState.Failed)
        assertTrue(api.calls.isEmpty())
        assertEquals(0, s.clientApiId)
        s.retryStartup(); s.retryStartup(); runCurrent()
        assertEquals(2, requests)
        assertEquals(listOf("start"), api.calls)
        assertEquals(params.apiId, s.clientApiId)
        s.retryStartup(); runCurrent()
        assertEquals(2, requests)
    }

    @Test
    fun `phone signs in with number, code and password`() = runTest {
        val api = FakeTelegramApi()
        val s = session(api, TelegramRole.Phone)
        api.authStates.emit(TgAuthState.WaitParameters); runCurrent()
        assertEquals(listOf("start"), api.calls)
        api.authStates.emit(TgAuthState.WaitPhoneNumber); runCurrent()
        assertEquals(TelegramState.NeedsPhoneNumber, s.state.value)

        s.submitPhoneNumber(" +31 6 1234 5678 "); runCurrent()
        assertEquals("setPhoneNumber:+31612345678", api.calls.last())
        api.authStates.emit(TgAuthState.WaitCode); runCurrent()
        assertEquals(TelegramState.NeedsCode, s.state.value)
        s.submitCode("12 345"); runCurrent()
        assertEquals("checkCode:12345", api.calls.last())
        api.authStates.emit(TgAuthState.WaitPassword("my hint")); runCurrent()
        assertEquals(TelegramState.NeedsPassword("my hint"), s.state.value)
        s.submitPassword("secret"); runCurrent()
        api.authStates.emit(TgAuthState.Ready); runCurrent()
        assertEquals(TelegramState.Ready(TgUser(42, "Test User")), s.state.value)
    }

    @Test
    fun `a TV never asks for a phone number, it waits for approval`() = runTest {
        val api = FakeTelegramApi()
        val s = session(api, TelegramRole.Tv)
        api.authStates.emit(TgAuthState.WaitPhoneNumber); runCurrent()
        assertEquals(listOf("requestQrLogin"), api.calls)
        api.authStates.emit(TgAuthState.WaitOtherDevice(link)); runCurrent()
        assertEquals(TelegramState.WaitingForApproval(link), s.state.value)
    }

    @Test
    fun `only one input is checked at a time`() = runTest {
        val api = FakeTelegramApi()
        val s = session(api, TelegramRole.Phone)
        api.authStates.emit(TgAuthState.WaitPhoneNumber); runCurrent()
        s.submitPhoneNumber("+31600000001")
        s.submitPhoneNumber("+31600000002") // second tap before the first finished
        runCurrent()
        assertEquals(listOf("setPhoneNumber:+31600000001"), api.calls)
        assertEquals(false, s.busy.value)
    }

    @Test
    fun `a rejected input keeps the step and says why`() = runTest {
        val api = FakeTelegramApi()
        val s = session(api, TelegramRole.Phone)
        api.authStates.emit(TgAuthState.WaitCode); runCurrent()
        api.failNext = TgException(400, "PHONE_CODE_INVALID")
        s.submitCode("00000"); runCurrent()
        assertEquals(TelegramState.NeedsCode, s.state.value)
        assertEquals("That code isn't right. Check the latest code in Telegram.", s.lastError.value)
        api.authStates.emit(TgAuthState.WaitPassword("")); runCurrent()
        assertNull("cleared when Telegram moves on", s.lastError.value)
    }

    @Test
    fun `waits and fresh-session refusals are explained`() {
        assertEquals(
            "Telegram asks to wait 5 minutes before trying again.",
            TelegramSession.describe(TgException(429, "Too Many Requests: retry after 300")),
        )
        assertEquals(
            "Telegram asks to wait 2 hours before trying again.",
            TelegramSession.describe(TgException(420, "FLOOD_WAIT_7200")),
        )
        assertTrue(TelegramSession.describe(TgException(406, "FRESH_RESET_AUTHORISATION_FORBIDDEN")).contains("24 hours"))
        assertTrue(TelegramSession.describe(TgException(500, "Wrong database encryption key")).contains("damaged"))
        assertTrue(TelegramSession.describe(TgException(500, "database disk image is malformed")).contains("connect it again"))
    }

    @Test
    fun `unsupported steps fail with a useful message`() = runTest {
        val api = FakeTelegramApi()
        val s = session(api, TelegramRole.Phone)
        api.authStates.emit(TgAuthState.Unsupported("WaitRegistration")); runCurrent()
        assertEquals(
            TelegramState.Failed("This phone number has no Telegram account. Create one in the Telegram app first."),
            s.state.value,
        )
    }

    @Test
    fun `phone approves only real login links`() = runTest {
        val api = FakeTelegramApi()
        val s = session(api, TelegramRole.Phone)
        assertTrue(s.approveTvLogin("https://example.com/?token=abc", OWN_API).isFailure)
        api.onConfirm = { api.sessions += TgSessionInfo(7, false, OWN_API, "Cablegram", "Chromecast", 50) }
        assertEquals(ApprovedLogin(TgSessionInfo(7, false, OWN_API, "Cablegram", "Chromecast", 50)), s.approveTvLogin(link, OWN_API).getOrNull())
        assertEquals(listOf("confirmQrLogin"), api.calls)
        api.onConfirm = null
        api.failNext = TgException(400, "AUTH_TOKEN_EXPIRED")
        assertEquals("400 AUTH_TOKEN_EXPIRED", s.approveTvLogin(link, OWN_API).exceptionOrNull()?.message)
    }

    @Test
    fun `a link made by another Telegram app is ended at once and the approval fails`() = runTest {
        val api = FakeTelegramApi()
        val s = session(api, TelegramRole.Phone)
        api.onConfirm = { api.sessions += TgSessionInfo(9, false, apiId = 2040, applicationName = "Telegram Desktop", deviceModel = "MacBook", logInDate = 60) }
        val outcome = s.approveTvLogin(link, OWN_API)
        assertEquals("403 UNEXPECTED_CLIENT", outcome.exceptionOrNull()?.message)
        assertEquals(listOf("confirmQrLogin", "terminateSession:9"), api.calls)
    }

    @Test
    fun `nothing is approved when the sessions can't be listed first`() = runTest {
        val api = FakeTelegramApi()
        val s = session(api, TelegramRole.Tv)
        api.failSessions = true
        assertEquals("500 SESSIONS_UNAVAILABLE", s.approvePhoneLogin(link, OWN_API).exceptionOrNull()?.message)
        assertEquals("never confirmed blind", emptyList<String>(), api.calls)
    }

    @Test
    fun `an unrelated session that already existed is never taken for the approved one`() = runTest {
        val api = FakeTelegramApi()
        val s = session(api, TelegramRole.Phone)
        // The owner's own desktop, signed in earlier and more recently active than anything else.
        api.sessions += TgSessionInfo(3, false, apiId = 2040, applicationName = "Telegram Desktop", deviceModel = "MacBook", logInDate = 99)
        // Telegram is slow to list the new TV session: the approval succeeds without naming a session.
        assertEquals(ApprovedLogin(null), s.approveTvLogin(link, OWN_API).getOrNull())
        assertEquals("the desktop is left alone", listOf("confirmQrLogin"), api.calls)
    }

    @Test
    fun `library channel is opened by id, found by title, or created by the phone`() = runTest {
        val api = FakeTelegramApi()
        val s = session(api, TelegramRole.Phone)
        api.chats[-100L] = TelegramSession.LIBRARY_TITLE
        assertEquals(TgLibrary(-100L, created = false), s.openLibrary(knownChatId = -100L, allowCreate = false))
        assertEquals("found by title when the known id is gone", -100L, s.openLibrary(knownChatId = -5L, allowCreate = false)?.chatId)

        api.chats.clear()
        assertNull("a TV never creates the channel", s.openLibrary(null, allowCreate = false))
        assertEquals(TgLibrary(-1001L, created = true), s.openLibrary(null, allowCreate = true))
        assertEquals("createChannel:Cablegram library", api.calls.last())
    }

    @Test
    fun `the logo goes on a new channel or one without a photo, never over the owner's photo`() = runTest {
        val api = FakeTelegramApi()
        val s = session(api, TelegramRole.Phone)
        s.openLibrary(null, allowCreate = true, logoPath = "/logo.jpg")
        assertEquals("setChannelPhoto:-1001", api.calls.last())

        api.calls.clear(); api.photos.clear()
        s.openLibrary(-1001L, allowCreate = false, logoPath = "/logo.jpg")
        assertEquals("existing channel without a photo gets the logo", listOf("setChannelPhoto:-1001"), api.calls)

        api.calls.clear(); api.photos[-1001L] = "/owner-chose-this.jpg"
        s.openLibrary(-1001L, allowCreate = false, logoPath = "/logo.jpg")
        assertEquals("the owner's photo is kept", emptyList<String>(), api.calls)

        api.photos.clear(); api.failNext = TgException(400, "PHOTO_INVALID_DIMENSIONS")
        assertEquals("a failed upload doesn't fail linking", -1001L, s.openLibrary(-1001L, allowCreate = false, logoPath = "/logo.jpg")?.chatId)
    }

    @Test
    fun `a channel the owner left is reported and replaced by a new one`() = runTest {
        val api = FakeTelegramApi()
        val s = session(api, TelegramRole.Phone)
        api.chats[-100L] = TelegramSession.LIBRARY_TITLE
        assertTrue(s.isLibraryAvailable(-100L))

        api.left += -100L
        assertEquals(false, s.isLibraryAvailable(-100L))
        assertEquals(false, s.isLibraryAvailable(-999L))
        assertNull("a left channel is not reused", s.openLibrary(-100L, allowCreate = false))
        assertEquals(TgLibrary(-1001L, created = true), s.openLibrary(-100L, allowCreate = true))
    }

    @Test
    fun `phone numbers are normalised to international format`() {
        assertEquals("+31612345678", TelegramSession.normalizePhone("+31 6 12 34 56 78"))
        assertEquals("+31612345678", TelegramSession.normalizePhone("0031 612345678"))
        assertEquals("0612345678", TelegramSession.normalizePhone("06-12345678"))
    }
}
