import Foundation
import Testing
@testable import CablegramCore

/// A scripted TDLib: answers by request type, records every request, and lets a test push updates.
final class FakeTd: TdTransport, @unchecked Sendable {
    private let lock = NSLock()
    private let broadcaster = Broadcaster<TdObject>()
    private(set) var requests: [[String: Any]] = []
    /// Answer per `@type`; a closure gets the request. Unlisted types answer `{"@type":"ok"}`.
    var handlers: [String: ([String: Any]) throws -> [String: Any]] = [:]

    var updates: AsyncStream<TdObject> { broadcaster.stream() }

    func send(_ request: TdObject) async throws -> TdObject {
        let type = request.type
        let handler: (([String: Any]) throws -> [String: Any])? = lock.withLock { requests.append(request.fields); return handlers[type] }
        guard let handler else { return TdObject(["@type": "ok"]) }
        let answer = try handler(request.fields)
        if answer["@type"] as? String == "error" { throw TgError(code: (answer["code"] as? Int) ?? 0, name: answer["message"] as? String ?? "") }
        return TdObject(answer)
    }

    func push(_ update: [String: Any]) { broadcaster.send(TdObject(update)) }
    func sent(_ type: String) -> [[String: Any]] { lock.withLock { requests.filter { ($0["@type"] as? String) == type } } }
    func fail(_ type: String, code: Int, _ message: String) { handlers[type] = { _ in ["@type": "error", "code": code, "message": message] } }
}

private func file(_ id: Int, unique: String = "u", size: Int = 100) -> [String: Any] {
    ["@type": "file", "id": id, "size": size, "expected_size": size,
     "local": ["download_offset": 10, "downloaded_prefix_size": 20, "downloaded_size": 30, "is_downloading_completed": false],
     "remote": ["unique_id": unique, "uploaded_size": 0]]
}

private func videoMessage(id: Int64 = 5, chat: Int64 = -1001, caption: String = "Title\n#movie_1", fileId: Int = 7, unique: String = "uniq") -> [String: Any] {
    ["@type": "message", "id": String(id), "chat_id": String(chat), "date": 1_700_000_000,
     "content": ["@type": "messageVideo", "video": ["duration": 61, "file_name": "a.mkv", "mime_type": "video/x-matroska", "supports_streaming": true, "video": file(fileId, unique: unique)],
                 "caption": ["text": caption]]]
}

@Suite struct TdlibTelegramApiTests {
    let td = FakeTd()
    var api: TdlibTelegramApi { TdlibTelegramApi(transport: td) }

    @Test func parametersGoOutAsTdlibExpectsThem() async throws {
        let params = TgParameters(databaseDirectory: "/db", filesDirectory: "/files", databaseKey: Data([1, 2, 3]), apiId: 12345, apiHash: "abcd", deviceModel: "iPhone", systemVersion: "iOS 26", applicationVersion: "Cablegram 0.3.0")
        try await api.start(params)
        let r = try #require(td.sent("setTdlibParameters").first)
        #expect(r["database_directory"] as? String == "/db" && r["files_directory"] as? String == "/files")
        #expect(r["database_encryption_key"] as? String == "AQID", "bytes travel as base64")
        #expect(r["api_id"] as? Int == 12345 && r["api_hash"] as? String == "abcd")
        #expect(r["use_secret_chats"] as? Bool == false && r["use_message_database"] as? Bool == true)
        #expect(r["system_language_code"] as? String == "en" && r["device_model"] as? String == "iPhone")
    }

    @Test func tdlibErrorsBecomeTgErrors() async {
        td.fail("checkAuthenticationCode", code: 400, "PHONE_CODE_INVALID")
        do { try await api.checkCode("1"); Issue.record("expected an error") } catch let error as TgError {
            #expect(error == TgError(code: 400, name: "PHONE_CODE_INVALID"))
        } catch { Issue.record("wrong error \(error)") }
    }

