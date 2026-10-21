import Foundation
import Testing
@testable import CablegramCore

private func tempStore() -> LibraryStore {
    LibraryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("lib-\(UUID().uuidString)"))
}

private func local(_ id: String) -> LibraryItem { LibraryItem(id: id, title: id, filename: "\(id).mp4", importedAt: "2026-01-01T00:00:00Z") }

@Suite struct LibraryStoreTests {
    @Test func theIndexSurvivesAReopen() {
        let store = tempStore()
        store.add(local("a"))
        #expect(LibraryStore(root: store.root).list().map(\.id) == ["a"])
    }

    @Test func anotherAccountsLibraryIsSetAsideAndRestored() {
        let store = tempStore()
        #expect(!store.bindHousehold("h1"))
        store.add(local("a"))
        #expect(store.bindHousehold("h2"), "h1's library is set aside")
        #expect(store.list().isEmpty)
        store.add(local("b"))
        store.bindHousehold("h1")
        #expect(store.list().map(\.id) == ["a"], "h1's library is back")
        store.bindHousehold("h2")
        #expect(store.list().map(\.id) == ["b"])
    }

    @Test func forgettingTheHouseholdKeepsPhoneFilesOnly() {
        let store = tempStore()
        store.bindHousehold("h1")
        store.add(local("mine"))
        var restored = local("restored"); restored.householdOnly = true
        var tg = local("tg"); tg.sourceKind = "telegram"
        store.add(restored); store.add(tg)
        store.forgetHousehold()
        #expect(store.list().map(\.id) == ["mine"])
        #expect(store.snapshot().householdId == nil)
    }

    @Test func aRemovedHouseholdTitleDoesNotComeBack() {
        let store = tempStore()
        let remote = RemoteCatalogItem(id: "r1", title: "Remote")
        store.restoreFromHousehold(remote, source: RemoteCatalogSource(originFilename: "remote.mkv"))
        #expect(store.get("r1")?.householdOnly == true && store.get("r1")?.fileName == "r1.mkv")
        store.removeFromLibrary("r1")
        #expect(store.dismissedRemoteIds() == ["r1"])
        store.add(local("plain")); store.removeFromLibrary("plain")
        #expect(store.dismissedRemoteIds() == ["r1"], "a phone file is not remembered as dismissed")
    }

    @Test func telegramTitlesAreRegisteredAndPruned() {
        let store = tempStore()
        let remote = RemoteCatalogItem(id: "t1", durationSeconds: 90)
        let source = RemoteCatalogSource(kind: "telegram", originFilename: "Show.S01E01.mkv", stableSourceKey: "tgfile:abc", availability: "available")
        let item = store.registerTelegram(remote, source: source)
        #expect(item.sourceKind == "telegram" && item.telegramFileKey == "tgfile:abc" && item.title == "Show.S01E01" && item.sourceAvailable == true)
        store.pruneTelegram(remoteIds: [])
        #expect(store.list().isEmpty)
    }

    @Test func collectionsAreCreatedAssignedAndDeleted() {
        let store = tempStore()
        store.add(local("a"))
        let c = store.addCollection("  Kids ")
        #expect(c.name == "Kids")
        store.setItemCollections("a", collectionIds: [c.id, c.id])
        #expect(store.get("a")?.collectionIds == [c.id])
        store.deleteCollection(c.id)
        #expect(store.get("a")?.collectionIds == [] && store.collections().isEmpty)
    }

    @Test func catalogPostersBumpTheVersionAndRespectUserArtwork() {
        let store = tempStore()
        store.add(local("a"))
        let relative = store.writeCatalogPoster("a", bytes: Data([1, 2, 3]))
        #expect(relative == "posters/a.jpg" && store.get("a")?.posterVersion == 1)
        store.updateItem("a") { var i = $0; i.artworkUserSelected = true; return i }
        #expect(store.writeCatalogPoster("a", bytes: Data([4])) == nil)
    }

    @Test func applyCatalogKeepsFieldsThePersonSet() {
        let store = tempStore()
        var item = local("a"); item.userMetadataFields = ["title"]; item.title = "Mine"
        store.add(item)
        let updated = store.applyCatalog("a", metadata: CatalogMetadata(title: "Catalog", year: 1999, tmdbId: 7), posterFile: nil)
        #expect(updated?.title == "Mine" && updated?.year == 1999 && updated?.tmdbId == 7)
    }

    @Test func saveArtworkDraftCopiesTheChosenCoverAndBumpsTheVersion() throws {
        let store = tempStore()
        store.add(local("a"))
        let frame = FileManager.default.temporaryDirectory.appendingPathComponent("frame-\(UUID().uuidString).jpg")
        try Data([9, 9]).write(to: frame)
        var draft = ArtworkDraft(base: store.get("a")!, session: 1)
        draft.cover = .frame(path: frame.path)
        let saved = try store.saveArtworkDraft(draft) { _ in true }
        #expect(saved.posterVersion == 1 && saved.artworkUserSelected && saved.artworkOrigin == artworkFrame)
        #expect(store.posterFile(saved) != nil)
        #expect(throws: MetadataValidationError.self) { try store.saveArtworkDraft(draft) { _ in false } }
    }

    @Test func removingTheLocalCopyNeedsAVerifiedCopyElsewhere() {
        let store = tempStore()
        var item = local("a"); item.ownCloudCopy = true
        store.add(item)
        try? Data([1]).write(to: store.videoFile(item))
        let freed = store.removeLocalCopy("a")
        #expect(freed?.copied == false && freed?.storageState == storageCloud && !store.hasLocalBytes(item))
        store.add(local("b"))
        #expect(store.removeLocalCopy("b")?.copied == true)
    }
}
