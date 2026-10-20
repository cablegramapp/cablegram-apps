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

    public var apiBaseUrl: String {
        get { defaults.string(forKey: "api_base_url").flatMap { $0.isEmpty ? nil : $0 } ?? defaultApiBase }
        set { defaults.set(newValue, forKey: "api_base_url") }
    }

    /// Signing out or deleting the account: forget the credentials (preferences such as the tour stay).
    public func clearAccount() {
        accountToken = nil
        refreshToken = nil
        phoneDeviceId = nil
        displayName = ""
    }
}
