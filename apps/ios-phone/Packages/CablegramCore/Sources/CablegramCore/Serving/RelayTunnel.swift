import Foundation

/// What the phone may do when a relay stream would be uploaded over mobile data (spec 003 US3).
public enum RelayMobileDataPolicy: String, Sendable { case ask, always, never }

/// Why a relay request was refused before reaching the media server.
public enum RelayRefusal: Sendable { case mobileDataPending, mobileDataNotAllowed }

/// Keeps this phone reachable through the Cablegram relay (spec 003, `contracts/relay-protocol.md`). The relay forwards a TV's
/// request over this WebSocket; it is run against the phone's own router, so the TV capability, private-play pass and Range
/// handling are exactly the LAN ones. Body bytes go out only within the credit the relay grants.
public final class RelayTunnel: @unchecked Sendable {
    public struct Activity: Sendable { public var activeStreams: Int; public var bytesSent: Int64; public var network: String }

    private let relayURL: @Sendable () -> URL?
    private let accountToken: @Sendable () -> String?
    private let phoneDeviceId: @Sendable () -> String?
    private let networkType: @Sendable () -> String
    private let admit: @Sendable (String) -> RelayRefusal?
    private let onActivity: @Sendable (Activity) -> Void
    private let router: LanLibraryRouter
    private let lifetime: LanLibraryLifetime?
    private let session: URLSession

    private let lock = NSLock()
    private var loop: Task<Void, Never>?
    private var socket: URLSessionWebSocketTask?
    /// A stream is registered before its task starts, so a fast answer or a `cancel` always finds it.
    private final class Entry: @unchecked Sendable { var task: Task<Void, Never>?; var cancelled = false }
    private var streams: [Int: Entry] = [:]
    private var credits: [Int: CreditGate] = [:]
    private var connectedNetwork = "other"
    private var sent: Int64 = 0

    public init(relayURL: @escaping @Sendable () -> URL?, accountToken: @escaping @Sendable () -> String?, phoneDeviceId: @escaping @Sendable () -> String?,
                networkType: @escaping @Sendable () -> String, admit: @escaping @Sendable (String) -> RelayRefusal?, router: LanLibraryRouter,
                lifetime: LanLibraryLifetime? = nil, onActivity: @escaping @Sendable (Activity) -> Void = { _ in },
                session: URLSession = URLSession(configuration: .ephemeral)) {
        self.relayURL = relayURL; self.accountToken = accountToken; self.phoneDeviceId = phoneDeviceId; self.networkType = networkType
        self.admit = admit; self.router = router; self.lifetime = lifetime; self.onActivity = onActivity; self.session = session
    }

    /// `https://api.example/` → `wss://api.example/relay/v1/phone`.
    public static func phoneURL(apiBaseURL: String) -> URL? {
        let base = apiBaseURL.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if base.hasPrefix("https://") { return URL(string: "wss://" + base.dropFirst(8) + "/relay/v1/phone") }
        if base.hasPrefix("http://") { return URL(string: "ws://" + base.dropFirst(7) + "/relay/v1/phone") }
        return nil
    }

    public func start() {
        lock.withLock {
            guard loop == nil else { return }
            loop = Task { [weak self] in
                var backoff: UInt64 = 1
                while !Task.isCancelled {
                    guard let self else { return }
                    guard let url = relayURL(), let token = accountToken(), !token.isEmpty, let deviceId = phoneDeviceId(), !deviceId.isEmpty else {
                        try? await Task.sleep(nanoseconds: 15_000_000_000)
                        continue
                    }
                    let opened = await self.run(url: url, token: token, deviceId: deviceId)
                    self.cancelAll()
                    backoff = opened ? 1 : min(backoff * 2, 30)
                    try? await Task.sleep(nanoseconds: backoff * 1_000_000_000)
                }
            }
        }
    }

    public func stop() {
        let (task, socket) = lock.withLock { () -> (Task<Void, Never>?, URLSessionWebSocketTask?) in defer { loop = nil }; return (loop, self.socket) }
        task?.cancel()
        socket?.cancel(with: .normalClosure, reason: nil)
        cancelAll()
    }

