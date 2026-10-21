import Foundation

/// Port of Android `PairingStore` (account and preference part; paired TVs arrive with phase 5).
/// Tokens and the phone device id live in the secret store, everything else in UserDefaults.
public final class PairingStore: AccountTokens, @unchecked Sendable {
    private let defaults: UserDefaults
    private let secrets: SecretStoring
    private let defaultApiBase: String
    private let lock = NSLock()

    public init(defaults: UserDefaults = .standard, secrets: SecretStoring, defaultApiBase: String = AppConfiguration.defaultApiBase.absoluteString) {
        self.defaults = defaults
        self.secrets = secrets
        self.defaultApiBase = defaultApiBase
    }

    public var accountToken: String? {
        get { lock.withLock { secrets.read("account_token") } }
        set { lock.withLock { secrets.write("account_token", newValue) } }
    }

    public var refreshToken: String? {
        get { lock.withLock { secrets.read("refresh_token") } }
        set { lock.withLock { secrets.write("refresh_token", newValue) } }
    }

    public var phoneDeviceId: String? {
        get { lock.withLock { secrets.read("phone_device_id") } }
        set { lock.withLock { secrets.write("phone_device_id", newValue) } }
    }

    private static let tvsKey = "paired_tvs"
    private static let pairingLock = NSRecursiveLock()

    /// The whole pairing record is stored in the secret store: no LAN capability or pairing PIN is kept in plain preferences. The last
    /// TV is the selected one.
    public var tvs: [PairedTv] {
        get {
            Self.pairingLock.withLock {
                guard let raw = secrets.read(Self.tvsKey), let data = raw.data(using: .utf8) else { return [] }
                return (try? JSONDecoder().decode([PairedTv].self, from: data)) ?? []
            }
        }
        set {
            Self.pairingLock.withLock {
                if let data = try? JSONEncoder().encode(newValue), let raw = String(data: data, encoding: .utf8) { secrets.write(Self.tvsKey, newValue.isEmpty ? nil : raw) }
                defaults.set(newValue.last?.name ?? "TV", forKey: "tv_name")
            }
        }
    }

    public var tvName: String { tvs.last?.name ?? defaults.string(forKey: "tv_name") ?? "TV" }

    public func addTv(pin: String, name: String, capability: String? = nil, deviceId: String? = nil, trust: TvTrust = .home) {
        tvs = tvs.filter { $0.pin != pin } + [PairedTv(pin: pin, name: name, capability: capability, deviceId: deviceId, trust: trust)]
    }

    /// Upgrade an existing pairing with the capability minted at claim time.
    public func attachCapability(pin: String, capability: String) { tvs = tvs.map { var t = $0; if t.pin == pin { t.capability = capability }; return t } }

    /// Record the server-side device id for this TV (used for revocation checks).
    public func attachDeviceId(pin: String, deviceId: String) { tvs = tvs.map { var t = $0; if t.pin == pin { t.deviceId = deviceId }; return t } }

    /// A Cast status may associate a route with an existing pairing, never create one.
    public func attachCastDeviceId(deviceId: String, castDeviceId: String) {
        guard tvs.contains(where: { $0.deviceId == deviceId }) else { return }
        tvs = tvs.map { tv in
            var t = tv
            if tv.deviceId == deviceId { t.castDeviceId = castDeviceId } else if tv.castDeviceId == castDeviceId { t.castDeviceId = nil }
            return t
        }
    }

    public func renameTv(pin: String, name: String) { tvs = tvs.map { var t = $0; if t.pin == pin { t.name = name }; return t } }
    public func removeTv(pin: String) { tvs = tvs.filter { $0.pin != pin } }
    public func clearTVs() { tvs = [] }

    public var displayName: String {
        get { defaults.string(forKey: "display_name") ?? "" }
        set { defaults.set(newValue, forKey: "display_name") }
    }

    public var castConnect: Bool {
        get { defaults.bool(forKey: "cast_connect") }
        set { defaults.set(newValue, forKey: "cast_connect") }
    }

    /// Android defaults this to true; the Remote tab then shows for everyone until the preference is turned off.
    public var legacyRemote: Bool {
        get { defaults.object(forKey: "legacy_remote") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "legacy_remote") }
    }

    /// The first-run welcome tour was finished or skipped on this install.
    public var welcomeTourSeen: Bool {
        get { defaults.bool(forKey: "welcome_tour_seen") }
        set { defaults.set(newValue, forKey: "welcome_tour_seen") }
    }

    /// An optional public https address of this phone (advanced settings); shown to the person, not sent to the control plane (as on Android).
    public var tunnelUrl: String {
        get { defaults.string(forKey: "tunnel_url") ?? "" }
        set { defaults.set(newValue, forKey: "tunnel_url") }
    }

    public var apiBaseUrl: String {
        get { defaults.string(forKey: "api_base_url").flatMap { $0.isEmpty ? nil : $0 } ?? defaultApiBase }
        set { defaults.set(newValue, forKey: "api_base_url") }
    }

    public var wifiOnlyTransfers: Bool {
        get { defaults.object(forKey: "wifi_only_transfers") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "wifi_only_transfers") }
    }

    public var transferWhileCharging: Bool {
        get { defaults.bool(forKey: "transfer_while_charging") }
        set { defaults.set(newValue, forKey: "transfer_while_charging") }
    }

    public var syncLibraryToApi: Bool {
        get { defaults.object(forKey: "sync_library_to_api") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "sync_library_to_api") }
    }

    /// Where new imports go: `local`, or `both` (phone and cloud).
    public var importDestination: String {
        get { defaults.string(forKey: "import_destination") ?? storageLocal }
        set { defaults.set(newValue, forKey: "import_destination") }
    }

    /// Spec 003: serving relay streams over mobile data (default: ask).
    public var relayMobileData: RelayMobileDataPolicy {
        get { RelayMobileDataPolicy(rawValue: defaults.string(forKey: "relay_mobile_data") ?? "") ?? .ask }
        set { defaults.set(newValue.rawValue, forKey: "relay_mobile_data") }
    }

    /// "Allow this time": mobile-data relay is allowed until this time (seconds since 1970) or until the phone leaves mobile data.
    public var relayMobileAllowedUntil: TimeInterval {
        get { defaults.double(forKey: "relay_mobile_allowed_until") }
        set { defaults.set(newValue, forKey: "relay_mobile_allowed_until") }
    }

    /// Signing out or deleting the account: forget the credentials (preferences such as the tour stay).
    public func clearAccount() {
        accountToken = nil
        refreshToken = nil
        phoneDeviceId = nil
        displayName = ""
        clearTVs()
    }
}