    @Test func authorizationStatesAreMappedAndRepeatsDropped() async throws {
        td.handlers["getAuthorizationState"] = { _ in ["@type": "authorizationStateWaitPhoneNumber"] }
        let api = self.api
        let stream = api.authStates
        let reader = Task { () -> [TgAuthState] in
            var states: [TgAuthState] = []
            for await s in stream { states.append(s); if states.count == 4 { break } }
            return states
        }
        try await Task.sleep(nanoseconds: 100_000_000)
        td.push(["@type": "updateAuthorizationState", "authorization_state": ["@type": "authorizationStateWaitPhoneNumber"]])   // repeat
        td.push(["@type": "updateAuthorizationState", "authorization_state": ["@type": "authorizationStateWaitCode"]])
        td.push(["@type": "updateAuthorizationState", "authorization_state": ["@type": "authorizationStateWaitPassword", "password_hint": "pet"]])
        td.push(["@type": "updateAuthorizationState", "authorization_state": ["@type": "authorizationStateWaitOtherDeviceConfirmation", "link": "tg://login?token=abc"]])
        let states = await reader.value
        #expect(states == [.waitPhoneNumber, .waitCode, .waitPassword(hint: "pet"), .waitOtherDevice(link: "tg://login?token=abc")])
        #expect(TdlibTelegramApi.authState(TdObject(["@type": "authorizationStateWaitRegistration"])) == .unsupported(name: "WaitRegistration"))
        #expect(TdlibTelegramApi.authState(TdObject(["@type": "authorizationStateClosing"])) == .closed)
    }

    @Test func videosAreReadFromVideoMessagesAndVideoDocumentsAndNotesFromTheRest() async throws {
        let doc: [String: Any] = ["@type": "message", "id": 4, "chat_id": -1001, "date": 5, "content": ["@type": "messageDocument", "document": ["file_name": "b.mp4", "mime_type": "video/mp4", "document": file(8, unique: "d")], "caption": ["text": "doc caption"]]]
        let pdf: [String: Any] = ["@type": "message", "id": 3, "chat_id": -1001, "date": 5, "content": ["@type": "messageDocument", "document": ["file_name": "c.pdf", "mime_type": "application/pdf", "document": file(9)], "caption": ["text": "a pdf"]]]
        let text: [String: Any] = ["@type": "message", "id": 2, "chat_id": -1001, "date": 5, "content": ["@type": "messageText", "text": ["text": "#movie_603 #uniq"]]]
        let empty: [String: Any] = ["@type": "message", "id": 1, "chat_id": -1001, "date": 5, "content": ["@type": "messagePhoto", "caption": ["text": "  "]]]
        td.handlers["getChatHistory"] = { _ in ["@type": "messages", "messages": [videoMessage(), doc, pdf, text, empty]] }
        let page = try await api.videos(chatId: -1001, fromMessageId: 0, limit: 100)
        #expect(page.videos.map(\.messageId) == [5, 4])
        let first = page.videos[0]
        #expect(first.chatId == -1001 && first.fileName == "a.mkv" && first.mimeType == "video/x-matroska" && first.durationSeconds == 61 && first.supportsStreaming)
        #expect(first.caption == "Title\n#movie_1" && first.file.uniqueId == "uniq" && first.file.id == 7)
        #expect(first.file.downloadOffset == 10 && first.file.downloadedPrefixSize == 20 && first.file.downloadedSize == 30 && !first.file.completed)
        #expect(page.videos[1].supportsStreaming == false && page.videos[1].durationSeconds == 0)
        #expect(page.notes.map(\.messageId) == [3, 2], "other messages with text; a blank caption is not a note")
        #expect(page.nextFromMessageId == 1)
        let request = try #require(td.sent("getChatHistory").first)
        #expect(request["chat_id"] as? Int64 == -1001 && request["limit"] as? Int == 100)
    }

