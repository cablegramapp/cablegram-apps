import Foundation
import Network

/// A small HTTP/1.1 server on Network.framework that hands every request to `LanLibraryRouter`. It supports keep-alive and
/// streams bodies in 64 KiB pieces, waiting for each to leave before reading the next, so a slow TV never makes the phone hold
/// a film in memory. TLS is optional so tests can run it plain.
public final class LanHTTPServer: @unchecked Sendable {
    public struct Advertisement: Sendable {
        public var name: String, type: String, txt: [String: String]
        public init(name: String, type: String, txt: [String: String] = [:]) { self.name = name; self.type = type; self.txt = txt }
    }

    public enum StartError: Error { case failed(String) }

    private let router: LanLibraryRouter
    private let requestedPort: UInt16
    private let tls: NWProtocolTLS.Options?
    private let advertisement: Advertisement?
    private let queue = DispatchQueue(label: "app.cablegram.lan-server")
    private let lock = NSLock()
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: NWConnection] = [:]

    private static let maxHeaderBytes = 32 * 1024
    private static let maxBodyBytes = 1024 * 1024
    private static let chunk = 64 * 1024

    public init(router: LanLibraryRouter, port: UInt16, tls: NWProtocolTLS.Options? = nil, advertisement: Advertisement? = nil) {
        self.router = router; requestedPort = port; self.tls = tls; self.advertisement = advertisement
    }

    /// Starts listening and returns the port actually bound (port 0 asks the system for one).
    public func start() async throws -> UInt16 {
        let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters, on: requestedPort == 0 ? .any : NWEndpoint.Port(rawValue: requestedPort)!)
        if let advertisement {
            var record = NWTXTRecord()
            for (key, value) in advertisement.txt { record[key] = value }
            listener.service = NWListener.Service(name: advertisement.name, type: advertisement.type, txtRecord: record)
        }
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        let port: UInt16 = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<UInt16, Error>) in
            let once = Once()
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready: if once.take() { continuation.resume(returning: listener.port?.rawValue ?? 0) }
                case let .failed(error): if once.take() { continuation.resume(throwing: StartError.failed(error.localizedDescription)) } else { listener.cancel() }
                case .cancelled: if once.take() { continuation.resume(throwing: StartError.failed("cancelled")) }
                default: break
                }
            }
            listener.start(queue: queue)
        }
        lock.withLock { self.listener = listener }
        return port
    }

    public func stop() {
        let (listener, open) = lock.withLock { () -> (NWListener?, [NWConnection]) in
            defer { self.listener = nil; connections = [:] }
            return (self.listener, Array(connections.values))
        }
        listener?.cancel()
        open.forEach { $0.cancel() }
    }

    // MARK: Connections

    private func accept(_ connection: NWConnection) {
        lock.withLock { connections[ObjectIdentifier(connection)] = connection }
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let connection else { return }
            if case .failed = state { self?.drop(connection) }
            if case .cancelled = state { self?.drop(connection) }
        }
        connection.start(queue: queue)
        Task { [router] in
            await Self.serve(connection, router: router)
            connection.cancel()
        }
    }

    private func drop(_ connection: NWConnection) { lock.withLock { _ = connections.removeValue(forKey: ObjectIdentifier(connection)) } }

    private static func serve(_ connection: NWConnection, router: LanLibraryRouter) async {
        var buffer = Data()
        while true {
            guard let head = await readHead(connection, &buffer) else { return }
            guard let request = parse(head) else {
                await write(connection, status: 400, reason: "Bad Request", headers: [("Content-Length", "0"), ("Connection", "close")])
                return
            }
            var body = Data()
            let expected = Int(request.headers["content-length"] ?? "") ?? 0
            if expected > maxBodyBytes {
                await write(connection, status: 400, reason: "Bad Request", headers: [("Content-Length", "0"), ("Connection", "close")])
                return
            }
            while buffer.count < expected { guard let more = await receive(connection) else { return }; buffer.append(more) }
            body = buffer.prefix(expected); buffer.removeFirst(expected)
            var full = request
            full.body = Data(body)
            let response = await router.handle(full, acceptQueryToken: false)
            let keepAlive = request.headers["connection"]?.lowercased() != "close"
            guard await send(response, on: connection, head: request.method == "HEAD", keepAlive: keepAlive), keepAlive else { return }
        }
    }

    /// Reads up to the blank line ending the headers; bytes after it stay in `buffer`.
    private static func readHead(_ connection: NWConnection, _ buffer: inout Data) async -> Data? {
        let marker = Data("\r\n\r\n".utf8)
        while true {
            if let range = buffer.range(of: marker) {
                let head = buffer.subdata(in: buffer.startIndex..<range.lowerBound)
                buffer.removeSubrange(buffer.startIndex..<range.upperBound)
                return head
            }
            if buffer.count > maxHeaderBytes { return nil }
            guard let more = await receive(connection) else { return nil }
            buffer.append(more)
        }
    }

    private static func parse(_ head: Data) -> HTTPRequest? {
        guard let text = String(data: head, encoding: .utf8) else { return nil }
        var lines = text.components(separatedBy: "\r\n")
        let first = lines.removeFirst().split(separator: " ")
        guard first.count >= 2 else { return nil }
        var headers: [String: String] = [:]
        for line in lines {
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers[line[..<colon].lowercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        return HTTPRequest(method: String(first[0]).uppercased(), target: String(first[1]), headers: headers)
    }

    // MARK: Writing

    private static func send(_ response: HTTPResponse, on connection: NWConnection, head isHead: Bool, keepAlive: Bool) async -> Bool {
        var headers = response.headers
        if !headers.contains(where: { $0.name.lowercased() == "content-length" }) { headers.append(("Content-Length", String(response.body.length))) }
        headers.append(("Connection", keepAlive ? "keep-alive" : "close"))
        switch response.body {
        case .empty:
            return await write(connection, status: response.status, reason: response.reason, headers: headers)
        case let .data(data):
            return await write(connection, status: response.status, reason: response.reason, headers: headers, body: isHead ? nil : data)
        case let .stream(_, source):
            defer { source.close() }
            guard await write(connection, status: response.status, reason: response.reason, headers: headers) else { return false }
            if isHead { return true }
            while let piece = try? await source.next(limit: chunk) {
                if !(await transmit(connection, piece)) { return false }
            }
            return true
        }
    }

    @discardableResult
    private static func write(_ connection: NWConnection, status: Int, reason: String, headers: [(name: String, value: String)], body: Data? = nil) async -> Bool {
        var text = "HTTP/1.1 \(status) \(reason)\r\n"
        for header in headers { text += "\(header.name): \(header.value)\r\n" }
        text += "\r\n"
        var data = Data(text.utf8)
        if let body { data.append(body) }
        return await transmit(connection, data)
    }

    private static func transmit(_ connection: NWConnection, _ data: Data) async -> Bool {
        await withCheckedContinuation { continuation in
            connection.send(content: data, completion: .contentProcessed { continuation.resume(returning: $0 == nil) })
        }
    }

    private static func receive(_ connection: NWConnection) async -> Data? {
        await withCheckedContinuation { continuation in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
                if let data, !data.isEmpty { continuation.resume(returning: data) } else { continuation.resume(returning: nil) }
            }
        }
    }
}

/// Lets a continuation be resumed exactly once from a handler that can fire again.
private final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var taken = false
    func take() -> Bool { lock.withLock { if taken { return false }; taken = true; return true } }
}
