import Foundation

/// Build-time settings, the iOS counterpart of the Android `BuildConfig` fields. The app target fills them from
/// Info.plist keys that Config/*.xcconfig set; core code never reads the bundle itself.
public struct AppConfiguration: Sendable, Equatable {
    public let apiBase: URL
    public let webAppBase: URL
    /// Empty hides Cast.
    public let castAppId: String
    /// Speech-based subtitle sync is off for the first release (CAB-77, CAB-80).
    public let subtitleSpeechSync: Bool
    public let lanIdleTimeoutMs: Int64
    /// Test-only: announce this host instead of the phone's own address.
    public let lanHostOverride: String

    public static let defaultApiBase = URL(string: "https://api.cablegram.app/")!
    public static let defaultWebAppBase = URL(string: "https://app.cablegram.app")!

    public init(
        apiBase: URL = defaultApiBase,
        webAppBase: URL = defaultWebAppBase,
        castAppId: String = "",
        subtitleSpeechSync: Bool = false,
        lanIdleTimeoutMs: Int64 = 1_800_000,
        lanHostOverride: String = ""
    ) {
        self.apiBase = apiBase
        self.webAppBase = webAppBase
        self.castAppId = castAppId
        self.subtitleSpeechSync = subtitleSpeechSync
        self.lanIdleTimeoutMs = lanIdleTimeoutMs
        self.lanHostOverride = lanHostOverride
    }

    /// Reads the Info.plist keys; anything missing or malformed falls back to the release default.
    public init(infoDictionary info: [String: Any]) {
        func string(_ key: String) -> String {
            (info[key] as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
        }
        var web = URL(string: string("CablegramWebAppBase")) ?? Self.defaultWebAppBase
        if web.absoluteString.hasSuffix("/") { web = URL(string: String(web.absoluteString.dropLast())) ?? web }
        let idle = Int64(string("CablegramLanIdleTimeoutMs")).flatMap { $0 > 0 ? $0 : nil } ?? 1_800_000
        self.init(
            apiBase: URL(string: string("CablegramAPIBase")).flatMap { $0.host == nil ? nil : $0 } ?? Self.defaultApiBase,
            webAppBase: web,
            castAppId: string("CablegramCastAppID"),
            subtitleSpeechSync: ["yes", "true", "1"].contains(string("CablegramSubtitleSpeechSync").lowercased()),
            lanIdleTimeoutMs: idle,
            lanHostOverride: string("CablegramLanHostOverride")
        )
    }
}
