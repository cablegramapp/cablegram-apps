import CablegramCore
import Foundation
import TDLibFramework

/// TDLib's JSON interface (`td_send` / `td_receive`) as a `TdTransport`. One client per instance; one receive thread per process hands
/// each answer to the request that carries its `@extra` and every other object to the updates.
final class TDLibJsonTransport: TdTransport, @unchecked Sendable {
    private let clientId: Int32
    private let lock = NSLock()
    private var nextExtra = 0
    private var pending: [Int: CheckedContinuation<TdObject, Error>] = [:]
    private let broadcaster = Broadcaster<TdObject>()

    init() {
        // TDLib logs to stderr; at 0 only fatal errors are printed, so no identifier or message text reaches the device log.
        _ = td_execute(Self.json(["@type": "setLogVerbosityLevel", "new_verbosity_level": 0]))
        clientId = td_create_client_id()
        TDLibReceiver.shared.register(self, clientId: clientId)
    }

    var updates: AsyncStream<TdObject> { broadcaster.stream() }

    func send(_ request: TdObject) async throws -> TdObject {
        try await withCheckedThrowingContinuation { continuation in
            let extra: Int = lock.withLock { nextExtra += 1; pending[nextExtra] = continuation; return nextExtra }
            var fields = request.fields
            fields["@extra"] = extra
            td_send(clientId, Self.json(fields))
        }
    }

    /// Called on the receive thread.
    fileprivate func deliver(_ object: [String: Any]) {
        if let extra = object["@extra"] as? Int, let continuation = lock.withLock({ pending.removeValue(forKey: extra) }) {
            var answer = object
            answer.removeValue(forKey: "@extra")
            answer.removeValue(forKey: "@client_id")
            if answer["@type"] as? String == "error" {
                continuation.resume(throwing: TgError(code: (answer["code"] as? NSNumber)?.intValue ?? 0, name: answer["message"] as? String ?? ""))
            } else {
                continuation.resume(returning: TdObject(answer))
            }
        } else {
            var update = object
            update.removeValue(forKey: "@client_id")
            broadcaster.send(TdObject(update))
        }
    }

    private static func json(_ object: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object), let text = String(data: data, encoding: .utf8) else { return "{}" }
        return text
    }
}

/// The one thread that calls `td_receive` (TDLib allows a single receiver) and routes by client id.
private final class TDLibReceiver: @unchecked Sendable {
    static let shared = TDLibReceiver()
    private let lock = NSLock()
    private var transports: [Int32: Weak] = [:]
    private var running = false

    private final class Weak: @unchecked Sendable { weak var transport: TDLibJsonTransport?; init(_ t: TDLibJsonTransport) { transport = t } }

    func register(_ transport: TDLibJsonTransport, clientId: Int32) {
        let start = lock.withLock { () -> Bool in
            transports[clientId] = Weak(transport)
            defer { running = true }
            return !running
        }
        if start {
            let thread = Thread { [self] in loop() }
            thread.name = "app.cablegram.tdlib-receive"
            thread.qualityOfService = .utility
            thread.start()
        }
    }

    private func loop() {
        while true {
            guard let text = td_receive(5.0), let data = text.data(using: .utf8),
                  let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { continue }
            let id = (object["@client_id"] as? NSNumber)?.int32Value ?? 0
            lock.withLock { transports[id]?.transport }?.deliver(object)
        }
    }
}
