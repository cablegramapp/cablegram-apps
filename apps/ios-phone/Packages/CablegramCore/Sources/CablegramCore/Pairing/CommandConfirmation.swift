import Foundation

/// Result of asking the control plane to accept a command (not of the TV running it).
public enum CommandSend: Sendable, Equatable {
    case accepted(id: String)
    case failed
    /// The control plane no longer knows the target TV (revoked or removed), as opposed to being unreachable.
    case targetGone
}

/// What the control plane reports for a command. `unsupported` is a server without the status endpoint.
public enum CommandStatus: Sendable, Equatable {
    /// `state` is pending, delivered, completed or rejected. `expiresInMs` is the time left before the command expires, by the
    /// server's clock (nil from a server that does not send it).
    case known(state: String, reason: String?, expiresInMs: Int64? = nil)
    case unsupported
}

public enum Outcome: Sendable, Equatable {
    case confirmed
    case rejected(reason: String?)
    case timedOut
    /// The server cannot report outcomes yet, so the TV's result is unknown.
    case unconfirmable
    /// A title start the TV never picked up, and the TV has gone offline (CAB-58).
    case unreachable
}

/// How long to wait: a control gives up quickly; a title start waits until the command expires.
public enum ConfirmationWait: Sendable, Equatable {
    case control(capMs: Int64 = controlCapMs)
    case titleStart
}

public let controlCapMs: Int64 = 8_000
private let fastPollMs: Int64 = 500
private let slowPollMs: Int64 = 1_000
private let fastPollWindowMs: Int64 = 2_000
// Slack past the command's expiry so the server's own "expired" verdict is read.
private let expiryGraceMs: Int64 = 1_500
// Upper bound for a title start whatever the server reports (its TTL is 300 s).
private let titleStartMaxMs: Int64 = 310_000
// CAB-58: a title start the TV has not picked up after this long is checked against the TV's presence.
public let unreachableGraceMs: Int64 = 5_000
private let presenceCheckMs: Int64 = 3_000

/// Polls the command's status until the TV confirms or rejects it, or the wait ends. `clock` and `sleep` (milliseconds) are
/// injected for tests.
///
/// CAB-58: a title start waits up to the command's 300 s life, which suits a TV that is busy, not one that is gone. The server counts
/// a TV as on for about 15 s after it last polled, so a title can be sent to a TV that was just closed or lost its network, and
/// nothing would ever pick it up. While the TV has not picked the title up, `tvOnline` is asked now and then; once it says the TV is
/// offline, the wait ends as `.unreachable`.
public func awaitOutcome(
    id: String,
    wait: ConfirmationWait,
    fetch: (String) async -> CommandStatus?,
    clock: () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
    sleep: (Int64) async -> Void = { try? await Task.sleep(for: .milliseconds($0)) },
    tvOnline: (() async -> Bool?)? = nil
) async -> Outcome {
    let start = clock()
    var presenceCheckedAt = Int64.min / 2
    var deadline = start + { if case .control(let cap) = wait { return cap } else { return titleStartMaxMs } }()
    while true {
        switch await fetch(id) {
        case .unsupported?: return .unconfirmable
        case .known(let state, let reason, let expiresInMs)?:
            switch state {
            case "completed": return .confirmed
            case "rejected": return .rejected(reason: reason)
            default:
                // Anchored on this phone's clock at the read: the server's absolute expiry would end the wait early on a phone whose
                // clock runs ahead.
                if wait == .titleStart, let expiresInMs { deadline = min(clock() + expiresInMs + expiryGraceMs, start + titleStartMaxMs) }
                let at = clock()
                if wait == .titleStart, state == "pending", let tvOnline, at - start >= unreachableGraceMs, at - presenceCheckedAt >= presenceCheckMs {
                    presenceCheckedAt = at
                    if await tvOnline() == false { return .unreachable }
                }
            }
        case nil: break // a transient read failure; keep polling until the deadline
        }
        let now = clock()
        if now >= deadline { return .timedOut }
        await sleep(now - start < fastPollWindowMs ? fastPollMs : slowPollMs)
    }
}
