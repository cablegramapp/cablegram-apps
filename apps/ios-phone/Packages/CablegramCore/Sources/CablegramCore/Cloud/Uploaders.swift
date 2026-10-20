import Foundation

// ---- Save to Cloud: the household's own Cloudflare R2 bucket or Google Drive (specs 005, 006) ----
// The control plane starts and completes an upload; the video bytes go straight to the provider through an `UploadTransport`
// (a background URLSession in the app), and an interrupted upload resumes from what the provider already holds.

public struct R2UploadStart: Codable, Sendable, Equatable {
    public var uploadId: String
    public var partSize: Int64
    public var partCount: Int
    public var resumed: Bool
    /// Which uploader runs it (spec 006): `s3_multipart` or `gdrive_resumable`. Older servers do not say.
    public var protocolName: String
    /// Drive only: the size of each chunk.
    public var chunkSize: Int64

    public init(uploadId: String, partSize: Int64 = 0, partCount: Int = 0, resumed: Bool = false, protocolName: String = "s3_multipart", chunkSize: Int64 = 0) {
        self.uploadId = uploadId; self.partSize = partSize; self.partCount = partCount; self.resumed = resumed
        self.protocolName = protocolName; self.chunkSize = chunkSize
    }

    enum CodingKeys: String, CodingKey {
        case uploadId = "upload_id", partSize = "part_size", partCount = "part_count", resumed, protocolName = "protocol", chunkSize = "chunk_size"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        uploadId = try c.decode(String.self, forKey: .uploadId)
        partSize = try c.decodeIfPresent(Int64.self, forKey: .partSize) ?? 0
        partCount = try c.decodeIfPresent(Int.self, forKey: .partCount) ?? 0
        resumed = try c.decodeIfPresent(Bool.self, forKey: .resumed) ?? false
        protocolName = try c.decodeIfPresent(String.self, forKey: .protocolName) ?? "s3_multipart"
        chunkSize = try c.decodeIfPresent(Int64.self, forKey: .chunkSize) ?? 0
    }
}

public struct R2PartInfo: Codable, Sendable, Equatable {
    public var part: Int
    public var etag: String
    public var size: Int64
    public init(part: Int, etag: String, size: Int64) { self.part = part; self.etag = etag; self.size = size }
}

public struct R2PartUrl: Codable, Sendable, Equatable {
    public var part: Int
    public var url: String
    public init(part: Int, url: String) { self.part = part; self.url = url }
}

public struct R2Parts: Codable, Sendable, Equatable {
    public var uploaded: [R2PartInfo]
    public var urls: [R2PartUrl]
    public init(uploaded: [R2PartInfo] = [], urls: [R2PartUrl] = []) { self.uploaded = uploaded; self.urls = urls }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        uploaded = try c.decodeIfPresent([R2PartInfo].self, forKey: .uploaded) ?? []
        urls = try c.decodeIfPresent([R2PartUrl].self, forKey: .urls) ?? []
    }
}

public struct R2Done: Codable, Sendable, Equatable {
    public var sourceId: String
    public var mediaItemId: String
    public var bytes: Int64
    public init(sourceId: String, mediaItemId: String, bytes: Int64) { self.sourceId = sourceId; self.mediaItemId = mediaItemId; self.bytes = bytes }
    enum CodingKeys: String, CodingKey { case sourceId = "source_id", mediaItemId = "media_item_id", bytes }
}

public struct R2PartRef: Sendable, Equatable {
    public var part: Int
    public var etag: String
    public init(part: Int, etag: String) { self.part = part; self.etag = etag }
}

/// Where a Drive upload stands. The session URL is a capability for that one upload (FR-009): it is never logged.
public struct DriveSession: Codable, Sendable, Equatable, CustomStringConvertible {
    public var sessionUrl: String
    public var receivedBytes: Int64
    public init(sessionUrl: String, receivedBytes: Int64) { self.sessionUrl = sessionUrl; self.receivedBytes = receivedBytes }
    enum CodingKeys: String, CodingKey { case sessionUrl = "session_url", receivedBytes = "received_bytes" }
    public var description: String { "DriveSession(receivedBytes=\(receivedBytes))" }
}

/// A control-plane refusal; `code` is its stable error string, never free text from the bucket.
public struct R2ApiError: Error, Equatable {
    public var status: Int
    public var code: String
    public init(status: Int, code: String) { self.status = status; self.code = code }

    /// Said to the person, not logged: what to do about it. Names Cloudflare, the provider this type began with.
    public var friendly: String { friendly(provider: "Cloudflare") }

