package app.cablegram.telegram

import kotlinx.coroutines.flow.MutableSharedFlow

// Shared by apps/android-phone and apps/android-tv tests (spec 004). Keep both copies identical.

/** In-memory Telegram: files "download" only when the test calls [deliver]. */
class FakeTelegramApi : TelegramApi {
    override val authStates = MutableSharedFlow<TgAuthState>(replay = 1, extraBufferCapacity = 16)
    override val fileUpdates = MutableSharedFlow<TgFile>(extraBufferCapacity = 64)
    override val newMessages = MutableSharedFlow<TgMessage>(extraBufferCapacity = 16)
    override val deletedMessages = MutableSharedFlow<TgDeletedMessages>(extraBufferCapacity = 16)

    val calls = mutableListOf<String>()
    val files = mutableMapOf<Int, TgFile>()
    val content = mutableMapOf<Int, ByteArray>()
    val chats = mutableMapOf<Long, String>()
    val photos = mutableMapOf<Long, String>()
    val left = mutableSetOf<Long>()
    var user = TgUser(42, "Test User")
    var failNext: TgException? = null
    var failReadsRemaining = 0
    var createdChannelId = -1001L

    private fun record(call: String) {
        calls += call
        failNext?.let { failNext = null; throw it }
    }

    override suspend fun start(parameters: TgParameters) = record("start")
    override suspend fun requestQrLogin() = record("requestQrLogin")
    override suspend fun setPhoneNumber(phone: String) = record("setPhoneNumber:$phone")
    override suspend fun checkCode(code: String) = record("checkCode:$code")
    override suspend fun checkPassword(password: String) = record("checkPassword")
    /** What confirming a login link adds to [sessions] (the device the link came from). */
    var onConfirm: (() -> Unit)? = null
    override suspend fun confirmQrLogin(link: String) {
        record("confirmQrLogin")
        onConfirm?.invoke()
    }
    override suspend fun logOut() = record("logOut")
    override suspend fun me(): TgUser = user

    override suspend fun chat(chatId: Long): TgChat? =
        chats[chatId]?.let { TgChat(chatId, it, hasPhoto = chatId in photos, isMember = chatId !in left) }
    override suspend fun findChatByTitle(title: String): Long? =
        chats.entries.firstOrNull { it.value == title && it.key !in left }?.key
    override suspend fun createChannel(title: String, description: String): Long {
        record("createChannel:$title")
        chats[createdChannelId] = title
        return createdChannelId
    }
    override suspend fun setChannelPhoto(chatId: Long, imagePath: String) {
        record("setChannelPhoto:$chatId")
        photos[chatId] = imagePath
    }
    /** Pages of the channel keyed by the `fromMessageId` that asks for them. */
    val videoPages = mutableMapOf<Long, TgVideoPage>()
    var videoPageRequests = 0
    override suspend fun videos(chatId: Long, fromMessageId: Long, limit: Int): TgVideoPage {
        videoPageRequests++
        return videoPages[fromMessageId] ?: TgVideoPage(emptyList(), 0)
    }
    override suspend fun message(chatId: Long, messageId: Long): TgMessage? = null
    var uploadLimit = 2000L * 1024 * 1024
    /** What the next [uploadVideo] returns; null makes it fail like a Telegram error. */
    var uploadResult: TgVideo? = null
    override suspend fun uploadLimitBytes(): Long = uploadLimit
    override suspend fun uploadVideo(chatId: Long, path: String, caption: String?, onProgress: (Long, Long) -> Unit): TgVideo {
        record("uploadVideo:$chatId:$path")
        val video = uploadResult ?: throw TgException(400, "FILE_PARTS_INVALID")
        onProgress(video.file.size / 2, video.file.size)
        onProgress(video.file.size, video.file.size)
        return video
    }
    override suspend fun deleteMessages(chatId: Long, messageIds: LongArray) = record("deleteMessages:$chatId:${messageIds.joinToString()}")

    override suspend fun file(fileId: Int): TgFile = files.getValue(fileId)
    override suspend fun download(fileId: Int, offset: Long, limit: Long) {
        record("download:$fileId@$offset+$limit")
        // Starting a download somewhere else moves TDLib's contiguous window there.
        val f = files.getValue(fileId)
        if (offset != f.downloadOffset) files[fileId] = f.copy(downloadOffset = offset, downloadedPrefixSize = 0)
    }
    override suspend fun readPart(fileId: Int, offset: Long, count: Long): ByteArray {
        if (failReadsRemaining > 0) {
            failReadsRemaining--
            throw TgException(400, "Failed to read the file")
        }
        val bytes = content.getValue(fileId)
        return bytes.copyOfRange(offset.toInt(), (offset + count).toInt())
    }
    override suspend fun cancelDownload(fileId: Int) = record("cancelDownload:$fileId")
    override suspend fun deleteLocalFile(fileId: Int) {
        record("deleteLocalFile:$fileId")
        files[fileId] = files.getValue(fileId).copy(downloadedPrefixSize = 0, downloadedSize = 0)
    }
    val sessions = mutableListOf(TgSessionInfo(1, isCurrent = true, apiId = 100, applicationName = "Cablegram", deviceModel = "Pixel", logInDate = 1))
    var failSessions = false
    override suspend fun activeSessions(): List<TgSessionInfo> {
        if (failSessions) throw TgException(500, "INTERNAL")
        return sessions.toList()
    }
    override suspend fun terminateSession(sessionId: Long) = record("terminateSession:$sessionId")

    fun addFile(id: Int, size: Int): TgFile {
        content[id] = ByteArray(size) { (it % 251).toByte() }
        return TgFile(id, size.toLong(), "unique-$id", 0, 0, 0, false).also { files[id] = it }
    }

    /** TDLib has now downloaded [bytes] contiguous bytes from the current window start. */
    suspend fun deliver(id: Int, bytes: Long, stored: Long = bytes) {
        val f = files.getValue(id)
        val updated = f.copy(downloadedPrefixSize = bytes, downloadedSize = stored)
        files[id] = updated
        fileUpdates.emit(updated)
    }
}
