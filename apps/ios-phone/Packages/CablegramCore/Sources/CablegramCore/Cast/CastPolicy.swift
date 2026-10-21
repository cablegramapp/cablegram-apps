import Foundation

// Port of `cast/CastPolicy.kt`: the rules around Cast that need no Cast SDK.

public struct CastRoute: Sendable, Equatable {
    public var deviceId: String
    public var name: String
    public init(deviceId: String, name: String) { self.deviceId = deviceId; self.name = name }
}

public struct CastDeviceStatus: Sendable, Equatable {
    public var deviceId: String
    public var wrongTv: Bool
    public init(deviceId: String, wrongTv: Bool) { self.deviceId = deviceId; self.wrongTv = wrongTv }
}

public func matchingCastRoute(_ tv: PairedTv?, _ routes: [CastRoute]) -> CastRoute? {
    guard let id = tv?.castDeviceId else { return nil }
    return routes.first { $0.deviceId == id }
}

/// Status cannot enroll a new TV or authorize crossing the signed-in household.
public func wrongTvRetry(_ status: CastDeviceStatus?, paired: [PairedTv], householdIds: Set<String>, alreadyRetried: Bool) -> PairedTv? {
    guard let status, status.wrongTv, !alreadyRetried, householdIds.contains(status.deviceId) else { return nil }
    return paired.first { $0.deviceId == status.deviceId }
}

/// The Cast SDK's media states: IDLE is 1; the idle reasons FINISHED, CANCELLED and ERROR are 1, 2 and 4.
public func terminalCastIdle(playerState: Int, idleReason: Int) -> Bool { playerState == 1 && [1, 2, 4].contains(idleReason) }

/// Poster metadata may use a public HTTPS image, never an authenticated/signed URL.
public func publicPosterUrl(_ raw: String?) -> String? {
    guard let raw, let components = URLComponents(string: raw), components.scheme == "https", let host = components.host, !host.isEmpty,
          components.user == nil, components.password == nil, components.query == nil, components.fragment == nil, !raw.contains("?"), !raw.contains("#") else { return nil }
    return raw
}

/// Unlike relay selection, an asleep Cast target must never switch to another online TV.
public func castCommandTarget(_ selected: PairedTv?, household: [MeDevice]?) -> TargetResult {
    guard let id = selected?.deviceId, let selected else { return .failed("Choose a paired TV in Settings.") }
    if let household, !household.contains(where: { $0.id == id }) { return .failed("\(selected.name) is no longer connected. Choose a TV in Settings.") }
    return .target(deviceId: id, name: selected.name)
}

public func useCastForTitle(enabled: Bool, senderAvailable: Bool, routeAvailable: Bool) -> Bool { enabled && senderAvailable && routeAvailable }
