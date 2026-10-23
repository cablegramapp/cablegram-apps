import Foundation

/// How this server lets people in (spec 007). `google` is nil when the server has no Google client (self-hosted); `apple` is nil
/// until the server is set up for Sign in with Apple (iPhone only).
public struct SignInMethods: Codable, Sendable, Equatable {
    public var google: GoogleMethod?
    public var apple: AppleMethod?
    public var emailCode: Bool

    public init(google: GoogleMethod? = nil, apple: AppleMethod? = nil, emailCode: Bool = true) {
        self.google = google
        self.apple = apple
        self.emailCode = emailCode
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        google = try c.decodeIfPresent(GoogleMethod.self, forKey: .google)
        apple = try c.decodeIfPresent(AppleMethod.self, forKey: .apple)
        emailCode = try c.decodeIfPresent(Bool.self, forKey: .emailCode) ?? true
    }

    enum CodingKeys: String, CodingKey { case google, apple, emailCode = "email_code" }
}

public struct AppleMethod: Codable, Sendable, Equatable {
    public var clientId: String
    public init(clientId: String) { self.clientId = clientId }
    enum CodingKeys: String, CodingKey { case clientId = "client_id" }
}

public struct GoogleMethod: Codable, Sendable, Equatable {
    public var clientId: String
    public init(clientId: String) { self.clientId = clientId }
    enum CodingKeys: String, CodingKey { case clientId = "client_id" }
}

/// The current Terms of Service and Privacy Policy, as `GET /api/legal` reports them.
public struct LegalDocuments: Codable, Sendable, Equatable {
    public var terms: LegalDocument
    public var privacy: LegalDocument

    public init(terms: LegalDocument, privacy: LegalDocument) {
        self.terms = terms
        self.privacy = privacy
    }

    /// Links that still work when the server can't be reached; the version is unknown, so nothing can be accepted.
    public static let fallback = LegalDocuments(
        terms: LegalDocument(version: "", url: "https://cablegram.app/terms.html"),
        privacy: LegalDocument(version: "", url: "https://cablegram.app/privacy.html")
    )
}

public struct LegalDocument: Codable, Sendable, Equatable {
    public var version: String
    public var url: String
    public init(version: String, url: String) {
        self.version = version
        self.url = url
    }
}

public struct ServerFeatures: Codable, Sendable, Equatable {
    public var artwork: Bool
    public var aiTitleResolution: Bool
    public var subtitles: Bool
    public var relay: Bool
    public var webImport: Bool
    public var cloudStorage: Bool
    public var telegram: Bool
    public var lanPlayback: Bool
    public var profileLock: Bool
    public var deleteAccount: Bool

    public init(
        artwork: Bool = false,
        aiTitleResolution: Bool = false,
        subtitles: Bool = false,
        relay: Bool = false,
        webImport: Bool = false,
        cloudStorage: Bool = false,
        telegram: Bool = true,
        lanPlayback: Bool = true,
        profileLock: Bool = true,
        deleteAccount: Bool = true
    ) {
        self.artwork = artwork
        self.aiTitleResolution = aiTitleResolution
        self.subtitles = subtitles
        self.relay = relay
        self.webImport = webImport
        self.cloudStorage = cloudStorage
        self.telegram = telegram
        self.lanPlayback = lanPlayback
        self.profileLock = profileLock
        self.deleteAccount = deleteAccount
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        artwork = try c.decodeIfPresent(Bool.self, forKey: .artwork) ?? false
        aiTitleResolution = try c.decodeIfPresent(Bool.self, forKey: .aiTitleResolution) ?? false
        subtitles = try c.decodeIfPresent(Bool.self, forKey: .subtitles) ?? false
        relay = try c.decodeIfPresent(Bool.self, forKey: .relay) ?? false
        webImport = try c.decodeIfPresent(Bool.self, forKey: .webImport) ?? false
        cloudStorage = try c.decodeIfPresent(Bool.self, forKey: .cloudStorage) ?? false
        telegram = try c.decodeIfPresent(Bool.self, forKey: .telegram) ?? true
        lanPlayback = try c.decodeIfPresent(Bool.self, forKey: .lanPlayback) ?? true
        profileLock = try c.decodeIfPresent(Bool.self, forKey: .profileLock) ?? true
        deleteAccount = try c.decodeIfPresent(Bool.self, forKey: .deleteAccount) ?? true
    }

