import Foundation

// Port of `TelegramRemoval.kt`.

/// A channel deletion the control plane asked for, kept until Telegram confirms it.
public struct PendingTelegramDeletion: Codable, Sendable, Equatable {
    public var stableSourceKey: String
    public var chatId: Int64
    public var messageIds: [Int64]
    public var title: String
    public var createdAt: Int64
    public var attempts: Int
    public var lastError: String?
    /// Telegram already deleted the messages; only telling the control plane is left.
    public var deletedInTelegram: Bool

    public init(stableSourceKey: String, chatId: Int64, messageIds: [Int64], title: String, createdAt: Int64, attempts: Int = 0, lastError: String? = nil, deletedInTelegram: Bool = false) {
        self.stableSourceKey = stableSourceKey; self.chatId = chatId; self.messageIds = messageIds; self.title = title; self.createdAt = createdAt
        self.attempts = attempts; self.lastError = lastError; self.deletedInTelegram = deletedInTelegram
    }
}

/// The deletions still to do, persisted so a failed or offline deletion is retried after a restart. `load` and `save` hold the JSON text.
public final class TelegramDeletionQueue: @unchecked Sendable {
    /// A deletion that still fails after this long is reported to the user.
    public static let giveUpAfter: TimeInterval = 24 * 3600

    private let load: () -> String?
    private let save: (String) -> Void
    private let lock = NSLock()

    public init(load: @escaping () -> String?, save: @escaping (String) -> Void) { self.load = load; self.save = save }

    /// A queue kept in a JSON file.
    public convenience init(file: URL) {
        self.init(load: { try? String(contentsOf: file, encoding: .utf8) }, save: { try? $0.write(to: file, atomically: true, encoding: .utf8) })
    }

    public func all() -> [PendingTelegramDeletion] { lock.withLock { read() } }

    public func add(_ item: PendingTelegramDeletion) { lock.withLock { write(read().filter { $0.stableSourceKey != item.stableSourceKey } + [item]) } }
    public func remove(_ key: String) { lock.withLock { write(read().filter { $0.stableSourceKey != key }) } }
    public func markDeletedInTelegram(_ key: String) { lock.withLock { write(read().map { var i = $0; if i.stableSourceKey == key { i.deletedInTelegram = true }; return i }) } }
    public func failed(_ key: String, error: String) { lock.withLock { write(read().map { var i = $0; if i.stableSourceKey == key { i.attempts += 1; i.lastError = error }; return i }) } }

    private func read() -> [PendingTelegramDeletion] {
        guard let text = load(), let data = text.data(using: .utf8) else { return [] }
        return (try? JSONDecoder().decode([PendingTelegramDeletion].self, from: data)) ?? []
    }

    private func write(_ items: [PendingTelegramDeletion]) {
        if let data = try? JSONEncoder().encode(items), let text = String(data: data, encoding: .utf8) { save(text) }
    }
}

public enum TelegramRemoval {
    /// For each file in `keys`, every message in the channel that carries it (a file forwarded twice is two messages; deleting one would
    /// leave the copy), found in one walk of the channel for the whole batch.
    public static func channelMessageIds(_ api: any TelegramApi, chatId: Int64, keys: Set<String>) async throws -> [String: Set<Int64>] {
        let found = Found(keys)
        try await TelegramLibrarySync.forEachVideo(api, chatId) { video in
            found.add(TelegramLibrarySync.stableKey(video), video.messageId)
            return false
        }
        return found.result
    }

    /// A video already in the channel from an earlier "Save to Telegram" of the same title that did not finish registering: one of the
    /// names it could have been uploaded under (`uploadNames`) and exactly the same size. Saving again then reuses it instead of adding a
    /// second copy of a large file.
    public static func findUpload(_ api: any TelegramApi, chatId: Int64, sizeBytes: Int64, uploadNames: Set<String>) async throws -> TgVideo? {
        let match = Match()
        try await TelegramLibrarySync.forEachVideo(api, chatId) { video in
            guard uploadNames.contains(video.fileName), video.file.size == sizeBytes else { return false }
            match.video = video
            return true
        }
        return match.video
    }

    private final class Found: @unchecked Sendable {
        private let lock = NSLock()
        private var map: [String: Set<Int64>]
        init(_ keys: Set<String>) { map = Dictionary(uniqueKeysWithValues: keys.map { ($0, Set<Int64>()) }) }
        func add(_ key: String, _ id: Int64) { lock.withLock { if map[key] != nil { map[key]!.insert(id) } } }
        var result: [String: Set<Int64>] { lock.withLock { map } }
    }

    private final class Match: @unchecked Sendable { var video: TgVideo? }
}

extension LibraryStore {
    /// The file name Telegram shows for this title's upload; the same every time, so a repeat can find it.
    public func telegramUploadName(_ item: LibraryItem) -> String {
        let last = item.filename.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? item.filename
        let safe = String(last.map { $0.isASCII && ($0.isLetter || $0.isNumber || "._ -".contains($0)) ? $0 : "_" })
        return "\(String(item.id.prefix(8)))-\(safe.trimmingCharacters(in: .whitespaces).isEmpty ? "video.mp4" : safe)"
    }

    /// Every name an upload of `item` can carry in Telegram: the prepared name, or the file's own name when the phone could not link it
    /// under the prepared name and uploaded the original.
    public func telegramUploadNames(_ item: LibraryItem) -> Set<String> { [telegramUploadName(item), videoFile(item).lastPathComponent] }
}
