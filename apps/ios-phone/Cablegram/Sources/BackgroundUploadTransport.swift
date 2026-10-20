import CablegramCore
import Foundation

/// Sends parts and chunks of a video through a background URLSession, so the system keeps a part going when the app is
/// suspended (plan §4, replacing Android's foreground service). Each call is one upload task whose body is a temporary file
/// holding just that byte range; the uploaders above decide what to send next, and the server's list of received parts or
/// Drive's offset is what a later run resumes from, so a killed app costs at most the part in flight.
final class BackgroundUploadTransport: NSObject, UploadTransport, URLSessionDataDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    static let sessionIdentifier = "app.cablegram.phone.uploads"
    // Background sessions keep retrying a refused or unreachable connection until `timeoutIntervalForResource`, so a part that cannot
    // be sent waits for the network instead of failing at once.
    /// Set by the app delegate when the system relaunches the app for finished background tasks.
    nonisolated(unsafe) static var backgroundCompletion: (() -> Void)?

    private struct Pending {
        var continuation: CheckedContinuation<UploadResponse, Error>
        var onBytes: @Sendable (Int64) -> Void
        var tempFile: URL
    }

    private let lock = NSLock()
    private var pending: [Int: Pending] = [:]
    private let allowsCellular: @Sendable () -> Bool
    private let waitsForConnectivity: Bool
    private let resourceTimeout: TimeInterval
    private let identifier: String
    private let ephemeral: Bool
    private let protocolClasses: [AnyClass]?
    private lazy var session: URLSession = {
        let configuration = ephemeral ? URLSessionConfiguration.ephemeral : URLSessionConfiguration.background(withIdentifier: identifier)
        if let protocolClasses { configuration.protocolClasses = protocolClasses }
        if !ephemeral {
            configuration.sessionSendsLaunchEvents = true
            configuration.isDiscretionary = false
        }
        // A part waits for the network to come back (airplane mode, a lift) instead of failing at once; after a quarter of an hour
        // it fails and the uploader retries.
        configuration.waitsForConnectivity = waitsForConnectivity
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = resourceTimeout
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    init(allowsCellular: @escaping @Sendable () -> Bool = { false }, waitsForConnectivity: Bool = true, resourceTimeout: TimeInterval = 15 * 60,
         identifier: String = BackgroundUploadTransport.sessionIdentifier, ephemeral: Bool = false, protocolClasses: [AnyClass]? = nil) {
        self.identifier = identifier
        self.protocolClasses = protocolClasses
        // Tests use an in-process session: the system's background daemon does not run for a test host.
        self.ephemeral = ephemeral
        self.allowsCellular = allowsCellular
        self.resourceTimeout = resourceTimeout
        self.waitsForConnectivity = waitsForConnectivity
        super.init()
        // Touching the session re-attaches the delegate to tasks that kept running while the app was gone.
        _ = session
    }

    func put(url: URL, headers: [String: String], file: URL, offset: Int64, length: Int64, onBytes: @escaping @Sendable (Int64) -> Void) async throws -> UploadResponse {
        let slice = try writeSlice(of: file, offset: offset, length: length)
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.allowsCellularAccess = allowsCellular()
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let task = session.uploadTask(with: request, fromFile: slice)
                lock.withLock { pending[task.taskIdentifier] = Pending(continuation: continuation, onBytes: onBytes, tempFile: slice) }
                task.resume()
            }
        } onCancel: {
            session.getAllTasks { tasks in for task in tasks where self.isPending(task.taskIdentifier) { task.cancel() } }
        }
    }

    private func isPending(_ taskIdentifier: Int) -> Bool { lock.withLock { pending[taskIdentifier] != nil } }

    /// Copies one byte range into its own file (1 MiB at a time), because background tasks can only upload from files.
    private func writeSlice(of file: URL, offset: Int64, length: Int64) throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("upload-slices", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let slice = directory.appendingPathComponent(UUID().uuidString)
        FileManager.default.createFile(atPath: slice.path, contents: nil)
        let input = try FileHandle(forReadingFrom: file)
        let output = try FileHandle(forWritingTo: slice)
        defer { try? input.close(); try? output.close() }
        try input.seek(toOffset: UInt64(offset))
        var remaining = length
        while remaining > 0 {
            guard let data = try input.read(upToCount: Int(min(remaining, 1 << 20))), !data.isEmpty else { throw UploadIOError("The video ended early") }
            try output.write(contentsOf: data)
            remaining -= Int64(data.count)
        }
        return slice
    }

    // MARK: Delegate

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        lock.withLock { pending[task.taskIdentifier]?.onBytes }?(totalBytesSent)
    }

    /// Drive answers a partly received chunk with 308, which is a status here, not a redirect.
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let entry = lock.withLock({ pending.removeValue(forKey: task.taskIdentifier) }) else { return }
        try? FileManager.default.removeItem(at: entry.tempFile)
        if let http = task.response as? HTTPURLResponse {
            var headers: [String: String] = [:]
            for (key, value) in http.allHeaderFields { if let k = key as? String, let v = value as? String { headers[k.lowercased()] = v } }
            entry.continuation.resume(returning: UploadResponse(status: http.statusCode, headers: headers))
        } else if (error as? URLError)?.code == .cancelled {
            entry.continuation.resume(throwing: CancellationError())
        } else {
            entry.continuation.resume(throwing: error ?? UploadIOError("network"))
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async { Self.backgroundCompletion?(); Self.backgroundCompletion = nil }
    }
}
