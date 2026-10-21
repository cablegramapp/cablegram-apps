import Foundation
import Testing
@testable import CablegramCore

/// Ported from R2UploaderTest and DriveUploaderTest: an in-memory bucket / Drive session behind a fake transport.
private final class FakeProvider: UploadTransport, @unchecked Sendable {
    private let lock = NSLock()
    var received: [Int: Data] = [:]
    var rejectOnce: Set<Int> = []
    var failOnce: Set<Int> = []
    var alwaysFail = false
    var requestCount = 0
    var stored = Data()
    var ranges: [String] = []
    var driveFailNext = 0
    var expired = false
    var acceptOnly: Int?

    func put(url: URL, headers: [String: String], file: URL, offset: Int64, length: Int64, onBytes: @escaping @Sendable (Int64) -> Void) async throws -> UploadResponse {
        let data = try slice(file, offset, length)
        onBytes(length)
        return lock.withLock { respond(url, headers, data) }
    }

    private func slice(_ file: URL, _ offset: Int64, _ length: Int64) throws -> Data {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(offset))
        return try handle.read(upToCount: Int(length)) ?? Data()
    }

    private func respond(_ url: URL, _ headers: [String: String], _ body: Data) -> UploadResponse {
        requestCount += 1
        if let range = headers["Content-Range"] { return drive(range, body) }
        if alwaysFail { return UploadResponse(status: 500) }
        let part = Int(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "partNumber" }?.value ?? "0")!
        if rejectOnce.remove(part) != nil { return UploadResponse(status: 403) }
        if failOnce.remove(part) != nil { return UploadResponse(status: 503) }
        received[part] = body
        return UploadResponse(status: 200, headers: ["etag": "\"etag-\(part)\""])
    }

    private func drive(_ header: String, _ body: Data) -> UploadResponse {
        ranges.append(header)
        if expired { return UploadResponse(status: 404) }
        if driveFailNext > 0 { driveFailNext -= 1; return UploadResponse(status: 503) }
        let m = header.dropFirst(6).split(whereSeparator: { $0 == "-" || $0 == "/" }).compactMap { Int64($0) }
        guard m.count == 3, m[0] == Int64(stored.count), Int64(body.count) == m[1] - m[0] + 1 else { return UploadResponse(status: 400) }
        var accepted = body
        if let only = acceptOnly { accepted = body.prefix(only); acceptOnly = nil }
        stored.append(accepted)
        return Int64(stored.count) >= m[2] ? UploadResponse(status: 200) : UploadResponse(status: 308, headers: ["range": "bytes=0-\(stored.count - 1)"])
    }
}

private final class FakeApi: OwnCloudApi, @unchecked Sendable {
    let provider: FakeProvider
    var partSize: Int64
    var chunk: Int64
    var protocolName: String
    var held: [Int: R2PartInfo] = [:]
    var fileSize: Int64 = 0
    var started = 0, sessions = 0, completedCount = 0, aborted = 0, urlRequests = 0
    var completedParts: [R2PartRef]?
    var loseUploadOnce = false
    var offlineSessions = 0

    init(provider: FakeProvider, partSize: Int64 = 10_000, chunk: Int64 = 256 * 1024, protocolName: String = "s3_multipart") {
        self.provider = provider; self.partSize = partSize; self.chunk = chunk; self.protocolName = protocolName
    }

    func start(originIdentity: String, sizeBytes: Int64, contentType: String, fileName: String) async throws -> R2UploadStart {
        started += 1
        fileSize = sizeBytes
        let size = protocolName == "s3_multipart" ? partSize : chunk
        return R2UploadStart(uploadId: "up-1", partSize: size, partCount: Int((sizeBytes + size - 1) / size), resumed: !held.isEmpty, protocolName: protocolName, chunkSize: chunk)
    }

    func parts(uploadId: String, from: Int, count: Int) async throws -> R2Parts {
        urlRequests += 1
        if loseUploadOnce { loseUploadOnce = false; throw R2ApiError(status: 410, code: "upload_gone") }
        for (n, data) in provider.received { held[n] = R2PartInfo(part: n, etag: "etag-\(n)", size: Int64(data.count)) }
        let total = Int((fileSize + partSize - 1) / partSize)
        let urls = (from..<min(from + count, total + 1)).map { R2PartUrl(part: $0, url: "https://bucket.test/b/key?partNumber=\($0)&uploadId=\(uploadId)") }
        return R2Parts(uploaded: held.values.sorted { $0.part < $1.part }, urls: urls)
    }

    func complete(uploadId: String, parts: [R2PartRef]) async throws -> R2Done { completedParts = parts; return R2Done(sourceId: "source-1", mediaItemId: "item-1", bytes: fileSize) }
    func session(uploadId: String) async throws -> DriveSession {
        sessions += 1
        if offlineSessions > 0 && sessions > 1 { offlineSessions -= 1; throw R2ApiError(status: 0, code: "offline") }
        return DriveSession(sessionUrl: "https://drive.test/upload?id=s1", receivedBytes: Int64(provider.stored.count))
    }
    func complete(uploadId: String) async throws -> R2Done { completedCount += 1; return R2Done(sourceId: "source-1", mediaItemId: "item-1", bytes: fileSize) }
    func abort(uploadId: String) async throws { aborted += 1; if provider.expired { provider.expired = false; provider.stored = Data() } }
}

