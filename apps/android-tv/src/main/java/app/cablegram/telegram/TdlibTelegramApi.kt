package app.cablegram.telegram

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateClosing
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatMemberStatusBanned
import dev.g000sha256.tdl.dto.ChatMemberStatusCreator
import dev.g000sha256.tdl.dto.ChatMemberStatusLeft
import dev.g000sha256.tdl.dto.ChatMemberStatusRestricted
import dev.g000sha256.tdl.dto.ChatType
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.File
import dev.g000sha256.tdl.dto.InputChatPhotoStatic
import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessageVideo
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputMessageVideo
import dev.g000sha256.tdl.dto.InputVideo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

// Shared by apps/android-phone and apps/android-tv (spec 004). Keep both copies identical.

/** [TelegramApi] over TDLib (`dev.g000sha256:tdl-coroutines-android`). */
class TdlibTelegramApi(private val client: TdlClient = TdlClient.create()) : TelegramApi {

    /**
     * Updates only report changes, so a late subscriber would miss the current state: subscribe first,
     * then ask for the current one; duplicates of the same state are dropped.
     */
    override val authStates: Flow<TgAuthState> = channelFlow {
        launch { client.authorizationStateUpdates.collect { send(toAuthState(it.authorizationState)) } }
        yield()
        (client.getAuthorizationState() as? TdlResult.Success)?.let { send(toAuthState(it.result)) }
    }.distinctUntilChanged()
    override val fileUpdates: Flow<TgFile> = client.fileUpdates.map { toFile(it.file) }
    override val newMessages: Flow<TgMessage> = client.newMessageUpdates.map { toMessage(it.message) }
    override val deletedMessages: Flow<TgDeletedMessages> = client.deleteMessagesUpdates
        // Messages evicted from TDLib's cache are not deletions in the channel.
        .filter { it.isPermanent && !it.fromCache }
        .map { TgDeletedMessages(it.chatId, it.messageIds) }

    override suspend fun start(parameters: TgParameters) {
        client.setTdlibParameters(
            false,
            parameters.databaseDirectory,
            parameters.filesDirectory,
            parameters.databaseKey,
            true, true, true, false,
            parameters.apiId,
            parameters.apiHash,
            "en",
            parameters.deviceModel,
            parameters.systemVersion,
            parameters.applicationVersion,
        ).ok()
    }

    override suspend fun requestQrLogin() { client.requestQrCodeAuthentication(LongArray(0)).ok() }
    override suspend fun setPhoneNumber(phone: String) { client.setAuthenticationPhoneNumber(phone, null).ok() }
    override suspend fun checkCode(code: String) { client.checkAuthenticationCode(code).ok() }
    override suspend fun checkPassword(password: String) { client.checkAuthenticationPassword(password).ok() }
    override suspend fun confirmQrLogin(link: String) { client.confirmQrCodeAuthentication(link).ok() }
    override suspend fun logOut() { client.logOut().ok() }

