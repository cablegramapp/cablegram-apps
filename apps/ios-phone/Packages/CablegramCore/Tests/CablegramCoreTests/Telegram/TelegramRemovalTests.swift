import Foundation
import Testing
@testable import CablegramCore

@Suite struct TelegramRemovalTests {
    let chat: Int64 = -1004481882279

    func video(_ message: Int64, _ unique: String, name: String = "f.mkv", size: Int64 = 100) -> TgVideo {
        TgVideo(chatId: chat, messageId: message, file: TgFile(id: Int(message), size: size, uniqueId: unique), fileName: name, mimeType: "video/x-matroska", durationSeconds: 60)
    }

    @Test func aFinishedUploadThatNeverRegisteredIsFoundAgainByNameAndExactSize() async throws {
        let api = FakeTelegramApi()
        let sent = video(44, "up", name: "abcd1234-Holiday.mkv", size: 500)
        api.videoPages[0] = (TgVideoPage(videos: [sent, video(40, "other")], nextFromMessageId: 0))
        #expect(try await TelegramRemoval.findUpload(api, chatId: chat, sizeBytes: 500, uploadNames: ["abcd1234-Holiday.mkv"])?.messageId == 44)
        #expect(try await TelegramRemoval.findUpload(api, chatId: chat, sizeBytes: 501, uploadNames: ["abcd1234-Holiday.mkv"]) == nil)
        #expect(try await TelegramRemoval.findUpload(api, chatId: chat, sizeBytes: 500, uploadNames: ["ffff0000-Holiday.mkv"]) == nil)
        // Uploaded under the file's own name (no link possible): still found, so it isn't uploaded twice.
        api.videoPages[0] = (TgVideoPage(videos: [video(45, "up", name: "abcd1234-5678.mp4", size: 500)], nextFromMessageId: 0))
        #expect(try await TelegramRemoval.findUpload(api, chatId: chat, sizeBytes: 500, uploadNames: ["abcd1234-Holiday.mkv", "abcd1234-5678.mp4"])?.messageId == 45)
    }

    @Test func everyMessageWithTheFileIsFoundAndOtherFilesAreLeftAlone() async throws {
        let api = FakeTelegramApi()
        api.videoPages[0] = (TgVideoPage(videos: [video(30, "a"), video(20, "b")], nextFromMessageId: 20))
        api.videoPages[20] = (TgVideoPage(videos: [video(10, "a")], nextFromMessageId: 0))
        let found = try await TelegramRemoval.channelMessageIds(api, chatId: chat, keys: ["tgfile:a", "tgfile:c"])
        #expect(found == ["tgfile:a": [30, 10], "tgfile:c": []])
        #expect(api.videoPageRequests == 2, "one walk of the channel for the whole batch")
    }

    @Test func theQueueSurvivesARestartRecordsFailuresAndForgetsFinishedDeletions() {
        let stored = TgLocked<String?>(nil)
        func queue() -> TelegramDeletionQueue { TelegramDeletionQueue(load: { stored.value }, save: { stored.value = $0 }) }
        #expect(queue().all().isEmpty)
        let first = queue()
        first.add(PendingTelegramDeletion(stableSourceKey: "tgfile:a", chatId: chat, messageIds: [1], title: "A", createdAt: 5))
        first.add(PendingTelegramDeletion(stableSourceKey: "tgfile:b", chatId: chat, messageIds: [2], title: "B", createdAt: 6))
        first.failed("tgfile:a", error: "offline")
        let reopened = queue()
        #expect(reopened.all().map { [$0.stableSourceKey, String($0.attempts)] } == [["tgfile:a", "1"], ["tgfile:b", "0"]])
        reopened.markDeletedInTelegram("tgfile:b")
        #expect(reopened.all().last?.deletedInTelegram == true)
        reopened.remove("tgfile:a")
        #expect(reopened.all().map(\.stableSourceKey) == ["tgfile:b"])
    }

    @Test func theUploadNameIsStableAndSafe() {
        let store = LibraryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("tn-\(UUID().uuidString)"))
        var item = LibraryItem(id: "abcdef1234567890", title: "T", filename: "dir/My Film (2021) é.mkv", importedAt: "2026-01-01T00:00:00Z")
        item.fileName = "abcdef.mkv"
        #expect(store.telegramUploadName(item) == "abcdef12-My Film _2021_ _.mkv")
        #expect(store.telegramUploadNames(item) == ["abcdef12-My Film _2021_ _.mkv", "abcdef.mkv"])
        item.filename = "///"
        #expect(store.telegramUploadName(item) == "abcdef12-video.mp4")
    }
}
