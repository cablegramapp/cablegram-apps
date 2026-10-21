import Foundation
import Network
import Testing
@testable import CablegramCore

/// A stand-in relay: a local WebSocket server that sends `req` frames and records what the phone answers.
private final class FakeRelay: @unchecked Sendable {
    let listener: NWListener
    private let lock = NSLock()
    private var connection: NWConnection?
    private(set) var texts: [[String: Any]] = []
    private(set) var binary: [Int: Data] = [:]
    private(set) var headers: [String: String] = [:]
    private var handshake: (@Sendable () -> Void)?

    init() throws {
        let ws = NWProtocolWebSocket.Options()
        ws.setClientRequestHandler(DispatchQueue.global()) { _, _ in .init(status: .accept, subprotocol: nil) }
        let parameters = NWParameters.tcp
        parameters.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)
        listener = try NWListener(using: parameters, on: .any)
    }

    func start() async throws -> UInt16 {
        listener.newConnectionHandler = { [self] connection in
            lock.withLock { self.connection = connection }
            connection.start(queue: .global())
            receive(connection)
        }
        return try await withCheckedThrowingContinuation { continuation in
            let once = NSLock(); nonisolated(unsafe) var done = false
            listener.stateUpdateHandler = { [listener] state in
                once.lock(); defer { once.unlock() }
                if case .ready = state, !done { done = true; continuation.resume(returning: listener.port!.rawValue) }
                if case let .failed(error) = state, !done { done = true; continuation.resume(throwing: error) }
            }
            listener.start(queue: .global())
        }
    }

    private func receive(_ connection: NWConnection) {
        connection.receiveMessage { [self] data, context, _, error in
            guard error == nil else { return }
            if let data, let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata {
                lock.withLock {
                    switch metadata.opcode {
                    case .text: if let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] { texts.append(object) }
                    case .binary where data.count >= 4:
                        let sid = Int(data.prefix(4).reduce(0) { $0 << 8 | Int($1) })
                        binary[sid, default: Data()].append(data.dropFirst(4))
                    default: break
                    }
                }
            }
            receive(connection)
        }
    }

    func send(_ object: [String: Any]) {
        guard let connection = lock.withLock({ self.connection }), let data = try? JSONSerialization.data(withJSONObject: object) else { return }
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "text", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { _ in })
    }

    func messages(_ type: String, sid: Int? = nil) -> [[String: Any]] {
        lock.withLock { texts.filter { ($0["t"] as? String) == type && (sid == nil || ($0["sid"] as? Int) == sid) } }
    }

    func body(_ sid: Int) -> Data { lock.withLock { binary[sid] ?? Data() } }

    func wait(_ condition: @escaping () -> Bool, seconds: Double = 8) async -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline { if condition() { return true }; try? await Task.sleep(nanoseconds: 25_000_000) }
        return condition()
    }
}