    /// Re-announce the network after a Wi-Fi ↔ mobile change so the TV shows the right notice.
    public func networkChanged() {
        let network = networkType()
        let socket: URLSessionWebSocketTask? = lock.withLock {
            guard network != connectedNetwork else { return nil }
            connectedNetwork = network
            return self.socket
        }
        if let socket { Self.sendText(socket, ["t": "hello", "v": 1, "network": network]) }
    }

    // MARK: Connection

    /// Returns whether the connection was ever open (to reset the reconnect backoff).
    private func run(url: URL, token: String, deviceId: String) async -> Bool {
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(deviceId, forHTTPHeaderField: "X-Cablegram-Device")
        let task = session.webSocketTask(with: request)
        lock.withLock { socket = task }
        task.resume()
        let network = networkType()
        lock.withLock { connectedNetwork = network }
        Self.sendText(task, ["t": "hello", "v": 1, "network": network])
        var opened = false
        let pinger = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                task.sendPing { _ in }
            }
        }
        defer { pinger.cancel(); task.cancel(with: .goingAway, reason: nil); lock.withLock { socket = nil } }
        while !Task.isCancelled {
            guard let message = try? await task.receive() else { return opened }
            opened = true
            if case let .string(text) = message { handle(text, on: task) }
        }
        return opened
    }

    private func handle(_ text: String, on task: URLSessionWebSocketTask) {
        guard let object = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else { return }
        let sid = object["sid"] as? Int
        switch object["t"] as? String {
        case "req": if let sid { serve(sid, object, on: task) }
        case "credit":
            if let sid, let bytes = (object["bytes"] as? NSNumber)?.int64Value, let gate = lock.withLock({ credits[sid] }) { Task { await gate.add(bytes) } }
        case "cancel": if let sid { finish(sid, cancel: true) }
        case "ping": Self.sendText(task, ["t": "pong"])
        default: break
        }
    }

    // MARK: Streams

    private func serve(_ sid: Int, _ message: [String: Any], on task: URLSessionWebSocketTask) {
        let network = networkType()
        if let refusal = admit(network) {
            let reason = refusal == .mobileDataPending ? "mobile_data_pending" : "mobile_data_not_allowed"
            Self.sendText(task, ["t": "error", "sid": sid, "status": 403, "reason": reason])
            return
        }
        let method = (message["method"] as? String) ?? "GET"
        guard let target = message["path"] as? String else { return }
        // Only the media server's own paths; the relay already restricts them, this is defense in depth.
        guard target.hasPrefix("/media/") || target.hasPrefix("/poster/") || target.hasPrefix("/telegram/") else {
            Self.sendText(task, ["t": "error", "sid": sid, "status": 404, "reason": "not_found"])
            return
        }
        var headers: [String: String] = [:]
        for (name, value) in (message["headers"] as? [String: Any]) ?? [:] { if let text = value as? String { headers[name.lowercased()] = text } }
        let initial = (message["credit"] as? NSNumber)?.int64Value ?? 1_048_576
        let gate = CreditGate(initial)
        let request = HTTPRequest(method: method, target: target, headers: headers)

        lifetime?.streamStarted()
        let entry = Entry()
        // A repeated sid replaces the old stream: the old one is cancelled, not leaked.
        let replaced: Entry? = lock.withLock { defer { streams[sid] = entry; credits[sid] = gate }; return streams[sid] }
        if let replaced { replaced.cancelled = true; replaced.task?.cancel(); lifetime?.streamFinished() }
        let work = Task { [weak self] in
            guard let self else { return }
            defer { self.finish(sid, entry: entry, cancel: false) }
            let response = await router.handle(request, acceptQueryToken: true)
            if Task.isCancelled { if case let .stream(_, source) = response.body { source.close() }; return }
            var head: [String: String] = [:]
            for name in ["content-type", "content-length", "content-range", "accept-ranges", "last-modified", "etag"] { if let v = response.header(name) { head[name] = v } }
            if head["content-length"] == nil { head["content-length"] = String(response.body.length) }
            Self.sendText(task, ["t": "head", "sid": sid, "status": response.status, "headers": head])
            do {
                switch response.body {
                case .empty: break
                case let .data(data): if method != "HEAD" { try await self.pump(data: data, sid: sid, gate: gate, on: task, network: network) }
                case let .stream(_, source):
                    defer { source.close() }
                    if method != "HEAD" { try await self.pump(source: source, sid: sid, gate: gate, on: task, network: network) }
                }
                if !Task.isCancelled { Self.sendText(task, ["t": "end", "sid": sid]) }
            } catch {
                if !Task.isCancelled { Self.sendText(task, ["t": "error", "sid": sid, "status": 502, "reason": "phone_error"]) }
            }
        }
        lock.withLock { entry.task = work }
        if lock.withLock({ entry.cancelled }) { work.cancel() }
        report(network)
    }

    private func pump(data: Data, sid: Int, gate: CreditGate, on task: URLSessionWebSocketTask, network: String) async throws {
        var offset = 0
        while offset < data.count && !Task.isCancelled {
            let allowed = try await gate.take(max: min(64 * 1024, data.count - offset))
            let piece = data.subdata(in: offset..<(offset + allowed))
            offset += allowed
            try await sendBinary(sid: sid, piece, on: task)
            noteSent(piece.count, network)
        }
    }

    private func pump(source: any ByteStream, sid: Int, gate: CreditGate, on task: URLSessionWebSocketTask, network: String) async throws {
        while !Task.isCancelled {
            let allowed = try await gate.take(max: 64 * 1024)
            guard let piece = try await source.next(limit: allowed) else { return }
            await gate.giveBack(allowed - piece.count)
            try await sendBinary(sid: sid, piece, on: task)
            noteSent(piece.count, network)
        }
    }

    private func sendBinary(sid: Int, _ payload: Data, on task: URLSessionWebSocketTask) async throws {
        var frame = Data()
        withUnsafeBytes(of: UInt32(truncatingIfNeeded: sid).bigEndian) { frame.append(contentsOf: $0) }
        frame.append(payload)
        try await task.send(.data(frame))
    }

    private func noteSent(_ count: Int, _ network: String) {
        lock.withLock { sent += Int64(count) }
        report(network)
    }

    private func report(_ network: String) {
        let activity = lock.withLock { Activity(activeStreams: streams.count, bytesSent: sent, network: network) }
        onActivity(activity)
    }

    /// Ends a stream. `entry` pins the stream a task belongs to, so a reused sid is not removed by its predecessor.
    private func finish(_ sid: Int, entry: Entry? = nil, cancel: Bool) {
        let (removed, gate): (Entry?, CreditGate?) = lock.withLock {
            guard let current = streams[sid], entry == nil || current === entry else { return (nil, nil) }
            streams[sid] = nil
            current.cancelled = cancel || current.cancelled
            return (current, credits.removeValue(forKey: sid))
        }
        guard let removed else { return }
        if cancel { removed.task?.cancel(); if let gate { Task { await gate.close() } } }
        lifetime?.streamFinished()
        report(networkType())
    }

    private func cancelAll() {
        let ids = lock.withLock { Array(streams.keys) }
        for id in ids { finish(id, cancel: true) }
    }

    private static func sendText(_ task: URLSessionWebSocketTask, _ object: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: object), let text = String(data: data, encoding: .utf8) else { return }
        task.send(.string(text)) { _ in }
    }
}

/// How many body bytes the relay has allowed for one stream.
actor CreditGate {
    private var available: Int64
    private var closed = false
    private var waiter: CheckedContinuation<Void, Never>?

    init(_ initial: Int64) { available = initial }

    func add(_ bytes: Int64) {
        available += bytes
        wake()
    }

    /// Stops the stream: a waiting `take` returns by throwing.
    func close() {
        closed = true
        wake()
    }

    private func wake() { if let waiter { self.waiter = nil; waiter.resume() } }

    /// Waits for credit, then spends up to `max` bytes of it. Throws when the stream was cancelled while waiting.
    func take(max: Int) async throws -> Int {
        while available <= 0 {
            if closed || Task.isCancelled { throw CancellationError() }
            await withTaskCancellationHandler {
                await withCheckedContinuation { waiter = $0 }
            } onCancel: {
                Task { await self.close() }
            }
        }
        if closed { throw CancellationError() }
        let granted = Int(Swift.min(Int64(max), available))
        available -= Int64(granted)
        return granted
    }

    /// Returns credit that was taken but not used (a short read).
    func giveBack(_ bytes: Int) { available += bytes > 0 ? Int64(bytes) : 0 }
}
