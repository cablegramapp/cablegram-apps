import Foundation

/// A video the phone can analyse and preview: a local file, or a Telegram title read in small windows.
public protocol SubtitleMediaInput: AnyObject, Sendable {
    var size: Int64 { get }
    /// The file itself when it lives on this phone (AVFoundation opens it directly); nil for a Telegram title.
    var fileURL: URL? { get }
    /// The type the container claims (`video/mp4`), when known.
    var mimeType: String? { get }
    /// True when reading means fetching over the network, which can be slow and must stay cancellable.
    var remote: Bool { get }
    /// Bytes fetched over the network so far (0 for local files); shown so a slow fetch doesn't look like nothing happening.
    var bytesFetched: Int64 { get }
    var budgetHit: Bool { get }
    /// Caps what the audio check may fetch (whole check, and each section); past it reads end as if the file stopped.
    func limitAnalysis(totalBytes: Int64, sectionBytes: Int64)
    func startSection()
    /// Up to `length` bytes at `position`, or nil when nothing could be read.
    func readAt(position: Int64, length: Int) async -> Data?
    /// Makes pending and later reads return at once.
    func close()
}

extension SubtitleMediaInput {
    public var fileURL: URL? { nil }
    public var mimeType: String? { nil }
    public var remote: Bool { false }
    public var bytesFetched: Int64 { 0 }
    public var budgetHit: Bool { false }
    public func limitAnalysis(totalBytes: Int64, sectionBytes: Int64) {}
    public func startSection() {}
}

/// A video file on this phone. `release` closes what made it readable (a security scope, a temporary copy).
public final class FileMediaInput: SubtitleMediaInput, @unchecked Sendable {
    public let fileURL: URL?
    public let size: Int64
    private let handle: FileHandle?
    private let lock = NSLock()
    private let onClose: @Sendable () -> Void
    private var closed = false

    public init(url: URL, release: @escaping @Sendable () -> Void = {}) {
        fileURL = url
        size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
        handle = try? FileHandle(forReadingFrom: url)
        onClose = release
    }

    public func readAt(position: Int64, length: Int) async -> Data? {
        lock.withLock {
            guard !closed, let handle else { return nil }
            do {
                try handle.seek(toOffset: UInt64(max(0, position)))
                let data = try handle.read(upToCount: length) ?? Data()
                return data.isEmpty ? nil : data
            } catch { return nil }
        }
    }

    public func close() {
        let first = lock.withLock { () -> Bool in defer { closed = true }; return !closed }
        guard first else { return }
        try? handle?.close()
        onClose()
    }
}

/// Reads a Telegram title through the phone's own session; nothing is stored beyond the reader's small window.
/// Every read is bounded and can be abandoned: `close` makes pending and later reads return at once, so skipping
/// or leaving the screen never waits on Telegram.
public final class TelegramMediaInput: SubtitleMediaInput, @unchecked Sendable {
    private let media: any TelegramMedia
    private let file: TelegramFileRef
    public var size: Int64 { file.size }
    public var remote: Bool { true }
    public var mimeType: String? { file.mime }

    private let lock = NSLock()
    private var closed = false
    private var fetched: Int64 = 0
    private var totalLimit = Int64.max
    private var sectionLimit = Int64.max
    private var sectionStart: Int64 = 0
    private var hit = false
    // The extractors ask for a few hundred bytes at a time. One Telegram round trip per request would take minutes, so the file is read
    // in aligned chunks and small requests are answered from memory.
    private var chunks: [(index: Int64, data: Data)] = []

    private static let readTimeout: TimeInterval = 30
    static let chunkSize: Int64 = 1024 * 1024

    public init(media: any TelegramMedia, file: TelegramFileRef) { self.media = media; self.file = file }

    public var bytesFetched: Int64 { lock.withLock { fetched } }
    public var budgetHit: Bool { lock.withLock { hit } }
    public func limitAnalysis(totalBytes: Int64, sectionBytes: Int64) { lock.withLock { totalLimit = totalBytes; sectionLimit = sectionBytes } }
    public func startSection() { lock.withLock { sectionStart = fetched } }

    private var isClosed: Bool { lock.withLock { closed } }

