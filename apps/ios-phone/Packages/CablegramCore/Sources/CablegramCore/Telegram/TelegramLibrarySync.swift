import Foundation

// Port of `TelegramLibrarySync.kt`.

/// Keeps the catalog in step with the household's library channel: every video in the channel is registered as a `telegram` source,
/// deleted ones are marked unavailable. Reads only that one channel. `register` and `reconcile` call the control plane; `onChanged`
/// refreshes the UI. `hints` sends TMDB references found in the channel for its videos (see `ChannelHints`).
public final class TelegramLibrarySync: @unchecked Sendable {
    private let api: any TelegramApi
    private let chatId: Int64
    private let register: @Sendable (TgVideo) async throws -> Void
    private let reconcile: @Sendable (Set<String>) async -> Void
    private let onChanged: @Sendable () async -> Void
    private let hints: @Sendable ([TgTmdbHint]) async throws -> Void
    /// After a new video: when to look again for a reference posted a little later (by a bot, say).
    private let hintRetryDelays: [Double]
    private let lock = NSLock()
    /// File ids of the channel's videos: only references to these are sent.
    private var known = Set<String>()

    public init(api: any TelegramApi, chatId: Int64, register: @escaping @Sendable (TgVideo) async throws -> Void, reconcile: @escaping @Sendable (Set<String>) async -> Void,
                onChanged: @escaping @Sendable () async -> Void = {}, hints: @escaping @Sendable ([TgTmdbHint]) async throws -> Void = { _ in },
                hintRetryDelays: [Double] = TelegramLibrarySync.defaultHintRetryDelays) {
        self.api = api; self.chatId = chatId; self.register = register; self.reconcile = reconcile; self.onChanged = onChanged; self.hints = hints
        self.hintRetryDelays = hintRetryDelays
    }

    public static let defaultHintRetryDelays: [Double] = [5, 15, 30, 60, 120, 300]
    private static let pageSize = 100
    private static let recentMessages = 50
    private static let hintsPerRequest = 500
    /// Enough for 10 000 messages; a library channel is far smaller.
    private static let maxPages = 100

    private var knownIds: Set<String> { lock.withLock { known } }
    private func remember(_ id: String) { lock.withLock { _ = known.insert(id) } }

    /// Registers every video in the channel, then marks the rest unavailable. Returns how many there are.
    @discardableResult
    public func fullScan() async throws -> Int {
        var keys: [String] = []
        var seen = Set<String>()
        var texts: [TgMessage] = []
        try await Self.forEachPage(api, chatId) { page in
            for video in page.videos {
                self.remember(video.file.uniqueId)
                let key = Self.stableKey(video)
                if seen.insert(key).inserted { keys.append(key); try? await self.register(video) }
                if !video.caption.trimmingCharacters(in: .whitespaces).isEmpty { texts.append(TgMessage(chatId: video.chatId, messageId: video.messageId, video: video, text: video.caption)) }
            }
            texts += page.notes
            return false
        }
        await reconcile(Set(keys))
        // Newest first: the newest reference for a video wins.
        let known = knownIds
        var unique: [TgTmdbHint] = []
        for hint in texts.flatMap({ ChannelHints.from($0, known: known) }) where !unique.contains(where: { $0.uniqueId == hint.uniqueId }) { unique.append(hint) }
        await sendHints(unique)
        await onChanged()
        return keys.count
    }

    /// New videos register at once; deletions trigger a rescan (TDLib reports ids, not files). New and edited messages are read for TMDB
    /// references, and after a new video the channel is looked at again a few times, since a bot's reply often comes seconds later, or
    /// first as a placeholder that is edited afterwards.
    public func watch() -> Task<Void, Never> {
        let new = api.newMessages, edited = api.editedMessages, deleted = api.deletedMessages
        let chat = chatId
        return Task { [weak self] in
            await withTaskGroup(of: Void.self) { group in
                group.addTask { [weak self] in
                    for await message in new where message.chatId == chat {
                        guard let self else { return }
                        if let video = message.video {
                            remember(video.file.uniqueId)
                            try? await register(video)
                            await onChanged()
                        }
                        let found = ChannelHints.from(message, known: knownIds)
                        await sendHints(found)
                        if let video = message.video, !found.contains(where: { $0.uniqueId == video.file.uniqueId }) {
                            let uniqueId = video.file.uniqueId
                            Task { [weak self] in await self?.lookAgain(uniqueId) }
                        }
                    }
                }
                group.addTask { [weak self] in
                    for await message in edited where message.chatId == chat { guard let self else { return }; await sendHints(ChannelHints.from(message, known: knownIds)) }
                }
                group.addTask { [weak self] in
                    for await gone in deleted where gone.chatId == chat { _ = try? await self?.fullScan() }
                }
            }
            _ = self
        }
    }

    /// Reads the newest messages again until one names a TMDB title for `uniqueId`, or the delays run out.
    private func lookAgain(_ uniqueId: String) async {
        for wait in hintRetryDelays {
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            if Task.isCancelled { return }
            guard let page = try? await api.videos(chatId: chatId, fromMessageId: 0, limit: Self.recentMessages) else { continue }
            let messages = page.notes + page.videos.map { TgMessage(chatId: $0.chatId, messageId: $0.messageId, video: $0, text: $0.caption) }
            let known = knownIds
            let found = messages.sorted { $0.messageId > $1.messageId }.flatMap { ChannelHints.from($0, known: known) }.first { $0.uniqueId == uniqueId }
            if let found { await sendHints([found]); return }
        }
    }

    private func sendHints(_ found: [TgTmdbHint]) async {
        guard !found.isEmpty else { return }
        var index = 0
        while index < found.count {
            try? await hints(Array(found[index..<min(index + Self.hintsPerRequest, found.count)]))
            index += Self.hintsPerRequest
        }
        await onChanged()
    }

    /// Every video in the library channel, newest first, page by page, until `visit` returns true or the channel ends. The one channel
    /// walk shared by the sync, deletions, uploads and the phone's streaming.
    public static func forEachVideo(_ api: any TelegramApi, _ chatId: Int64, visit: @escaping (TgVideo) async -> Bool) async throws {
        try await forEachPage(api, chatId) { page in
            for video in page.videos where await visit(video) { return true }
            return false
        }
    }

    /// The channel page by page, newest first, until `visit` returns true or the channel ends.
    private static func forEachPage(_ api: any TelegramApi, _ chatId: Int64, visit: (TgVideoPage) async throws -> Bool) async throws {
        var from: Int64 = 0
        for _ in 0..<maxPages {
            let result = try await api.videos(chatId: chatId, fromMessageId: from, limit: pageSize)
            if try await visit(result) { return }
            let next = result.nextFromMessageId
            if next == 0 || next == from { return }
            from = next
        }
    }

    public static func stableKey(_ video: TgVideo) -> String { "tgfile:\(video.file.uniqueId)" }
    public static func originIdentity(_ video: TgVideo) -> String { "tg:\(video.chatId):\(video.messageId)" }

    /// A caption's first line is the owner's title for the video; otherwise the file name is used.
    public static func title(_ video: TgVideo) -> String? {
        let first = video.caption.split(separator: "\n", omittingEmptySubsequences: false).first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
        return first.isEmpty ? nil : String(first.prefix(200))
    }
}
