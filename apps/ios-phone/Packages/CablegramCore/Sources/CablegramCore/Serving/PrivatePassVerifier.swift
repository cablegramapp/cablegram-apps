import Foundation

/// A LAN pass the control plane confirmed for an approved private playback.
public struct VerifiedPass: Sendable, Equatable {
    public var originIdentities: Set<String>
    public var tvDeviceId: String?
    public var expiresAt: Date
    public init(originIdentities: Set<String>, tvDeviceId: String?, expiresAt: Date) {
        self.originIdentities = originIdentities; self.tvDeviceId = tvDeviceId; self.expiresAt = expiresAt
    }
}

/// Private titles are streamed only with a LAN pass minted when a TV starts an approved playback. A pass is confirmed with the
/// control plane once and cached until it expires, so seeks and range requests stay local.
public final class PrivatePassVerifier: @unchecked Sendable {
    private let verify: @Sendable (String) async -> VerifiedPass?
    private let now: @Sendable () -> Date
    private let lock = NSLock()
    private var cache: [String: VerifiedPass] = [:]

    public init(verify: @escaping @Sendable (String) async -> VerifiedPass?, now: @escaping @Sendable () -> Date = { Date() }) {
        self.verify = verify; self.now = now
    }

    public func allows(pass: String?, itemId: String, tvDeviceId: String?) async -> Bool {
        guard let pass, !pass.isEmpty else { return false }
        let cached = lock.withLock { () -> VerifiedPass? in
            cache = cache.filter { $0.value.expiresAt > now() }
            return cache[pass]
        }
        var verified = cached
        if verified == nil, let fresh = await verify(pass), fresh.expiresAt > now() {
            lock.withLock { cache[pass] = fresh }
            verified = fresh
        }
        guard let verified, verified.originIdentities.contains(itemId) else { return false }
        // A pass is bound to the TV that started the playback.
        return tvDeviceId == nil || verified.tvDeviceId == nil || tvDeviceId == verified.tvDeviceId
    }
}