private final class Progress: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [Int64] = []
    func add(_ v: Int64) { lock.withLock { values.append(v) } }
    var all: [Int64] { lock.withLock { values } }
}

private func makeFile(_ size: Int) throws -> (URL, Data) {
    let bytes = Data((0..<size).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ 7) })
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("up-\(UUID().uuidString).bin")
    try bytes.write(to: url)
    return (url, bytes)
}

@Suite struct UploaderTests {
    private func upload(_ api: FakeApi, _ provider: FakeProvider, _ file: URL, _ progress: Progress = Progress()) async throws -> R2Done {
        try await OwnCloudUploader(api: api, transport: provider, retryDelayMs: { _ in 0 })
            .upload(originIdentity: "local-1", fileName: "film.mkv", contentType: "video/x-matroska", file: file) { sent, _ in progress.add(sent) }
    }

    // MARK: R2

    @Test func partsGoToTheBucketByteForByteAndAreCompletedWithTheBucketsOwnEtags() async throws {
        let (file, bytes) = try makeFile(25_000)
        let provider = FakeProvider(), api = FakeApi(provider: provider), progress = Progress()
        let done = try await upload(api, provider, file, progress)
        #expect(done.sourceId == "source-1" && provider.received.keys.sorted() == [1, 2, 3])
        #expect(provider.received[1] == bytes[0..<10_000] && provider.received[2] == bytes[10_000..<20_000] && provider.received[3] == bytes[20_000..<25_000])
        #expect(api.completedParts == [R2PartRef(part: 1, etag: "etag-1"), R2PartRef(part: 2, etag: "etag-2"), R2PartRef(part: 3, etag: "etag-3")])
        #expect(progress.all.last == 25_000 && zip(progress.all, progress.all.dropFirst()).allSatisfy { $1 >= $0 })
        #expect(provider.requestCount == 3, "a part is sent once")
    }

    @Test func anInterruptedUploadResumesAndSendsOnlyThePartsTheBucketDoesNotHold() async throws {
        let (file, bytes) = try makeFile(45_000)
        let provider = FakeProvider(), api = FakeApi(provider: provider), progress = Progress()
        for n in 1...3 { provider.received[n] = bytes[(n - 1) * 10_000..<n * 10_000] }
        _ = try await upload(api, provider, file, progress)
        #expect(provider.requestCount == 2 && progress.all.first == 30_000 && progress.all.last == 45_000 && api.completedParts?.count == 5)
    }

    @Test func aHeldPartOfTheWrongSizeIsSentAgain() async throws {
        let (file, _) = try makeFile(20_000)
        let provider = FakeProvider(), api = FakeApi(provider: provider)
        api.held[1] = R2PartInfo(part: 1, etag: "stale", size: 9_999)
        _ = try await upload(api, provider, file)
        #expect(provider.requestCount == 2 && api.completedParts?.first?.etag == "etag-1")
    }

    @Test func aNetworkOrServerErrorOnAPartIsRetried() async throws {
        let (file, bytes) = try makeFile(20_000)
        let provider = FakeProvider(), api = FakeApi(provider: provider)
        provider.failOnce = [2]
        _ = try await upload(api, provider, file)
        #expect(provider.requestCount == 3 && provider.received[2] == bytes[10_000..<20_000])
    }

    @Test func aRejectedURLIsReplacedWithAFreshOne() async throws {
        let (file, _) = try makeFile(20_000)
        let provider = FakeProvider(), api = FakeApi(provider: provider)
        provider.rejectOnce = [1]
        _ = try await upload(api, provider, file)
        #expect(provider.received.count == 2 && api.urlRequests >= 3)
    }

    @Test func aPartThatKeepsFailingEndsTheUploadWithoutCompletingIt() async throws {
        let (file, _) = try makeFile(20_000)
        let provider = FakeProvider(), api = FakeApi(provider: provider)
        provider.alwaysFail = true
        await #expect(throws: UploadIOError.self) { _ = try await upload(api, provider, file) }
        #expect(api.completedParts == nil && provider.requestCount == 4, "4 attempts, then give up")
    }

    @Test func anUploadTheServerLostIsStartedAgainOnce() async throws {
        let (file, _) = try makeFile(10_000)
        let provider = FakeProvider(), api = FakeApi(provider: provider)
        api.loseUploadOnce = true
        _ = try await upload(api, provider, file)
        #expect(api.started == 2 && provider.received.count == 1)
    }

    @Test func refusalsAreExplainedInWords() {
        #expect(R2ApiError(status: 409, code: "storage_not_connected").friendly == "Your Cloudflare storage is disconnected. Connect it again in Storage.")
        #expect(R2ApiError(status: 502, code: "storage_unavailable").friendly.contains("Try again later"))
        #expect(R2ApiError(status: 418, code: "strange").friendly.hasSuffix("(strange)."))
        #expect(R2ApiError(status: 403, code: "insufficient_storage").friendly(provider: "Google Drive") == "Your Google Drive doesn't have enough free space for this video.")
    }

