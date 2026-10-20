import CablegramCore
import CryptoKit
import XCTest
@testable import Cablegram

/// The LAN listener over real TLS with the phone's Keychain identity, as a TV reaches it: pinned by certificate hash.
final class LanTlsTests: XCTestCase {
    private final class Pinning: NSObject, URLSessionDelegate, @unchecked Sendable {
        let fingerprint: String
        init(_ fingerprint: String) { self.fingerprint = fingerprint }
        func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge) async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
            guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust, let trust = challenge.protectionSpace.serverTrust,
                  let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let leaf = chain.first else { return (.cancelAuthenticationChallenge, nil) }
            let hash = SHA256.hash(data: SecCertificateCopyData(leaf) as Data).map { String(format: "%02x", $0) }.joined()
            return hash == fingerprint ? (.useCredential, URLCredential(trust: trust)) : (.cancelAuthenticationChallenge, nil)
        }
    }

    func testIdentityIsStableAndServesTlsToATvThatPinsIt() async throws {
        let label = "Cablegram LAN TLS test \(UUID().uuidString)"
        let tag = Data("test.\(UUID().uuidString)".utf8)
        let first = try LanTlsIdentity.load(service: label, tag: tag)
        let second = try LanTlsIdentity.load(service: label, tag: tag)
        XCTAssertEqual(first.fingerprint, second.fingerprint, "the identity is made once")
        XCTAssertEqual(first.fingerprint.count, 64)

        let root = FileManager.default.temporaryDirectory.appendingPathComponent("tls-\(UUID().uuidString)")
        let store = LibraryStore(root: root)
        let bytes = Data((0..<5000).map { UInt8($0 % 200) })
        var item = LibraryItem(id: "a", title: "A", filename: "a.mp4", importedAt: "2026-01-01T00:00:00Z")
        item.fileName = "a.mp4"
        store.add(item)
        try bytes.write(to: store.videoFile(item))
        let credentials = LanCredentials()
        credentials.sync([PairedTv(pin: "1", name: "Den", capability: "capA", deviceId: "tvA")])
        let router = LanLibraryRouter(store: store, commands: CommandQueue(root: root), credentials: credentials)
        let server = LanHTTPServer(router: router, port: 0, tls: first.serverOptions())
        let port = try await server.start()
        defer { server.stop() }

        let session = URLSession(configuration: .ephemeral, delegate: Pinning(first.fingerprint), delegateQueue: nil)
        var request = URLRequest(url: URL(string: "https://127.0.0.1:\(port)/media/a")!)
        request.setValue("Bearer capA", forHTTPHeaderField: "Authorization")
        request.setValue("bytes=10-19", forHTTPHeaderField: "Range")
        let (data, response) = try await session.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 206)
        XCTAssertEqual(data, bytes[10...19])

        // A TV that pins a different certificate refuses to talk.
        let wrong = URLSession(configuration: .ephemeral, delegate: Pinning(String(repeating: "0", count: 64)), delegateQueue: nil)
        do { _ = try await wrong.data(for: request); XCTFail("a wrong pin must fail") } catch {}
    }
}
