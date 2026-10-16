package app.cablegram.telegram

import org.junit.Assert.assertEquals
import org.junit.Test

// Shared by apps/android-phone and apps/android-tv tests (spec 004). Keep both copies identical.

/**
 * Pins the Telegram surface to library management, device sign-in, and joining the fixed Cablegram
 * channels after an explicit Save. No reading other chats, listing contacts, or messaging people.
 * Adding a call needs review against the README and privacy claims.
 */
class TelegramApiSurfaceTest {
    private val allowed = setOf(
        // Sign-in and sign-out.
        "start", "requestQrLogin", "setPhoneNumber", "checkCode", "checkPassword", "confirmQrLogin", "logOut", "close", "me",
        // The library channel only: find or create it, set its logo, list its videos, delete its messages.
        "chat", "findChatByTitle", "createChannel", "setChannelPhoto", "videos", "message", "deleteMessages",
        // Save to Telegram (T011): the only call that sends anything. It sends one video the owner chose,
        // into the library channel, and nothing else; uploadLimitBytes only reads the account's size limit.
        "uploadVideo", "uploadLimitBytes",
        // Optional fixed Cablegram channels: only joined after the owner taps Save.
        "joinCommunityChannel",
        // Files of those videos.
        "file", "download", "readPart", "cancelDownload", "deleteLocalFile",
        // Ending Cablegram's own TV sessions (spec 004 US8).
        "passwordState", "activeSessions", "terminateSession",
        // Updates.
        "getAuthStates", "getFileUpdates", "getNewMessages", "getDeletedMessages", "getEditedMessages",
    )

    @Test
    fun `the Telegram layer exposes only what the library needs`() {
        val actual = TelegramApi::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(allowed, actual)
    }
}
