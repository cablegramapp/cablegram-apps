import Foundation
import Testing
@testable import CablegramCore

// Port of `TelegramFileReaderTest.kt` (real short waits instead of virtual time).

@Suite struct TelegramFileReaderTests {
    let mb = TelegramFileReader.mb

    @Test func rangeHeaderParsingCoversWhatPlayersSend() {
        #expect(TelegramByteRange.parse(nil, size: 1000) == .full(size: 1000))
        #expect(TelegramByteRange.parse("bytes=0-", size: 1000) == .partial(start: 0, end: 999, size: 1000))
        #expect(TelegramByteRange.parse("bytes=100-199", size: 1000) == .partial(start: 100, end: 199, size: 1000))
        #expect(TelegramByteRange.parse("bytes=900-5000", size: 1000) == .partial(start: 900, end: 999, size: 1000))
        #expect(TelegramByteRange.parse("bytes=-100", size: 1000) == .partial(start: 900, end: 999, size: 1000))
        #expect(TelegramByteRange.parse("bytes=1000-", size: 1000) == .unsatisfiable(size: 1000))
        #expect(TelegramByteRange.parse("bytes=500-400", size: 1000) == .unsatisfiable(size: 1000))
        #expect(TelegramByteRange.parse("items=0-1", size: 1000) == .full(size: 1000))
        #expect(TelegramByteRange.parse("bytes=100-199", size: 1000).contentRange == "bytes 100-199/1000")
    }

    @Test func readsWaitForTelegramAndNeverAskForMoreThanAWindow() async throws {
        let api = FakeTelegramApi()
        api.addFile(1, size: 4096)
        let reader = TelegramFileReader(api: api, window: 1024, refillAt: 256)

        let read = Task { try await reader.read(fileId: 1, position: 0, want: 512) }
        #expect(await eventually { api.calls == ["download:1@0+1024"] })
        api.deliver(1, bytes: 300)
        let bytes = try #require(try await read.value)
        #expect(bytes.count == 300, "only what is downloaded is served")
        #expect(bytes == api.content[1]!.subdata(in: 0..<300))
        #expect(reader.bytesServed == 300)
    }

    @Test func theWindowMovesWithTheReader() async throws {
        let api = FakeTelegramApi()
        api.addFile(1, size: 8192)
        let reader = TelegramFileReader(api: api, window: 1024, refillAt: 256, timeout: 0.5)
        #expect(!reader.needsMove(start: 0, position: 700))
        #expect(reader.needsMove(start: 0, position: 800), "past window - refillAt")
        #expect(reader.needsMove(start: 1000, position: 10), "seek backwards")
        #expect(reader.needsMove(start: nil, position: 0))

        api.deliver(1, bytes: 1024)
        _ = try await reader.read(fileId: 1, position: 0, want: 100)
        api.updateFile(1) { $0.downloadedPrefixSize = 8192 } // everything present
        _ = try await reader.read(fileId: 1, position: 900, want: 100)
        #expect(api.calls == ["download:1@0+1024", "download:1@900+1024"])
    }

    @Test func theLocalCopyIsDroppedOnceItExceedsTheStorageCap() async throws {
        let api = FakeTelegramApi()
        api.addFile(1, size: 64 * 1024)
        let reader = TelegramFileReader(api: api, window: 1024, refillAt: 256, maxStored: 2048, timeout: 10)
        api.deliver(1, bytes: 1024, stored: 1024)
        _ = try await reader.read(fileId: 1, position: 0, want: 10)
        // A seek far ahead after 3 KB were stored: drop, then download at the new position.
        api.updateFile(1) { $0.downloadedSize = 3072 }
        let far = Task { try await reader.read(fileId: 1, position: 40_000, want: 10) }
        let expected = ["download:1@0+1024", "cancelDownload:1", "deleteLocalFile:1", "download:1@40000+1024"]
        #expect(await eventually { api.calls == expected })
        #expect(api.calls == expected)
        api.deliver(1, bytes: 1024)
        #expect(try await far.value?.count == 10)
    }

    @Test func aFailedReadIsRetriedInsteadOfEndingTheStream() async throws {
        let api = FakeTelegramApi()
        api.addFile(1, size: 4096)
        api.deliver(1, bytes: 4096)
        api.failReadsRemaining = 2
        let reader = TelegramFileReader(api: api, window: 1024, refillAt: 256)
        #expect(try await reader.read(fileId: 1, position: 0, want: 64)?.count == 64)
    }

    @Test func nothingArrivingEndsTheReadWithNilAfterTheTimeout() async throws {
        let api = FakeTelegramApi()
        api.addFile(1, size: 4096)
        let reader = TelegramFileReader(api: api, window: 1024, refillAt: 256, timeout: 0.4)
        #expect(try await reader.read(fileId: 1, position: 0, want: 64) == nil)
    }

    @Test func releaseCancelsAndDeletesTheLocalCopy() async {
        let api = FakeTelegramApi()
        api.addFile(7, size: 10)
        await TelegramFileReader(api: api).release(7)
        #expect(api.calls == ["cancelDownload:7", "deleteLocalFile:7"])
    }

    @Test func availableBytesOnlyCountTheContiguousPrefixOfTheWindow() {
        let f = TgFile(id: 1, size: 10 * mb, uniqueId: "u", downloadOffset: 2 * mb, downloadedPrefixSize: mb, downloadedSize: 5 * mb, completed: false)
        #expect(TelegramFileReader.availableAt(f, position: 0) == 0)
        #expect(TelegramFileReader.availableAt(f, position: 2 * mb) == mb)
        #expect(TelegramFileReader.availableAt(f, position: 3 * mb - 1) == 1)
        #expect(TelegramFileReader.availableAt(f, position: 3 * mb) == 0)
    }
}
