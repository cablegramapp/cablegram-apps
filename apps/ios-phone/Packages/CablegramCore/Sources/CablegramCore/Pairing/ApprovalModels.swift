import Foundation

/// What a TV or another phone is waiting for: a private title to play, a profile to open, or a phone join request.
public enum ApprovalKind: String, Sendable { case playback = "Playback", profile = "Profile", phoneJoin = "PhoneJoin" }

public struct PendingApproval: Sendable, Equatable, Identifiable {
    public var attemptId: String
    public var mediaItemId: String?
    public var title: String?
    public var expiresAt: String?
    public var tvName: String?
    /// CAB-67: other accounts use this TV too (profile requests only).
    public var sharedTv: Bool
    public var kind: ApprovalKind

    public var id: String { attemptId }

    public init(attemptId: String, mediaItemId: String? = nil, title: String? = nil, expiresAt: String? = nil, tvName: String? = nil,
                sharedTv: Bool = false, kind: ApprovalKind = .playback) {
        self.attemptId = attemptId; self.mediaItemId = mediaItemId; self.title = title; self.expiresAt = expiresAt; self.tvName = tvName
        self.sharedTv = sharedTv; self.kind = kind
    }
}

/// What the phone's answer to a TV's request needs: where the pairing claim ended.
public struct PairClaim: Sendable, Equatable {
    public var lanCapability: String?
    public var deviceId: String?
    public init(lanCapability: String?, deviceId: String?) {
        self.lanCapability = lanCapability
        self.deviceId = deviceId
    }
}

/// How `pairPhone` ended: the claim, or the server's error code (e.g. `pin_expired`, CAB-62; nil when offline).
public struct PairOutcome: Sendable, Equatable {
    public var claim: PairClaim?
    public var error: String?
    public init(claim: PairClaim?, error: String?) {
        self.claim = claim
        self.error = error
    }
}

/// The one poll for pending approvals: quick while the app is on screen, slow in the background.
public func approvalPollDelayMs(appVisible: Bool) -> Int64 { appVisible ? 6_000 : 15_000 }
