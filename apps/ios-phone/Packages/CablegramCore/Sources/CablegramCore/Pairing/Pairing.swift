import Foundation

/// How much the owner trusts a paired TV (spec 004 US8). A temporary TV (hotel, friend, rental) is unpaired at `expiresAt` and
/// holds no Telegram session unless `telegramDirect` was opted into.
public struct TvTrust: Sendable, Equatable {
    public var temporary: Bool
    public var expiresAt: Date?
    public var telegramDirect: Bool

    public init(temporary: Bool = false, expiresAt: Date? = nil, telegramDirect: Bool = false) {
        self.temporary = temporary
        self.expiresAt = expiresAt
        self.telegramDirect = telegramDirect
    }

    public static let home = TvTrust()
}

public enum TvTrustRules {
    /// Telegram will not let an account end a session created less than this long ago.
    public static let freshLink: TimeInterval = 24 * 3600
    public static let reminderBefore: TimeInterval = 3600
    public static let extendBy: TimeInterval = 24 * 3600

    /// "Checkout": the next 12:00 at least a few hours away, so tomorrow at 12:00 unless it is early morning.
    public static func defaultCheckout(now: Date, calendar: Calendar = .current) -> Date { checkoutAfterDays(now: now, days: 1, calendar: calendar) }

    public static func checkoutAfterDays(now: Date, days: Int, calendar: Calendar = .current) -> Date {
        let start = calendar.startOfDay(for: now)
        let day = calendar.date(byAdding: .day, value: days, to: start)!
        return calendar.date(bySettingHour: 12, minute: 0, second: 0, of: day)!
    }

    /// Choices offered next to the default, each at 12:00 local time (labels are English-only, like Android).
    public static func checkoutChoices(now: Date = Date(), calendar: Calendar = .current) -> [(label: String, date: Date)] {
        [("Tomorrow at 12:00", 1), ("In 2 days at 12:00", 2), ("In 3 days at 12:00", 3), ("In a week at 12:00", 7)]
            .map { ($0.0, checkoutAfterDays(now: now, days: $0.1, calendar: calendar)) }
    }

    /// False while the account's Telegram link is under 24 h old (`FRESH_RESET_AUTHORISATION_FORBIDDEN`).
    public static func directAllowed(linkedAt: Date?, now: Date) -> Bool {
        guard let linkedAt else { return false }
        return now.timeIntervalSince(linkedAt) >= freshLink
    }

    /// A reminder is due in the hour before the end time, once; expired TVs are already unpaired.
    public static func reminderDue(_ trust: TvTrust, now: Date) -> Bool {
        guard trust.temporary, let end = trust.expiresAt else { return false }
        return now < end && now >= end.addingTimeInterval(-reminderBefore)
    }
}

/// One paired TV from the phone's point of view. `capability` is the per-device LAN secret minted by the control plane at claim
/// time (R-1) and the only credential the LAN server accepts. `pin` is the 6-digit pairing code the phone claimed; it identifies
/// the TV locally and is never a LAN credential.
public struct PairedTv: Codable, Sendable, Equatable {
    public var pin: String
    public var name: String
    public var capability: String?
    public var deviceId: String?
    public var trust: TvTrust
    public var castDeviceId: String?

    public init(pin: String, name: String, capability: String? = nil, deviceId: String? = nil, trust: TvTrust = .home, castDeviceId: String? = nil) {
        self.pin = pin; self.name = name; self.capability = capability; self.deviceId = deviceId; self.trust = trust; self.castDeviceId = castDeviceId
    }

    // Stored as the Android record does: flat keys, empty strings for missing values.
    enum CodingKeys: String, CodingKey {
        case pin, name, capability, deviceId = "device_id", castDeviceId = "cast_device_id", temporary, expiresAt = "expires_at", telegramDirect = "telegram_direct"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func text(_ key: CodingKeys) throws -> String? { try c.decodeIfPresent(String.self, forKey: key).flatMap { $0.isEmpty ? nil : $0 } }
        pin = try c.decode(String.self, forKey: .pin)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? "TV"
        capability = try text(.capability)
        deviceId = try text(.deviceId)
        castDeviceId = try text(.castDeviceId)
        trust = TvTrust(temporary: try c.decodeIfPresent(Bool.self, forKey: .temporary) ?? false,
                        expiresAt: try text(.expiresAt).flatMap(parseISO8601),
                        telegramDirect: try c.decodeIfPresent(Bool.self, forKey: .telegramDirect) ?? false)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(pin, forKey: .pin)
        try c.encode(name, forKey: .name)
        try c.encode(capability ?? "", forKey: .capability)
        try c.encode(deviceId ?? "", forKey: .deviceId)
        try c.encode(castDeviceId ?? "", forKey: .castDeviceId)
        try c.encode(trust.temporary, forKey: .temporary)
        try c.encode(trust.expiresAt.map { ISO8601DateFormatter().string(from: $0) } ?? "", forKey: .expiresAt)
        try c.encode(trust.telegramDirect, forKey: .telegramDirect)
    }
}

/// An ISO-8601 instant with or without fractional seconds (`2026-10-02T10:00:00Z`, `…00.123Z`).
public func parseISO8601(_ text: String) -> Date? {
    let plain = ISO8601DateFormatter()
    if let date = plain.date(from: text) { return date }
    let fractional = ISO8601DateFormatter()
    fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return fractional.date(from: text)
}

/// A device of the account as `GET /api/me` lists it (Android `MeDevice`).
public struct MeDevice: Codable, Sendable, Equatable {
    public var id: String
    public var kind: String?
    public var displayName: String?
    public var revokedAt: String?
    public var trustLevel: String
    public var trustExpiresAt: String?
    public var telegramDirectAllowed: Bool
    /// A TV with Cablegram open that takes remote commands now; nil from a control plane that doesn't say.
    public var online: Bool?