    @Test func fileUpdatesAndNewAndEditedMessagesFlowThrough() async throws {
        let api = self.api
        let files = api.fileUpdates, newOnes = api.newMessages, edited = api.editedMessages
        td.push(["@type": "updateFile", "file": file(3, unique: "x")])
        td.push(["@type": "updateNewMessage", "message": videoMessage(id: 9)])
        td.push(["@type": "updateMessageContent", "chat_id": "-1001", "message_id": "9", "new_content": ["@type": "messageText", "text": ["text": "#movie_1"]]])
        var iterator1 = files.makeAsyncIterator(), iterator2 = newOnes.makeAsyncIterator(), iterator3 = edited.makeAsyncIterator()
        #expect(await iterator1.next()?.uniqueId == "x")
        #expect(await iterator2.next()?.video?.messageId == 9)
        let change = await iterator3.next()
        #expect(change?.messageId == 9 && change?.text == "#movie_1" && change?.video == nil)
    }

    @Test func onlyPermanentDeletionsAreReported() async {
        let deleted = api.deletedMessages
        td.push(["@type": "updateDeleteMessages", "chat_id": "-1001", "message_ids": ["1"], "is_permanent": false, "from_cache": false])
        td.push(["@type": "updateDeleteMessages", "chat_id": "-1001", "message_ids": ["2"], "is_permanent": true, "from_cache": true])
        td.push(["@type": "updateDeleteMessages", "chat_id": "-1001", "message_ids": ["3", "4"], "is_permanent": true, "from_cache": false])
        var iterator = deleted.makeAsyncIterator()
        let gone = await iterator.next()
        #expect(gone == TgDeletedMessages(chatId: -1001, messageIds: [3, 4]))
    }

    @Test func aChannelTheOwnerLeftIsNotAMember() async {
        td.handlers["getChat"] = { _ in ["@type": "chat", "id": "-1001", "title": "Cablegram library", "photo": ["@type": "chatPhotoInfo"], "type": ["@type": "chatTypeSupergroup", "supergroup_id": "1001"]] }
        td.handlers["getSupergroup"] = { _ in ["@type": "supergroup", "status": ["@type": "chatMemberStatusCreator", "is_member": false]] }
        let left = await api.chat(-1001)
        #expect(left == TgChat(id: -1001, title: "Cablegram library", hasPhoto: true, isMember: false))
        td.handlers["getSupergroup"] = { _ in ["@type": "supergroup", "status": ["@type": "chatMemberStatusMember"]] }
        #expect(await api.chat(-1001)?.isMember == true)
        td.fail("getChat", code: 400, "Chat not found")
        #expect(await api.chat(-1001) == nil)
    }

    @Test func aChannelByTitleIsFoundOnlyIfTheOwnerIsStillIn() async throws {
        td.handlers["searchChats"] = { _ in ["@type": "chats", "chat_ids": ["-5", "-6"]] }
        td.handlers["getChat"] = { request in
            let id = String(describing: request["chat_id"] ?? "")
            return ["@type": "chat", "id": id, "title": "Cablegram library", "type": ["@type": "chatTypeSupergroup", "supergroup_id": id.replacingOccurrences(of: "-", with: "")]]
        }
        td.handlers["getSupergroup"] = { request in
            let left = String(describing: request["supergroup_id"] ?? "") == "5"
            return ["@type": "supergroup", "status": left ? ["@type": "chatMemberStatusLeft"] : ["@type": "chatMemberStatusMember"]]
        }
        #expect(try await api.findChatByTitle("Cablegram library") == -6)
        #expect(td.sent("loadChats").count == 1, "the main chat list is loaded once so TDLib knows the channel")
    }

    @Test func aChannelIsCreatedAsABroadcastChannel() async throws {
        td.handlers["createNewSupergroupChat"] = { _ in ["@type": "chat", "id": "-100777"] }
        let id = try await api.createChannel(title: "Cablegram library", description: "d")
        #expect(id == -100777)
        let r = try #require(td.sent("createNewSupergroupChat").first)
        #expect(r["is_channel"] as? Bool == true && r["is_forum"] as? Bool == false && r["title"] as? String == "Cablegram library")
    }

