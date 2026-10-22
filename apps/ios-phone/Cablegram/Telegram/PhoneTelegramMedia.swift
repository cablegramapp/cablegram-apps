import CablegramCore
import Foundation

/// The household's hidden and deleted Telegram sources, as the control plane lists them. Refreshed at most once a minute. Until it has been
/// loaded once, every source counts as removed: serving nothing is safer than serving a video the owner hid.
actor RemovedTelegramSources {
    private let client: @Sendable () -> CatalogClient
    private let token: @Sendable () -> String?
    private var keys: Set<String>?
    private var loadedAt = Date.distantPast

    init(client: @escaping @Sendable () -> CatalogClient, token: @escaping @Sendable () -> String?) { self.client = client; self.token = token }

    func contains(_ stableSourceKey: String) async -> Bool {
        if keys == nil || Date().timeIntervalSince(loadedAt) > 60, let token = token(), let latest = await client().telegramTombstones(token: token) {
            keys = Set(latest.map(\.stableSourceKey))
            loadedAt = Date()
        }
        return keys?.contains(stableSourceKey) ?? true
    }
}

/// Serves Telegram titles from this phone's own session, for TVs that must not hold one (temporary TVs). Only the household's library
/// channel is read. The window reader keeps at most a small part of the file on the phone, and it is dropped a minute after the last request.
final class PhoneTelegramMedia: TelegramMedia, @unchecked Sendable {
    private let removed: RemovedTelegramSources
    private let chatId: @Sendable () async -> Int64?
    private let window: Int64
    private let refillAt: Int64
    private let lock = NSLock()
    private var known: [String: TelegramFileRef] = [:]
    private var lastUsed: [Int: Date] = [:]
    private var releaseTask: Task<Void, Never>?
    private var reader: TelegramFileReader?
    /// The session everything cached here belongs to; TDLib file ids mean nothing in another client's database.
    private var cachedFor: ObjectIdentifier?

    private static let idleRelease: TimeInterval = 60

    init(removed: RemovedTelegramSources, chatId: @escaping @Sendable () async -> Int64?, window: Int64 = 48 * TelegramFileReader.mb, refillAt: Int64 = 16 * TelegramFileReader.mb) {
        self.removed = removed; self.chatId = chatId; self.window = window; self.refillAt = refillAt
    }

    private func dropCachesIfSessionChanged() async {
        let now = await MainActor.run { PhoneTelegram.sessionIdentity }
        lock.withLock {
            guard cachedFor != now else { return }
            cachedFor = now
            known = [:]; lastUsed = [:]; reader = nil
        }
    }

    func resolve(uniqueId: String) async -> TelegramFileRef? {
        guard await MainActor.run(body: { PhoneTelegram.wasLinked }) else { return nil }
        await dropCachesIfSessionChanged()
        if await removed.contains("tgfile:\(uniqueId)") { return nil }
        let session = await MainActor.run { PhoneTelegram.session() }
        guard await session.first(timeout: 20, where: { if case .ready = $0 { true } else { false } }) != nil else { return nil }
        if let hit = lock.withLock({ known[uniqueId] }) { return hit }
        guard let channel = await chatId() else { return nil }
        let api = session.api
        try? await TelegramLibrarySync.forEachVideo(api, channel) { [self] video in
            lock.withLock { known[video.file.uniqueId] = TelegramFileRef(fileId: video.file.id, size: video.file.size, mime: video.mimeType.isEmpty ? "video/mp4" : video.mimeType) }
            return video.file.uniqueId == uniqueId
        }
        return lock.withLock { known[uniqueId] }
    }

    func read(fileId: Int, position: Int64, want: Int) async -> Data? {
        await dropCachesIfSessionChanged()
        touch(fileId)
        let reader = await readerForSession()
        return try? await reader.read(fileId: fileId, position: position, want: want)
    }

    func release(fileId: Int) {
        lock.withLock { _ = lastUsed.removeValue(forKey: fileId) }
        Task { await readerForSession().release(fileId) }
    }

    private func readerForSession() async -> TelegramFileReader {
        if let existing = lock.withLock({ reader }) { return existing }
        let api = await MainActor.run { PhoneTelegram.session().api }
        return lock.withLock {
            if let existing = reader { return existing }
            let created = TelegramFileReader(api: api, window: window, refillAt: refillAt)
            reader = created
            return created
        }
    }

    /// Each file's window is dropped a minute after its own last read, not when another file is read.
    private func touch(_ fileId: Int) {
        let start: Bool = lock.withLock {
            lastUsed[fileId] = Date()
            guard releaseTask == nil else { return false }
            releaseTask = Task {}   // placeholder replaced below
            return true
        }
        guard start else { return }
        let task = Task { [self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                let stale: [Int] = lock.withLock {
                    let now = Date()
                    let ids = lastUsed.filter { now.timeIntervalSince($0.value) >= Self.idleRelease }.map(\.key)
                    ids.forEach { lastUsed[$0] = nil }
                    return ids
                }
                for id in stale { await readerForSession().release(id) }
                let done = lock.withLock { () -> Bool in if lastUsed.isEmpty { releaseTask = nil; return true }; return false }
                if done { return }
            }
        }
        lock.withLock { releaseTask = task }
    }
}
