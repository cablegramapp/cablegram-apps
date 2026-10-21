import Foundation
import Testing
@testable import CablegramCore

private func client() -> CatalogClient { CatalogClient(baseURL: MockHTTP.baseURL, tokens: nil, session: MockHTTP.session) }
private func tempStore() -> LibraryStore { LibraryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("sync-\(UUID().uuidString)")) }

private struct FakeSources: SourceInspector {
    var present: Set<String> = []
    func hasSource(_ item: LibraryItem) -> Bool { present.contains(item.id) }
    func fingerprint(_ item: LibraryItem) -> String? { sourceFingerprintOf(name: item.filename, size: item.fileSizeBytes ?? 0, mtime: 0) }
    func selectedVideosByFingerprint() -> [String: DeviceVideo] { [:] }
}

extension Network {
@Suite struct LibrarySyncTests {
    init() { MockHTTP.reset() }

    @Test func fingerprintMatchesTheAndroidFormula() {
        #expect(sourceFingerprintOf(name: "a", size: 100, mtime: 0) == "sha:6100000064")
        #expect(sourceFingerprintOf(name: " A ", size: 100, mtime: 0) == "sha:6100000064", "trimmed and lower-cased")
    }

    @Test func aSyncPushesPhoneTitlesAndRestoresHouseholdOnesWithoutAFile() async throws {
        let store = tempStore()
        var mine = LibraryItem(id: "m1", title: "Dune", filename: "Dune.mkv", fileSizeBytes: 100, importedAt: "2026-01-01T00:00:00Z", sourceUri: "ph://1")
        mine.durationSeconds = 600
        store.add(mine)
        MockHTTP.route("/api/catalog/sources/reconcile", 200)
        MockHTTP.route("/api/catalog/items", 200, #"""
        {"id":"c1","items":[{"id":"c9","title":"Remote Film","media_type":"movie","sources":[{"kind":"phone_local","origin_identity":"x9","origin_filename":"Remote.mkv"}]}]}
        """#)
        let sync = LibrarySync(store: store, client: client(), token: "t", phoneDeviceId: "dev", sources: FakeSources(present: ["m1"]))
        try await sync.run()
        let paths = MockHTTP.requests.map(\.path)
        #expect(paths.contains("/api/catalog/sources/reconcile"))
        let push = MockHTTP.requests.first { $0.method == "POST" && $0.path == "/api/catalog/items" }
        #expect(push?.body["origin_identity"] as? String == "m1")
        #expect(push?.body["serving_device_id"] as? String == "dev")
        #expect(push?.body["stable_source_key"] as? String == "ph://1")
        #expect(store.get("m1")?.catalogItemId == "c1" && store.get("m1")?.sourceAvailable == true)
        #expect(store.get("c9")?.householdOnly == true, "a household title without a file is restored as 'not on this phone'")
    }

    @Test func aFailedReconcileStopsTheSyncAndKeepsTheCatalog() async {
        let store = tempStore()
        store.add(LibraryItem(id: "m1", title: "Dune", filename: "Dune.mkv", importedAt: "2026-01-01T00:00:00Z", sourceUri: "ph://1"))
        MockHTTP.route("/api/catalog/sources/reconcile", 500)
        let sync = LibrarySync(store: store, client: client(), token: "t", phoneDeviceId: nil, sources: FakeSources())
        await #expect(throws: LibrarySyncError.self) { try await sync.run() }
        #expect(store.get("m1")?.sourceAvailable == false, "an unreachable source is marked unavailable, never deleted")
        #expect(store.list().count == 1)
    }

    @Test func patchMetadataHandlesSavedConflictAndFailure() async throws {
        let item = try commitManualMetadata(
            LibraryItem(id: "a", title: "Old", filename: "a.mp4", importedAt: "x", catalogItemId: "c1", metadataRevision: 1),
            title: "New", year: nil, mediaType: "movie", overview: nil)
        MockHTTP.enqueue(200, #"{"id":"c1","metadata_revision":2,"user_metadata_fields":["title"],"title":"New"}"#)
        if case .saved(let saved) = await client().patchMetadata(token: "t", item: item) { #expect(saved.metadataRevision == 2) } else { Issue.record("expected saved") }
        let sent = MockHTTP.requests[0].body
        #expect(sent["expected_revision"] as? Int == 1 && sent["title"] as? String == "New" && sent["edit_id"] as? String == item.metadataEditId)
        MockHTTP.enqueue(409, #"{"current":{"id":"c1","metadata_revision":5,"title":"Theirs"}}"#)
        if case .conflict(let current) = await client().patchMetadata(token: "t", item: item) { #expect(current.metadataRevision == 5) } else { Issue.record("expected conflict") }
        MockHTTP.enqueue(500)
        if case .failed = await client().patchMetadata(token: "t", item: item) {} else { Issue.record("expected failed") }
    }

    @Test func householdArtworkIsUploadedOnlyWhenTheCoverWasSaved() async throws {
        let store = tempStore()
        var item = LibraryItem(id: "a", title: "A", filename: "a.mp4", posterPath: "posters/a.jpg", posterVersion: 1, importedAt: "x",
                               catalogItemId: "c1", artworkOrigin: artworkFrame)
        item.householdArtworkVersion = 1
        store.add(item)
        try Data([1, 2]).write(to: store.postersDir.appendingPathComponent("a.jpg"))
        MockHTTP.enqueue(200)
        let sync = LibrarySync(store: store, client: client(), token: "t", phoneDeviceId: nil)
        #expect(await sync.syncHouseholdArtwork("a"))
        #expect(MockHTTP.requests[0].path == "/api/catalog/items/c1/poster" && MockHTTP.requests[0].body["poster_data"] as? String == "AQI=")
        #expect(store.get("a")?.householdArtworkUploadedVersion == 1)
    }
}
}
