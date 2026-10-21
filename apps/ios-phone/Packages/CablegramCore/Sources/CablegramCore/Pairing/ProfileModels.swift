import Foundation

/// A profile of the household (the account owner first), as `GET /api/profiles` lists them.
public struct HouseholdProfile: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var sortOrder: Int
    public var pinSet: Bool
    public var uiLanguage: String
    /// The language this profile chose for itself; nil follows the phone.
    public var uiLanguageOverride: String?

    public init(id: String, name: String, sortOrder: Int = 0, pinSet: Bool = false, uiLanguage: String = "en", uiLanguageOverride: String? = nil) {
        self.id = id; self.name = name; self.sortOrder = sortOrder; self.pinSet = pinSet; self.uiLanguage = uiLanguage; self.uiLanguageOverride = uiLanguageOverride
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        sortOrder = try c.decodeIfPresent(Int.self, forKey: .sortOrder) ?? 0
        pinSet = try c.decodeIfPresent(Bool.self, forKey: .pinSet) ?? false
        uiLanguage = try c.decodeIfPresent(String.self, forKey: .uiLanguage) ?? "en"
        uiLanguageOverride = try c.decodeIfPresent(String.self, forKey: .uiLanguageOverride)
    }

    enum CodingKeys: String, CodingKey {
        case id, name, sortOrder = "sort_order", pinSet = "pin_set", uiLanguage = "ui_language", uiLanguageOverride = "ui_language_override"
    }
}

/// `GET /api/relay/usage` (spec 003 US4).
public struct RelayUsage: Codable, Sendable, Equatable {
    public var plan: String
    public var quotaBytes: Int64?
    public var usedBytes: Int64
    public var remainingBytes: Int64?
    public var limitedReason: String?
    public var resetsAt: String?

    public init(plan: String = "free", quotaBytes: Int64? = nil, usedBytes: Int64 = 0, remainingBytes: Int64? = nil, limitedReason: String? = nil, resetsAt: String? = nil) {
        self.plan = plan; self.quotaBytes = quotaBytes; self.usedBytes = usedBytes; self.remainingBytes = remainingBytes; self.limitedReason = limitedReason; self.resetsAt = resetsAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        plan = try c.decodeIfPresent(String.self, forKey: .plan) ?? "free"
        quotaBytes = try c.decodeIfPresent(Int64.self, forKey: .quotaBytes)
        usedBytes = try c.decodeIfPresent(Int64.self, forKey: .usedBytes) ?? 0
        remainingBytes = try c.decodeIfPresent(Int64.self, forKey: .remainingBytes)
        limitedReason = try c.decodeIfPresent(String.self, forKey: .limitedReason)
        resetsAt = try c.decodeIfPresent(String.self, forKey: .resetsAt)
    }

    enum CodingKeys: String, CodingKey {
        case plan, quotaBytes = "quota_bytes", usedBytes = "used_bytes", remainingBytes = "remaining_bytes", limitedReason = "limited_reason", resetsAt = "resets_at"
    }
}

/// The languages the app and its TVs ship translations for (Android `R.array.ui_languages`).
public let supportedUiLanguages = ["en", "de", "ar", "fa", "ru", "es", "it"]

private func languageCode(_ tag: String) -> String {
    String(tag.split(whereSeparator: { $0 == "-" || $0 == "_" }).first ?? "").lowercased()
}

/// The first of the person's preferred languages the app is translated into, else English.
public func phoneUiLanguage(preferred: [String], supported: Set<String> = Set(supportedUiLanguages)) -> String {
    preferred.map(languageCode).first { supported.contains($0) } ?? "en"
}

/// The tag reported to the server: the device preference this app shows, with its region kept, or the first preference when none is bundled,
/// so TVs that ship more translations than this phone can use it.
public func phoneLanguageTag(preferred: [String], supported: Set<String> = Set(supportedUiLanguages)) -> String {
    preferred.first { supported.contains(languageCode($0)) } ?? preferred.first { !languageCode($0).isEmpty } ?? "en"
}