    override suspend fun me(): TgUser {
        val user = client.getMe().ok()
        return TgUser(user.id, listOf(user.firstName, user.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Telegram" })
    }

    override suspend fun chat(chatId: Long): TgChat? = when (val r = client.getChat(chatId)) {
        is TdlResult.Success -> TgChat(r.result.id, r.result.title, hasPhoto = r.result.photo != null, isMember = isMember(r.result.type))
        is TdlResult.Failure -> null
    }

    private suspend fun isMember(type: ChatType): Boolean {
        if (type !is ChatTypeSupergroup) return true
        val status = (client.getSupergroup(type.supergroupId) as? TdlResult.Success)?.result?.status ?: return false
        return when (status) {
            is ChatMemberStatusCreator -> status.isMember
            is ChatMemberStatusRestricted -> status.isMember
            is ChatMemberStatusLeft, is ChatMemberStatusBanned -> false
            else -> true
        }
    }

    override suspend fun findChatByTitle(title: String): Long? {
        // Loads the main chat list once so TDLib knows the channel; nothing is read from other chats.
        client.loadChats(ChatListMain(), 200)
        val ids = client.searchChats(title, null, 20).ok().chatIds
        // A channel the owner left can still be in TDLib's list; only one they are in counts.
        return ids.firstOrNull { id -> chat(id)?.let { it.title == title && it.isMember } == true }
    }

    override suspend fun createChannel(title: String, description: String): Long =
        client.createNewSupergroupChat(title, false, true, description, null, 0, false).ok().id

    override suspend fun setChannelPhoto(chatId: Long, imagePath: String) {
        client.setChatPhoto(chatId, InputChatPhotoStatic(InputFileLocal(imagePath))).ok()
    }

    override suspend fun videos(chatId: Long, fromMessageId: Long, limit: Int): TgVideoPage {
        val messages = client.getChatHistory(chatId, fromMessageId, 0, limit, false).ok().messages.filterNotNull()
        return TgVideoPage(
            videos = messages.mapNotNull { toMessage(it).video },
            nextFromMessageId = messages.lastOrNull()?.id ?: 0,
        )
    }

    override suspend fun message(chatId: Long, messageId: Long): TgMessage? = when (val r = client.getMessage(chatId, messageId)) {
        is TdlResult.Success -> toMessage(r.result)
        is TdlResult.Failure -> null
    }

    override suspend fun deleteMessages(chatId: Long, messageIds: LongArray) {
        client.deleteMessages(chatId, messageIds, true).ok()
    }

    override suspend fun uploadLimitBytes(): Long = if (client.getMe().ok().isPremium) PREMIUM_UPLOAD_LIMIT else UPLOAD_LIMIT

    override suspend fun uploadVideo(chatId: Long, path: String, caption: String?, onProgress: (Long, Long) -> Unit): TgVideo = coroutineScope {
        // Updates are hot: listen before sending, so a fast upload's result cannot be missed.
        val outcomes = ConcurrentHashMap<Long, Result<Message>>()
        val signal = CompletableDeferred<Unit>()
        var temporaryId = 0L
        val listeners = listOf(
            launch(start = CoroutineStart.UNDISPATCHED) {
                client.messageSendSucceededUpdates.collect { outcomes[it.oldMessageId] = Result.success(it.message); if (it.oldMessageId == temporaryId) signal.complete(Unit) }
            },
            launch(start = CoroutineStart.UNDISPATCHED) {
                client.messageSendFailedUpdates.collect {
                    outcomes[it.oldMessageId] = Result.failure(TgException(it.error.code, it.error.message)); if (it.oldMessageId == temporaryId) signal.complete(Unit)
                }
            },
        )
        val video = InputVideo(InputFileLocal(path), null, null, 0, IntArray(0), 0, 0, 0, true)
        val content = InputMessageVideo(video, FormattedText(caption.orEmpty(), emptyArray()), false, null, false)
        val sent = client.sendMessage(chatId, null, null, null, null, content).ok()
        temporaryId = sent.id
        val uploading = (sent.content as? MessageVideo)?.video?.video?.id
        val progress = launch {
            client.fileUpdates.filter { it.file.id == uploading }.collect { onProgress(it.file.remote.uploadedSize, it.file.size.takeIf { size -> size > 0 } ?: it.file.expectedSize) }
        }
        try {
            if (outcomes[temporaryId] == null) signal.await()
            val message = outcomes.getValue(temporaryId).getOrThrow()
            toMessage(message).video ?: throw TgException(0, "The uploaded message has no video")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelled by the user: remove the half-sent message so no broken video stays in the channel.
            runCatching { client.deleteMessages(chatId, longArrayOf(temporaryId), true) }
            throw e
        } finally {
            progress.cancel()
            listeners.forEach { it.cancel() }
        }
    }

    override suspend fun file(fileId: Int): TgFile = toFile(client.getFile(fileId).ok())
    override suspend fun download(fileId: Int, offset: Long, limit: Long) {
        client.downloadFile(fileId, DOWNLOAD_PRIORITY, offset, limit, false).ok()
    }
    override suspend fun readPart(fileId: Int, offset: Long, count: Long): ByteArray = client.readFilePart(fileId, offset, count).ok().data
    override suspend fun cancelDownload(fileId: Int) { client.cancelDownloadFile(fileId, false).ok() }
    override suspend fun deleteLocalFile(fileId: Int) { client.deleteFile(fileId).ok() }

    override suspend fun activeSessions(): List<TgSessionInfo> = client.getActiveSessions().ok().sessions.map {
        TgSessionInfo(it.id, it.isCurrent, it.apiId, it.applicationName, it.deviceModel, it.logInDate.toLong())
    }

    override suspend fun terminateSession(sessionId: Long) { client.terminateSession(sessionId).ok() }

    private fun toMessage(message: Message): TgMessage {
        val video = when (val content = message.content) {
            is MessageVideo -> TgVideo(
                chatId = message.chatId,
                messageId = message.id,
                file = toFile(content.video.video),
                fileName = content.video.fileName,
                caption = content.caption.text,
                mimeType = content.video.mimeType,
                durationSeconds = content.video.duration,
                supportsStreaming = content.video.supportsStreaming,
                date = message.date.toLong(),
            )
            is MessageDocument -> content.document.takeIf { it.mimeType.startsWith("video/") }?.let { doc ->
                TgVideo(
                    chatId = message.chatId,
                    messageId = message.id,
                    file = toFile(doc.document),
                    fileName = doc.fileName,
                    caption = content.caption.text,
                    mimeType = doc.mimeType,
                    durationSeconds = 0,
                    supportsStreaming = false,
                    date = message.date.toLong(),
                )
            }
            else -> null
        }
        return TgMessage(message.chatId, message.id, video)
    }

    private companion object {
        const val DOWNLOAD_PRIORITY = 32
        const val UPLOAD_LIMIT = 2000L * 1024 * 1024
        const val PREMIUM_UPLOAD_LIMIT = 4000L * 1024 * 1024

        fun toFile(file: File) = TgFile(
            id = file.id,
            size = file.size.takeIf { it > 0 } ?: file.expectedSize,
            uniqueId = file.remote.uniqueId,
            downloadOffset = file.local.downloadOffset,
            downloadedPrefixSize = file.local.downloadedPrefixSize,
            downloadedSize = file.local.downloadedSize,
            completed = file.local.isDownloadingCompleted,
        )

        fun toAuthState(state: AuthorizationState): TgAuthState = when (state) {
            is AuthorizationStateWaitTdlibParameters -> TgAuthState.WaitParameters
            is AuthorizationStateWaitPhoneNumber -> TgAuthState.WaitPhoneNumber
            is AuthorizationStateWaitCode -> TgAuthState.WaitCode
            is AuthorizationStateWaitPassword -> TgAuthState.WaitPassword(state.passwordHint)
            is AuthorizationStateWaitOtherDeviceConfirmation -> TgAuthState.WaitOtherDevice(state.link)
            is AuthorizationStateReady -> TgAuthState.Ready
            is AuthorizationStateLoggingOut -> TgAuthState.LoggingOut
            is AuthorizationStateClosing, is AuthorizationStateClosed -> TgAuthState.Closed
            else -> TgAuthState.Unsupported(state.javaClass.simpleName.removePrefix("AuthorizationState"))
        }

        fun <T> TdlResult<T>.ok(): T = when (this) {
            is TdlResult.Success -> result
            is TdlResult.Failure -> throw TgException(code, message)
        }
    }
}
