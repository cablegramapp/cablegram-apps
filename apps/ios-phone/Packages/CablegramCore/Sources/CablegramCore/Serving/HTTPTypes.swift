import Foundation

/// A parsed HTTP/1.1 request as the library router sees it. Header names are lowercased; `query` is percent-decoded.
public struct HTTPRequest: Sendable {
    public var method: String
    public var path: String
    public var query: [String: String]
    public var headers: [String: String]
    public var body: Data

    public init(method: String, path: String, query: [String: String] = [:], headers: [String: String] = [:], body: Data = Data()) {
        self.method = method; self.path = path; self.query = query; self.headers = headers; self.body = body
    }

    /// Splits a request target such as `/media/x?link=abc` into path and query.
    public init(method: String, target: String, headers: [String: String] = [:], body: Data = Data()) {
        let parts = target.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        var path = String(parts.first ?? "")
        path = path.removingPercentEncoding ?? path
        var query: [String: String] = [:]
        if parts.count > 1 {
            for pair in parts[1].split(separator: "&") {
                let kv = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                let name = String(kv[0]).replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? String(kv[0])
                let value = kv.count > 1 ? (String(kv[1]).replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? String(kv[1])) : ""
                if query[name] == nil { query[name] = value }
            }
        }
        self.init(method: method, path: path, query: query, headers: headers, body: body)
    }
}

/// A body read in pieces, so a film is never held in memory.
public protocol ByteStream: Sendable {
    /// The next piece (at most `limit` bytes); nil at the end.
    func next(limit: Int) async throws -> Data?
    func close()
}

public enum HTTPBody: Sendable {
    case empty
    case data(Data)
    case stream(length: Int64, source: any ByteStream)

    public var length: Int64 {
        switch self {
        case .empty: 0
        case let .data(data): Int64(data.count)
        case let .stream(length, _): length
        }
    }
}

public struct HTTPResponse: Sendable {
    public var status: Int
    public var headers: [(name: String, value: String)]
    public var body: HTTPBody

    public init(status: Int, headers: [(name: String, value: String)] = [], body: HTTPBody = .empty) {
        self.status = status; self.headers = headers; self.body = body
    }

    public func header(_ name: String) -> String? { headers.first { $0.name.lowercased() == name.lowercased() }?.value }

    static func json(_ text: String, status: Int = 200) -> HTTPResponse {
        HTTPResponse(status: status, headers: [("Content-Type", "application/json")], body: .data(Data(text.utf8)))
    }

    static func error(_ status: Int, _ code: String) -> HTTPResponse { .json("{\"error\":\"\(code)\"}", status: status) }

    public var reason: String {
        switch status {
        case 200: "OK"; case 206: "Partial Content"; case 400: "Bad Request"; case 401: "Unauthorized"; case 403: "Forbidden"
        case 404: "Not Found"; case 416: "Range Not Satisfiable"; case 502: "Bad Gateway"; default: "Status"
        }
    }
}

/// Reads `length` bytes of a file from `offset`, then ends.
public final class FileByteStream: ByteStream, @unchecked Sendable {
    private let handle: FileHandle
    private let lock = NSLock()
    private var remaining: Int64
    private var onClose: (() -> Void)?

    public init?(url: URL, offset: Int64, length: Int64, onClose: (() -> Void)? = nil) {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        do { try handle.seek(toOffset: UInt64(offset)) } catch { try? handle.close(); return nil }
        self.handle = handle; remaining = length; self.onClose = onClose
    }

    public func next(limit: Int) async throws -> Data? {
        let want: Int = lock.withLock { Int(min(Int64(limit), remaining)) }
        guard want > 0 else { return nil }
        let data = try handle.read(upToCount: want) ?? Data()
        guard !data.isEmpty else { lock.withLock { remaining = 0 }; return nil }
        lock.withLock { remaining -= Int64(data.count) }
        return data
    }

    public func close() {
        let callback = lock.withLock { () -> (() -> Void)? in defer { onClose = nil }; return onClose }
        try? handle.close()
        callback?()
    }
}

/// Wraps a stream so the lifetime tracker hears when a film's last byte was sent or the connection dropped.
public final class ActivityByteStream: ByteStream, @unchecked Sendable {
    private let inner: any ByteStream
    private let lock = NSLock()
    private var finished: (() -> Void)?

    public init(_ inner: any ByteStream, onFinish: @escaping () -> Void) { self.inner = inner; finished = onFinish }

    public func next(limit: Int) async throws -> Data? {
        let data: Data?
        do { data = try await inner.next(limit: limit) } catch { close(); throw error }
        if data == nil { close() }
        return data
    }

    public func close() {
        inner.close()
        let callback = lock.withLock { () -> (() -> Void)? in defer { finished = nil }; return finished }
        callback?()
    }
}
