package app.cablegram.telegram

import dev.g000sha256.tdl.dto.ChatAdministratorRights
import dev.g000sha256.tdl.dto.ChatMemberStatusAdministrator
import dev.g000sha256.tdl.dto.ChatTypePrivate
import dev.g000sha256.tdl.dto.MessageSenderUser
import dev.g000sha256.tdl.dto.UserTypeBot
import dev.g000sha256.tdl.dto.ReplyMarkupInlineKeyboard
import dev.g000sha256.tdl.dto.InlineKeyboardButtonTypeUrl
import dev.g000sha256.tdl.dto.TextEntityTypeTextUrl
import dev.g000sha256.tdl.dto.ChatFolder
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatFolderInfo
import kotlinx.coroutines.flow.MutableStateFlow
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
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessageContent
import dev.g000sha256.tdl.dto.MessagePhoto
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputMessageVideo
import dev.g000sha256.tdl.dto.InputVideo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Shared by apps/android-phone and apps/android-tv (spec 004). Keep both copies identical.

/** [TelegramApi] over TDLib (`dev.g000sha256:tdl-coroutines-android`). */
class TdlibTelegramApi(private val client: TdlClient = TdlClient.create()) : TelegramApi {

    private val knownFolders = MutableStateFlow<List<ChatFolderInfo>?>(null)
    private val folderOrganizer = CablegramFolderOrganizer(object : CablegramFolderStore {
        override suspend fun folders(): List<ChatFolderInfo> = knownFolders.first { it != null }!!
        override suspend fun folder(id: Int): ChatFolder = client.getChatFolder(id).ok()
        override suspend fun create(folder: ChatFolder) {
            val created = client.createChatFolder(folder).ok()
            // The native update may arrive after this call; cache the result before another merge.
            knownFolders.value = knownFolders.value.orEmpty().filter { it.id != created.id } + created
        }
        override suspend fun edit(id: Int, folder: ChatFolder) { client.editChatFolder(id, folder).ok() }
    })

