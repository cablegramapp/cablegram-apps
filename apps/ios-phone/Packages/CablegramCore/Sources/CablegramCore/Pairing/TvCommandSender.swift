import Foundation

/// Sends remote commands to the TV selected on this phone and waits for the TV's answer (Android `CastSession.send/confirm`,
/// without the Cast route).
public enum TvCommandSender {
    public enum Result: Sendable, Equatable {
        /// `switchedFrom` as in `TargetResult.target`.
        case sent(tvName: String, commandId: String, token: String, switchedFrom: String?, deviceId: String?)
        case failed(String)
    }

    /// Sends to the TV selected on this phone. A selected TV the account no longer lists fails instead of falling back to another TV.
    /// `household` is the caller's cached list; it is refreshed from the server only when it does not already contain the selected TV.
    public static func send(pairing: PairingStore, client: CatalogClient, command: String, videoId: String? = nil, arguments: [String: any Sendable] = [:],
                            household: [MeDevice]? = nil, castTargetDeviceId: String? = nil) async -> Result {
        guard let token = pairing.accountToken, !token.isEmpty else { return .failed("Sign in to use the remote.") }
        // A Cast launch goes to the TV the Cast device belongs to, which need not be the last selected one.
        let selected: PairedTv?
        if let castTargetDeviceId {
            guard let match = pairing.tvs.first(where: { $0.deviceId == castTargetDeviceId }) else { return .failed("Choose a paired TV in Settings.") }
            selected = match
        } else { selected = pairing.tvs.last }
        var list = household
        // A failed refresh means offline (nil), not the stale cache: offline sends to the stored device ID, and the server refuses a
        // revoked TV. A title start always asks afresh which TVs are on.
        let titleStart = command == "play" && videoId != nil
        if list == nil || titleStart || (selected != nil && !(list ?? []).contains { $0.id == selected?.deviceId }) {
            list = await client.householdTvs(token: token)
        }
        let target: (deviceId: String, name: String, offline: Bool, switchedFrom: String?)
        switch castTargetDeviceId != nil ? castCommandTarget(selected, household: list) : resolveTarget(selected: selected, household: list) {
        case .failed(let message): return .failed(message)
        case .target(let id, let name, let offline, let from): target = (id, name, offline, from)
        }
        // A TV without Cablegram open can't take a title: when it opens it shows "Who's watching?", and a title is never kept for a
        // profile chosen later. Say so now instead of waiting on it. Only from a fresh list, never the cache.
        if titleStart && target.offline {
            return .failed("\(target.name) isn't on. Open Cablegram on it, choose who's watching, then try again.")
        }
        switch await client.postCommand(token: token, command: command, videoId: videoId, targetDeviceId: target.deviceId, arguments: arguments) {
        case .accepted(let id):
            // The TV that is on becomes the current one, so the remote and the next title go there too.
            if target.switchedFrom != nil, let tv = pairing.tvs.first(where: { $0.deviceId == target.deviceId }) {
                pairing.tvs = pairing.tvs.filter { $0.pin != tv.pin } + [tv]
            }
            return .sent(tvName: target.name, commandId: id, token: token, switchedFrom: target.switchedFrom, deviceId: target.deviceId)
        case .targetGone: return .failed("\(target.name) is no longer connected. Choose a TV in Settings.")
        case .failed: return .failed("Could not reach the control service. Check your connection and try again.")
        }
    }

    /// Waits for the TV to confirm a command the control plane accepted.
    public static func confirm(client: CatalogClient, commandId: String, token: String, deviceId: String?, wait: ConfirmationWait) async -> Outcome {
        await awaitOutcome(id: commandId, wait: wait, fetch: { await client.commandStatus(token: token, id: $0) },
                           tvOnline: deviceId.map { id in { await client.tvPlayback(token: token, deviceId: id)?.online } })
    }
}

extension Outcome {
    /// Whether the phone's screens may apply what was asked: the TV confirmed it, or could not be asked.
    public var applies: Bool { self == .confirmed || self == .unconfirmable }
}
