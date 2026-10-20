import Foundation

// Port of `TelegramFileReader.kt`.

/// Random-access reads of Telegram files for a local HTTP Range server.
///
/// TDLib, left alone, downloads a whole file as fast as it can: on a Chromecast that filled the storage and froze playback. So only a
/// window ahead of the reader is requested, it moves with the reader, and once more than `maxStored` bytes of a file are on disk the
/// local copy is dropped and the window restarts at the reader's position.
public final class TelegramFileReader: @unchecked Sendable {
    public static let mb: Int64 = 1024 * 1024

    private let api: any TelegramApi
    private let window: Int64
    private let refillAt: Int64
    private let maxStored: Int64
    private let timeout: TimeInterval
    private let now: @Sendable () -> Date
    private let lock = NSLock()
    private var windowStart: [Int: Int64] = [:]
    private var served: Int64 = 0

    public init(api: any TelegramApi, window: Int64 = 48 * TelegramFileReader.mb, refillAt: Int64 = 16 * TelegramFileReader.mb,
                maxStored: Int64 = 256 * TelegramFileReader.mb, timeout: TimeInterval = 60, now: @escaping @Sendable () -> Date = { Date() }) {
        self.api = api; self.window = window; self.refillAt = refillAt; self.maxStored = maxStored; self.timeout = timeout; self.now = now
    }

    public var bytesServed: Int64 { lock.withLock { served } }

    /// Up to `want` bytes of `fileId` at `position`, waiting for Telegram to deliver them. Nil if nothing arrives within the timeout.
    public func read(fileId: Int, position: Int64, want: Int) async throws -> Data? {
        let deadline = now().addingTimeInterval(timeout)
        while now() < deadline {
            try Task.checkCancellation()
            try await ensureWindow(fileId, position)
            let file = try await api.file(fileId)
            let available = Self.availableAt(file, position: position)
            if available > 0 {
                let count = min(Int64(want), available)
                do {
                    let bytes = try await api.readPart(fileId: fileId, offset: position, count: count)
                    if !bytes.isEmpty { lock.withLock { served += Int64(bytes.count) }; return bytes }
                } catch is TgError {
                    // The window can move between the check and the read; look again shortly.
                    try await Task.sleep(nanoseconds: 250_000_000)
                    continue
                }
            }
            await waitForUpdate(fileId, seconds: 0.5)
        }
        return nil
    }

    /// Drops what is stored for `fileId` (playback stopped).
    public func release(_ fileId: Int) async {
        lock.withLock { _ = windowStart.removeValue(forKey: fileId) }
        try? await api.cancelDownload(fileId)
        try? await api.deleteLocalFile(fileId)
    }

    private func waitForUpdate(_ fileId: Int, seconds: Double) async {
        let updates = api.fileUpdates
        _ = try? await withTimeout(seconds: seconds) { () -> Bool in
            for await file in updates where file.id == fileId { return true }
            return false
        }
    }

    private func ensureWindow(_ fileId: Int, _ position: Int64) async throws {
        let start = lock.withLock { windowStart[fileId] }
        guard needsMove(start: start, position: position) else { return }
        let file = try await api.file(fileId)
        if file.downloadedSize > maxStored {
            try? await api.cancelDownload(fileId)
            try? await api.deleteLocalFile(fileId)
        }
        lock.withLock { windowStart[fileId] = position }
        try await api.download(fileId: fileId, offset: position, limit: window)
    }

    func needsMove(start: Int64?, position: Int64) -> Bool {
        guard let start else { return true }
        return position < start || position > start + window - refillAt
    }

    /// Bytes readable at `position` from what TDLib has contiguously downloaded.
    public static func availableAt(_ file: TgFile, position: Int64) -> Int64 {
        let start = file.downloadOffset
        let end = start + file.downloadedPrefixSize
        return position >= start && position < end ? end - position : 0
    }
}

/// A parsed HTTP `Range` header against a Telegram file of `size` bytes. Single ranges only (what players send); anything malformed is
/// served as the full file. (Not the library server's strict `ByteRange`, which answers 416 to a malformed range.)
public enum TelegramByteRange: Equatable, Sendable {
    case full(size: Int64)
    case partial(start: Int64, end: Int64, size: Int64)
    case unsatisfiable(size: Int64)

    public var length: Int64 { if case let .partial(start, end, _) = self { return end - start + 1 }; return 0 }
    public var contentRange: String? {
        switch self {
        case let .partial(start, end, size): "bytes \(start)-\(end)/\(size)"
        case let .unsatisfiable(size): "bytes */\(size)"
        case .full: nil
        }
    }

    public static func parse(_ header: String?, size: Int64) -> TelegramByteRange {
        guard let header, header.hasPrefix("bytes="), size > 0 else { return .full(size: size) }
        let spec = String(header.dropFirst(6)).split(separator: ",", omittingEmptySubsequences: false).first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
        let dash = spec.firstIndex(of: "-")
        let first = dash.flatMap { Int64(spec[..<$0].trimmingCharacters(in: .whitespaces)) }
        let last = dash.flatMap { Int64(spec[spec.index(after: $0)...].trimmingCharacters(in: .whitespaces)) }
        if let first, first >= size { return .unsatisfiable(size: size) }
        if let first {
            let end = min(last ?? (size - 1), size - 1)
            return first <= end ? .partial(start: first, end: end, size: size) : .unsatisfiable(size: size)
        }
        if let last, last > 0 { return .partial(start: max(0, size - last), end: size - 1, size: size) }
        return .full(size: size)
    }
}
