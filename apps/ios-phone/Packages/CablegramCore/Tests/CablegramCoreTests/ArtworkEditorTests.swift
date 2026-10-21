import Foundation
import Testing
@testable import CablegramCore

private struct FakeMedia: ArtworkMedia {
    var frames: [String] = ["/tmp/f0.jpg", "/tmp/f1.jpg"]
    func extractPreviewFrames(_ item: LibraryItem, count: Int, session: Int64) async -> [String] { frames }
    func stageArtwork(session: Int64, revision: Int64, bytes: Data) throws -> String {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("staged-\(UUID().uuidString).jpg")
        try bytes.write(to: url)
        return url.path
    }
    func discardArtworkPreviews(session: Int64) {}
    func isValidImage(_ url: URL) -> Bool { true }
}

@MainActor private func wait(_ condition: @MainActor () -> Bool) async {
    for _ in 0..<200 {
        if condition() { return }
        try? await Task.sleep(for: .milliseconds(10))
    }
}

extension Network {
    @Suite @MainActor struct ArtworkEditorTests {
        init() { MockHTTP.reset() }

        private func makeController(saved: @escaping @MainActor (LibraryItem) -> Void = { _ in }) -> (ArtworkEditorController, LibraryStore) {
            let store = LibraryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("art-\(UUID().uuidString)"))
            store.add(LibraryItem(id: "a", title: "dune 2021", filename: "Dune.S01E02.mkv", importedAt: "x", catalogItemId: "c1"))
            let controller = ArtworkEditorController(
                store: store, media: FakeMedia(),
                catalog: { CatalogClient(baseURL: MockHTTP.baseURL, tokens: nil, session: MockHTTP.session) },
                token: { "t" }, onSaved: saved)
            return (controller, store)
        }

        @Test func openingLoadsPreviewFramesAndCancelDiscardsNothing() async {
            let (c, store) = makeController()
            c.open(store.get("a")!)
            await wait { c.draft?.loadingFrames == false }
            #expect(c.draft?.frames == ["/tmp/f0.jpg", "/tmp/f1.jpg"] && c.draft?.changed == false)
            c.requestClose()
            #expect(c.draft == nil)
        }

        @Test func closingWithChangesAsksBeforeDiscarding() async {
            let (c, store) = makeController()
            c.open(store.get("a")!)
            c.update { $0.title = "Dune" }
            c.requestClose()
            #expect(c.draft?.discardRequested == true)
        }

        @Test func aSearchShowsTheCandidateWithItsStagedCoverThenUseDetailsFillsTheDraft() async throws {
            MockHTTP.route("/api/library/resolve-title", 200, #"{"found":true,"metadata":{"title":"Dune","mediaType":"tv","year":2021}}"#)
            MockHTTP.route("/api/library/enrich", 200, #"{"metadata":{"title":"Dune","mediaType":"tv","year":2021,"matchStatus":"matched","tmdbId":7}}"#)
            let (c, store) = makeController()
            c.open(store.get("a")!)
            c.search()
            await wait { c.draft?.searching == false && c.draft?.candidate != nil }
            #expect(c.draft?.candidate?.tmdbId == 7)
            #expect(MockHTTP.requests.first { $0.path == "/api/library/resolve-title" }?.body["item_id"] as? String == "c1")
            c.useDetails()
            #expect(c.draft?.title == "Dune" && c.draft?.year == "2021" && c.draft?.useCatalogDetails == true)
            #expect(c.draft?.season == "1" && c.draft?.episode == "2", "season and episode come from the file name")
        }

        @Test func aMissingMatchSaysSoAndOfflineIsReported() async {
            MockHTTP.route("/api/library/resolve-title", 200, #"{"found":false}"#)
            let (c, store) = makeController()
            c.open(store.get("a")!)
            c.search()
            await wait { c.draft?.searching == false }
            #expect(c.draft?.message?.hasPrefix("No matching movie or series found") == true)
            MockHTTP.reset()
            MockHTTP.enqueueFailure()
            c.search()
            await wait { c.draft?.searching == false && c.draft?.message?.hasPrefix("Couldn't connect") == true }
            #expect(c.draft?.message?.hasPrefix("Couldn't connect") == true)
        }

        @Test func savingCommitsTheDraftAndClosesTheEditor() async {
            var savedTitle: String?
            let (c, store) = makeController { savedTitle = $0.title }
            c.open(store.get("a")!)
            c.update { $0.title = "Dune" }
            c.save()
            await wait { c.draft == nil }
            #expect(savedTitle == "Dune" && store.get("a")?.title == "Dune" && store.get("a")?.pendingMetadataFields == ["title"])
        }
    }
}