    public init(id: String, kind: String? = nil, displayName: String? = nil, revokedAt: String? = nil, trustLevel: String = "home",
                trustExpiresAt: String? = nil, telegramDirectAllowed: Bool = false, online: Bool? = nil) {
        self.id = id; self.kind = kind; self.displayName = displayName; self.revokedAt = revokedAt; self.trustLevel = trustLevel
        self.trustExpiresAt = trustExpiresAt; self.telegramDirectAllowed = telegramDirectAllowed; self.online = online
    }

    enum CodingKeys: String, CodingKey {
        case id, kind, online
        case displayName = "display_name", revokedAt = "revoked_at", trustLevel = "trust_level", trustExpiresAt = "trust_expires_at"
        case telegramDirectAllowed = "telegram_direct_allowed"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        kind = try c.decodeIfPresent(String.self, forKey: .kind)
        displayName = try c.decodeIfPresent(String.self, forKey: .displayName)
        revokedAt = try c.decodeIfPresent(String.self, forKey: .revokedAt)
        trustLevel = try c.decodeIfPresent(String.self, forKey: .trustLevel) ?? "home"
        trustExpiresAt = try c.decodeIfPresent(String.self, forKey: .trustExpiresAt)
        telegramDirectAllowed = try c.decodeIfPresent(Bool.self, forKey: .telegramDirectAllowed) ?? false
        online = try c.decodeIfPresent(Bool.self, forKey: .online)
    }

    public var trust: TvTrust {
        TvTrust(temporary: trustLevel == "temporary",
                expiresAt: trustExpiresAt.flatMap(parseISO8601),
                telegramDirect: telegramDirectAllowed)
    }
}

// MARK: Pairing links

/// Where TVs' pairing QR codes point (CAB-62): the public web remote, and the one this build was made for.
public func pairWebOrigins(_ webAppBase: URL) -> Set<String> {
    ["https://app.cablegram.app", webAppBase.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))]
}

/// What a person typed or scanned: the 6-digit PIN, or a pairing link. Returns the PIN and the TV's name.
public func parsePairCode(_ input: String, webOrigins: Set<String> = ["https://app.cablegram.app"]) -> (pin: String, name: String)? {
    let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.range(of: #"^\d{6}$"#, options: .regularExpression) != nil { return (trimmed, "TV") }
    return parsePairUri(trimmed, webOrigins: webOrigins)
}

private func queryParameters(_ rawQuery: String?) -> [String: String] {
    var result: [String: String] = [:]
    for part in (rawQuery ?? "").split(separator: "&", omittingEmptySubsequences: true) {
        guard let eq = part.firstIndex(of: "="), eq != part.startIndex else { continue }
        let key = String(part[..<eq])
        // `+` is a space in a query string, like Java's URLDecoder.
        let value = String(part[part.index(after: eq)...]).replacingOccurrences(of: "+", with: " ")
        guard let decoded = value.removingPercentEncoding else { return [:] }
        result[key] = decoded
    }
    return result
}

/// A TV's pairing link: `https://app.cablegram.app/?pair=<PIN>&tv=<name>` (CAB-62) from the QR code since spec 007, or the older
/// `cablegram://pair?token=<PIN>&name=<name>`. Returns the PIN and the TV's name.
public func parsePairUri(_ uri: String, webOrigins: Set<String> = ["https://app.cablegram.app"]) -> (pin: String, name: String)? {
    if let web = parsePairWebLink(uri, webOrigins: webOrigins) { return web }
    // A malformed escape (`%ZZ`) must not sneak through: Foundation would re-encode it, so the text is checked first.
    let cleaned = uri.trimmingCharacters(in: .whitespacesAndNewlines)
    if cleaned.range(of: #"%(?![0-9A-Fa-f]{2})"#, options: .regularExpression) != nil { return nil }
    guard let components = URLComponents(string: cleaned),
          components.scheme == "cablegram", components.host == "pair", components.user == nil, components.path.isEmpty, components.fragment == nil else { return nil }
    // `%ZZ` and the like must not crash or sneak through: an undecodable query is refused.
    let raw = components.percentEncodedQuery
    if let raw, raw.removingPercentEncoding == nil { return nil }
    let params = queryParameters(raw)
    guard let token = params["token"], !token.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
    let name = params["name"].flatMap { $0.isEmpty ? nil : $0 } ?? "TV"
    return (token, name)
}

private func parsePairWebLink(_ uri: String, webOrigins: Set<String>) -> (pin: String, name: String)? {
    guard let components = URLComponents(string: uri.trimmingCharacters(in: .whitespacesAndNewlines)), let scheme = components.scheme, let host = components.host else { return nil }
    let origin = "\(scheme)://\(host)" + (components.port.map { ":\($0)" } ?? "")
    guard webOrigins.contains(origin), components.user == nil, components.password == nil, components.fragment == nil else { return nil }
    guard components.path.isEmpty || components.path == "/" else { return nil }
    let params = queryParameters(components.percentEncodedQuery)
    guard let pin = params["pair"], pin.range(of: #"^\d{6}$"#, options: .regularExpression) != nil else { return nil }
    let name = String((params["tv"]?.trimmingCharacters(in: .whitespaces) ?? "").prefix(80))
    return (pin, name.isEmpty ? "TV" : name)
}

public func isValidServerUrl(_ value: String) -> Bool {
    guard let url = URL(string: value.trimmingCharacters(in: .whitespaces)), let scheme = url.scheme, ["http", "https"].contains(scheme),
          let host = url.host, !host.isEmpty, url.user == nil, url.query == nil, url.fragment == nil else { return false }
    if let port = url.port { return (1...65535).contains(port) }
    return true
}