    enum CodingKeys: String, CodingKey {
        case artwork
        case aiTitleResolution = "ai_title_resolution"
        case subtitles
        case relay
        case webImport = "web_import"
        case cloudStorage = "cloud_storage"
        case telegram
        case lanPlayback = "lan_playback"
        case profileLock = "profile_lock"
        case deleteAccount = "delete_account"
    }

    public static let hostedDefault = ServerFeatures(
        artwork: true,
        aiTitleResolution: true,
        subtitles: true,
        relay: true,
        webImport: true,
        cloudStorage: true,
        telegram: true,
        lanPlayback: true,
        profileLock: true,
        deleteAccount: true
    )
}

public struct ServerAuthConfig: Codable, Sendable, Equatable {
    public var mode: String
    public var google: GoogleMethod?
    public var apple: AppleMethod?
    public var emailCode: Bool
    public var setupCode: Bool
    public var deviceCode: Bool

    public init(
        mode: String = "account",
        google: GoogleMethod? = nil,
        apple: AppleMethod? = nil,
        emailCode: Bool = true,
        setupCode: Bool = false,
        deviceCode: Bool = false
    ) {
        self.mode = mode
        self.google = google
        self.apple = apple
        self.emailCode = emailCode
        self.setupCode = setupCode
        self.deviceCode = deviceCode
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        mode = try c.decodeIfPresent(String.self, forKey: .mode) ?? "account"
        google = try c.decodeIfPresent(GoogleMethod.self, forKey: .google)
        apple = try c.decodeIfPresent(AppleMethod.self, forKey: .apple)
        emailCode = try c.decodeIfPresent(Bool.self, forKey: .emailCode) ?? true
        setupCode = try c.decodeIfPresent(Bool.self, forKey: .setupCode) ?? false
        deviceCode = try c.decodeIfPresent(Bool.self, forKey: .deviceCode) ?? false
    }

    enum CodingKeys: String, CodingKey {
        case mode
        case google
        case apple
        case emailCode = "email_code"
        case setupCode = "setup_code"
        case deviceCode = "device_code"
    }
}

public struct ServerCapabilities: Codable, Sendable, Equatable {
    public var apiVersion: Int
    public var edition: String
    public var serverVersion: String
    public var auth: ServerAuthConfig
    public var features: ServerFeatures
    public var legal: LegalDocuments?

    public init(
        apiVersion: Int = 1,
        edition: String = "hosted",
        serverVersion: String = "0.1.0",
        auth: ServerAuthConfig = ServerAuthConfig(),
        features: ServerFeatures = .hostedDefault,
        legal: LegalDocuments? = nil
    ) {
        self.apiVersion = apiVersion
        self.edition = edition
        self.serverVersion = serverVersion
        self.auth = auth
        self.features = features
        self.legal = legal
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        apiVersion = try c.decodeIfPresent(Int.self, forKey: .apiVersion) ?? 1
        edition = try c.decodeIfPresent(String.self, forKey: .edition) ?? "hosted"
        serverVersion = try c.decodeIfPresent(String.self, forKey: .serverVersion) ?? "0.1.0"
        auth = try c.decodeIfPresent(ServerAuthConfig.self, forKey: .auth) ?? ServerAuthConfig()
        features = try c.decodeIfPresent(ServerFeatures.self, forKey: .features) ?? .hostedDefault
        legal = try c.decodeIfPresent(LegalDocuments.self, forKey: .legal)
    }

    enum CodingKeys: String, CodingKey {
        case apiVersion = "api_version"
        case edition
        case serverVersion = "server_version"
        case auth
        case features
        case legal
    }

    public static let hostedDefault = ServerCapabilities()
}

public struct JoinRequestCreated: Codable, Sendable, Equatable {
    public var id: String
    public var shortCode: String
    public var pollSecret: String
    public var expiresAt: String

    public init(id: String, shortCode: String, pollSecret: String, expiresAt: String = "") {
        self.id = id
        self.shortCode = shortCode
        self.pollSecret = pollSecret
        self.expiresAt = expiresAt
    }

