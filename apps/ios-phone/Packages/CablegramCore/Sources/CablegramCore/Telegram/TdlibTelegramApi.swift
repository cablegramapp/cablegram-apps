import Foundation

// Port of `TdlibTelegramApi.kt` over TDLib's JSON interface (TDLib 1.8.67, the version the Android app ships).

/// `TelegramApi` over TDLib.
public final class TdlibTelegramApi: TelegramApi, @unchecked Sendable {
    private let td: any TdTransport
    private let lock = NSLock()
    private var knownFolderInfos: [TgChatFolderInfo]?
    private var discoveredLibraryBotIds = Set<Int64>()
    private let folderUpdates = Broadcaster<Void>()
    private var folderTask: Task<Void, Never>?

    public init(transport: any TdTransport) {
        td = transport
        // Folder metadata arrives as an update; keep the latest from the first moment, as Android does.
        let updates = transport.updates
        folderTask = Task { [weak self] in
            for await update in updates where update.type == "updateChatFolders" {
                self?.setFolders(update.objects("chat_folders").map { Self.folderInfo($0) })
            }
        }
    }

    deinit { folderTask?.cancel() }

    private func setFolders(_ folders: [TgChatFolderInfo]) {
        lock.withLock { knownFolderInfos = folders }
        folderUpdates.send(())
    }

    // MARK: Updates

