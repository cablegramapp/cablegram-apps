import Foundation

/// How this server lets people in (spec 007). `google` is nil when the server has no Google client (self-hosted).
public struct SignInMethods: Codable, Sendable, Equatable {
    public var google: GoogleMethod?
    public var emailCode: Bool

    public init(google: GoogleMethod? = nil, emailCode: Bool = true) {
        self.google = google
        self.emailCode = emailCode
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        google = try c.decodeIfPresent(GoogleMethod.self, forKey: .google)
        emailCode = try c.decodeIfPresent(Bool.self, forKey: .emailCode) ?? true
    }

    enum CodingKeys: String, CodingKey { case google, emailCode = "email_code" }
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

    public init(
        token: String, refreshToken: String? = nil, userId: String? = nil, displayName: String? = nil,
        needsName: Bool = false, householdId: String? = nil, tvName: String = "TV",
        lanCapability: String? = nil, tvDeviceId: String? = nil
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
    }
}

struct AuthTokenResponse: Decodable {
    var accessToken: String
    var refreshToken: String?
    var userId: String?
    var householdId: String?
    var displayName: String?

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token", refreshToken = "refresh_token", userId = "user_id"
        case householdId = "household_id", displayName = "display_name"
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
