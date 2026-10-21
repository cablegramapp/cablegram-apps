import Foundation
import Testing
@testable import CablegramCore

// Ports of `SubtitleMediaInputTest.kt`.

/// Delivers at most `chunk` bytes per read, like a Telegram window that has only part of the range yet.
private final class Chunked: SubtitleMediaInput, @unchecked Sendable {
    let data: Data, chunk: Int, failAt: Int64
    var size: Int64 { Int64(data.count) }
    init(_ data: Data, chunk: Int, failAt: Int64 = -1) { self.data = data; self.chunk = chunk; self.failAt = failAt }
    func readAt(position: Int64, length: Int) async -> Data? {
        if position == failAt || position >= Int64(data.count) { return nil }
        let n = min(length, chunk, data.count - Int(position))
        return data.subdata(in: Int(position)..<(Int(position) + n))
    }
    func close() {}
}

private func reference(_ data: Data) -> String {
    var total = UInt64(data.count)
    for start in [0, data.count - 65536] {
        var i = start
        while i + 8 <= start + 65536 {
            total &+= data.subdata(in: i..<(i + 8)).withUnsafeBytes { $0.loadUnaligned(as: UInt64.self) }
            i += 8
        }
    }
    let hex = String(total, radix: 16)
    return String(repeating: "0", count: 16 - hex.count) + hex
}

@Suite struct SubtitleHashTests {
    @Test func hashMatchesTheProviderAlgorithmOverShortReads() async {
        let data = Data((0..<300_000).map { UInt8(truncatingIfNeeded: $0 * 31 + $0 / 7) })
        #expect(await SubtitleAnalysis.hash(Chunked(data, chunk: 4096)) == reference(data))
        #expect(await SubtitleAnalysis.hash(Chunked(data, chunk: 1 << 20)) == reference(data))
    }

    @Test func hashIsUnavailableWhenTheTailCannotBeRead() async {
        let data = Data((0..<300_000).map { UInt8(truncatingIfNeeded: $0) })
        #expect(await SubtitleAnalysis.hash(Chunked(data, chunk: 4096, failAt: 300_000 - 65536)) == nil)
        #expect(await SubtitleAnalysis.hash(Chunked(Data(count: 1000), chunk: 100)) == nil)
    }
}

private final class StuckMedia: TelegramMedia, @unchecked Sendable {
    private let lock = NSLock()
    private var startedFlag = false
    var started: Bool { lock.withLock { startedFlag } }
    func resolve(uniqueId: String) async -> TelegramFileRef? { nil }
    func read(fileId: Int, position: Int64, want: Int) async -> Data? {
        lock.withLock { startedFlag = true }
        try? await Task.sleep(nanoseconds: 60_000_000_000)
        return nil
    }
    func release(fileId: Int) {}
}

private final class CountingMedia: TelegramMedia, @unchecked Sendable {
    let data: Data
    private let lock = NSLock()
    private var count = 0, released = 0
    var calls: Int { lock.withLock { count } }
    var releases: Int { lock.withLock { released } }
    init(_ data: Data) { self.data = data }
    func resolve(uniqueId: String) async -> TelegramFileRef? { nil }
    func read(fileId: Int, position: Int64, want: Int) async -> Data? {
        lock.withLock { count += 1 }
        if position >= Int64(data.count) { return nil }
        let n = min(want, data.count - Int(position), 300_000)   // Telegram often has only part of a range ready
        return data.subdata(in: Int(position)..<(Int(position) + n))
    }
    func release(fileId: Int) { lock.withLock { released += 1 } }
}

private final class PlentyMedia: TelegramMedia, @unchecked Sendable {
    let size: Int
    init(_ size: Int) { self.size = size }
    func resolve(uniqueId: String) async -> TelegramFileRef? { nil }
    func read(fileId: Int, position: Int64, want: Int) async -> Data? {
        let n = max(0, min(want, size - Int(position)))
        return n == 0 ? nil : Data(count: n)
    }
    func release(fileId: Int) {}
}