    /// Updates only report changes, so a late subscriber would miss the current state: subscribe first, then ask for the current one;
    /// duplicates of the same state are dropped.
    public var authStates: AsyncStream<TgAuthState> {
        let updates = td.updates
        let td = self.td
        return AsyncStream { continuation in
            let last = LastState()
            let emit: @Sendable (TgAuthState) -> Void = { state in if last.changed(to: state) { continuation.yield(state) } }
            let task = Task {
                let current = Task {
                    if let state = try? await td.send(TdObject(["@type": "getAuthorizationState"])) { emit(Self.authState(state)) }
                }
                for await update in updates where update.type == "updateAuthorizationState" {
                    if let state = update.object("authorization_state") { emit(Self.authState(state)) }
                }
                current.cancel()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    public var fileUpdates: AsyncStream<TgFile> { mapped("updateFile") { Self.file($0.object("file") ?? $0) } }
    public var newMessages: AsyncStream<TgMessage> { mapped("updateNewMessage") { Self.message($0.object("message") ?? $0) } }
    public var editedMessages: AsyncStream<TgMessage> {
        mapped("updateMessageContent") { Self.message(chatId: $0.int64("chat_id"), messageId: $0.int64("message_id"), date: 0, content: $0.object("new_content") ?? TdObject([:])) }
    }
    /// Messages evicted from TDLib's cache are not deletions in the channel.
    public var deletedMessages: AsyncStream<TgDeletedMessages> {
        mapped("updateDeleteMessages", where: { $0.bool("is_permanent") && !$0.bool("from_cache") }) { TgDeletedMessages(chatId: $0.int64("chat_id"), messageIds: $0.int64s("message_ids")) }
    }

    private func mapped<T: Sendable>(_ type: String, where include: @escaping @Sendable (TdObject) -> Bool = { _ in true }, _ transform: @escaping @Sendable (TdObject) -> T) -> AsyncStream<T> {
        let updates = td.updates
        return AsyncStream { continuation in
            let task = Task {
                for await update in updates where update.type == type && include(update) { continuation.yield(transform(update)) }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: Requests

    @discardableResult
    private func call(_ type: String, _ fields: [String: Any] = [:]) async throws -> TdObject {
        var request = fields
        request["@type"] = type
        return try await td.send(TdObject(request))
    }

    public func start(_ parameters: TgParameters) async throws {
        try await call("setTdlibParameters", [
            "use_test_dc": parameters.useTestDc, "database_directory": parameters.databaseDirectory, "files_directory": parameters.filesDirectory,
            "database_encryption_key": parameters.databaseKey.base64EncodedString(), "use_file_database": true, "use_chat_info_database": true,
            "use_message_database": true, "use_secret_chats": false, "api_id": parameters.apiId, "api_hash": parameters.apiHash,
            "system_language_code": "en", "device_model": parameters.deviceModel, "system_version": parameters.systemVersion,
            "application_version": parameters.applicationVersion,
        ])
    }

    public func requestQrLogin() async throws { try await call("requestQrCodeAuthentication", ["other_user_ids": [Int64]()]) }
    public func setPhoneNumber(_ phone: String) async throws { try await call("setAuthenticationPhoneNumber", ["phone_number": phone]) }
    public func checkCode(_ code: String) async throws { try await call("checkAuthenticationCode", ["code": code]) }
    public func checkPassword(_ password: String) async throws { try await call("checkAuthenticationPassword", ["password": password]) }
    public func confirmQrLogin(_ link: String) async throws { try await call("confirmQrCodeAuthentication", ["link": link]) }
    public func logOut() async throws { try await call("logOut") }

    public func close() async {
        // Subscribe before asking, so the Closed update can't be missed.
        let updates = td.updates
        let closed = Task { () -> Bool in
            for await update in updates where update.type == "updateAuthorizationState" && update.object("authorization_state")?.type == "authorizationStateClosed" { return true }
            return false
        }
        if (try? await call("close")) != nil {
            _ = try? await withTimeout(seconds: 10) { await closed.value }
        }
        closed.cancel()
    }

    public func joinCommunityChannel(_ channel: CablegramChannel) async throws {
        let chat = try await call("searchPublicChat", ["username": channel.username])
        guard chat.object("type")?.type == "chatTypeSupergroup" else { throw TgError(code: 0, name: "The Cablegram channel is unavailable") }
        if !(await isMember(chat.object("type")!)) { try await call("joinChat", ["chat_id": chat.int64("id")]) }
    }

    // MARK: Bot recommendations

    public func recommendedLibraryBots() async throws -> [RecommendedLibraryBot] {
        lock.withLock { discoveredLibraryBotIds = [] }
        let hub = try await call("searchPublicChat", ["username": CablegramChannel.botHubs.username])
        guard let hubType = hub.object("type"), hubType.type == "chatTypeSupergroup",
              try await call("getSupergroup", ["supergroup_id": hubType.int64("supergroup_id")]).bool("is_channel") else {
            throw TgError(code: 0, name: "Cablegram Bot Hubs is unavailable")
        }
        var candidates: [String] = []
        var from: Int64 = 0
        while true {
            let messages = try await call("getChatHistory", ["chat_id": hub.int64("id"), "from_message_id": from, "offset": 0, "limit": 100, "only_local": false]).objects("messages")
            if messages.isEmpty { break }
            for message in messages {
                let content = message.object("content") ?? TdObject([:])
                let formatted: TdObject? = switch content.type {
                case "messageText": content.object("text")
                case "messagePhoto", "messageVideo", "messageDocument", "messageAnimation": content.object("caption")
                default: nil
                }
                var links = formatted?.objects("entities").compactMap { $0.object("type")?.type == "textEntityTypeTextUrl" ? $0.object("type")?.string("url") : nil } ?? []
                if let markup = message.object("reply_markup"), markup.type == "replyMarkupInlineKeyboard" {
                    for row in markup.fields["rows"] as? [[[String: Any]]] ?? [] {
                        for button in row { let type = TdObject(button["type"] as? [String: Any] ?? [:]); if type.type == "inlineKeyboardButtonTypeUrl" { links.append(type.string("url")) } }
                    }
                }
                for name in BotHubLinks.usernames(formatted?.string("text") ?? "", embeddedUrls: links).sorted() where !candidates.contains(name) { candidates.append(name) }
            }
            let next = messages.last?.int64("id") ?? 0
            if next == from { break }
            from = next
        }
        var bots: [RecommendedLibraryBot] = []
        for username in candidates {
            let chat: TdObject
            do { chat = try await call("searchPublicChat", ["username": username]) } catch let error as TgError {
                if error.code == 400 || error.code == 404 { continue }
                throw error
            }
            guard let type = chat.object("type"), type.type == "chatTypePrivate" else { continue }
            let user = try await call("getUser", ["user_id": type.int64("user_id")])
            guard let botType = user.object("type"), botType.type == "userTypeBot", botType.bool("can_join_groups") else { continue }
            let active = user.object("usernames")?.fields["active_usernames"] as? [String]
            bots.append(RecommendedLibraryBot(id: user.int64("id"), username: active?.first ?? username, displayName: chat.string("title")))
        }
        var seen = Set<Int64>()
        let unique = bots.filter { seen.insert($0.id).inserted }
        lock.withLock { discoveredLibraryBotIds = Set(unique.map(\.id)) }
        return unique
    }

    public func addRecommendedBotToLibrary(libraryChatId: Int64, botUserId: Int64) async throws {
        guard lock.withLock({ discoveredLibraryBotIds.contains(botUserId) }) else { throw TgError(code: 0, name: "The bot was not discovered in Cablegram Bot Hubs") }
        let user = try await call("getUser", ["user_id": botUserId])
        guard let bot = user.object("type"), bot.type == "userTypeBot", bot.bool("can_join_groups") else { throw TgError(code: 0, name: "This account is not an available bot") }
        let library = try await call("getChat", ["chat_id": libraryChatId])
        guard let type = library.object("type"), type.type == "chatTypeSupergroup",
              try await call("getSupergroup", ["supergroup_id": type.int64("supergroup_id")]).bool("is_channel") else { throw TgError(code: 0, name: "The library is not a channel") }
        let member: TdObject = ["@type": "messageSenderUser", "user_id": botUserId].td
        var status: TdObject?
        do { status = try await call("getChatMember", ["chat_id": libraryChatId, "member_id": member.fields]).object("status") } catch let error as TgError {
            if !(error.code == 400 && error.name == "USER_NOT_PARTICIPANT") { throw error }
        }
        // Never demote or overwrite a bot the owner already configured.
        if status?.type == "chatMemberStatusAdministrator" { return }
        try await call("setChatMemberStatus", [
            "chat_id": libraryChatId, "member_id": member.fields,
            "status": ["@type": "chatMemberStatusAdministrator", "custom_title": "", "can_be_edited": true, "rights": Self.libraryBotPostingRights],
        ])
    }

    /// Posting plus Telegram's implied administrator access; no extra optional privileges.
    static var libraryBotPostingRights: [String: Any] { [
        "@type": "chatAdministratorRights", "can_manage_chat": true, "can_change_info": false, "can_post_messages": true, "can_edit_messages": false,
        "can_delete_messages": false, "can_invite_users": false, "can_restrict_members": false, "can_pin_messages": false, "can_manage_topics": false,
        "can_promote_members": false, "can_manage_video_chats": false, "can_post_stories": false, "can_edit_stories": false, "can_delete_stories": false,
        "can_manage_direct_messages": false, "can_manage_tags": false, "can_send_welcome_messages": false, "is_anonymous": false,
    ] }

    // MARK: Folder

    public func organizeCablegramFolder(libraryChatId: Int64, reviewedBotUserIds: [Int64]) async throws -> TgFolderResult {
        // TDLib needs dialog-list metadata before it can put existing chats into a folder. No unrelated chat objects or histories are read
        // by the app; loadChats returns only Ok.
        for list in ["chatListMain", "chatListArchive"] {
            while true {
                do { try await call("loadChats", ["chat_list": ["@type": list], "limit": 100]) } catch let error as TgError {
                    if error.code == 404 { break }
                    throw error
                }
            }
        }
        var unlisted: [String] = []
        let library = try await call("getChat", ["chat_id": libraryChatId])
        guard let libraryType = library.object("type"), libraryType.type == "chatTypeSupergroup",
              try await call("getSupergroup", ["supergroup_id": libraryType.int64("supergroup_id")]).bool("is_channel"), await isMember(libraryType) else {
            throw TgError(code: 0, name: "The library channel is unavailable")
        }
        var chats: [Int64] = [libraryChatId]
        func add(_ id: Int64) { if !chats.contains(id) { chats.append(id) } }
        for channel in CablegramChannel.allCases {
            let resolved: TdObject
            do { resolved = try await call("searchPublicChat", ["username": channel.username]) } catch let error as TgError {
                if error.code == 400 || error.code == 404 { continue }
                throw error
            }
            guard let type = resolved.object("type"), type.type == "chatTypeSupergroup" else { continue }
            if await isMember(type) { add(resolved.int64("id")) }
        }
        // Resolving a bot chat does not start it or send /start.
        let support = try await call("searchPublicChat", ["username": cablegramSupportBot])
        guard let supportType = support.object("type"), supportType.type == "chatTypePrivate" else { throw TgError(code: 0, name: "The Cablegram support bot is unavailable") }
        guard try await call("getUser", ["user_id": supportType.int64("user_id")]).object("type")?.type == "userTypeBot" else {
            throw TgError(code: 0, name: "The Cablegram support account is not a bot")
        }
        let supportChat = try await call("createPrivateChat", ["user_id": supportType.int64("user_id"), "force": false])
        if Self.isListed(supportChat) { add(supportChat.int64("id")) } else { unlisted.append("@\(cablegramSupportBot)") }
        var reviewed: [Int64] = []
        for id in reviewedBotUserIds where !reviewed.contains(id) { reviewed.append(id) }
        for botId in reviewed {
            let user = try await call("getUser", ["user_id": botId])
            guard user.object("type")?.type == "userTypeBot" else { continue }
            let member: TdObject
            do { member = try await call("getChatMember", ["chat_id": libraryChatId, "member_id": ["@type": "messageSenderUser", "user_id": botId]]) } catch let error as TgError {
                if error.code == 400 && error.name == "USER_NOT_PARTICIPANT" { continue }
                throw error
            }
            if member.object("status")?.type == "chatMemberStatusAdministrator" {
                let botChat = try await call("createPrivateChat", ["user_id": botId, "force": false])
                if Self.isListed(botChat) { add(botChat.int64("id")) }
                else {
                    let active = user.object("usernames")?.fields["active_usernames"] as? [String]
                    unlisted.append(active?.first.map { "@\($0)" } ?? user.string("first_name"))
                }
            }
        }
        try await CablegramFolderOrganizer(store: FolderStore(api: self)).include(chats)
        var seen = Set<String>()
        return TgFolderResult(unlistedBots: unlisted.filter { seen.insert($0).inserted })
    }

    private static func isListed(_ chat: TdObject) -> Bool { chat.objects("positions").contains { $0.int64("order") != 0 } }

    /// The folder store over TDLib: only folder metadata, never chat histories.
    private struct FolderStore: CablegramFolderStore {
        let api: TdlibTelegramApi

        func folders() async throws -> [TgChatFolderInfo] {
            // Folder metadata arrives as an update after the parameters are set; wait for the first list.
            if let known = api.lock.withLock({ api.knownFolderInfos }) { return known }
            let waiting = api.folderUpdates.stream()
            if let known = api.lock.withLock({ api.knownFolderInfos }) { return known }
            for await _ in waiting { if let known = api.lock.withLock({ api.knownFolderInfos }) { return known } }
            return []
        }

        func folder(_ id: Int) async throws -> TgChatFolder { Self.parse(try await api.call("getChatFolder", ["chat_folder_id": id])) }

        func create(_ folder: TgChatFolder) async throws {
            let created = try await api.call("createChatFolder", ["folder": Self.json(folder)])
            // The native update may arrive after this call; cache the result before another merge.
            let info = Self.folderInfo(created)
            api.lock.withLock { api.knownFolderInfos = (api.knownFolderInfos ?? []).filter { $0.id != info.id } + [info] }
        }

        func edit(_ id: Int, _ folder: TgChatFolder) async throws {
            // Start from what Telegram holds, so fields Cablegram does not model (and newer ones) survive the edit.
            var raw = try await api.call("getChatFolder", ["chat_folder_id": id]).fields
            raw.removeValue(forKey: "@type")
            raw["included_chat_ids"] = folder.includedChatIds
            raw["excluded_chat_ids"] = folder.excludedChatIds
            raw["pinned_chat_ids"] = folder.pinnedChatIds
            var folderJson: [String: Any] = raw
            folderJson["@type"] = "chatFolder"
            try await api.call("editChatFolder", ["chat_folder_id": id, "folder": folderJson])
        }

        static func parse(_ object: TdObject) -> TgChatFolder {
            var folder = TgChatFolder(name: object.object("name")?.object("text")?.string("text") ?? "")
            folder.icon = object.object("icon")?.string("name")
            folder.colorId = object.fields["color_id"] != nil ? object.int("color_id") : -1
            folder.isShareable = object.bool("is_shareable")
            folder.pinnedChatIds = object.int64s("pinned_chat_ids")
            folder.includedChatIds = object.int64s("included_chat_ids")
            folder.excludedChatIds = object.int64s("excluded_chat_ids")
            folder.excludeMuted = object.bool("exclude_muted"); folder.excludeRead = object.bool("exclude_read"); folder.excludeArchived = object.bool("exclude_archived")
            folder.includeContacts = object.bool("include_contacts"); folder.includeNonContacts = object.bool("include_non_contacts")
            folder.includeBots = object.bool("include_bots"); folder.includeGroups = object.bool("include_groups"); folder.includeChannels = object.bool("include_channels")
            return folder
        }

        static func json(_ folder: TgChatFolder) -> [String: Any] {
            var json: [String: Any] = [
                "@type": "chatFolder",
                "name": ["@type": "chatFolderName", "text": ["@type": "formattedText", "text": folder.name, "entities": [Any]()], "animate_custom_emoji": false],
                "color_id": folder.colorId, "is_shareable": folder.isShareable, "pinned_chat_ids": folder.pinnedChatIds,
                "included_chat_ids": folder.includedChatIds, "excluded_chat_ids": folder.excludedChatIds, "exclude_muted": folder.excludeMuted,
                "exclude_read": folder.excludeRead, "exclude_archived": folder.excludeArchived, "include_contacts": folder.includeContacts,
                "include_non_contacts": folder.includeNonContacts, "include_bots": folder.includeBots, "include_groups": folder.includeGroups,
                "include_channels": folder.includeChannels,
            ]
            if let icon = folder.icon { json["icon"] = ["@type": "chatFolderIcon", "name": icon] }
            return json
        }

        static func folderInfo(_ object: TdObject) -> TgChatFolderInfo { TdlibTelegramApi.folderInfo(object) }
    }

    fileprivate static func folderInfo(_ object: TdObject) -> TgChatFolderInfo {
        TgChatFolderInfo(id: object.int("id"), name: object.object("name")?.object("text")?.string("text") ?? "")
    }

    // MARK: Chats

    public func me() async throws -> TgUser {
        let user = try await call("getMe")
        let name = [user.string("first_name"), user.string("last_name")].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.joined(separator: " ")
        return TgUser(id: user.int64("id"), displayName: name.trimmingCharacters(in: .whitespaces).isEmpty ? "Telegram" : name)
    }

    public func chat(_ chatId: Int64) async -> TgChat? {
        guard let chat = try? await call("getChat", ["chat_id": chatId]) else { return nil }
        let member = await isMember(chat.object("type") ?? TdObject([:]))
        return TgChat(id: chat.int64("id"), title: chat.string("title"), hasPhoto: chat.object("photo") != nil, isMember: member)
    }

    private func isMember(_ type: TdObject) async -> Bool {
        guard type.type == "chatTypeSupergroup" else { return true }
        guard let status = try? await call("getSupergroup", ["supergroup_id": type.int64("supergroup_id")]).object("status") else { return false }
        switch status.type {
        case "chatMemberStatusCreator", "chatMemberStatusRestricted": return status.bool("is_member")
        case "chatMemberStatusLeft", "chatMemberStatusBanned": return false
        default: return true
        }
    }

    public func findChatByTitle(_ title: String) async throws -> Int64? {
        // Loads the main chat list once so TDLib knows the channel; nothing is read from other chats.
        _ = try? await call("loadChats", ["chat_list": ["@type": "chatListMain"], "limit": 200])
        let ids = try await call("searchChats", ["query": title, "limit": 20]).int64s("chat_ids")
        // A channel the owner left can still be in TDLib's list; only one they are in counts.
        for id in ids { if let chat = await chat(id), chat.title == title, chat.isMember { return id } }
        return nil
    }

    public func createChannel(title: String, description: String) async throws -> Int64 {
        try await call("createNewSupergroupChat", ["title": title, "is_forum": false, "is_channel": true, "description": description, "message_auto_delete_time": 0, "for_import": false]).int64("id")
    }

    public func setChannelPhoto(chatId: Int64, imagePath: String) async throws {
        try await call("setChatPhoto", ["chat_id": chatId, "photo": ["@type": "inputChatPhotoStatic", "photo": ["@type": "inputFileLocal", "path": imagePath]]])
    }

    // MARK: Messages

    public func videos(chatId: Int64, fromMessageId: Int64, limit: Int) async throws -> TgVideoPage {
        let messages = try await call("getChatHistory", ["chat_id": chatId, "from_message_id": fromMessageId, "offset": 0, "limit": limit, "only_local": false]).objects("messages")
        let mapped = messages.map(Self.message)
        return TgVideoPage(videos: mapped.compactMap(\.video), nextFromMessageId: messages.last?.int64("id") ?? 0,
                           notes: mapped.filter { $0.video == nil && !$0.text.trimmingCharacters(in: .whitespaces).isEmpty })
    }

    public func message(chatId: Int64, messageId: Int64) async -> TgMessage? {
        (try? await call("getMessage", ["chat_id": chatId, "message_id": messageId])).map(Self.message)
    }

    public func deleteMessages(chatId: Int64, messageIds: [Int64]) async throws { try await call("deleteMessages", ["chat_id": chatId, "message_ids": messageIds, "revoke": true]) }

    public func uploadLimitBytes() async throws -> Int64 { try await call("getMe").bool("is_premium") ? Self.premiumUploadLimit : Self.uploadLimit }

    public func uploadVideo(chatId: Int64, path: String, caption: String?, onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> TgVideo {
        // Updates are hot: listen before sending, so a fast upload's result cannot be missed.
        let updates = td.updates
        let outcomes = UploadOutcomes()
        let listener = Task {
            for await update in updates {
                switch update.type {
                case "updateMessageSendSucceeded": outcomes.set(update.int64("old_message_id"), .success(update.object("message") ?? TdObject([:])))
                case "updateMessageSendFailed":
                    let error = update.object("error")
                    outcomes.set(update.int64("old_message_id"), .failure(TgError(code: error?.int("code") ?? 0, name: error?.string("message") ?? "")))
                case "updateFile": outcomes.progress(update.object("file") ?? TdObject([:]), onProgress)
                default: break
                }
            }
        }
        defer { listener.cancel() }
        let content: [String: Any] = [
            "@type": "inputMessageVideo", "video": ["@type": "inputFileLocal", "path": path], "added_sticker_file_ids": [Int](), "duration": 0, "width": 0, "height": 0,
            "supports_streaming": true, "caption": ["@type": "formattedText", "text": caption ?? "", "entities": [Any]()], "show_caption_above_media": false, "has_spoiler": false,
        ]
        let sent = try await call("sendMessage", ["chat_id": chatId, "input_message_content": content])
        let temporaryId = sent.int64("id")
        outcomes.watch(file: sent.object("content")?.object("video")?.object("video")?.int("id"))
        do {
            let message = try await outcomes.wait(for: temporaryId)
            guard let video = Self.message(message).video else { throw TgError(code: 0, name: "The uploaded message has no video") }
            return video
        } catch is CancellationError {
            // Cancelled by the user: remove the half-sent message so no broken video stays in the channel.
            _ = try? await call("deleteMessages", ["chat_id": chatId, "message_ids": [temporaryId], "revoke": true])
            throw CancellationError()
        }
    }

    // MARK: Files

    public func file(_ fileId: Int) async throws -> TgFile { Self.file(try await call("getFile", ["file_id": fileId])) }
    public func download(fileId: Int, offset: Int64, limit: Int64) async throws {
        try await call("downloadFile", ["file_id": fileId, "priority": Self.downloadPriority, "offset": offset, "limit": limit, "synchronous": false])
    }
    public func readPart(fileId: Int, offset: Int64, count: Int64) async throws -> Data { try await call("readFilePart", ["file_id": fileId, "offset": offset, "count": count]).data("data") }
    public func cancelDownload(_ fileId: Int) async throws { try await call("cancelDownloadFile", ["file_id": fileId, "only_if_pending": false]) }
    public func deleteLocalFile(_ fileId: Int) async throws { try await call("deleteFile", ["file_id": fileId]) }

    // MARK: Account

    public func passwordState() async throws -> TgPasswordState {
        let state = try await call("getPasswordState")
        return TgPasswordState(hasPassword: state.bool("has_password"), hint: state.string("password_hint"))
    }

    public func activeSessions() async throws -> [TgSessionInfo] {
        try await call("getActiveSessions").objects("sessions").map {
            TgSessionInfo(id: $0.int64("id"), isCurrent: $0.bool("is_current"), apiId: $0.int("api_id"), applicationName: $0.string("application_name"), deviceModel: $0.string("device_model"), logInDate: $0.int64("log_in_date"))
        }
    }

    public func terminateSession(_ sessionId: Int64) async throws { try await call("terminateSession", ["session_id": sessionId]) }

    // MARK: Mapping

    static let downloadPriority = 32
    static let uploadLimit: Int64 = 2000 * 1024 * 1024
    static let premiumUploadLimit: Int64 = 4000 * 1024 * 1024

    static func file(_ file: TdObject) -> TgFile {
        let local = file.object("local") ?? TdObject([:])
        let size = file.int64("size")
        return TgFile(id: file.int("id"), size: size > 0 ? size : file.int64("expected_size"), uniqueId: file.object("remote")?.string("unique_id") ?? "",
                      downloadOffset: local.int64("download_offset"), downloadedPrefixSize: local.int64("downloaded_prefix_size"),
                      downloadedSize: local.int64("downloaded_size"), completed: local.bool("is_downloading_completed"))
    }

    static func authState(_ state: TdObject) -> TgAuthState {
        switch state.type {
        case "authorizationStateWaitTdlibParameters": .waitParameters
        case "authorizationStateWaitPhoneNumber": .waitPhoneNumber
        case "authorizationStateWaitCode": .waitCode
        case "authorizationStateWaitPassword": .waitPassword(hint: state.string("password_hint"))
        case "authorizationStateWaitOtherDeviceConfirmation": .waitOtherDevice(link: state.string("link"))
        case "authorizationStateReady": .ready
        case "authorizationStateLoggingOut": .loggingOut
        case "authorizationStateClosing", "authorizationStateClosed": .closed
        default: .unsupported(name: state.type.hasPrefix("authorizationState") ? String(state.type.dropFirst("authorizationState".count)).prefix(1).uppercased() + String(state.type.dropFirst("authorizationState".count + 1)) : state.type)
        }
    }

    static func message(_ message: TdObject) -> TgMessage {
        self.message(chatId: message.int64("chat_id"), messageId: message.int64("id"), date: message.int64("date"), content: message.object("content") ?? TdObject([:]))
    }

    static func message(chatId: Int64, messageId: Int64, date: Int64, content: TdObject) -> TgMessage {
        var video: TgVideo?
        switch content.type {
        case "messageVideo":
            let v = content.object("video") ?? TdObject([:])
            video = TgVideo(chatId: chatId, messageId: messageId, file: file(v.object("video") ?? TdObject([:])), fileName: v.string("file_name"),
                            caption: content.object("caption")?.string("text") ?? "", mimeType: v.string("mime_type"), durationSeconds: v.int("duration"),
                            supportsStreaming: v.bool("supports_streaming"), date: date)
        case "messageDocument":
            let d = content.object("document") ?? TdObject([:])
            if d.string("mime_type").hasPrefix("video/") {
                video = TgVideo(chatId: chatId, messageId: messageId, file: file(d.object("document") ?? TdObject([:])), fileName: d.string("file_name"),
                                caption: content.object("caption")?.string("text") ?? "", mimeType: d.string("mime_type"), durationSeconds: 0, supportsStreaming: false, date: date)
            }
        default: break
        }
        let text: String
        switch content.type {
        case "messageText": text = content.object("text")?.string("text") ?? ""
        case "messageVideo", "messageDocument", "messagePhoto", "messageAnimation": text = content.object("caption")?.string("text") ?? ""
        default: text = ""
        }
        return TgMessage(chatId: chatId, messageId: messageId, video: video, text: text)
    }
}

/// Remembers the last auth state, so repeats are dropped (Kotlin `distinctUntilChanged`).
private final class LastState: @unchecked Sendable {
    private let lock = NSLock()
    private var last: TgAuthState?
    func changed(to state: TgAuthState) -> Bool { lock.withLock { if last == state { return false }; last = state; return true } }
}

/// Results of sends in flight, keyed by the temporary message id TDLib gave them.
private final class UploadOutcomes: @unchecked Sendable {
    private let lock = NSLock()
    private var results: [Int64: Result<TdObject, TgError>] = [:]
    private var waiters: [Int64: CheckedContinuation<TdObject, Error>] = [:]
    private var uploading: Int?

    func set(_ oldId: Int64, _ result: Result<TdObject, TgError>) {
        let waiter = lock.withLock { () -> CheckedContinuation<TdObject, Error>? in
            if let waiter = waiters.removeValue(forKey: oldId) { return waiter }
            results[oldId] = result
            return nil
        }
        waiter?.resume(with: result.mapError { $0 as Error })
    }

    func watch(file: Int?) { lock.withLock { uploading = file } }

    func progress(_ file: TdObject, _ report: @Sendable (Int64, Int64) -> Void) {
        guard lock.withLock({ uploading }) == file.int("id") else { return }
        let size = file.int64("size")
        report(file.object("remote")?.int64("uploaded_size") ?? 0, size > 0 ? size : file.int64("expected_size"))
    }

    func wait(for id: Int64) async throws -> TdObject {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let ready = lock.withLock { results.removeValue(forKey: id) }
                if let ready { continuation.resume(with: ready.mapError { $0 as Error }) }
                else { lock.withLock { waiters[id] = continuation } }
            }
        } onCancel: {
            let waiter = lock.withLock { waiters.removeValue(forKey: id) }
            waiter?.resume(throwing: CancellationError())
        }
    }
}

private extension Dictionary where Key == String, Value == Any {
    var td: TdObject { TdObject(self) }
}
