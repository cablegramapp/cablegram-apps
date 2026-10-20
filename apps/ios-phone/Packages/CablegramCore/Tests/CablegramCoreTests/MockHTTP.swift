import Foundation
@testable import CablegramCore

struct RecordedRequest: @unchecked Sendable {
    var method: String
    var path: String
    var authorization: String?
    var body: [String: Any]

    // JSONSerialization output is unordered, so tests compare parsed bodies.
    var bodyDescription: String { String(describing: body) }
}

/// A scripted server: answers are queued per request in order, like Android's MockWebServer. nil = connection fails.
final class MockHTTP: URLProtocol, @unchecked Sendable {
    enum Reply { case response(Int, String), failure }

    nonisolated(unsafe) static var queue: [Reply] = []
    nonisolated(unsafe) static var requests: [RecordedRequest] = []
    /// When set, routes by path instead of the FIFO queue.
    nonisolated(unsafe) static var routes: [String: Reply] = [:]
    private static let lock = NSLock()

    static func reset() {
        lock.withLock { queue = []; requests = []; routes = [:] }
    }

    static func enqueue(_ status: Int, _ body: String = "{}") { lock.withLock { queue.append(.response(status, body)) } }
    static func enqueueFailure() { lock.withLock { queue.append(.failure) } }
    static func route(_ path: String, _ status: Int, _ body: String = "{}") { lock.withLock { routes[path] = .response(status, body) } }

    static var session: URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockHTTP.self]
        return URLSession(configuration: config)
    }

    static let baseURL = URL(string: "https://mock.test/")!

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var data = request.httpBody ?? Data()
        if data.isEmpty, let stream = request.httpBodyStream {
            stream.open()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buffer, maxLength: buffer.count)
                if n <= 0 { break }
                data.append(buffer, count: n)
            }
            stream.close()
        }
        let path = request.url?.path ?? ""
        let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let reply: Reply = Self.lock.withLock {
            Self.requests.append(RecordedRequest(method: request.httpMethod ?? "GET", path: path,
                                                 authorization: request.value(forHTTPHeaderField: "Authorization"), body: body))
            if let routed = Self.routes[path] { return routed }
            return Self.queue.isEmpty ? .response(500, "{}") : Self.queue.removeFirst()
        }
        switch reply {
        case .failure:
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
        case .response(let status, let text):
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(text.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {}
}

final class TestTokens: AccountTokens, @unchecked Sendable {
    private let lock = NSLock()
    private var _access: String?
    private var _refresh: String?
    init(access: String?, refresh: String?) { _access = access; _refresh = refresh }
    var accountToken: String? {
        get { lock.withLock { _access } }
        set { lock.withLock { _access = newValue } }
    }
    var refreshToken: String? {
        get { lock.withLock { _refresh } }
        set { lock.withLock { _refresh = newValue } }
    }
}