@Suite struct TelegramMediaInputTests {
    @Test func aReadThatNeverReturnsIsAbandonedAsSoonAsTheInputIsClosed() async throws {
        let media = StuckMedia()
        let input = TelegramMediaInput(media: media, file: TelegramFileRef(fileId: 1, size: 1_000_000, mime: "video/mp4"))
        let reader = Task { await input.readAt(position: 0, length: 1024) }
        for _ in 0..<200 where !media.started { try await Task.sleep(nanoseconds: 10_000_000) }
        #expect(media.started)
        let closedAt = Date()
        input.close()
        #expect(await reader.value == nil)
        #expect(Date().timeIntervalSince(closedAt) < 2)
        #expect(await input.readAt(position: 0, length: 1024) == nil)
    }

    @Test func closingStopsTheDownload() {
        let media = CountingMedia(Data(count: 10))
        let input = TelegramMediaInput(media: media, file: TelegramFileRef(fileId: 7, size: 10, mime: "video/mp4"))
        input.close(); input.close()
        #expect(media.releases == 1)
    }

    @Test func thousandsOfTinyReadsBecomeAFewTelegramCalls() async {
        let data = Data((0..<(3 * 1024 * 1024 + 123)).map { UInt8(truncatingIfNeeded: $0 * 7) })
        let media = CountingMedia(data)
        let input = TelegramMediaInput(media: media, file: TelegramFileRef(fileId: 1, size: Int64(data.count), mime: "video/mp4"))
        var position: Int64 = 0
        for _ in 0..<4_000 {
            guard let got = await input.readAt(position: position, length: 700) else { Issue.record("read failed at \(position)"); return }
            #expect(got == data.subdata(in: Int(position)..<(Int(position) + got.count)))
            position += 700
        }
        #expect(media.calls <= 16)
        #expect(input.bytesFetched >= 2_800_000)
        input.close()
    }

    @Test func aReadAcrossAChunkBoundaryAndTheEndOfTheFileIsExact() async {
        let data = Data((0..<(1024 * 1024 + 500)).map { UInt8($0 % 251) })
        let input = TelegramMediaInput(media: CountingMedia(data), file: TelegramFileRef(fileId: 1, size: Int64(data.count), mime: "video/mp4"))
        #expect(await input.readAt(position: 1024 * 1024 - 100, length: 400) == data.subdata(in: (1024 * 1024 - 100)..<(1024 * 1024 + 300)))
        #expect(await input.readAt(position: Int64(data.count - 50), length: 1000) == data.subdata(in: (data.count - 50)..<data.count))
        #expect(await input.readAt(position: Int64(data.count), length: 10) == nil)
        input.close()
    }

    @Test func theAnalysisBudgetStopsFetchingAndSaysSo() async {
        let size = 40 * 1024 * 1024
        let input = TelegramMediaInput(media: PlentyMedia(size), file: TelegramFileRef(fileId: 1, size: Int64(size), mime: "video/mp4"))
        input.limitAnalysis(totalBytes: 5 * 1024 * 1024, sectionBytes: 64 * 1024 * 1024)
        var position: Int64 = 0, reads = 0
        while await input.readAt(position: position, length: 500_000) != nil, reads < 100 { position += 500_000; reads += 1 }
        #expect(input.budgetHit); #expect(input.bytesFetched <= 7 * 1024 * 1024)
        input.close()
    }

    @Test func eachSectionHasItsOwnAllowance() async {
        let size = 40 * 1024 * 1024
        let input = TelegramMediaInput(media: PlentyMedia(size), file: TelegramFileRef(fileId: 1, size: Int64(size), mime: "video/mp4"))
        input.limitAnalysis(totalBytes: 100 * 1024 * 1024, sectionBytes: 2 * 1024 * 1024)
        input.startSection()
        var a: Int64 = 0
        while await input.readAt(position: a, length: 500_000) != nil, a < 20_000_000 { a += 500_000 }
        #expect(input.budgetHit)
        input.close()
    }
}

@Suite struct FileMediaInputTests {
    @Test func readsRangesAndReleasesOnce() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("input-\(UUID().uuidString).bin")
        let data = Data((0..<1000).map { UInt8($0 % 256) })
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let released = Flag()
        let input = FileMediaInput(url: url) { released.raise() }
        #expect(input.size == 1000)
        #expect(await input.readAt(position: 10, length: 5) == data.subdata(in: 10..<15))
        #expect(await input.readAt(position: 998, length: 10) == data.subdata(in: 998..<1000))
        #expect(await input.readAt(position: 1000, length: 10) == nil)
        input.close(); input.close()
        #expect(released.raised)
        #expect(await input.readAt(position: 0, length: 1) == nil)
    }
}
