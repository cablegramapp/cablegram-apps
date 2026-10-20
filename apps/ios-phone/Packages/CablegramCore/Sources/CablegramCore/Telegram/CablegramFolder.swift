import Foundation

// Port of `CablegramFolder.kt`.

let cablegramFolderName = "Cablegram"
let cablegramSupportBot = "CablegramSupportBot"

/// A Telegram chat folder as far as Cablegram merges it (TDLib `chatFolder`).
public struct TgChatFolder: Sendable, Equatable {
    public var name: String
    public var icon: String?
    public var colorId: Int = -1
    public var isShareable = false
    public var pinnedChatIds: [Int64] = []
    public var includedChatIds: [Int64] = []
    public var excludedChatIds: [Int64] = []
    public var excludeMuted = false
    public var excludeRead = false
    public var excludeArchived = false
    public var includeContacts = false
    public var includeNonContacts = false
    public var includeBots = false
    public var includeGroups = false
    public var includeChannels = false
    public init(name: String) { self.name = name }
}

public struct TgChatFolderInfo: Sendable, Equatable {
    public var id: Int
    public var name: String
    public init(id: Int, name: String) { self.id = id; self.name = name }
}

/// Only folder metadata: no chat histories or contacts are read to organize the folder.
public protocol CablegramFolderStore: Sendable {
    func folders() async throws -> [TgChatFolderInfo]
    func folder(_ id: Int) async throws -> TgChatFolder
    func create(_ folder: TgChatFolder) async throws
    func edit(_ id: Int, _ folder: TgChatFolder) async throws
}

/// Merge explicit chats, preserving the owner's pins, filters, style, and unrelated entries.
public final class CablegramFolderOrganizer: Sendable {
    private let store: any CablegramFolderStore
    private let mutex = AsyncMutex()
    public init(store: any CablegramFolderStore) { self.store = store }

    public func include(_ chatIds: [Int64]) async throws {
        try await mutex.withLock { try await self.merge(chatIds) }
    }

    private func merge(_ chatIds: [Int64]) async throws {
        let desired = Set(chatIds.filter { $0 != 0 })
        if desired.isEmpty { return }
        let info = try await store.folders().first { $0.name.trimmingCharacters(in: .whitespaces).lowercased() == cablegramFolderName.lowercased() }
        var old: TgChatFolder?
        if let info { old = try await store.folder(info.id) }
        let pinned = old?.pinnedChatIds ?? []
        var included: [Int64] = []
        for id in (old?.includedChatIds ?? []) + chatIds.filter({ $0 != 0 }) where !pinned.contains(id) && !included.contains(id) { included.append(id) }
        var merged = old ?? TgChatFolder(name: cablegramFolderName)
        merged.pinnedChatIds = pinned
        merged.includedChatIds = included
        merged.excludedChatIds = (old?.excludedChatIds ?? []).filter { !desired.contains($0) }
        if let info {
            if old != merged { try await store.edit(info.id, merged) }
        } else {
            try await store.create(merged)
        }
    }
}