    enum CodingKeys: String, CodingKey {
        case id
        case shortCode = "short_code"
        case pollSecret = "poll_secret"
        case expiresAt = "expires_at"
    }
}

public struct JoinRequestPollResponse: Codable, Sendable, Equatable {
    public var id: String
    public var status: String
    public var accessToken: String?
    public var refreshToken: String?
    public var userId: String?
    public var householdId: String?
    public var deviceId: String?
    public var displayName: String?

    public init(
        id: String,
        status: String,
        accessToken: String? = nil,
        refreshToken: String? = nil,
        userId: String? = nil,
        householdId: String? = nil,
        deviceId: String? = nil,
        displayName: String? = nil
    ) {
        self.id = id
        self.status = status
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.userId = userId
        self.householdId = householdId
        self.deviceId = deviceId
        self.displayName = displayName
    }

    enum CodingKeys: String, CodingKey {
        case id
        case status
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case userId = "user_id"
        case householdId = "household_id"
        case deviceId = "device_id"
        case displayName = "display_name"
    }
}

public struct PendingJoinRequest: Codable, Sendable, Equatable {
    public var id: String
    public var deviceName: String
    public var expiresAt: String?
    public var createdAt: String?

    public init(id: String, deviceName: String = "Phone", expiresAt: String? = nil, createdAt: String? = nil) {
        self.id = id
        self.deviceName = deviceName
        self.expiresAt = expiresAt
        self.createdAt = createdAt
    }

    enum CodingKeys: String, CodingKey {
        case id
        case deviceName = "device_name"
        case expiresAt = "expires_at"
        case createdAt = "created_at"
    }

    public func asApproval() -> PendingApproval {
        PendingApproval(
            attemptId: id,
            mediaItemId: nil,
            title: deviceName,
            expiresAt: expiresAt,
            tvName: deviceName,
            sharedTv: false,
            kind: .phoneJoin
        )
    }
}

public struct PendingJoinRequests: Codable, Sendable, Equatable {
    public var requests: [PendingJoinRequest]
    public init(requests: [PendingJoinRequest] = []) {
        self.requests = requests
    }
}

public func isPrivateLanHost(_ host: String) -> Bool {
    let lower = host.lowercased()
    if lower == "localhost" || lower == "127.0.0.1" || lower == "::1" { return true }
    let parts = lower.split(separator: ".")
    if parts.count == 4, let p0 = Int(parts[0]), let p1 = Int(parts[1]) {
        if p0 == 10 { return true }
        if p0 == 172 && (16...31).contains(p1) { return true }
        if p0 == 192 && p1 == 168 { return true }
    }
    return false
}

public func isCleartextLanServerUrl(_ urlString: String) -> Bool {
    guard let url = URL(string: urlString), url.scheme?.lowercased() == "http", let host = url.host else {
        return false
    }
    return isPrivateLanHost(host)
}

public func serverOrigin(_ urlString: String) -> String {
    let trimmed = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(), let host = url.host else {
        return trimmed
    }
    let port = url.port.map { ":\($0)" } ?? ""
    return "\(scheme)://\(host)\(port)"
}

public func normalizeServerUrl(_ raw: String) -> String? {
    var trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty { return nil }
    if !trimmed.contains("://") {
        let firstPart = trimmed.split(separator: ":").first.map(String.init) ?? trimmed
        trimmed = (isPrivateLanHost(firstPart) ? "http://" : "https://") + trimmed
    }
    guard let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https", let host = url.host else {
        return nil
    }
    let port = url.port.map { ":\($0)" } ?? ""
    return "\(scheme)://\(host)\(port)"
}

/// What the server returned for a signed-in person (Android `PhonePairResponse`, account fields only for now).
public struct PhonePairResponse: Sendable, Equatable {
    public var token: String
    public var refreshToken: String?
    public var userId: String?
    public var displayName: String?
    public var needsName: Bool
    public var householdId: String?
    public var tvName: String
    public var lanCapability: String?
    public var tvDeviceId: String?
    public var deviceId: String?
    public var deviceSecret: String?

