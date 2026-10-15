package app.cablegram.telegram

import kotlinx.coroutines.flow.Flow

// Shared by apps/android-phone and apps/android-tv (spec 004). Keep both copies identical;
// scripts/quality/check-telegram-sync.mjs fails the build when they drift.

/**
 * The part of Telegram (TDLib) Cablegram uses, behind an interface so the session logic and the
 * stream reader can be tested without the native library. [TdlibTelegramApi] is the real one.
 */
interface TelegramApi {
    val authStates: Flow<TgAuthState>
    val fileUpdates: Flow<TgFile>
    val newMessages: Flow<TgMessage>
    val deletedMessages: Flow<TgDeletedMessages>
    /** A message's text or caption was edited (a bot that posts a placeholder first, say); `date` is 0. */
    val editedMessages: Flow<TgMessage>

    suspend fun start(parameters: TgParameters)
    suspend fun requestQrLogin()
    suspend fun setPhoneNumber(phone: String)
    suspend fun checkCode(code: String)
    suspend fun checkPassword(password: String)
    suspend fun confirmQrLogin(link: String)
    suspend fun logOut()
    /** Closes the client and waits until its database is released, without signing out: another client may then open it. */
    suspend fun close()

    suspend fun me(): TgUser
    suspend fun chat(chatId: Long): TgChat?
    suspend fun findChatByTitle(title: String): Long?
    suspend fun createChannel(title: String, description: String): Long
    /** Sets the library channel's photo from a local image file (the Cablegram logo). */
    suspend fun setChannelPhoto(chatId: Long, imagePath: String)
    suspend fun videos(chatId: Long, fromMessageId: Long, limit: Int): TgVideoPage
    suspend fun message(chatId: Long, messageId: Long): TgMessage?
    suspend fun deleteMessages(chatId: Long, messageIds: LongArray)
    /**
     * "Save to Telegram" (spec 004 T011): sends one video the owner chose into the library channel, marked
     * streamable, and returns it once Telegram has stored it. [onProgress] gets (uploaded, total) bytes. This
     * is the only call that sends anything, and only into the household's library channel.
     */
    suspend fun uploadVideo(chatId: Long, path: String, caption: String?, onProgress: (Long, Long) -> Unit): TgVideo
    /** The largest file the account may upload: 2 GB, or 4 GB with Telegram Premium. */
    suspend fun uploadLimitBytes(): Long

    suspend fun file(fileId: Int): TgFile
    suspend fun download(fileId: Int, offset: Long, limit: Long)
    suspend fun readPart(fileId: Int, offset: Long, count: Long): ByteArray
    suspend fun cancelDownload(fileId: Int)
    suspend fun deleteLocalFile(fileId: Int)

    /** Whether the account has two-step verification, and its hint; never the password itself. */
    suspend fun passwordState(): TgPasswordState

    suspend fun activeSessions(): List<TgSessionInfo>
    suspend fun terminateSession(sessionId: Long)
}

/** A failed Telegram request: [code] is TDLib's error code, [name] its message such as `PASSWORD_HASH_INVALID`. */
class TgException(val code: Int, val name: String) : Exception("$code $name") {
    /** Telegram asks to wait before retrying (`FLOOD_WAIT_x` or "Too Many Requests: retry after x"). */
    val retryAfterSeconds: Int?
        get() = Regex("""(?:FLOOD_WAIT_|retry after )(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
}

data class TgParameters(
    val databaseDirectory: String,
    val filesDirectory: String,
    val databaseKey: ByteArray,
    val apiId: Int,
    val apiHash: String,
    val deviceModel: String,
    val systemVersion: String,
    val applicationVersion: String,
)

sealed interface TgAuthState {
    data object WaitParameters : TgAuthState
    data object WaitPhoneNumber : TgAuthState
    data object WaitCode : TgAuthState
    data class WaitPassword(val hint: String) : TgAuthState
    data class WaitOtherDevice(val link: String) : TgAuthState
    data object Ready : TgAuthState
    data object LoggingOut : TgAuthState
    data object Closed : TgAuthState
    /** A state Cablegram does not support, e.g. registration of a new Telegram account. */
    data class Unsupported(val name: String) : TgAuthState
}

data class TgUser(val id: Long, val displayName: String)

data class TgPasswordState(val hasPassword: Boolean, val hint: String)

/** [isMember] is false after the owner left the channel (Telegram keeps a creator who left as a non-member). */
data class TgChat(val id: Long, val title: String, val hasPhoto: Boolean, val isMember: Boolean = true)

/** The household's library channel, and whether Cablegram just created it. */
data class TgLibrary(val chatId: Long, val created: Boolean)

data class TgFile(
    val id: Int,
    val size: Long,
    /** Telegram's id for the file's content, stable across forwards and accounts. */
    val uniqueId: String,
    val downloadOffset: Long,
    val downloadedPrefixSize: Long,
    val downloadedSize: Long,
    val completed: Boolean,
)

/** A video in the library channel, sent as a video or as a video document. */
data class TgVideo(
    val chatId: Long,
    val messageId: Long,
    val file: TgFile,
    val fileName: String,
    val caption: String,
    val mimeType: String,
    val durationSeconds: Int,
    val supportsStreaming: Boolean,
    val date: Long,
)

/** [text] is a text message's text or a media message's caption (photo, video, document, animation). */
data class TgMessage(val chatId: Long, val messageId: Long, val video: TgVideo?, val text: String = "")

data class TgDeletedMessages(val chatId: Long, val messageIds: LongArray)

/** [notes] are the page's other messages that have text: a TMDB reference to one of the videos may be among them. */
data class TgVideoPage(val videos: List<TgVideo>, val nextFromMessageId: Long, val notes: List<TgMessage> = emptyList())

data class TgSessionInfo(
    val id: Long,
    val isCurrent: Boolean,
    val apiId: Int,
    val applicationName: String,
    val deviceModel: String,
    val logInDate: Long,
)
