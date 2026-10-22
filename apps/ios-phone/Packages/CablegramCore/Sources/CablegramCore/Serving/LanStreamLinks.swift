import Foundation
import Security

/// Short-lived stream links for one title and one TV (CAB-44). A player cannot send request headers, so the TV asks for a link
/// with its capability in a header and the player only ever sees the link. A link is a random id this phone keeps, not a
/// signature: removing or revoking the TV ends it at once, and a restart ends them all. It lasts `idle` after it was issued or
/// last used, but never longer than `maximum` in all.
public final class LanStreamLinks: @unchecked Sendable {
    public struct Grant: Sendable, Equatable { public var tvDeviceId: String? }

    private struct Link { var path: String; var capability: String; var tvDeviceId: String?; var endsAt: TimeInterval; var idleUntil: TimeInterval }

    private let now: @Sendable () -> TimeInterval
    private let idle: TimeInterval
    private let maximum: TimeInterval
    private let lock = NSLock()
    private var links: [String: Link] = [:]
    private static let maxLinks = 256

    public init(now: @escaping @Sendable () -> TimeInterval = { Date().timeIntervalSince1970 }, idle: TimeInterval = 15 * 60, maximum: TimeInterval = 12 * 3600) {
        self.now = now; self.idle = idle; self.maximum = maximum
    }

    /// How long an unused link lasts, for the TV to know when to ask again.
    public var idleSeconds: Int { Int(idle) }

    /// A new link id for `path` (`/media/<id>` or `/telegram/<id>`), for the TV holding `capability`.
    public func issue(path: String, capability: String, tvDeviceId: String?) -> String {
        lock.withLock {
            let t = now()
            links = links.filter { !Self.expired($0.value, at: t) }
            while links.count >= Self.maxLinks, let oldest = links.min(by: { $0.value.idleUntil < $1.value.idleUntil }) { links.removeValue(forKey: oldest.key) }
            var bytes = [UInt8](repeating: 0, count: 32)
            _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
            let id = Data(bytes).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
            links[id] = Link(path: path, capability: capability, tvDeviceId: tvDeviceId, endsAt: t + maximum, idleUntil: t + idle)
            return id
        }
    }

    /// The grant when `id` is a live link for exactly `path` and `stillAccepted` still accepts its capability. Using a link keeps
    /// it alive for another `idle`.
    public func redeem(_ id: String?, path: String, stillAccepted: (String) async -> Bool) async -> Grant? {
        guard let id, !id.isEmpty else { return nil }
        let t = now()
        let link: Link? = lock.withLock {
            guard let link = links[id] else { return nil }
            if Self.expired(link, at: t) { links.removeValue(forKey: id); return nil }
            return link
        }
        guard let link, link.path == path else { return nil }
        // The TV may have been removed or revoked since: a link never outlives its TV's capability.
        guard await stillAccepted(link.capability) else { lock.withLock { _ = links.removeValue(forKey: id) }; return nil }
        lock.withLock { links[id]?.idleUntil = min(t + idle, link.endsAt) }
        return Grant(tvDeviceId: link.tvDeviceId)
    }

    private static func expired(_ link: Link, at t: TimeInterval) -> Bool { t >= link.idleUntil || t >= link.endsAt }
}