    private func overBudget() -> Bool {
        lock.withLock {
            let over = fetched > totalLimit || fetched - sectionStart > sectionLimit
            if over { hit = true }
            return over
        }
    }

    /// Waits for `pending` in short slices so a close, the deadline or the caller's cancellation ends the wait at once.
    private func wait(_ pending: Task<Data?, Never>, deadline: Date) async -> Data? {
        let slot = ReadSlot()
        let watcher = Task { slot.set(await pending.value) }
        defer { watcher.cancel() }
        while true {
            if let done = slot.value { return done }
            if isClosed || Task.isCancelled || Date() > deadline { pending.cancel(); return nil }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    private func fetchChunk(_ index: Int64) async -> Data? {
        let start = index * Self.chunkSize
        let length = Int(min(Self.chunkSize, file.size - start))
        if length <= 0 { return nil }
        var out = Data()
        let deadline = Date().addingTimeInterval(Self.readTimeout)
        while out.count < length {
            if isClosed || overBudget() || Date() > deadline { break }
            let at = start + Int64(out.count), want = length - out.count
            let media = media, id = file.fileId
            let pending = Task<Data?, Never> { await media.read(fileId: id, position: at, want: want) }
            guard let part = await wait(pending, deadline: deadline), !part.isEmpty else { break }
            out.append(part)
            lock.withLock { fetched += Int64(part.count) }
        }
        return out.isEmpty ? nil : out
    }

    public func readAt(position: Int64, length: Int) async -> Data? {
        if isClosed || position < 0 || position >= file.size { return nil }
        var out = Data()
        var at = position
        while out.count < length, at < file.size {
            let index = at / Self.chunkSize
            let cached = lock.withLock { () -> Data? in
                guard let i = chunks.firstIndex(where: { $0.index == index }) else { return nil }
                let entry = chunks.remove(at: i); chunks.append(entry)   // most recently used last
                return entry.data
            }
            let chunk: Data
            if let cached { chunk = cached } else {
                if overBudget() { break }
                guard let fresh = await fetchChunk(index) else { break }
                lock.withLock { chunks.removeAll { $0.index == index }; chunks.append((index, fresh)); if chunks.count > 3 { chunks.removeFirst() } }
                chunk = fresh
            }
            let offset = Int(at - index * Self.chunkSize)
            if offset >= chunk.count { break }
            let n = min(length - out.count, chunk.count - offset)
            out.append(chunk.subdata(in: offset..<(offset + n)))
            at += Int64(n)
            // A short chunk: stop rather than guess.
            if Int64(chunk.count) < Self.chunkSize, index * Self.chunkSize + Int64(chunk.count) < file.size { break }
        }
        return out.isEmpty ? nil : out
    }

    public func close() {
        let first = lock.withLock { () -> Bool in defer { closed = true }; return !closed }
        guard first else { return }
        // Stop Telegram's download of this file now rather than after the reader's idle minute.
        media.release(fileId: file.fileId)
    }
}

private final class ReadSlot: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Data??
    func set(_ data: Data?) { lock.withLock { stored = .some(data) } }
    var value: Data?? { lock.withLock { stored } }
}

/// What the phone reads out of a video: the provider's hash, and (through AVFoundation) its technical details.
public enum SubtitleAnalysis {
    /// The OpenSubtitles movie hash: size plus the first and last 64 KiB read as little-endian 64-bit words. Nil when a part can't be read.
    public static func hash(_ input: any SubtitleMediaInput) async -> String? {
        let size = input.size
        if size < 131_072 { return nil }
        var total = UInt64(size)
        for position in [Int64(0), size - 65_536] {
            var bytes = Data()
            while bytes.count < 65_536 {
                guard let part = await input.readAt(position: position + Int64(bytes.count), length: 65_536 - bytes.count) else { return nil }
                bytes.append(part)
            }
            bytes.withUnsafeBytes { raw in
                var i = 0
                while i + 8 <= raw.count { total &+= raw.loadUnaligned(fromByteOffset: i, as: UInt64.self).littleEndian; i += 8 }
            }
        }
        let hex = String(total, radix: 16)
        return String(repeating: "0", count: max(0, 16 - hex.count)) + hex
    }
}