    @Test func partsAreReadAsBase64AndTheDownloadWindowIsRequested() async throws {
        td.handlers["readFilePart"] = { _ in ["@type": "filePart", "data": Data([9, 8, 7]).base64EncodedString()] }
        #expect(try await api.readPart(fileId: 3, offset: 5, count: 3) == Data([9, 8, 7]))
        try await api.download(fileId: 3, offset: 1000, limit: 48 << 20)
        let r = try #require(td.sent("downloadFile").first)
        #expect(r["file_id"] as? Int == 3 && r["offset"] as? Int64 == 1000 && r["limit"] as? Int64 == 48 << 20 && r["priority"] as? Int == 32 && r["synchronous"] as? Bool == false)
    }

    @Test func sessionsAndThePasswordStateAreMapped() async throws {
        td.handlers["getActiveSessions"] = { _ in ["@type": "sessions", "sessions": [["id": "9007199254740993", "is_current": true, "api_id": 100, "application_name": "Cablegram", "device_model": "iPhone", "log_in_date": 55]]] }
        let sessions = try await api.activeSessions()
        #expect(sessions == [TgSessionInfo(id: 9_007_199_254_740_993, isCurrent: true, apiId: 100, applicationName: "Cablegram", deviceModel: "iPhone", logInDate: 55)], "ids above 2^53 survive as strings")
        td.handlers["getPasswordState"] = { _ in ["@type": "passwordState", "has_password": true, "password_hint": "pet"] }
        #expect(try await api.passwordState() == TgPasswordState(hasPassword: true, hint: "pet"))
    }

    @Test func theUploadLimitDependsOnPremium() async throws {
        td.handlers["getMe"] = { _ in ["@type": "user", "id": 1, "first_name": "A", "last_name": "", "is_premium": false] }
        #expect(try await api.uploadLimitBytes() == 2000 * 1024 * 1024)
        td.handlers["getMe"] = { _ in ["@type": "user", "id": 1, "first_name": "A", "last_name": "B", "is_premium": true] }
        #expect(try await api.uploadLimitBytes() == 4000 * 1024 * 1024)
        #expect(try await api.me() == TgUser(id: 1, displayName: "A B"))
    }

    @Test func anUploadSendsAStreamableVideoAndReturnsItOnceTelegramStoredIt() async throws {
        td.handlers["sendMessage"] = { _ in ["@type": "message", "id": "100", "chat_id": "-1001", "date": 1, "content": ["@type": "messageVideo", "video": ["video": file(55, unique: "tmp", size: 1000)], "caption": ["text": ""]]] }
        let api = self.api
        let progress = ProgressLog()
        let upload = Task { try await api.uploadVideo(chatId: -1001, path: "/tmp/a.mp4", caption: "Cap", onProgress: { progress.add($0, $1) }) }
        try await Task.sleep(nanoseconds: 150_000_000)
        td.push(["@type": "updateFile", "file": ["id": 55, "size": 1000, "expected_size": 1000, "remote": ["uploaded_size": 400, "unique_id": "tmp"], "local": [String: Any]()]])
        try await Task.sleep(nanoseconds: 100_000_000)
        td.push(["@type": "updateMessageSendSucceeded", "old_message_id": "100", "message": videoMessage(id: 101, caption: "Cap", fileId: 56, unique: "final")])
        let video = try await upload.value
        #expect(video.messageId == 101 && video.file.uniqueId == "final")
        #expect(progress.values.contains { $0 == (400, 1000) })
        let sent = try #require(td.sent("sendMessage").first)
        let content = try #require(sent["input_message_content"] as? [String: Any])
        // TDLib 1.8.67: inputMessageVideo { video: inputVideo { video: InputFile, supports_streaming, … }, caption, … }.
        #expect(content["@type"] as? String == "inputMessageVideo")
        let inputVideo = try #require(content["video"] as? [String: Any])
        #expect(inputVideo["@type"] as? String == "inputVideo" && inputVideo["supports_streaming"] as? Bool == true)
        let inputFile = try #require(inputVideo["video"] as? [String: Any])
        #expect(inputFile["@type"] as? String == "inputFileLocal" && inputFile["path"] as? String == "/tmp/a.mp4")
        #expect(((content["caption"] as? [String: Any])?["text"]) as? String == "Cap")
    }

