import Foundation
import Security

/// The account credentials a `CatalogClient` reads and renews.
public protocol AccountTokens: AnyObject, Sendable {
    var accountToken: String? { get set }
    /// Rotated by the server on every renewal; the previous value stops working.
    var refreshToken: String? { get set }
}

/// Secrets at rest. The iOS counterpart of Android `SecretStore`: the Keychain, never UserDefaults.
public protocol SecretStoring: AnyObject, Sendable {
    func read(_ key: String) -> String?
    func write(_ key: String, _ value: String?)
}

public final class InMemorySecretStore: SecretStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: String] = [:]
    public init() {}
    public func read(_ key: String) -> String? { lock.withLock { values[key] } }
    public func write(_ key: String, _ value: String?) { lock.withLock { values[key] = value } }
}

/// Generic-password Keychain items, readable after the first unlock and never leaving this device.
public final class KeychainSecretStore: SecretStoring, @unchecked Sendable {
    private let service: String
    private let accessGroup: String?

    public init(service: String = "app.cablegram.phone", accessGroup: String? = nil) {
        self.service = service
        self.accessGroup = accessGroup
    }

    private func query(_ key: String) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        if let accessGroup { q[kSecAttrAccessGroup as String] = accessGroup }
        return q
    }

    public func read(_ key: String) -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public func write(_ key: String, _ value: String?) {
        SecItemDelete(query(key) as CFDictionary)
        guard let value, !value.isEmpty else { return }
        var q = query(key)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }
}