    /// The same, naming where the video was going (`provider` is "Google Drive", "Cloudflare R2", …).
    public func friendly(provider: String) -> String {
        switch code {
        case "storage_not_connected": "Your \(provider) storage is disconnected. Connect it again in Storage."
        case "size_mismatch": "\(provider) stored a different size than the phone sent. Save it again."
        case "file_too_large": "This video is too large to save to \(provider)."
        case "storage_unavailable": "\(provider) isn't answering. Try again later."
        case "upload_gone": "The upload expired. Save it again."
        case "insufficient_storage": "Your \(provider) doesn't have enough free space for this video."
        case "reauthorization_required": "\(provider) needs you to sign in again. Open Storage and sign in."
        case "provider_rate_limited": "\(provider) is limiting uploads right now. Try again later."
        case "upload_incomplete": "\(provider) hasn't received the whole video yet. Save it again to continue."
        default: "Couldn't save to \(provider) (\(code))."
        }
    }
}

/// A network-level or provider-level failure while sending bytes (Android `IOException`).
public struct UploadIOError: Error, Equatable {
    public var message: String
    public init(_ message: String) { self.message = message }
}

/// The control plane's side of Save to Cloud. The video bytes never go through it.
public protocol OwnCloudApi: Sendable {
    func start(originIdentity: String, sizeBytes: Int64, contentType: String, fileName: String) async throws -> R2UploadStart
    func parts(uploadId: String, from: Int, count: Int) async throws -> R2Parts
    func complete(uploadId: String, parts: [R2PartRef]) async throws -> R2Done
    func session(uploadId: String) async throws -> DriveSession
    /// Completion is decided by the server from Drive's answer; the phone sends no file id.
    func complete(uploadId: String) async throws -> R2Done
    func abort(uploadId: String) async throws
}

public struct UploadResponse: Sendable, Equatable {
    public var status: Int
    /// Header names are lower-cased.
    public var headers: [String: String]
    public init(status: Int, headers: [String: String] = [:]) { self.status = status; self.headers = headers }
}

/// Sends bytes `[offset, offset + length)` of `file` with an HTTP PUT. A background URLSession in the app; a fake in tests.
/// It throws when the network fails (any error is a retryable `UploadIOError`) and returns the status for everything else.
public protocol UploadTransport: Sendable {
    func put(url: URL, headers: [String: String], file: URL, offset: Int64, length: Int64, onBytes: @escaping @Sendable (Int64) -> Void) async throws -> UploadResponse
}

/// 1, 2, 4, 8, 16, then 30 seconds.
public func backoffMs(_ attempt: Int) -> Int64 { min(Int64(1_000) << min(max(attempt - 1, 0), 5), 30_000) }

/// Uploads one video to the household's bucket in parts straight to presigned URLs, and resumes from the parts the bucket
/// already holds, so a killed app or a lost connection costs at most one part.
public struct R2Uploader: Sendable {
    let api: any OwnCloudApi
    let transport: any UploadTransport
    let retryDelayMs: @Sendable (Int) -> Int64

    public init(api: any OwnCloudApi, transport: any UploadTransport, retryDelayMs: @escaping @Sendable (Int) -> Int64 = { Int64(1_000) << ($0 - 1) }) {
        self.api = api
        self.transport = transport
        self.retryDelayMs = retryDelayMs
    }

    private static let batch = 4
    private static let maxAttempts = 4

