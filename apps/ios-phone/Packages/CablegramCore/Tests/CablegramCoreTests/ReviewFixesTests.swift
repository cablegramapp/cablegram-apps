import Foundation
import Testing
@testable import CablegramCore

private func tempStore() -> LibraryStore {
    LibraryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("rv-\(UUID().uuidString)"))
}

private func item(_ id: String, file: String? = nil) -> LibraryItem {
    var i = LibraryItem(id: id, title: id, filename: "\(id).mp4", importedAt: "2026-01-01T00:00:00Z")
    i.fileName = file ?? "\(id).mp4"
    return i
}

/// Regressions for the findings of the Phase 0-6 review.
@Suite struct IndexSafetyTests {
    @Test func anUnreadableIndexIsNeverOverwrittenOrSweptIntoAnEmptyLibrary() throws {
        let store = tempStore()
        let a = item("a")
        store.add(a)
        try Data("video".utf8).write(to: store.videoFile(a))
        // Make the video look old, then damage the index.
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSinceNow: -3600)], ofItemAtPath: store.videoFile(a).path)
        let index = store.root.appendingPathComponent("index.json")
        try Data("{ not json".utf8).write(to: index)

        #expect(store.list().isEmpty)
        #expect(!store.indexTrusted)
        #expect(store.sweepVideos() == 0, "an index that could not be read says nothing about which videos are in use")
        #expect(FileManager.default.fileExists(atPath: store.videoFile(a).path))
        store.add(item("b"))   // a write while the index is unreadable must not replace it
        #expect(try Data(contentsOf: index) == Data("{ not json".utf8))
        let asides = try FileManager.default.contentsOfDirectory(atPath: store.root.path).filter { $0.hasPrefix("index.unreadable-") }
        #expect(asides.count == 1, "the damaged file is kept for support")
    }

    @Test func aMissingIndexIsANewLibrary() {
        let store = tempStore()
        #expect(store.list().isEmpty)
        #expect(store.indexTrusted)
        store.add(item("a"))
        #expect(store.list().map(\.id) == ["a"])
    }

    @Test func aSetAsideLibraryIsKeptWhenItCannotBeWritten() throws {
        let store = tempStore()
        _ = store.bindHousehold("h1")
        store.add(item("a"))
        // A directory where the set-aside file must go makes the write fail.
        try FileManager.default.createDirectory(at: store.root.appendingPathComponent("index-h1.json"), withIntermediateDirectories: false)
        #expect(!store.bindHousehold("h2"))
        #expect(store.list().map(\.id) == ["a"], "h1's library must survive a failed hand-over")
    }
}

@Suite struct FileNameTests {
    @Test func theExtensionIsWhatFollowsTheLastDot() {
        #expect(fileExtension(of: "film.mkv", fallback: "mp4") == "mkv")
        #expect(fileExtension(of: "a.b.c") == "c")
        #expect(fileExtension(of: "noext", fallback: "mp4") == "mp4")
        #expect(fileExtension(of: "noext") == "")
    }

    @Test func namesMadeOnlyOfDotsDoNotCrash() {
        for name in [".", "..", "...", "a.", ".hidden"] { _ = fileExtension(of: name, fallback: "mp4"); _ = looksLikeVideo(name) }
        #expect(fileExtension(of: "...", fallback: "mp4") == "")
        let store = tempStore()
        let restored = store.restoreFromHousehold(RemoteCatalogItem(id: "x1", title: "..."), source: RemoteCatalogSource(kind: "phone_local", originIdentity: "x1", originFilename: "..."))
        #expect(restored.id == "x1")
    }

    @Test func aStoredNameCanNeverLeaveItsFolder() {
        let store = tempStore()
        let evil = item("e", file: "../../escape.mp4")
        #expect(store.videoFile(evil).deletingLastPathComponent() == store.videosDir)
        #expect(store.cloudFile(item("e", file: "..")).deletingLastPathComponent() == store.cloudDir)
        #expect(LibraryStore.isSafeId("a1-B_2"))
        #expect(!LibraryStore.isSafeId("../x"))
        #expect(!LibraryStore.isSafeId("a/b"))
        #expect(!LibraryStore.isSafeId(""))
    }
}

@Suite struct WeakNameTests {
    @Test func onlyWholeNamesOfTheForm24_7AreWeak() {
        #expect(isWeakCatalogLabel("24_7.mkv"))
        #expect(!isWeakCatalogLabel("24_7 Wall Street.mkv"))
    }
}

@Suite struct QueryEncodingTests {
    @Test func queriesEscapeWhatJavaEscapes() {
        #expect("Tom & Jerry".addingPercentEncoding(withAllowedCharacters: .queryValueAllowed) == "Tom%20%26%20Jerry")
        #expect("C++".addingPercentEncoding(withAllowedCharacters: .queryValueAllowed) == "C%2B%2B")
        #expect("a=b".addingPercentEncoding(withAllowedCharacters: .queryValueAllowed) == "a%3Db")
    }
}

private func client(_ tokens: TestTokens? = nil) -> CatalogClient { CatalogClient(baseURL: MockHTTP.baseURL, tokens: tokens, session: MockHTTP.session) }

extension Network {
@Suite struct ReviewNetworkTests {
    init() { MockHTTP.reset() }

    @Test func concurrentUnauthorizedAnswersRefreshOnlyOnce() async {
        let tokens = TestTokens(access: "expired", refresh: "refresh-1")
        MockHTTP.handler = { request in
            switch request.path {
            case "/api/auth/refresh":
                // The server rotates: a second refresh with the old token is refused.
                let first = request.body["refresh_token"] as? String == "refresh-1"
                return first ? (.response(200, #"{"access_token":"fresh","refresh_token":"refresh-2"}"#), 0.25) : (.response(401, "{}"), 0)
            default:
                return request.authorization == "Bearer fresh" ? (.response(200, "{}"), 0) : (.response(401, "{}"), 0)
            }
        }
        let c = client(tokens)
        async let a = c.send("GET", "/api/me", token: "expired")
        async let b = c.send("GET", "/api/library/x", token: "expired")
        async let d = c.send("GET", "/api/devices", token: "expired")
        let replies = await [a, b, d]
        #expect(replies.allSatisfy { $0.status == 200 })
        #expect(MockHTTP.requests.filter { $0.path == "/api/auth/refresh" }.count == 1)
        #expect(tokens.accountToken == "fresh" && tokens.refreshToken == "refresh-2", "the rotated token must not be wiped by a second renewal")
    }

    @Test func oneBadSuggestionDoesNotEmptyTheList() async {
        MockHTTP.enqueue(200, #"{"suggestions":[{"title":"Dune","year":2021},{"year":1},{"title":"Arrival","mediaType":"movie","source":"catalog"}]}"#)
        let titles = await client().suggestTitles(query: "du", token: "t").map(\.title)
        #expect(titles == ["Dune", "Arrival"])
    }

    @Test func approvalCountsOnlyWhenTheServerHandsBackAToken() async {
        MockHTTP.enqueue(200, #"{"token":"abc"}"#)
        #expect(await client().approvePrivatePlayback(token: "t", attemptId: "a1"))
        MockHTTP.enqueue(200, "{}")
        #expect(!(await client().approvePrivatePlayback(token: "t", attemptId: "a2")))
    }
}
}
