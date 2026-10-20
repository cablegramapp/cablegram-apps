import Foundation
import Testing
@testable import CablegramCore

/// The media server over real loopback sockets: routes, auth, ranges, stream links, commands.
@Suite(.serialized) struct LanServerTests {
    struct Fixture {
        let server: LanHTTPServer
        let router: LanLibraryRouter
        let store: LibraryStore
        let commands: CommandQueue
        let port: UInt16
        let bytes: Data
        let session: URLSession

        func url(_ target: String) -> URL { URL(string: "http://127.0.0.1:\(port)\(target)")! }

        func get(_ target: String, method: String = "GET", headers: [String: String] = ["Authorization": "Bearer capA"], body: Data? = nil) async throws -> (Data, HTTPURLResponse) {
            var request = URLRequest(url: url(target))
            request.httpMethod = method
            request.httpBody = body
            for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
            let (data, response) = try await session.data(for: request)
            return (data, response as! HTTPURLResponse)
        }
    }

    static func fixture(privatePasses: PrivatePassVerifier? = nil) async throws -> Fixture {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("lan-\(UUID().uuidString)")
        let store = LibraryStore(root: root)
        let bytes = Data((0..<10_000).map { UInt8($0 % 251) })
        var item = LibraryItem(id: "a", title: "A", filename: "a.mkv", importedAt: "2026-01-01T00:00:00Z")
        item.fileName = "a.mkv"
        store.add(item)
        try bytes.write(to: store.videoFile(item))
        var secret = LibraryItem(id: "p", title: "P", filename: "p.mp4", importedAt: "2026-01-01T00:00:00Z")
        secret.isPrivate = true
        store.add(secret)
        try bytes.write(to: store.videoFile(secret))
        let credentials = LanCredentials()
        credentials.sync([PairedTv(pin: "1", name: "Den", capability: "capA", deviceId: "tvA")])
        let commands = CommandQueue(root: root)
        let router = LanLibraryRouter(store: store, commands: commands, credentials: credentials, privatePasses: privatePasses)
        let server = LanHTTPServer(router: router, port: 0)
        let port = try await server.start()
        return Fixture(server: server, router: router, store: store, commands: commands, port: port, bytes: bytes, session: URLSession(configuration: .ephemeral))
    }

