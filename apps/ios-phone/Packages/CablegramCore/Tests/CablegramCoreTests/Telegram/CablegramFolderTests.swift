import Foundation
import Testing
@testable import CablegramCore

// Port of `CablegramFolderTest.kt`.

@Suite struct CablegramFolderTests {
    struct FolderLimit: Error {}

    func folder(_ name: String = "Cablegram", included: [Int64] = [], pinned: [Int64] = [], excluded: [Int64] = []) -> TgChatFolder {
        var f = TgChatFolder(name: name)
        f.icon = "Custom"; f.includedChatIds = included; f.pinnedChatIds = pinned; f.excludedChatIds = excluded
        return f
    }

    final class Store: CablegramFolderStore, @unchecked Sendable {
        private let lock = NSLock()
        private var entries: [(id: Int, folder: TgChatFolder)] = []
        private var createCount = 0, editCount = 0, failing = false
        var creates: Int { lock.withLock { createCount } }
        var edits: Int { lock.withLock { editCount } }
        var fail: Bool { get { lock.withLock { failing } } set { lock.withLock { failing = newValue } } }
        var saved: [Int: TgChatFolder] { lock.withLock { Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0.folder) }) } }
        func seed(_ id: Int, _ folder: TgChatFolder) { lock.withLock { entries.append((id, folder)) } }

        func folders() async throws -> [TgChatFolderInfo] { lock.withLock { entries.map { TgChatFolderInfo(id: $0.id, name: $0.folder.name) } } }
        func folder(_ id: Int) async throws -> TgChatFolder { lock.withLock { entries.first { $0.id == id }!.folder } }
        func create(_ folder: TgChatFolder) async throws {
            await Task.yield()
            try lock.withLock {
                if failing { throw FolderLimit() }
                createCount += 1
                entries.append((createCount, folder))
            }
        }
        func edit(_ id: Int, _ folder: TgChatFolder) async throws {
            try lock.withLock {
                if failing { throw FolderLimit() }
                editCount += 1
                entries[entries.firstIndex { $0.id == id }!].folder = folder
            }
        }
    }

    @Test func createsOneExplicitCablegramFolderWithoutBroadChatFilters() async throws {
        let store = Store()
        let organizer = CablegramFolderOrganizer(store: store)
        try await organizer.include([-1001, -1002, 42, 42, 0])
        let created = try #require(store.saved.values.first)
        #expect(store.saved.count == 1)
        #expect(created.name == "Cablegram")
        #expect(created.includedChatIds == [-1001, -1002, 42])
        #expect(created.pinnedChatIds.isEmpty)
        #expect(!(created.includeContacts || created.includeNonContacts || created.includeBots || created.includeGroups || created.includeChannels))
        #expect(!created.isShareable)
    }

    @Test func reusesExistingNamedFolderAndPreservesPinsFiltersAndUnrelatedEntries() async throws {
        let store = Store()
        var old = TgChatFolder(name: " cablegram ")
        old.icon = "Favorite"; old.colorId = 3; old.pinnedChatIds = [91]; old.includedChatIds = [92]; old.excludedChatIds = [93, 94]
        old.excludeMuted = true; old.excludeRead = true; old.excludeArchived = true
        old.includeContacts = true; old.includeNonContacts = true; old.includeBots = true; old.includeGroups = true; old.includeChannels = true
        store.seed(17, old)
        store.seed(18, folder("Personal", included: [500]))
        try await CablegramFolderOrganizer(store: store).include([91, 93, 95])
        let merged = try #require(store.saved[17])
        #expect(store.creates == 0)
        #expect(store.edits == 1)
        #expect(merged.name == old.name)
        #expect(merged.icon == old.icon)
        #expect(merged.colorId == old.colorId)
        #expect(merged.pinnedChatIds == [91])
        #expect(merged.includedChatIds == [92, 93, 95])
        #expect(merged.excludedChatIds == [94])
        #expect(merged.excludeMuted && merged.excludeRead && merged.excludeArchived)
        #expect(merged.includeContacts && merged.includeNonContacts && merged.includeBots && merged.includeGroups && merged.includeChannels)
        #expect(store.saved[18]?.includedChatIds == [500])
    }

    @Test func repeatOrganizationIsIdempotentAndDoesNotEditAnUnchangedFolder() async throws {
        let store = Store()
        let organizer = CablegramFolderOrganizer(store: store)
        try await organizer.include([11, 12])
        try await organizer.include([11, 12, 12])
        #expect(store.creates == 1)
        #expect(store.edits == 0)
        #expect(store.saved.values.first?.includedChatIds == [11, 12])
    }

    @Test func concurrentMergesCreateOnlyOneFolderAndKeepBothAdditions() async throws {
        let store = Store()
        let organizer = CablegramFolderOrganizer(store: store)
        // Concurrent merges are serialised across awaits (Kotlin holds a Mutex): one folder, both additions kept.
        async let first: Void = organizer.include([11])
        async let second: Void = organizer.include([12])
        try await first
        try await second
        #expect(store.creates == 1)
        #expect(store.saved.values.first?.includedChatIds.sorted() == [11, 12])
    }

    @Test func folderLimitFailureLeavesExistingFolderUntouchedAndCanBeRetried() async throws {
        let store = Store()
        let old = folder(included: [11])
        store.seed(7, old)
        store.fail = true
        let organizer = CablegramFolderOrganizer(store: store)
        await #expect(throws: FolderLimit.self) { try await organizer.include([12]) }
        #expect(store.saved[7] == old)
        store.fail = false
        try await organizer.include([12])
        #expect(store.saved[7]?.includedChatIds == [11, 12])
    }

    @Test func emptyInclusionDoesNotCreateAFolder() async throws {
        let store = Store()
        try await CablegramFolderOrganizer(store: store).include([0])
        #expect(store.saved.isEmpty)
    }
}
