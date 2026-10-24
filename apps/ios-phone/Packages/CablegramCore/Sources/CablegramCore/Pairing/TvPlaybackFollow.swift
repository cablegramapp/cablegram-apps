import Foundation

/// What a TV last said its player was doing (`GET /api/control/playback/{deviceId}`).
public struct TvPlayback: Sendable, Equatable {
    public var state: String
    public var videoId: String?
    public var originId: String?
    public var positionMs: Int64?
    /// How long ago the server received the report; nil when the TV has not reported since the server started.
    public var ageMs: Int64?
    public var online: Bool
    public var volume: Int?

    public init(state: String, videoId: String? = nil, originId: String? = nil, positionMs: Int64? = nil, ageMs: Int64? = nil, online: Bool, volume: Int? = nil) {
        self.state = state; self.videoId = videoId; self.originId = originId; self.positionMs = positionMs; self.ageMs = ageMs; self.online = online; self.volume = volume
    }
}

/// What the phone's remote should do after reading `TvPlayback`.
public enum TvFollow: Sendable, Equatable {
    /// Nothing new, or nothing the phone can trust: keep showing what it has.
    case keep
    /// The TV is not playing anything (the title ended or was stopped there, or the TV went away).
    case stopped
    case playing(paused: Bool)
    /// The TV is playing another title, started on the TV itself.
    case other(videoId: String?, originId: String?, paused: Bool)
}

/// How often the phone asks while a title plays on a TV and the app is open.
public let tvFollowPollMs: Int64 = 3_000
/// For this long after the phone's own command, a TV report made before it is not trusted (CAB-57).
public let ownCommandShieldMs: Int64 = 10_000
/// A "playing" TV refreshes its report every 15 s; one this old has stopped reporting.
public let tvPlayingStaleMs: Int64 = 45_000
/// The TV's command channel has been closed this long: it is off or the app was closed.
public let tvGoneMs: Int64 = 20_000

/// CAB-57: the phone's remote follows the TV instead of the last command it sent. `sinceOwnCommandMs` is how long ago this phone's
/// last command was answered: shortly after it, a report older than that may predate the command's effect. Later the TV's newest
/// report wins, even an old one: a stopped or paused TV does not report again.
public func followTv(_ report: TvPlayback, nowPlayingId: String?, sinceOwnCommandMs: Int64) -> TvFollow {
    guard let age = report.ageMs, report.state != "unknown" else { return .keep }
    if age >= sinceOwnCommandMs && sinceOwnCommandMs < ownCommandShieldMs { return .keep }
    if !report.online && age > tvGoneMs { return .stopped }
    switch report.state {
    case "stopped": return .stopped
    case "playing", "paused":
        let paused = report.state == "paused"
        if report.state == "playing" && age > tvPlayingStaleMs { return .stopped }
        if nowPlayingId == nil || nowPlayingId == report.videoId || nowPlayingId == report.originId { return .playing(paused: paused) }
        return .other(videoId: report.videoId, originId: report.originId, paused: paused)
    default: return .keep
    }
}