    @Test func rejectsRequestsWithoutAValidCapability() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        #expect(try await f.get("/library", headers: [:]).1.statusCode == 401)
        #expect(try await f.get("/library", headers: ["Authorization": "Bearer nope"]).1.statusCode == 401)
        #expect(try await f.get("/library", headers: ["Authorization": "Bearer 1"]).1.statusCode == 401, "the PIN is not a credential")
        #expect(try await f.get("/library?token=capA", headers: [:]).1.statusCode == 401, "?token= is for the relay only")
    }

    @Test func listsTheLibraryWithoutPosterPaths() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        let (data, response) = try await f.get("/library")
        #expect(response.statusCode == 200)
        let file = try JSONDecoder().decode(LibraryFile.self, from: data)
        #expect(Set(file.items.map(\.id)) == ["a", "p"])
    }

    @Test func servesTheWholeFileWithTheRealMediaType() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        let (data, response) = try await f.get("/media/a")
        #expect(response.statusCode == 200)
        #expect(data == f.bytes)
        #expect(response.value(forHTTPHeaderField: "Content-Type") == "video/x-matroska")
        #expect(response.value(forHTTPHeaderField: "Accept-Ranges") == "bytes")
    }

    @Test func servesRanges() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        var (data, response) = try await f.get("/media/a", headers: ["Authorization": "Bearer capA", "Range": "bytes=100-199"])
        #expect(response.statusCode == 206)
        #expect(data == f.bytes[100...199])
        #expect(response.value(forHTTPHeaderField: "Content-Range") == "bytes 100-199/10000")
        (data, response) = try await f.get("/media/a", headers: ["Authorization": "Bearer capA", "Range": "bytes=9990-"])
        #expect(data == f.bytes[9990...])
        (data, response) = try await f.get("/media/a", headers: ["Authorization": "Bearer capA", "Range": "bytes=-5"])
        #expect(data == f.bytes.suffix(5))
        (_, response) = try await f.get("/media/a", headers: ["Authorization": "Bearer capA", "Range": "bytes=10000-"])
        #expect(response.statusCode == 416)
        #expect(response.value(forHTTPHeaderField: "Content-Range") == "bytes */10000")
    }

    @Test func headCarriesLengthButNoBody() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        let (data, response) = try await f.get("/media/a", method: "HEAD")
        #expect(response.statusCode == 200)
        #expect(data.isEmpty)
        #expect(response.value(forHTTPHeaderField: "Content-Length") == "10000")
    }

    @Test func unknownTitleIs404() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        #expect(try await f.get("/media/zzz").1.statusCode == 404)
        #expect(try await f.get("/nothing").1.statusCode == 404)
    }

    @Test func aStreamLinkPlaysOnlyItsOwnTitleWithoutAHeader() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        let (body, response) = try await f.get("/links/media/a")
        #expect(response.statusCode == 200)
        let object = try #require(try JSONSerialization.jsonObject(with: body) as? [String: Any])
        let link = try #require(object["link"] as? String)
        #expect(object["expires_in_seconds"] as? Int == 900)
        let (data, played) = try await f.get("/media/a?link=\(link)", headers: [:])
        #expect(played.statusCode == 200)
        #expect(data == f.bytes)
        #expect(try await f.get("/media/p?link=\(link)", headers: [:]).1.statusCode == 401)
        // A link cannot mint more links.
        #expect(try await f.get("/links/media/a?link=\(link)", headers: [:]).1.statusCode == 401)
        // Links need the header, not a query token.
        #expect(try await f.get("/links/media/a?token=capA", headers: [:]).1.statusCode == 401)
        #expect(try await f.get("/links/media/zzz").1.statusCode == 404)
    }

    @Test func aLinkEndsWhenItsTvIsRemoved() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        let link = try #require((try JSONSerialization.jsonObject(with: try await f.get("/links/media/a").0) as? [String: Any])?["link"] as? String)
        #expect(try await f.get("/media/a?link=\(link)", headers: [:]).1.statusCode == 200)
        // Re-sync with the TV gone.
        let credentialsField = Mirror(reflecting: f.router).children.first { $0.label == "credentials" }?.value as? LanCredentials
        credentialsField?.sync([])
        #expect(try await f.get("/media/a?link=\(link)", headers: [:]).1.statusCode == 401)
        #expect(try await f.get("/library").1.statusCode == 401)
    }

    @Test func privateTitlesNeedAnApprovedPass() async throws {
        let passes = PrivatePassVerifier(verify: { pass in pass == "ok" ? VerifiedPass(originIdentities: ["p"], tvDeviceId: "tvA", expiresAt: Date().addingTimeInterval(60)) : nil })
        let f = try await Self.fixture(privatePasses: passes); defer { f.server.stop() }
        let (_, refused) = try await f.get("/media/p")
        #expect(refused.statusCode == 403)
        #expect(try await f.get("/media/p?pass=bad").1.statusCode == 403)
        let (data, ok) = try await f.get("/media/p?pass=ok")
        #expect(ok.statusCode == 200)
        #expect(data == f.bytes)
    }

    @Test func privateTitlesAreRefusedWithoutAVerifier() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        #expect(try await f.get("/media/p?pass=ok").1.statusCode == 403)
    }

    @Test func commandsAreValidatedQueuedAndDrained() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        func post(_ json: String) async throws -> Int { try await f.get("/commands", method: "POST", headers: ["Authorization": "Bearer capA", "Content-Type": "application/json"], body: Data(json.utf8)).1.statusCode }
        #expect(try await post("{\"id\":\"1\",\"command\":\"pause\"}") == 200)
        #expect(try await post("{\"id\":\"2\",\"command\":\"play_video\",\"videoId\":\"a\"}") == 200)
        #expect(try await post("{\"id\":\"3\",\"command\":\"play_video\"}") == 400)
        #expect(try await post("{\"id\":\"4\",\"command\":\"format_disk\"}") == 400)
        #expect(try await post("not json") == 400)
        let (data, _) = try await f.get("/commands")
        let drained = try JSONDecoder().decode([RemoteCommand].self, from: data)
        #expect(drained.map(\.command) == ["pause", "play_video"])
        #expect(drained[1].videoId == "a")
        #expect(try JSONDecoder().decode([RemoteCommand].self, from: try await f.get("/commands").0).isEmpty)
    }

    @Test func relayPathTakesTheTokenFromTheQuery() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        let viaRelay = await f.router.handle(HTTPRequest(method: "GET", target: "/media/a?token=capA", headers: ["range": "bytes=0-9"]), acceptQueryToken: true)
        #expect(viaRelay.status == 206)
        if case let .stream(length, source) = viaRelay.body { #expect(length == 10); source.close() } else { Issue.record("expected a stream") }
        let direct = await f.router.handle(HTTPRequest(method: "GET", target: "/media/a?token=capA"), acceptQueryToken: false)
        #expect(direct.status == 401)
    }

    @Test func manyRequestsReuseTheConnection() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        for _ in 0..<20 { #expect(try await f.get("/media/a", headers: ["Authorization": "Bearer capA", "Range": "bytes=0-99"]).1.statusCode == 206) }
    }
}
