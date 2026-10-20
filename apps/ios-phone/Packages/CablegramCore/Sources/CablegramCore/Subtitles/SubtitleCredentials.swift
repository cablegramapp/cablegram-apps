import CryptoKit
import Foundation

/// Never stringify credentials.
public struct PersonalSubtitleCredentials: Codable, Sendable, Equatable, CustomStringConvertible, CustomDebugStringConvertible {
    public var key: String
    public var username: String
    public var password: String
    public init(key: String, username: String = "", password: String = "") { self.key = key; self.username = username; self.password = password }
    public var description: String { "PersonalSubtitleCredentials(redacted)" }
    public var debugDescription: String { description }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decode(String.self, forKey: .key)
        username = try c.decodeIfPresent(String.self, forKey: .username) ?? ""
        password = try c.decodeIfPresent(String.self, forKey: .password) ?? ""
    }
}

/// The person's own SubDL / OpenSubtitles details, in the Keychain (this device only), one item per account and provider.
public final class SubtitleCredentialStore: @unchecked Sendable {
    public static let providers: Set<String> = ["subdl", "opensubtitles"]
    private let secrets: any SecretStoring
    private let lock = NSLock()

    public init(secrets: any SecretStoring) { self.secrets = secrets }

    private func name(_ user: String, _ provider: String) -> String {
        "cablegram.subtitles." + SHA256.hash(data: Data("\(user):\(provider)".utf8)).map { String(format: "%02x", $0) }.joined()
    }

    public func read(user: String, provider: String) -> PersonalSubtitleCredentials? {
        lock.withLock {
            let item = name(user, provider)
            guard let text = secrets.read(item) else { return nil }
            guard text.utf8.count >= 2, text.utf8.count <= 16384, let value = try? JSONDecoder().decode(PersonalSubtitleCredentials.self, from: Data(text.utf8)) else {
                secrets.write(item, nil)   // Unreadable: forget it rather than keep failing.
                return nil
            }
            return value
        }
    }

    public func save(user: String, provider: String, credentials: PersonalSubtitleCredentials) throws {
        guard Self.providers.contains(provider),
              !credentials.key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, credentials.key.count <= 4096,
              credentials.key.unicodeScalars.allSatisfy({ (33...126).contains($0.value) }),
              credentials.username.count <= 1024, credentials.password.count <= 4096,
              provider != "opensubtitles" || (!credentials.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !credentials.password.isEmpty)
        else { throw LocalSubtitleError("invalid_credentials") }
        let data = try JSONEncoder().encode(credentials)
        lock.withLock { secrets.write(name(user, provider), String(decoding: data, as: UTF8.self)) }
    }

    public func remove(user: String, provider: String) { lock.withLock { secrets.write(name(user, provider), nil) } }

    public func clear(user: String) { Self.providers.forEach { remove(user: user, provider: $0) } }
}
