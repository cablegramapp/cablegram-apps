package app.cablegram.telegram

import org.junit.Assert.assertEquals
import org.junit.Test

// Shared by apps/android-phone and apps/android-tv tests (spec 004). Keep both copies identical.

/**
 * FR-022: Cablegram tells users it only uses the "Cablegram library" channel, never reads chats or lists
 * contacts, and sends nothing except a video the owner chose to save into that channel. This pins what the Telegram layer can do. Adding a call here
 * needs a review against that promise (and the privacy page), not just a green build.
 */
class TelegramApiSurfaceTest {
    private val allowed = setOf(
        // Sign-in and sign-out.
        "start", "requestQrLogin", "setPhoneNumber", "checkCode", "checkPassword", "confirmQrLogin", "logOut", "me",
        // The library channel only: find or create it, set its logo, list its videos, delete its messages.
        "chat", "findChatByTitle", "createChannel", "setChannelPhoto", "videos", "message", "deleteMessages",
        // Save to Telegram (T011): the only call that sends anything. It sends one video the owner chose,
        // into the library channel, and nothing else; uploadLimitBytes only reads the account's size limit.
        "uploadVideo", "uploadLimitBytes",
        // Files of those videos.
        "file", "download", "readPart", "cancelDownload", "deleteLocalFile",
        // Ending Cablegram's own TV sessions (spec 004 US8).
        "activeSessions", "terminateSession",
        // Updates.
        "getAuthStates", "getFileUpdates", "getNewMessages", "getDeletedMessages",
    )

    @Test
    fun `the Telegram layer exposes only what the library needs`() {
        val actual = TelegramApi::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(allowed, actual)
    }
}
