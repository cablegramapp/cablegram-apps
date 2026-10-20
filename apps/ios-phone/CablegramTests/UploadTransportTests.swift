import CablegramCore
import XCTest
@testable import Cablegram

/// Answers upload requests in-process, recording what the transport sent.
final class StubUploadProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var requests: [(method: String, headers: [String: String], body: Data)] = []
    nonisolated(unsafe) static var reply: (status: Int, headers: [String: String]) = (200, [:])
    nonisolated(unsafe) static var fail = false

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var body = Data()
        if let stream = request.httpBodyStream {
            stream.open()
            var buffer = [UInt8](repeating: 0, count: 64 * 1024)
            while stream.hasBytesAvailable {
                let n = stream.read(&buffer, maxLength: buffer.count)
                if n <= 0 { break }
                body.append(buffer, count: n)
            }
            stream.close()
        }
        Self.requests.append((request.httpMethod ?? "", request.allHTTPHeaderFields ?? [:], body))
        if Self.fail {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            return
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: Self.reply.status, httpVersion: nil, headerFields: Self.reply.headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// The transport is exercised on an in-process session: the system's background daemon does not run for a test host. The
/// background configuration itself is checked by hand on a device or simulator (see the plan's phase 4 notes).
final class UploadTransportTests: XCTestCase {
    override func setUp() {
        StubUploadProtocol.requests = []
        StubUploadProtocol.reply = (200, [:])
        StubUploadProtocol.fail = false
    }

    private func transport() -> BackgroundUploadTransport {
        BackgroundUploadTransport(allowsCellular: { true }, waitsForConnectivity: false, identifier: "test.\(UUID().uuidString)", ephemeral: true,
                                  protocolClasses: [StubUploadProtocol.self])
    }

    private func makeFile(_ size: Int) throws -> (URL, Data) {
        let data = Data((0..<size).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ 7) })
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("transport-\(UUID().uuidString).bin")
        try data.write(to: url)
        return (url, data)
    }

    func testAPartIsSentAsItsOwnByteRangeWithItsHeaders() async throws {
        let (file, data) = try makeFile(300_000)
        let response = try await transport().put(url: URL(string: "https://bucket.test/part")!, headers: ["Content-Range": "bytes 100000-199999/300000"],
                                                 file: file, offset: 100_000, length: 100_000) { _ in }
        XCTAssertEqual(response.status, 200)
        XCTAssertEqual(StubUploadProtocol.requests.count, 1)
        XCTAssertEqual(StubUploadProtocol.requests[0].method, "PUT")
        XCTAssertEqual(StubUploadProtocol.requests[0].headers["Content-Range"], "bytes 100000-199999/300000")
        XCTAssertEqual(StubUploadProtocol.requests[0].body, data[100_000..<200_000])
    }

    func testTheResponseHeadersComeBackLowerCased() async throws {
        StubUploadProtocol.reply = (308, ["Range": "bytes=0-99999"])
        let (file, _) = try makeFile(100_000)
        let response = try await transport().put(url: URL(string: "https://drive.test/s")!, headers: [:], file: file, offset: 0, length: 100_000) { _ in }
        XCTAssertEqual(response.status, 308)
        XCTAssertEqual(response.headers["range"], "bytes=0-99999")
    }

    func testANetworkFailureThrowsSoTheUploaderRetries() async throws {
        StubUploadProtocol.fail = true
        let (file, _) = try makeFile(1_000)
        do {
            _ = try await transport().put(url: URL(string: "https://bucket.test/none")!, headers: [:], file: file, offset: 0, length: 1_000) { _ in }
            XCTFail("expected a failure")
        } catch {}
    }

    func testASliceBeyondTheEndOfTheFileIsRefused() async throws {
        let (file, _) = try makeFile(1_000)
        do {
            _ = try await transport().put(url: URL(string: "https://bucket.test/x")!, headers: [:], file: file, offset: 900, length: 500) { _ in }
            XCTFail("expected a failure")
        } catch let error as UploadIOError { XCTAssertEqual(error.message, "The video ended early") } catch { XCTFail("\(error)") }
    }
}