    /**
     * Updates only report changes, so a late subscriber would miss the current state: subscribe first,
     * then ask for the current one; duplicates of the same state are dropped.
     */
    override val authStates: Flow<TgAuthState> = channelFlow {
        // Subscribe before TDLib parameters are supplied so initial folder metadata cannot be missed.
        launch { client.chatFoldersUpdates.collect { knownFolders.value = it.chatFolders.toList() } }
        launch { client.authorizationStateUpdates.collect { send(toAuthState(it.authorizationState)) } }
        yield()
        (client.getAuthorizationState() as? TdlResult.Success)?.let { send(toAuthState(it.result)) }
    }.distinctUntilChanged()
    override val fileUpdates: Flow<TgFile> = client.fileUpdates.map { toFile(it.file) }
    override val newMessages: Flow<TgMessage> = client.newMessageUpdates.map { toMessage(it.message) }
    override val editedMessages: Flow<TgMessage> = client.messageContentUpdates.map { toMessage(it.chatId, it.messageId, 0, it.newContent) }
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
    override suspend fun close() {
        withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
            coroutineScope {
                // Subscribe before asking, so the Closed update can't be missed.
                val closed = async(start = CoroutineStart.UNDISPATCHED) {
                    client.authorizationStateUpdates.first { it.authorizationState is AuthorizationStateClosed }
                }
                if (client.close() is TdlResult.Success) closed.await() else closed.cancel()
            }
        }
    }

    override suspend fun joinCommunityChannel(channel: CablegramChannel) {
        val chat = client.searchPublicChat(channel.username).ok()
        require(chat.type is ChatTypeSupergroup) { "The Cablegram channel is unavailable" }
        if (!isMember(chat.type)) client.joinChat(chat.id).ok()
    }

    private var discoveredLibraryBotIds: Set<Long> = emptySet()

    override suspend fun recommendedLibraryBots(): List<RecommendedLibraryBot> {
        discoveredLibraryBotIds = emptySet()
        val hub = client.searchPublicChat(CablegramChannel.BotHubs.username).ok()
        require(hub.type is ChatTypeSupergroup && client.getSupergroup((hub.type as ChatTypeSupergroup).supergroupId).ok().isChannel) {
            "Cablegram Bot Hubs is unavailable"
        }
        val candidates = linkedSetOf<String>()
        var from = 0L
        while (true) {
            val messages = client.getChatHistory(hub.id, from, 0, 100, false).ok().messages.filterNotNull()
            if (messages.isEmpty()) break
            for (message in messages) {
                val formatted = when (val content = message.content) {
                    is MessageText -> content.text
                    is MessagePhoto -> content.caption
                    is MessageVideo -> content.caption
                    is MessageDocument -> content.caption
                    is MessageAnimation -> content.caption
                    else -> null
                }
                val links = formatted?.entities.orEmpty().mapNotNull { (it.type as? TextEntityTypeTextUrl)?.url } +
                    (message.replyMarkup as? ReplyMarkupInlineKeyboard)?.rows.orEmpty().flatMap { row ->
                        row.mapNotNull { (it.type as? InlineKeyboardButtonTypeUrl)?.url }
                    }
                candidates += BotHubLinks.usernames(formatted?.text.orEmpty(), links)
            }
            val next = messages.last().id
            if (next == from) break
            from = next
        }
        val bots = mutableListOf<RecommendedLibraryBot>()
        for (username in candidates) {
            val chat = when (val result = client.searchPublicChat(username)) {
                is TdlResult.Success -> result.result
                is TdlResult.Failure -> if (result.code == 400 || result.code == 404) continue else throw TgException(result.code, result.message)
            }
            val privateChat = chat.type as? ChatTypePrivate ?: continue
            val user = client.getUser(privateChat.userId).ok()
            val bot = user.type as? UserTypeBot ?: continue
            if (!bot.canJoinGroups) continue
            bots += RecommendedLibraryBot(user.id, user.usernames?.activeUsernames?.firstOrNull() ?: username, chat.title)
        }
        return bots.distinctBy { it.id }.also { discoveredLibraryBotIds = it.map { bot -> bot.id }.toSet() }
    }

    override suspend fun addRecommendedBotToLibrary(libraryChatId: Long, botUserId: Long) {
        require(botUserId in discoveredLibraryBotIds) { "The bot was not discovered in Cablegram Bot Hubs" }
        val user = client.getUser(botUserId).ok()
        require((user.type as? UserTypeBot)?.canJoinGroups == true) { "This account is not an available bot" }
        val library = client.getChat(libraryChatId).ok()
        val type = library.type as? ChatTypeSupergroup ?: error("The library is not a channel")
        require(client.getSupergroup(type.supergroupId).ok().isChannel) { "The library is not a channel" }
        val status = when (val result = client.getChatMember(libraryChatId, MessageSenderUser(botUserId))) {
            is TdlResult.Success -> result.result.status
            is TdlResult.Failure -> if (result.code == 400 && result.message == "USER_NOT_PARTICIPANT") null
                else throw TgException(result.code, result.message)
        }
        // Never demote or overwrite a bot the owner already configured.
        if (status is ChatMemberStatusAdministrator) return
        client.setChatMemberStatus(
            libraryChatId, MessageSenderUser(botUserId),
            ChatMemberStatusAdministrator(canBeEdited = true, rights = libraryBotPostingRights()),
        ).ok()
    }

    override suspend fun organizeCablegramFolder(libraryChatId: Long, reviewedBotUserIds: LongArray): TgFolderResult {
        // TDLib needs dialog-list metadata before it can put existing chats into a folder. No
        // unrelated chat objects or histories are read by the app; loadChats returns only Ok.
        for (list in listOf(ChatListMain(), ChatListArchive())) {
            while (true) {
                when (val result = client.loadChats(list, 100)) {
                    is TdlResult.Success -> Unit
                    is TdlResult.Failure -> if (result.code == 404) break else throw TgException(result.code, result.message)
                }
            }
        }
        val unlistedBots = mutableListOf<String>()
        val library = client.getChat(libraryChatId).ok()
        val libraryType = library.type as? ChatTypeSupergroup ?: error("The library is not a channel")
        require(client.getSupergroup(libraryType.supergroupId).ok().isChannel && isMember(libraryType)) {
            "The library channel is unavailable"
        }
        val chats = linkedSetOf(libraryChatId)
        for (channel in CablegramChannel.entries) {
            val resolved = when (val result = client.searchPublicChat(channel.username)) {
                is TdlResult.Success -> result.result
                is TdlResult.Failure -> if (result.code == 400 || result.code == 404) continue else throw TgException(result.code, result.message)
            }
            val type = resolved.type as? ChatTypeSupergroup ?: continue
            if (isMember(type)) chats += resolved.id
        }
        // Resolving a bot chat does not start it or send /start.
        val support = client.searchPublicChat(CABLEGRAM_SUPPORT_BOT).ok()
        val supportType = support.type as? ChatTypePrivate ?: error("The Cablegram support bot is unavailable")
        require(client.getUser(supportType.userId).ok().type is UserTypeBot) { "The Cablegram support account is not a bot" }
        val supportChat = client.createPrivateChat(supportType.userId, false).ok()
        if (supportChat.positions.any { it.order != 0L }) chats += supportChat.id
        else unlistedBots += "@$CABLEGRAM_SUPPORT_BOT"
        for (botId in reviewedBotUserIds.distinct()) {
            val user = client.getUser(botId).ok()
            if (user.type !is UserTypeBot) continue
            val member = when (val result = client.getChatMember(libraryChatId, MessageSenderUser(botId))) {
                is TdlResult.Success -> result.result
                is TdlResult.Failure -> if (result.code == 400 && result.message == "USER_NOT_PARTICIPANT") continue
                    else throw TgException(result.code, result.message)
            }
            if (member.status is ChatMemberStatusAdministrator) {
                val botChat = client.createPrivateChat(botId, false).ok()
                if (botChat.positions.any { it.order != 0L }) chats += botChat.id
                else unlistedBots += user.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: user.firstName
            }
        }
        folderOrganizer.include(chats.toLongArray())
        return TgFolderResult(unlistedBots.distinct())
    }

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
        val mapped = messages.map { toMessage(it) }
        return TgVideoPage(
            videos = mapped.mapNotNull { it.video },
            nextFromMessageId = messages.lastOrNull()?.id ?: 0,
            notes = mapped.filter { it.video == null && it.text.isNotBlank() },
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

    override suspend fun passwordState(): TgPasswordState = client.getPasswordState().ok().let { TgPasswordState(it.hasPassword, it.passwordHint) }

    override suspend fun activeSessions(): List<TgSessionInfo> = client.getActiveSessions().ok().sessions.map {
        TgSessionInfo(it.id, it.isCurrent, it.apiId, it.applicationName, it.deviceModel, it.logInDate.toLong())
    }

    override suspend fun terminateSession(sessionId: Long) { client.terminateSession(sessionId).ok() }

    private fun toMessage(message: Message): TgMessage = toMessage(message.chatId, message.id, message.date.toLong(), message.content)

    private fun toMessage(chatId: Long, messageId: Long, date: Long, content: MessageContent): TgMessage {
        val video = when (content) {
            is MessageVideo -> TgVideo(
                chatId = chatId,
                messageId = messageId,
                file = toFile(content.video.video),
                fileName = content.video.fileName,
                caption = content.caption.text,
                mimeType = content.video.mimeType,
                durationSeconds = content.video.duration,
                supportsStreaming = content.video.supportsStreaming,
                date = date,
            )
            is MessageDocument -> content.document.takeIf { it.mimeType.startsWith("video/") }?.let { doc ->
                TgVideo(
                    chatId = chatId,
                    messageId = messageId,
                    file = toFile(doc.document),
                    fileName = doc.fileName,
                    caption = content.caption.text,
                    mimeType = doc.mimeType,
                    durationSeconds = 0,
                    supportsStreaming = false,
                    date = date,
                )
            }
            else -> null
        }
        val text = when (content) {
            is MessageText -> content.text.text
            is MessageVideo -> content.caption.text
            is MessageDocument -> content.caption.text
            is MessagePhoto -> content.caption.text
            is MessageAnimation -> content.caption.text
            else -> ""
        }
        return TgMessage(chatId, messageId, video, text)
    }

    private companion object {
        const val DOWNLOAD_PRIORITY = 32
        const val UPLOAD_LIMIT = 2000L * 1024 * 1024
        const val PREMIUM_UPLOAD_LIMIT = 4000L * 1024 * 1024
        const val CLOSE_TIMEOUT_MS = 10_000L

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

/** Posting plus Telegram's implied administrator access; no extra optional privileges. */
internal fun libraryBotPostingRights() = ChatAdministratorRights(
    canManageChat = true, canChangeInfo = false, canPostMessages = true, canEditMessages = false,
    canDeleteMessages = false, canInviteUsers = false, canRestrictMembers = false, canPinMessages = false,
    canManageTopics = false, canPromoteMembers = false, canManageVideoChats = false, canPostStories = false,
    canEditStories = false, canDeleteStories = false, canManageDirectMessages = false, canManageTags = false,
    canSendWelcomeMessages = false, isAnonymous = false,
)