    public init(
        token: String, refreshToken: String? = nil, userId: String? = nil, displayName: String? = nil,
        needsName: Bool = false, householdId: String? = nil, tvName: String = "TV",
        lanCapability: String? = nil, tvDeviceId: String? = nil,
        deviceId: String? = nil, deviceSecret: String? = nil
    ) {
        self.token = token
        self.refreshToken = refreshToken
        self.userId = userId
        self.displayName = displayName
        self.needsName = needsName
        self.householdId = householdId
        self.tvName = tvName
        self.lanCapability = lanCapability
        self.tvDeviceId = tvDeviceId
        self.deviceId = deviceId
        self.deviceSecret = deviceSecret
    }
}

struct AuthTokenResponse: Decodable {
    var accessToken: String
    var refreshToken: String?
    var userId: String?
    var householdId: String?
    var displayName: String?
    var deviceId: String?
    var deviceSecret: String?

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token", refreshToken = "refresh_token", userId = "user_id"
        case householdId = "household_id", displayName = "display_name"
        case deviceId = "device_id", deviceSecret = "device_secret"
    }
}

public struct PhoneDeviceResponse: Codable, Sendable, Equatable {
    public var deviceId: String
    public var deviceSecret: String?
    enum CodingKeys: String, CodingKey { case deviceId = "device_id", deviceSecret = "device_secret" }
}

public enum PhoneDeviceStatus: Sendable { case registered, missing, unknown }

/// What a sign-in step returned: an account, or a new person who must first create one.
public enum SignInResult: Sendable, Equatable {
    case signedIn(PhonePairResponse)
    /// The email is proven (by code or Google) but has no account: show Create your account.
    case signupRequired(ticket: String, email: String, name: String?)
    /// `status` nil: no answer (offline). `error` is the server's error code, e.g. `invalid_code`.
    case failed(SignInFailure)
}

public struct SignInFailure: Sendable, Equatable, Error {
    public var status: Int?
    public var error: String?
    public init(status: Int?, error: String?) {
        self.status = status
        self.error = error
    }

    /// The String Catalog key of a short, specific sentence for this failure (same wording as Android).
    public var messageKey: String {
        switch error {
        case "invalid_code": "ui_that_code_isn_t_right_check_the_email_and_try_again_38ed4a"
        case "code_expired": "ui_that_code_expired_or_was_tried_too_often_ask_for_a_new_0aa93e"
        case "code_rate_limited": "ui_a_code_was_sent_a_moment_ago_wait_a_minute_before_aski_cc929d"
        case "rate_limited": "ui_too_many_tries_wait_a_while_and_try_again_114288"
        case "mail_unavailable": "ui_we_couldn_t_send_the_email_try_again_in_a_few_minutes_fb5f5f"
        case "disposable_email": "ui_temporary_email_addresses_can_t_be_used_use_an_email_a_5c8d8f"
        case "invalid_google_token": "ui_google_sign_in_didn_t_work_try_again_78c785"
        case "google_email_unverified": "ui_your_google_account_s_email_isn_t_verified_sign_in_wit_c385f8"
        case "google_account_mismatch": "ui_this_email_is_already_linked_to_another_google_account_05cce9"
        case "google_not_configured": "ui_google_sign_in_isn_t_available_on_this_server_abf693"
        case "invalid_apple_token": "ios_apple_sign_in_failed"
        case "apple_email_missing": "ios_apple_email_missing"
        case "apple_email_unverified": "ios_apple_email_unverified"
        case "apple_account_mismatch": "ios_apple_account_mismatch"
        case "apple_not_configured": "ios_apple_not_configured"
        case "terms_not_accepted": "ui_tick_the_box_to_accept_the_terms_first_5e5035"
        case "terms_outdated": "ui_the_terms_just_changed_please_read_them_again_648707"
        default:
            switch status {
            case nil: "ui_can_t_reach_cablegram_check_your_connection_or_the_api_e3f77a"
            case 400: "ui_check_the_email_address_and_try_again_5e6910"
            default: "ui_something_went_wrong_try_again_in_a_moment_156a12"
            }
        }
    }
}

public enum AccountDeletionResult: Sendable { case deleted, wrongCode, rateLimited, sessionExpired, offline, failed }
