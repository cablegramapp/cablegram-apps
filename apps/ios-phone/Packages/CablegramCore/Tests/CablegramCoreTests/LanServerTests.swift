import Darwin
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

    static func fixture(privatePasses: PrivatePassVerifier? = nil, headDeadline: TimeInterval = 10) async throws -> Fixture {
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
        let server = LanHTTPServer(router: router, port: 0, headDeadline: headDeadline)
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

    /// Sends raw bytes and reads whatever comes back until the server closes (or `seconds` pass).
    static func raw(_ port: UInt16, _ request: String, seconds: Int = 3) -> String {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        defer { close(fd) }
        var tv = timeval(tv_sec: seconds, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let connected = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        guard connected == 0 else { return "connect failed" }
        _ = request.withCString { send(fd, $0, strlen($0), 0) }
        var out = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while true {
            let n = recv(fd, &buffer, buffer.count, 0)
            if n <= 0 { break }
            out.append(buffer, count: n)
        }
        return String(decoding: out, as: UTF8.self)
    }

    @Test func malformedContentLengthIsRefusedWithoutCrashing() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        for value in ["-1", "abc", "99999999999999999999", "9000"] {
            let reply = Self.raw(f.port, "POST /commands HTTP/1.1\r\nHost: x\r\nContent-Length: \(value)\r\n\r\n")
            #expect(reply.hasPrefix("HTTP/1.1 400"), "Content-Length \(value): \(reply.prefix(40))")
        }
        let chunked = Self.raw(f.port, "POST /commands HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
        #expect(chunked.hasPrefix("HTTP/1.1 400"))
        let getWithBody = Self.raw(f.port, "GET /library HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello")
        #expect(getWithBody.hasPrefix("HTTP/1.1 400"), "only POST carries a body")
        // The server is still up.
        #expect(try await f.get("/library").1.statusCode == 200)
    }

    static func openSocket(_ port: UInt16) -> Int32 {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        _ = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        return fd
    }

    /// True when the server has closed `fd` (a read returns 0 or fails) within `seconds`.
    static func closedByServer(_ fd: Int32, within seconds: Int) -> Bool {
        var tv = timeval(tv_sec: seconds, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var byte: UInt8 = 0
        let n = recv(fd, &byte, 1, 0)
        // A close with unread data arrives as a reset, which is still the server hanging up (not a timeout).
        return n == 0 || (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK)
    }

    @Test func aRequestHeadThatTricklesInIsCutOffAtTheDeadline() async throws {
        let f = try await Self.fixture(headDeadline: 1); defer { f.server.stop() }
        let fd = Self.openSocket(f.port); defer { close(fd) }
        let start = Date()
        // One byte every 0.3 s would hold the connection for minutes if each byte restarted the timeout.
        for byte in "GET /library HTTP/1.1\r\nHost: x\r\n".utf8 where Date().timeIntervalSince(start) < 1.6 {
            var b = byte
            if send(fd, &b, 1, 0) <= 0 { break }
            usleep(300_000)
        }
        #expect(Self.closedByServer(fd, within: 3))
        #expect(Date().timeIntervalSince(start) < 4)
        #expect(try await f.get("/library").1.statusCode == 200)
    }

    @Test func oneHostCannotTakeAllTheConnections() async throws {
        let f = try await Self.fixture(); defer { f.server.stop() }
        let held = (0..<8).map { _ in Self.openSocket(f.port) }
        try await Task.sleep(nanoseconds: 300_000_000)
        let ninth = Self.openSocket(f.port)
        #expect(Self.closedByServer(ninth, within: 3), "the ninth connection from one address is refused")
        close(ninth)
        held.forEach { close($0) }
        try await Task.sleep(nanoseconds: 400_000_000)
        #expect(try await f.get("/library").1.statusCode == 200, "closing the others frees the slots")
    }
}

/// `/telegram/<id>`: titles in the household's Telegram channel, streamed through this phone's session.
@Suite(.serialized) struct TelegramRouteTests {
    final class FakeTelegramMedia: TelegramMedia, @unchecked Sendable {
        let bytes = Data((0..<5000).map { UInt8($0 % 249) })
        private let lock = NSLock()
        private(set) var released: [Int] = []
        func resolve(uniqueId: String) async -> TelegramFileRef? { uniqueId == "abcdefgh1234" ? TelegramFileRef(fileId: 9, size: Int64(bytes.count), mime: "video/x-matroska") : nil }
        func read(fileId: Int, position: Int64, want: Int) async -> Data? { bytes.subdata(in: Int(position)..<min(Int(position) + want, bytes.count)) }
        func release(fileId: Int) { lock.withLock { released.append(fileId) } }
    }

    func router(telegram: TelegramMedia?) -> LanLibraryRouter {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("tgr-\(UUID().uuidString)")
        let credentials = LanCredentials()
        credentials.sync([PairedTv(pin: "1", name: "Den", capability: "capA", deviceId: "tvA")])
        return LanLibraryRouter(store: LibraryStore(root: root), commands: CommandQueue(root: root), credentials: credentials, telegram: telegram)
    }

    func body(_ response: HTTPResponse) async -> Data {
        guard case let .stream(_, source) = response.body else { if case let .data(d) = response.body { return d }; return Data() }
        var all = Data()
        while let piece = try? await source.next(limit: 700) { all.append(piece) }
        source.close()
        return all
    }

    @Test func serveARangeOfATelegramFile() async {
        let media = FakeTelegramMedia()
        let r = router(telegram: media)
        let response = await r.handle(HTTPRequest(method: "GET", target: "/telegram/abcdefgh1234", headers: ["authorization": "Bearer capA", "range": "bytes=100-1999"]))
        #expect(response.status == 206)
        #expect(response.header("Content-Range") == "bytes 100-1999/5000" && response.header("Content-Type") == "video/x-matroska")
        #expect(await body(response) == media.bytes.subdata(in: 100..<2000))
        #expect(media.released == [9], "the download window is dropped when the stream ends")
    }

    @Test func noRangeIsTheWholeFileAndAMalformedRangeToo() async {
        let media = FakeTelegramMedia()
        let r = router(telegram: media)
        for range in [nil, "bytes=oops", "items=1-2"] as [String?] {
            var headers = ["authorization": "Bearer capA"]; headers["range"] = range
            let response = await r.handle(HTTPRequest(method: "GET", target: "/telegram/abcdefgh1234", headers: headers))
            #expect(response.status == 200 && response.header("Content-Range") == nil, "\(String(describing: range))")
            #expect(await body(response) == media.bytes)
        }
    }

    @Test func aRangePastTheEndIs416AndHeadHasNoBody() async {
        let r = router(telegram: FakeTelegramMedia())
        let past = await r.handle(HTTPRequest(method: "GET", target: "/telegram/abcdefgh1234", headers: ["authorization": "Bearer capA", "range": "bytes=5000-"]))
        #expect(past.status == 416 && past.header("Content-Range") == "bytes */5000")
        let head = await r.handle(HTTPRequest(method: "HEAD", target: "/telegram/abcdefgh1234", headers: ["authorization": "Bearer capA"]))
        #expect(head.status == 200 && head.header("Content-Length") == "5000")
        if case .empty = head.body {} else { Issue.record("HEAD must carry no body") }
    }

    @Test func unknownIdsMalformedIdsAndNoTelegramAre404() async {
        let r = router(telegram: FakeTelegramMedia())
        for id in ["unknownid123", "short", "has%20space%20in%20it", "../etc/passwd"] {
            let status = await r.handle(HTTPRequest(method: "GET", target: "/telegram/\(id)", headers: ["authorization": "Bearer capA"])).status
            #expect(status == 404, Comment(rawValue: id))
        }
        let none = router(telegram: nil)
        #expect(await none.handle(HTTPRequest(method: "GET", target: "/telegram/abcdefgh1234", headers: ["authorization": "Bearer capA"])).status == 404)
    }

    @Test func aTelegramTitleNeedsACapabilityOrALink() async throws {
        let r = router(telegram: FakeTelegramMedia())
        #expect(await r.handle(HTTPRequest(method: "GET", target: "/telegram/abcdefgh1234")).status == 401)
        let issued = await r.handle(HTTPRequest(method: "GET", target: "/links/telegram/abcdefgh1234", headers: ["authorization": "Bearer capA"]))
        #expect(issued.status == 200)
        guard case let .data(data) = issued.body, let link = (try JSONSerialization.jsonObject(with: data) as? [String: Any])?["link"] as? String else { Issue.record("no link"); return }
        let played = await r.handle(HTTPRequest(method: "GET", target: "/telegram/abcdefgh1234?link=\(link)"))
        #expect(played.status == 200)
        _ = await body(played)
        #expect(await r.handle(HTTPRequest(method: "GET", target: "/links/telegram/nope", headers: ["authorization": "Bearer capA"])).status == 404)
    }
}
