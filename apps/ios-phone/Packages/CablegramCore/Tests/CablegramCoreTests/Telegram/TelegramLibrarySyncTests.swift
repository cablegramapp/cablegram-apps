import Foundation
import Testing
@testable import CablegramCore

// Port of `TelegramLibrarySyncTest.kt` (real short waits and `hintRetryDelays` in place of virtual time).

@Suite struct TelegramLibrarySyncTests {
    let chat: Int64 = -1004481882279

    func video(_ message: Int64, _ unique: String, caption: String = "", chatId: Int64 = -1004481882279) -> TgVideo {
        TgVideo(chatId: chatId, messageId: message, file: TgFile(id: Int(message), size: 100, uniqueId: unique), fileName: "file-\(message).mkv", caption: caption,
                mimeType: "video/x-matroska", durationSeconds: 60, supportsStreaming: false, date: 0)
    }

    @Test func aFullScanRegistersEveryVideoAcrossPagesAndReconcilesTheRestAway() async throws {
        let api = FakeTelegramApi()
        api.videoPages[0] = TgVideoPage(videos: [video(30, "c"), video(20, "b")], nextFromMessageId: 20)
        api.videoPages[20] = TgVideoPage(videos: [video(10, "a"), video(5, "a")], nextFromMessageId: 5) // "a" forwarded twice
        api.videoPages[5] = TgVideoPage(videos: [], nextFromMessageId: 0)
        let registered = TgLocked<[Int64]>([])
        let reconciled = TgLocked<Set<String>?>(nil)
        let changes = TgLocked(0)
        let sync = TelegramLibrarySync(api: api, chatId: chat, register: { video in registered.update { $0.append(video.messageId) } },
                                       reconcile: { reconciled.value = $0 }, onChanged: { changes.update { $0 += 1 } })

        #expect(try await sync.fullScan() == 3)
        #expect(registered.value == [30, 20, 10], "one registration per file")
        #expect(reconciled.value == ["tgfile:c", "tgfile:b", "tgfile:a"])
        #expect(changes.value == 1)
    }

    @Test func newVideosInTheChannelRegisterAtOnceOtherChatsAreIgnored() async {
        let api = FakeTelegramApi()
        let registered = TgLocked<[Int64]>([])
        let sync = TelegramLibrarySync(api: api, chatId: chat, register: { video in registered.update { $0.append(video.messageId) } }, reconcile: { _ in })
        let watch = sync.watch()
        defer { watch.cancel() }
        api.emitNew(TgMessage(chatId: chat, messageId: 40, video: video(40, "d")))
        api.emitNew(TgMessage(chatId: -1009999, messageId: 41, video: video(41, "e", chatId: -1009999)))
        api.emitNew(TgMessage(chatId: chat, messageId: 42, video: nil)) // a text message
        #expect(await eventually { registered.value == [40] })
        await settle()
        #expect(registered.value == [40])
    }

    @Test func aDeletionInTheChannelRescansSoTheGoneVideoBecomesUnavailable() async {
        let api = FakeTelegramApi()
        api.videoPages[0] = TgVideoPage(videos: [video(30, "c")], nextFromMessageId: 0)
        let reconciled = TgLocked<Set<String>?>(nil)
        let sync = TelegramLibrarySync(api: api, chatId: chat, register: { _ in }, reconcile: { reconciled.value = $0 })
        let watch = sync.watch()
        defer { watch.cancel() }
        api.emitDeleted(TgDeletedMessages(chatId: chat, messageIds: [20]))
        #expect(await eventually { reconciled.value == ["tgfile:c"] })
    }

    @Test func aCaptionNamesTheVideoOtherwiseTheFileNameDoes() {
        #expect(TelegramLibrarySync.title(video(1, "x", caption: "  Gary \nThe Bear special")) == "Gary")
        #expect(TelegramLibrarySync.title(video(1, "x")) == nil)
        #expect(TelegramLibrarySync.originIdentity(video(7, "x")) == "tg:\(chat):7")
        #expect(TelegramLibrarySync.stableKey(video(7, "x")) == "tgfile:x")
    }

    @Test func aFullScanSendsTheNewestTMDBReferenceForEachLibraryVideo() async throws {
        let api = FakeTelegramApi()
        api.videoPages[0] = TgVideoPage(videos: [video(30, "cccccccc")], nextFromMessageId: 0, notes: [
            TgMessage(chatId: chat, messageId: 33, video: nil, text: "video_id: #cccccccc #movie_1313826"),
            TgMessage(chatId: chat, messageId: 32, video: nil, text: "video_id: #not_a_library_video #movie_7"), // never sent
            TgMessage(chatId: chat, messageId: 31, video: nil, text: "video_id: #cccccccc #movie_99"), // older: the newer one wins
        ])
        let sent = TgLocked<[TgTmdbHint]>([])
        let sync = TelegramLibrarySync(api: api, chatId: chat, register: { _ in }, reconcile: { _ in }, hints: { hints in sent.update { $0 += hints } })
        try await sync.fullScan()
        #expect(sent.value == [TgTmdbHint(uniqueId: "cccccccc", tmdbType: "movie", tmdbId: 1313826)])
    }

    @Test func aReferenceThatComesLaterOrByAnEditIsStillPickedUp() async {
        let api = FakeTelegramApi()
        let sent = TgLocked<[TgTmdbHint]>([])
        let sync = TelegramLibrarySync(api: api, chatId: chat, register: { _ in }, reconcile: { _ in }, hints: { hints in sent.update { $0 += hints } },
                                       hintRetryDelays: [0.4, 1.2])
        let watch = sync.watch()
        defer { watch.cancel() }
        api.emitNew(TgMessage(chatId: chat, messageId: 40, video: video(40, "dddddddd")))
        // The bot's placeholder, then its edit.
        api.emitNew(TgMessage(chatId: chat, messageId: 41, video: nil, text: "⏳ Processing video… video_id: #dddddddd"))
        await settle()
        #expect(sent.value == [])
        api.emitEdited(TgMessage(chatId: chat, messageId: 41, video: nil, text: "✅ video_id: #dddddddd #movie_1313826"))
        #expect(await eventually { sent.value == [TgTmdbHint(uniqueId: "dddddddd", tmdbType: "movie", tmdbId: 1313826)] })

        // Another video whose reference only shows up when the channel is read again.
        sent.value = []
        api.videoPages[0] = TgVideoPage(videos: [], nextFromMessageId: 0, notes: [TgMessage(chatId: chat, messageId: 51, video: nil, text: "#eeeeeeee #tv_42_s01e02")])
        api.emitNew(TgMessage(chatId: chat, messageId: 50, video: video(50, "eeeeeeee")))
        #expect(await eventually(timeout: 3) { sent.value == [TgTmdbHint(uniqueId: "eeeeeeee", tmdbType: "tv", tmdbId: 42, season: 1, episode: 2)] })
    }
}
