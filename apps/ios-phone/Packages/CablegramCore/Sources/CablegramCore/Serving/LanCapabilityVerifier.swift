import Foundation

/// Only the phone that claimed a TV's PIN receives that TV's LAN capability. Another household phone serving media would
/// reject the TV forever, so unknown capabilities are checked with the control plane. Accepted answers are cached for
/// `acceptFor`, rejected ones for a short `rejectFor`, so a bad token cannot make this phone hammer the server.
public final class LanCapabilityVerifier: @unchecked Sendable {
    private struct Entry { var tvDeviceId: String?; var until: Date }

    private let verify: @Sendable (String) async -> String?
    private let now: @Sendable () -> Date
    private let acceptFor: TimeInterval
    private let rejectFor: TimeInterval
    private let lock = NSLock()
    private var cache: [String: Entry] = [:]

    public init(verify: @escaping @Sendable (String) async -> String?, now: @escaping @Sendable () -> Date = { Date() },
                acceptFor: TimeInterval = 600, rejectFor: TimeInterval = 30) {
        self.verify = verify; self.now = now; self.acceptFor = acceptFor; self.rejectFor = rejectFor
    }

    /// The TV device id for `capability`, or nil when it is not a paired TV.
    public func tvDeviceId(_ capability: String) async -> String? {
        if let hit = lock.withLock({ cache[capability] }), hit.until > now() { return hit.tvDeviceId }
        let tv = await verify(capability)
        lock.withLock { cache[capability] = Entry(tvDeviceId: tv, until: now().addingTimeInterval(tv != nil ? acceptFor : rejectFor)) }
        return tv
    }

    public func forget(_ capability: String) { lock.withLock { _ = cache.removeValue(forKey: capability) } }

    /// Drops every cached answer for this TV (CAB-37).
    public func forgetDevice(_ deviceId: String) { lock.withLock { cache = cache.filter { $0.value.tvDeviceId != deviceId } } }
}