    @Test func aFailedUploadThrowsTelegramsError() async throws {
        td.handlers["sendMessage"] = { _ in ["@type": "message", "id": "100", "chat_id": "-1001", "date": 1, "content": ["@type": "messageVideo", "video": ["video": file(55)], "caption": ["text": ""]]] }
        let api = self.api
        let upload = Task { try await api.uploadVideo(chatId: -1001, path: "/x", caption: nil, onProgress: { _, _ in }) }
        try await Task.sleep(nanoseconds: 150_000_000)
        td.push(["@type": "updateMessageSendFailed", "old_message_id": "100", "error": ["code": 400, "message": "FILE_PARTS_INVALID"]])
        do { _ = try await upload.value; Issue.record("expected failure") } catch let error as TgError { #expect(error.name == "FILE_PARTS_INVALID") }
    }

    @Test func aCancelledUploadRemovesTheHalfSentMessage() async throws {
        td.handlers["sendMessage"] = { _ in ["@type": "message", "id": "100", "chat_id": "-1001", "date": 1, "content": ["@type": "messageVideo", "video": ["video": file(55)], "caption": ["text": ""]]] }
        let api = self.api
        let upload = Task { try await api.uploadVideo(chatId: -1001, path: "/x", caption: nil, onProgress: { _, _ in }) }
        try await Task.sleep(nanoseconds: 200_000_000)
        upload.cancel()
        _ = try? await upload.value
        try await Task.sleep(nanoseconds: 100_000_000)
        let deletion = try #require(td.sent("deleteMessages").first)
        #expect(deletion["message_ids"] as? [Int64] == [100] && deletion["revoke"] as? Bool == true)
    }

    @Test func recommendedBotsAreOnlyVerifiedBotsThatCanJoinGroups() async throws {
        td.handlers["searchPublicChat"] = { request in
            switch request["username"] as? String {
            case "CablegramBotHubs": return ["@type": "chat", "id": "-900", "type": ["@type": "chatTypeSupergroup", "supergroup_id": "900"], "title": "Hub"]
            case "goodbot": return ["@type": "chat", "id": "11", "title": "Good Bot", "type": ["@type": "chatTypePrivate", "user_id": "11"]]
            case "humanuser": return ["@type": "chat", "id": "12", "title": "Human", "type": ["@type": "chatTypePrivate", "user_id": "12"]]
            case "nogroups": return ["@type": "chat", "id": "13", "title": "No Groups", "type": ["@type": "chatTypePrivate", "user_id": "13"]]
            default: return ["@type": "error", "code": 400, "message": "USERNAME_NOT_OCCUPIED"]
            }
        }
        td.handlers["getSupergroup"] = { _ in ["@type": "supergroup", "is_channel": true] }
        td.handlers["getChatHistory"] = { request in
            (request["from_message_id"] as? Int64 ?? 0) == 0
                ? ["@type": "messages", "messages": [["@type": "message", "id": "5", "chat_id": "-900", "date": 1, "content": ["@type": "messageText", "text": ["text": "Try https://t.me/GoodBot and @humanuser, @nogroups and @gone_bot", "entities": [Any]()]]]]]
                : ["@type": "messages", "messages": [Any]()]
        }
        td.handlers["getUser"] = { request in
            let id = String(describing: request["user_id"] ?? "")
            switch id {
            case "11": return ["@type": "user", "id": "11", "first_name": "Good", "usernames": ["active_usernames": ["GoodBot"]], "type": ["@type": "userTypeBot", "can_join_groups": true]]
            case "13": return ["@type": "user", "id": "13", "first_name": "NG", "type": ["@type": "userTypeBot", "can_join_groups": false]]
            default: return ["@type": "user", "id": id, "first_name": "H", "type": ["@type": "userTypeRegular"]]
            }
        }
        let bots = try await api.recommendedLibraryBots()
        #expect(bots == [RecommendedLibraryBot(id: 11, username: "GoodBot", displayName: "Good Bot")])
    }

    @Test func aBotIsAddedOnlyIfDiscoveredAndNeverOverwritesAnExistingAdministrator() async throws {
        let api = self.api
        do { try await api.addRecommendedBotToLibrary(libraryChatId: -1001, botUserId: 11); Issue.record("must refuse an undiscovered bot") } catch {}
        #expect(td.sent("setChatMemberStatus").isEmpty)

        td.handlers["searchPublicChat"] = { request in
            request["username"] as? String == "CablegramBotHubs"
                ? ["@type": "chat", "id": "-900", "type": ["@type": "chatTypeSupergroup", "supergroup_id": "900"]]
                : ["@type": "chat", "id": "11", "title": "Good", "type": ["@type": "chatTypePrivate", "user_id": "11"]]
        }
        td.handlers["getSupergroup"] = { _ in ["@type": "supergroup", "is_channel": true] }
        td.handlers["getChatHistory"] = { request in
            (request["from_message_id"] as? Int64 ?? 0) == 0
                ? ["@type": "messages", "messages": [["@type": "message", "id": "5", "chat_id": "-900", "content": ["@type": "messageText", "text": ["text": "@goodbot"]]]]]
                : ["@type": "messages", "messages": [Any]()]
        }
        td.handlers["getUser"] = { _ in ["@type": "user", "id": "11", "first_name": "G", "type": ["@type": "userTypeBot", "can_join_groups": true]] }
        td.handlers["getChat"] = { _ in ["@type": "chat", "id": "-1001", "type": ["@type": "chatTypeSupergroup", "supergroup_id": "1001"]] }
        td.handlers["getChatMember"] = { _ in ["@type": "error", "code": 400, "message": "USER_NOT_PARTICIPANT"] }
        _ = try await api.recommendedLibraryBots()
        try await api.addRecommendedBotToLibrary(libraryChatId: -1001, botUserId: 11)
        let request = try #require(td.sent("setChatMemberStatus").first)
        let status = try #require(request["status"] as? [String: Any])
        let rights = try #require(status["rights"] as? [String: Any])
        #expect(status["@type"] as? String == "chatMemberStatusAdministrator" && rights["can_post_messages"] as? Bool == true && rights["can_manage_chat"] as? Bool == true)
        #expect(rights.filter { $0.value as? Bool == true }.keys.sorted() == ["can_manage_chat", "can_post_messages"], "no optional privileges")

        td.handlers["getChatMember"] = { _ in ["@type": "chatMember", "status": ["@type": "chatMemberStatusAdministrator"]] }
        try await api.addRecommendedBotToLibrary(libraryChatId: -1001, botUserId: 11)
        #expect(td.sent("setChatMemberStatus").count == 1, "an administrator the owner configured is left alone")
    }

    @Test func theFolderIsMergedKeepingTheOwnersEntriesAndUnknownFields() async throws {
        let api = self.api
        td.handlers["loadChats"] = { _ in ["@type": "error", "code": 404, "message": "Not Found"] }
        td.handlers["getChat"] = { request in
            let id = String(describing: request["chat_id"] ?? "")
            return ["@type": "chat", "id": id, "title": "T", "type": ["@type": "chatTypeSupergroup", "supergroup_id": "1"], "positions": [["order": "5"]]]
        }
        td.handlers["getSupergroup"] = { _ in ["@type": "supergroup", "is_channel": true, "status": ["@type": "chatMemberStatusMember"]] }
        td.handlers["searchPublicChat"] = { request in
            request["username"] as? String == "CablegramSupportBot"
                ? ["@type": "chat", "id": "77", "type": ["@type": "chatTypePrivate", "user_id": "77"]]
                : ["@type": "error", "code": 400, "message": "USERNAME_NOT_OCCUPIED"]
        }
        td.handlers["getUser"] = { _ in ["@type": "user", "id": "77", "type": ["@type": "userTypeBot"]] }
        td.handlers["createPrivateChat"] = { _ in ["@type": "chat", "id": "77", "positions": [["order": "0"]]] }
        td.push(["@type": "updateChatFolders", "chat_folders": [["id": 4, "name": ["text": ["text": " cablegram "]]]]])
        try await Task.sleep(nanoseconds: 100_000_000)
        td.handlers["getChatFolder"] = { _ in ["@type": "chatFolder", "name": ["@type": "chatFolderName", "text": ["text": " cablegram "]], "color_id": 3, "pinned_chat_ids": ["-9"], "included_chat_ids": ["-9", "-8"], "excluded_chat_ids": ["-1001"], "exclude_muted": true, "future_field": "kept"] }
        let result = try await api.organizeCablegramFolder(libraryChatId: -1001, reviewedBotUserIds: [])
        #expect(result.unlistedBots == ["@CablegramSupportBot"], "a bot dialog that was never started cannot be listed")
        let edit = try #require(td.sent("editChatFolder").first)
        let folder = try #require(edit["folder"] as? [String: Any])
        #expect(edit["chat_folder_id"] as? Int == 4)
        #expect(folder["included_chat_ids"] as? [Int64] == [-8, -1001], "pinned stay pinned, not duplicated; the library is added")
        #expect(folder["excluded_chat_ids"] as? [Int64] == [], "an included chat is no longer excluded")
        #expect(folder["pinned_chat_ids"] as? [Int64] == [-9] && folder["exclude_muted"] as? Bool == true && folder["future_field"] as? String == "kept" && folder["color_id"] as? Int == 3)
        #expect(td.sent("createChatFolder").isEmpty)
    }
}

final class ProgressLog: @unchecked Sendable {
    private let lock = NSLock()
    private var all: [(Int64, Int64)] = []
    func add(_ a: Int64, _ b: Int64) { lock.withLock { all.append((a, b)) } }
    var values: [(Int64, Int64)] { lock.withLock { all } }
}

@Suite struct TimeoutTests {
    @Test func aCallThatNeverAnswersTimesOutPromptly() async {
        let start = Date()
        do {
            _ = try await withTimeout(seconds: 0.3) { () -> Int in
                // Ignores cancellation, like a request waiting on the network.
                await withCheckedContinuation { (_: CheckedContinuation<Void, Never>) in }
                return 1
            }
            Issue.record("expected a timeout")
        } catch is TimeoutError {}
        catch { Issue.record("wrong error \(error)") }
        // Generous bound: the operation never ends, so any return proves the point; CI runners start the timer late when tests run in parallel.
        #expect(Date().timeIntervalSince(start) < 10, "the caller is not held back by work that will not stop")
    }

