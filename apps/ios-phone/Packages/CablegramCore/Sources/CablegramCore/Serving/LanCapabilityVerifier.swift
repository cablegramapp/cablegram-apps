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
    private var recentCalls: [Date] = []
    private let maxCalls: Int
    private let callWindow: TimeInterval
    private static let maxEntries = 512

    public init(verify: @escaping @Sendable (String) async -> String?, now: @escaping @Sendable () -> Date = { Date() },
                acceptFor: TimeInterval = 600, rejectFor: TimeInterval = 30, maxCalls: Int = 30, callWindow: TimeInterval = 60) {
        self.verify = verify; self.now = now; self.acceptFor = acceptFor; self.rejectFor = rejectFor
        self.maxCalls = maxCalls; self.callWindow = callWindow
    }

    /// What a device capability looks like. Anything else is refused without asking the control plane, so a peer on the LAN cannot
    /// make this phone call the server (with the owner's token) by sending random values.
    static func looksLikeCapability(_ value: String) -> Bool {
        value.range(of: #"^[A-Za-z0-9_\-.~+/=]{16,256}$"#, options: .regularExpression) != nil
    }

    /// The TV device id for `capability`, or nil when it is not a paired TV.
    public func tvDeviceId(_ capability: String) async -> String? {
        guard Self.looksLikeCapability(capability) else { return nil }
        if let hit = lock.withLock({ cache[capability] }), hit.until > now() { return hit.tvDeviceId }
        // A global budget for questions to the control plane: a flood of different values costs the phone a few calls a minute, not one each.
        let allowed = lock.withLock { () -> Bool in
            let t = now()
            recentCalls.removeAll { t.timeIntervalSince($0) >= callWindow }
            guard recentCalls.count < maxCalls else { return false }
            recentCalls.append(t)
            return true
        }
        guard allowed else { return nil }
        let tv = await verify(capability)
        lock.withLock {
            let t = now()
            if cache.count >= Self.maxEntries {
                cache = cache.filter { $0.value.until > t }
                // Still full of live answers: drop the soonest to expire.
                while cache.count >= Self.maxEntries, let oldest = cache.min(by: { $0.value.until < $1.value.until }) { cache.removeValue(forKey: oldest.key) }
            }
            cache[capability] = Entry(tvDeviceId: tv, until: t.addingTimeInterval(tv != nil ? acceptFor : rejectFor))
        }
        return tv
    }

    public func forget(_ capability: String) { lock.withLock { _ = cache.removeValue(forKey: capability) } }

    /// Drops every cached answer for this TV (CAB-37).
    public func forgetDevice(_ deviceId: String) { lock.withLock { cache = cache.filter { $0.value.tvDeviceId != deviceId } } }
}