    /// Runs an upload the control plane has already started; `restart` begins a fresh one if it was lost.
    public func run(start: R2UploadStart, restart: @Sendable () async throws -> R2UploadStart, file: URL, size: Int64,
                    onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> R2Done {
        do {
            return try await send(start, file, size, onProgress)
        } catch let gone as R2ApiError {
            // The open upload vanished (aborted, or the bucket was disconnected meanwhile): begin again once.
            guard gone.code == "upload_gone" else { throw gone }
            return try await send(try await restart(), file, size, onProgress)
        }
    }

    private struct PartRejected: Error { var status: Int }

    private func send(_ start: R2UploadStart, _ file: URL, _ size: Int64, _ onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> R2Done {
        let partSize = start.partSize
        func partLength(_ part: Int) -> Int64 { min(partSize, size - Int64(part - 1) * partSize) }
        var sent: Int64 = 0
        var batchFrom = 1
        var held: [Int: R2PartInfo] = [:]
        while batchFrom <= start.partCount {
            let batch = try await api.parts(uploadId: start.uploadId, from: batchFrom, count: Self.batch)
            if batchFrom == 1 {
                // Only parts of the right size count: a part from an older, different file is sent again.
                held = Dictionary(uniqueKeysWithValues: batch.uploaded.filter { $0.size == partLength($0.part) }.map { ($0.part, $0) })
                sent = held.values.reduce(0) { $0 + $1.size }
                onProgress(sent, size)
            }
            for target in batch.urls where held[target.part] == nil {
                try Task.checkCancellation()
                let length = partLength(target.part)
                let offset = Int64(target.part - 1) * partSize
                var url = target.url
                var attempt = 0
                while true {
                    attempt += 1
                    let before = sent
                    do {
                        try await putPart(url, file, offset, length) { onProgress(before + $0, size) }
                        sent = before + length
                        onProgress(sent, size)
                        break
                    } catch is CancellationError {
                        throw CancellationError()
                    } catch let failure as PartRejected {
                        sent = before
                        if attempt >= Self.maxAttempts { throw UploadIOError("part rejected (\(failure.status))") }
                        try await Task.sleep(for: .milliseconds(retryDelayMs(attempt)))
                        // A rejected URL (expired or revoked) is replaced; a network error retries the same one.
                        if failure.status == 403 {
                            guard let fresh = try await api.parts(uploadId: start.uploadId, from: target.part, count: 1).urls.first(where: { $0.part == target.part })?.url else {
                                throw UploadIOError("part rejected (403)")
                            }
                            url = fresh
                        }
                    } catch let failure as UploadIOError {
                        sent = before
                        if attempt >= Self.maxAttempts { throw failure }
                        try await Task.sleep(for: .milliseconds(retryDelayMs(attempt)))
                    }
                }
            }
            batchFrom += Self.batch
        }
        // The bucket's own list is the record of what arrived, not this process's memory.
        let stored = Dictionary(try await api.parts(uploadId: start.uploadId, from: 1, count: 1).uploaded.map { ($0.part, $0) }, uniquingKeysWith: { $1 })
        var refs: [R2PartRef] = []
        for part in 1...max(start.partCount, 1) where start.partCount > 0 {
            guard let info = stored[part] else { throw UploadIOError("Part \(part) did not arrive") }
            refs.append(R2PartRef(part: part, etag: info.etag))
        }
        return try await api.complete(uploadId: start.uploadId, parts: refs)
    }

    private func putPart(_ url: String, _ file: URL, _ offset: Int64, _ length: Int64, onBytes: @escaping @Sendable (Int64) -> Void) async throws {
        guard let target = URL(string: url) else { throw UploadIOError("bad part URL") }
        let response: UploadResponse
        do {
            response = try await transport.put(url: target, headers: [:], file: file, offset: offset, length: length, onBytes: onBytes)
        } catch is CancellationError { throw CancellationError() } catch { throw UploadIOError("network") }
        if !(200...299).contains(response.status) {
            if (400...499).contains(response.status) { throw PartRejected(status: response.status) }
            throw UploadIOError("R2 answered \(response.status)")
        }
    }
}

/// Uploads one video to the household's Google Drive in chunks straight to the resumable session, and resumes from the offset
/// the server reports (taken from Drive), so a killed app or a lost connection costs at most one chunk.
public struct DriveUploader: Sendable {
    let api: any OwnCloudApi
    let transport: any UploadTransport
    let retryDelayMs: @Sendable (Int) -> Int64
    /// Progress notes for the log. They never contain the session URL, a token or a file name.
    let log: @Sendable (String) -> Void

    public init(api: any OwnCloudApi, transport: any UploadTransport, retryDelayMs: @escaping @Sendable (Int) -> Int64 = backoffMs, log: @escaping @Sendable (String) -> Void = { _ in }) {
        self.api = api
        self.transport = transport
        self.retryDelayMs = retryDelayMs
        self.log = log
    }

    private static let granule: Int64 = 256 * 1024
    /// With `backoffMs` this waits about two and a half minutes in all, enough to ride out airplane mode or a lift. After that
    /// the save is marked failed and Retry continues from where Drive stopped.
    private static let maxAttempts = 10

    private struct ChunkRefused: Error { var status: Int }

    public func run(start: R2UploadStart, restart: @Sendable () async throws -> R2UploadStart, file: URL, size: Int64,
                    onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> R2Done {
        do {
            return try await send(start, file, size, onProgress)
        } catch let gone as R2ApiError {
            guard gone.code == "upload_gone" else { throw gone }
            // The session expired (Drive keeps one for a week): cancel it, so the server starts a new one, then begin again once.
            try? await api.abort(uploadId: start.uploadId)
            return try await send(try await restart(), file, size, onProgress)
        }
    }

    private func send(_ start: R2UploadStart, _ file: URL, _ size: Int64, _ onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> R2Done {
        let chunk = start.chunkSize
        // Drive wants every chunk but the last to be a multiple of 256 KiB.
        guard chunk > 0, chunk % Self.granule == 0 else { throw UploadIOError("The server sent a chunk size Drive would refuse") }
        // The first answer also says where to begin: Drive's own count, so a resume never trusts this process's memory.
        let first = try await api.session(uploadId: start.uploadId)
        let url = first.sessionUrl
        var offset = min(max(first.receivedBytes, 0), size)
        log("Drive upload: \(start.resumed ? "resumed" : "started") at \(offset) of \(size)")
        onProgress(offset, size)
        var failures = 0
        while offset < size {
            try Task.checkCancellation()
            let from = offset
            let to = min(from + chunk, size)
            do {
                let next = try await putChunk(url, file, from, to, size) { onProgress(from + $0, size) }
                if next <= from { throw UploadIOError("Drive did not advance") }
                offset = next
                failures = 0
                log("Drive upload: chunk ok, Drive holds \(offset) of \(size)")
                onProgress(offset, size)
            } catch is CancellationError {
                throw CancellationError()
            } catch let refused as ChunkRefused {
                if refused.status == 404 || refused.status == 410 { throw R2ApiError(status: 410, code: "upload_gone") }
                try await recover(&failures, &offset, start, size, onProgress, UploadIOError("Drive refused the chunk (\(refused.status))"))
            } catch let failure as UploadIOError {
                try await recover(&failures, &offset, start, size, onProgress, failure)
            }
        }
        return try await api.complete(uploadId: start.uploadId)
    }

    /// Waits, then asks where Drive stands: whether a chunk that failed half way arrived is Drive's to say.
    private func recover(_ failures: inout Int, _ offset: inout Int64, _ start: R2UploadStart, _ size: Int64,
                         _ onProgress: @Sendable (Int64, Int64) -> Void, _ failure: UploadIOError) async throws {
        failures += 1
        log("Drive upload: chunk failed (\(failures)/\(Self.maxAttempts))")
        if failures >= Self.maxAttempts { throw failure }
        try await Task.sleep(for: .milliseconds(retryDelayMs(failures)))
        // If the server cannot be reached either (the network is still down), keep going: the next attempt asks again.
        do {
            offset = min(max(try await api.session(uploadId: start.uploadId).receivedBytes, 0), size)
            log("Drive upload: after the failure Drive holds \(offset) of \(size)")
            onProgress(offset, size)
        } catch let refused as R2ApiError where refused.status != 0 {
            // A real answer from the server (the session is gone): stop. Status 0 is only "no network", which is waited out.
            throw refused
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            log("Drive upload: could not ask where Drive stands")
        }
    }

    /// Sends bytes [from, to) and returns how far Drive now says it has: 308 reports a `Range`, 200 or 201 means all of it.
    private func putChunk(_ url: String, _ file: URL, _ from: Int64, _ to: Int64, _ total: Int64, onBytes: @escaping @Sendable (Int64) -> Void) async throws -> Int64 {
        guard let target = URL(string: url) else { throw UploadIOError("bad session URL") }
        let response: UploadResponse
        do {
            response = try await transport.put(url: target, headers: ["Content-Range": "bytes \(from)-\(to - 1)/\(total)"], file: file, offset: from, length: to - from, onBytes: onBytes)
        } catch is CancellationError { throw CancellationError() } catch { throw UploadIOError("network") }
        switch response.status {
        case 200, 201: return total
        case 308:
            guard let range = response.headers["range"], let match = range.range(of: #"^bytes=0-(\d+)$"#, options: .regularExpression) else { return 0 }
            return (Int64(range[match].split(separator: "-").last ?? "") ?? -1) + 1
        case 400...499: throw ChunkRefused(status: response.status)
        default: throw UploadIOError("Drive answered \(response.status)")
        }
    }
}

/// The one uploader the phone runs. It starts the upload, then hands it to the uploader the server's `protocol` names, so a
/// provider that reuses a protocol needs no change here.
public struct OwnCloudUploader: Sendable {
    let api: any OwnCloudApi
    let s3: R2Uploader
    let drive: DriveUploader

    public init(api: any OwnCloudApi, transport: any UploadTransport, retryDelayMs: @escaping @Sendable (Int) -> Int64 = backoffMs, log: @escaping @Sendable (String) -> Void = { _ in }) {
        self.api = api
        s3 = R2Uploader(api: api, transport: transport, retryDelayMs: retryDelayMs)
        drive = DriveUploader(api: api, transport: transport, retryDelayMs: retryDelayMs, log: log)
    }

    public func upload(originIdentity: String, fileName: String, contentType: String, file: URL, onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> R2Done {
        let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        guard size > 0 else { throw UploadIOError("Nothing to upload") }
        let restart: @Sendable () async throws -> R2UploadStart = { [api] in try await api.start(originIdentity: originIdentity, sizeBytes: size, contentType: contentType, fileName: fileName) }
        let start = try await restart()
        switch start.protocolName {
        case "s3_multipart": return try await s3.run(start: start, restart: restart, file: file, size: size, onProgress: onProgress)
        case "gdrive_resumable": return try await drive.run(start: start, restart: restart, file: file, size: size, onProgress: onProgress)
        default:
            // The server has already opened the upload; don't leave it waiting for bytes that never come.
            try? await api.abort(uploadId: start.uploadId)
            throw R2ApiError(status: 0, code: "unsupported_protocol")
        }
    }
}