    @Test func fastWorkReturnsItsValueAndSlowWorkIsCancelled() async throws {
        #expect(try await withTimeout(seconds: 5) { 42 } == 42)
        let cancelled = TgLocked(false)
        do {
            _ = try await withTimeout(seconds: 0.2) { () -> Int in
                do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { cancelled.value = true; throw error }
                return 1
            }
        } catch is TimeoutError {}
        try await Task.sleep(nanoseconds: 200_000_000)
        #expect(cancelled.value, "the abandoned work is told to stop")
    }

    @Test func cancellingTheCallerCancelsTheWork() async {
        let task = Task { try await withTimeout(seconds: 30) { try await Task.sleep(nanoseconds: 20_000_000_000); return 1 } }
        try? await Task.sleep(nanoseconds: 100_000_000)
        task.cancel()
        do { _ = try await task.value; Issue.record("expected cancellation") } catch is CancellationError {} catch { Issue.record("wrong error \(error)") }
    }

    @Test func theMutexSerialisesAcrossAwaits() async {
        let mutex = AsyncMutex()
        let inside = TgLocked(0), maxInside = TgLocked(0)
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<8 { group.addTask { await mutex.withLock { inside.value += 1; maxInside.value = max(maxInside.value, inside.value); try? await Task.sleep(nanoseconds: 10_000_000); inside.value -= 1 } } }
        }
        #expect(maxInside.value == 1)
    }

    @Test func aRangeWithNoDashStartsThere() {
        #expect(TelegramByteRange.parse("bytes=500", size: 5000) == .partial(start: 500, end: 4999, size: 5000))
    }
}