    // MARK: Drive

    @Test func chunksGoToTheSessionByteForByteWithContentRange() async throws {
        let (file, bytes) = try makeFile(600 * 1024)
        let provider = FakeProvider(), api = FakeApi(provider: provider, protocolName: "gdrive_resumable"), progress = Progress()
        let done = try await upload(api, provider, file, progress)
        #expect(done.sourceId == "source-1")
        #expect(provider.ranges == ["bytes 0-262143/614400", "bytes 262144-524287/614400", "bytes 524288-614399/614400"])
        #expect(provider.stored == bytes && api.completedCount == 1 && progress.all.last == 614_400)
    }

    @Test func aDriveUploadResumesFromTheOffsetTheServerReports() async throws {
        let (file, bytes) = try makeFile(600 * 1024)
        let provider = FakeProvider(), api = FakeApi(provider: provider, protocolName: "gdrive_resumable"), progress = Progress()
        provider.stored.append(bytes[0..<262_144])
        _ = try await upload(api, provider, file, progress)
        #expect(provider.ranges == ["bytes 262144-524287/614400", "bytes 524288-614399/614400"] && progress.all.first == 262_144 && provider.stored == bytes)
    }

    @Test func aChunkDriveOnlyPartlyKeptIsContinuedFromDrivesOwnCount() async throws {
        let (file, bytes) = try makeFile(600 * 1024)
        let provider = FakeProvider(), api = FakeApi(provider: provider, protocolName: "gdrive_resumable")
        provider.acceptOnly = 100 * 1024
        _ = try await upload(api, provider, file)
        #expect(provider.ranges[0] == "bytes 0-262143/614400" && provider.stored == bytes)
    }

    @Test func aDriveServerErrorIsRetriedAfterAskingWhereTheUploadStands() async throws {
        let (file, bytes) = try makeFile(300 * 1024)
        let provider = FakeProvider(), api = FakeApi(provider: provider, protocolName: "gdrive_resumable")
        provider.driveFailNext = 2
        _ = try await upload(api, provider, file)
        #expect(provider.stored == bytes && api.sessions >= 3)
    }

    @Test func aDriveUploadRidesOutTheNetworkBeingDownWhenItAsksWhereItStands() async throws {
        let (file, bytes) = try makeFile(300 * 1024)
        let provider = FakeProvider(), api = FakeApi(provider: provider, protocolName: "gdrive_resumable")
        provider.driveFailNext = 1
        api.offlineSessions = 3   // the control plane cannot be reached either: airplane mode, a lift
        _ = try await upload(api, provider, file)
        #expect(provider.stored == bytes)
    }

    @Test func aDriveChunkThatKeepsFailingEndsTheUploadWithoutCompletingIt() async throws {
        let (file, _) = try makeFile(300 * 1024)
        let provider = FakeProvider(), api = FakeApi(provider: provider, protocolName: "gdrive_resumable")
        provider.driveFailNext = 1_000
        await #expect(throws: UploadIOError.self) { _ = try await upload(api, provider, file) }
        #expect(api.completedCount == 0 && provider.ranges.count == 10)
    }

    @Test func anExpiredSessionIsCancelledAndTheUploadStartsAgainOnce() async throws {
        let (file, bytes) = try makeFile(300 * 1024)
        let provider = FakeProvider(), api = FakeApi(provider: provider, protocolName: "gdrive_resumable")
        provider.stored.append(bytes[0..<262_144])
        provider.expired = true
        _ = try await upload(api, provider, file)
        #expect(api.aborted == 1 && api.started == 2 && provider.stored == bytes)
    }

    @Test func aFinishedSessionIsNotSentAgainOnlyCompleted() async throws {
        let (file, bytes) = try makeFile(300 * 1024)
        let provider = FakeProvider(), api = FakeApi(provider: provider, protocolName: "gdrive_resumable")
        provider.stored.append(bytes)
        _ = try await upload(api, provider, file)
        #expect(provider.ranges.isEmpty && api.completedCount == 1)
    }

    @Test func aChunkSizeDriveWouldRefuseIsNeverUsed() async throws {
        let (file, _) = try makeFile(300 * 1024)
        let provider = FakeProvider(), api = FakeApi(provider: provider, chunk: 1000, protocolName: "gdrive_resumable")
        await #expect(throws: UploadIOError.self) { _ = try await upload(api, provider, file) }
        #expect(provider.ranges.isEmpty)
    }

    @Test func anUnknownProtocolIsAbortedBeforeAnyByteIsSent() async throws {
        let (file, _) = try makeFile(10_000)
        let provider = FakeProvider(), api = FakeApi(provider: provider, protocolName: "mystery")
        await #expect(throws: R2ApiError.self) { _ = try await upload(api, provider, file) }
        #expect(api.aborted == 1 && provider.requestCount == 0)
    }

    @Test func theWaitsBetweenAttemptsGrowToHalfAMinute() {
        #expect((1...8).map(backoffMs) == [1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000])
    }
}