@Suite(.serialized) struct RelayTunnelTests {
    func makeRouter(lifetime: LanLibraryLifetime? = nil) throws -> (LanLibraryRouter, Data) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("relay-\(UUID().uuidString)")
        let store = LibraryStore(root: root)
        let bytes = Data((0..<300_000).map { UInt8($0 % 241) })
        var item = LibraryItem(id: "a", title: "A", filename: "a.mp4", importedAt: "2026-01-01T00:00:00Z")
        item.fileName = "a.mp4"
        store.add(item)
        try bytes.write(to: store.videoFile(item))
        let credentials = LanCredentials()
        credentials.sync([PairedTv(pin: "1", name: "Den", capability: "capA", deviceId: "tvA")])
        return (LanLibraryRouter(store: store, commands: CommandQueue(root: root), credentials: credentials,
                                 onStreamStarted: { lifetime?.streamStarted() }, onStreamFinished: { lifetime?.streamFinished() }), bytes)
    }

    func tunnel(port: UInt16, router: LanLibraryRouter, admit: @escaping @Sendable (String) -> RelayRefusal? = { _ in nil }) -> RelayTunnel {
        RelayTunnel(relayURL: { URL(string: "ws://127.0.0.1:\(port)/relay/v1/phone") }, accountToken: { "jwt" }, phoneDeviceId: { "phone1" },
                    networkType: { "wifi" }, admit: admit, router: router)
    }

    @Test func phoneUrlFollowsTheApiBase() {
        #expect(RelayTunnel.phoneURL(apiBaseURL: "https://api.example/")?.absoluteString == "wss://api.example/relay/v1/phone")
        #expect(RelayTunnel.phoneURL(apiBaseURL: "http://10.0.0.1:3000")?.absoluteString == "ws://10.0.0.1:3000/relay/v1/phone")
        #expect(RelayTunnel.phoneURL(apiBaseURL: "ftp://x") == nil)
    }

    @Test func announcesItselfAndAnswersARangeRequestWithinCredit() async throws {
        let (router, bytes) = try makeRouter()
        let relay = try FakeRelay()
        let port = try await relay.start()
        let phone = tunnel(port: port, router: router)
        phone.start(); defer { phone.stop() }
        #expect(await relay.wait { !relay.messages("hello").isEmpty })
        #expect(relay.messages("hello").first?["network"] as? String == "wifi")

        relay.send(["t": "req", "sid": 7, "method": "GET", "path": "/media/a?token=capA", "headers": ["range": "bytes=1000-150999"], "credit": 1_048_576])
        #expect(await relay.wait { !relay.messages("end", sid: 7).isEmpty })
        let head = try #require(relay.messages("head", sid: 7).first)
        #expect(head["status"] as? Int == 206)
        let headers = try #require(head["headers"] as? [String: String])
        #expect(headers["content-range"] == "bytes 1000-150999/300000")
        #expect(headers["content-length"] == "150000")
        #expect(relay.body(7) == bytes[1000...150999])
    }

    @Test func sendsBodyBytesOnlyWithinCreditAndResumesWhenGranted() async throws {
        let (router, bytes) = try makeRouter()
        let relay = try FakeRelay()
        let port = try await relay.start()
        let phone = tunnel(port: port, router: router)
        phone.start(); defer { phone.stop() }
        #expect(await relay.wait { !relay.messages("hello").isEmpty })

        relay.send(["t": "req", "sid": 3, "method": "GET", "path": "/media/a?token=capA", "headers": [:], "credit": 100_000])
        #expect(await relay.wait { relay.body(3).count >= 100_000 })
        try await Task.sleep(nanoseconds: 400_000_000)
        #expect(relay.body(3).count == 100_000, "no bytes beyond the granted credit")
        #expect(relay.messages("end", sid: 3).isEmpty)

        relay.send(["t": "credit", "sid": 3, "bytes": 1_000_000])
        #expect(await relay.wait { !relay.messages("end", sid: 3).isEmpty })
        #expect(relay.body(3) == bytes)
    }

    @Test func cancelStopsAStream() async throws {
        let (router, _) = try makeRouter()
        let relay = try FakeRelay()
        let port = try await relay.start()
        let phone = tunnel(port: port, router: router)
        phone.start(); defer { phone.stop() }
        #expect(await relay.wait { !relay.messages("hello").isEmpty })
        relay.send(["t": "req", "sid": 5, "method": "GET", "path": "/media/a?token=capA", "headers": [:], "credit": 10_000])
        #expect(await relay.wait { relay.body(5).count >= 10_000 })
        relay.send(["t": "cancel", "sid": 5])
        relay.send(["t": "credit", "sid": 5, "bytes": 1_000_000])
        try await Task.sleep(nanoseconds: 400_000_000)
        #expect(relay.body(5).count == 10_000)
        #expect(relay.messages("end", sid: 5).isEmpty)
    }

    @Test func refusesWhenMobileDataIsNotAllowedAndOutsideMediaPaths() async throws {
        let (router, _) = try makeRouter()
        let relay = try FakeRelay()
        let port = try await relay.start()
        let phone = tunnel(port: port, router: router, admit: { _ in .mobileDataPending })
        phone.start(); defer { phone.stop() }
        #expect(await relay.wait { !relay.messages("hello").isEmpty })
        relay.send(["t": "req", "sid": 1, "method": "GET", "path": "/media/a?token=capA", "headers": [:]])
        #expect(await relay.wait { !relay.messages("error", sid: 1).isEmpty })
        #expect(relay.messages("error", sid: 1).first?["reason"] as? String == "mobile_data_pending")
        #expect(relay.messages("error", sid: 1).first?["status"] as? Int == 403)
    }

    @Test func wrongTokenOrPathIsRefused() async throws {
        let (router, _) = try makeRouter()
        let relay = try FakeRelay()
        let port = try await relay.start()
        let phone = tunnel(port: port, router: router)
        phone.start(); defer { phone.stop() }
        #expect(await relay.wait { !relay.messages("hello").isEmpty })
        relay.send(["t": "req", "sid": 2, "method": "GET", "path": "/media/a?token=wrong", "headers": [:]])
        #expect(await relay.wait { !relay.messages("end", sid: 2).isEmpty })
        #expect(relay.messages("head", sid: 2).first?["status"] as? Int == 401)
        relay.send(["t": "req", "sid": 4, "method": "GET", "path": "/library?token=capA", "headers": [:]])
        #expect(await relay.wait { !relay.messages("error", sid: 4).isEmpty })
        #expect(relay.messages("error", sid: 4).first?["status"] as? Int == 404)
        relay.send(["t": "ping"])
        #expect(await relay.wait { !relay.messages("pong").isEmpty })
    }

    @Test func aStreamCancelledWhileWaitingForCreditReleasesEverything() async throws {
        let lifetime = LanLibraryLifetime(idleTimeout: 100)
        let (router, _) = try makeRouter(lifetime: lifetime)
        let relay = try FakeRelay()
        let port = try await relay.start()
        let phone = tunnel(port: port, router: router)
        phone.start(); defer { phone.stop() }
        #expect(await relay.wait { !relay.messages("hello").isEmpty })
        for sid in 10..<15 {
            relay.send(["t": "req", "sid": sid, "method": "GET", "path": "/media/a?token=capA", "headers": [:], "credit": 1000])
            #expect(await relay.wait { relay.body(sid).count >= 1000 })
            #expect(lifetime.activeStreams == 1, "waiting for credit holds the file open")
            relay.send(["t": "cancel", "sid": sid])
            #expect(await relay.wait { lifetime.activeStreams == 0 }, "cancel must release the stream (sid \(sid))")
        }
    }

    @Test func aFastRefusalLeavesNoStreamBehind() async throws {
        let lifetime = LanLibraryLifetime(idleTimeout: 100)
        let (router, _) = try makeRouter(lifetime: lifetime)
        let relay = try FakeRelay()
        let port = try await relay.start()
        let phone = RelayTunnel(relayURL: { URL(string: "ws://127.0.0.1:\(port)/relay/v1/phone") }, accountToken: { "jwt" }, phoneDeviceId: { "p" },
                                networkType: { "wifi" }, admit: { _ in nil }, router: router, lifetime: lifetime)
        phone.start(); defer { phone.stop() }
        #expect(await relay.wait { !relay.messages("hello").isEmpty })
        for sid in 20..<40 { relay.send(["t": "req", "sid": sid, "method": "GET", "path": "/media/a?token=wrong", "headers": [:]]) }
        #expect(await relay.wait { relay.messages("end").count >= 20 })
        #expect(await relay.wait { lifetime.activeStreams == 0 })
    }
}
