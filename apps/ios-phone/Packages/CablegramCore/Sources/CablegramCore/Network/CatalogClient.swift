import Foundation

/// Serialises token renewal so concurrent 401s renew only once (Android `AccountAuthenticator.lock`). An actor alone is not
/// enough: it is reentrant, so a second caller would enter while the first waits on the network and refresh with a refresh
/// token the server has already rotated. Callers arriving during a renewal wait for that same renewal.
private actor RenewalGate {
    static let shared = RenewalGate()
    private var inFlight: Task<String?, Never>?

    func renew(after sent: String, current: @Sendable () -> String?, _ body: @escaping @Sendable () async -> String?) async -> String? {
        // Another request may already have renewed the token while this one was in flight.
        if let now = current(), !now.isEmpty, now != sent { return now }
        if let running = inFlight { return await running.value }
        let task = Task { await body() }
        inFlight = task
        let result = await task.value
        inFlight = nil
        return result
    }
}

/// Port of `CatalogClient.kt`, in the same order. Account and sign-in endpoints come first; the rest of the Android
/// client is added with the phase that needs it. Field names follow `contracts/*.md` and `control-api.yaml`.
public struct CatalogClient: Sendable {
    public let baseURL: URL
    /// Renews the two-hour access token on 401; nil only for anonymous calls.
    public let tokens: (any AccountTokens)?
    let session: URLSession

    public init(baseURL: URL = AppConfiguration.defaultApiBase, tokens: (any AccountTokens)? = nil, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.tokens = tokens
        self.session = session
    }

    // MARK: Transport

    private func url(_ path: String) -> URL {
        URL(string: baseURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path)!
    }

    private func build(_ method: String, _ path: String, token: String?, json: [String: Any]?) -> URLRequest {
        var request = URLRequest(url: url(path), timeoutInterval: 20)
        request.httpMethod = method
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let json {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try? JSONSerialization.data(withJSONObject: json)
        }
        return request
    }

    private func perform(_ request: URLRequest) async -> APIReply {
        do {
            let (data, response) = try await session.data(for: request)
            return APIReply(status: (response as? HTTPURLResponse)?.statusCode, data: data)
        } catch {
            return APIReply(status: nil)
        }
    }

    /// Sends the request; when a Bearer token is rejected with 401 and `renew` is on, renews it once and retries.
    func send(_ method: String, _ path: String, token: String? = nil, json: [String: Any]? = nil, renew: Bool = true) async -> APIReply {
        let first = await perform(build(method, path, token: token, json: json))
        guard renew, first.status == 401, let sent = token, tokens != nil else { return first }
        guard let fresh = await freshToken(after: sent) else { return first }
        return await perform(build(method, path, token: fresh, json: json))
    }

    /// Another request may already have renewed the token while this one was in flight.
    private func freshToken(after sent: String) async -> String? {
        guard let tokens else { return nil }
        return await RenewalGate.shared.renew(after: sent, current: { tokens.accountToken }) { await renewAccessToken() }
    }

    /// Exchanges the refresh token for a new access token. A rejected refresh token clears the dead access token so
    /// the app asks the person to sign in again instead of failing quietly.
    private func renewAccessToken() async -> String? {
        guard let tokens else { return nil }
        guard let refresh = tokens.refreshToken, !refresh.isEmpty else {
            tokens.accountToken = nil
            return nil
        }
        let reply = await perform(build("POST", "/api/auth/refresh", token: nil, json: ["refresh_token": refresh]))
        if reply.status == 401 {
            tokens.accountToken = nil
            return nil
        }
        guard reply.isSuccess, let body = reply.object else { return nil }
        // Store the rotated refresh token before the access token: the old one is already revoked server-side, so
        // losing the new one would sign the person out.
        if let rotated = body["refresh_token"] as? String, !rotated.isEmpty { tokens.refreshToken = rotated }
        if let access = body["access_token"] as? String, !access.isEmpty {
            tokens.accountToken = access
            return access
        }
        return nil
    }

    // MARK: Sign-in (spec 007): Google or an email code, no passwords

    /// How this server lets people in; nil when it can't be reached.
    public func signInMethods() async -> SignInMethods? {
        let reply = await send("GET", "/api/auth/methods")
        return reply.isSuccess ? reply.decode() : nil
    }

    /// The current Terms and Privacy Policy; nil when the server can't be reached.
    public func legalDocuments() async -> LegalDocuments? {
        let reply = await send("GET", "/api/legal")
        return reply.isSuccess ? reply.decode() : nil
    }

    /// Mails a sign-in code. The server answers the same whether or not the email has an account. Nil: sent.
    /// `forDeletion` makes the email say it confirms deleting the account instead of signing in.
    public func requestSignInCode(email: String, forDeletion: Bool = false) async -> SignInFailure? {
        var body: [String: Any] = ["email": email]
        if forDeletion { body["purpose"] = "delete_account" }
        let reply = await send("POST", "/api/auth/email-code/request", json: body)
        return reply.status == 202 ? nil : SignInFailure(status: reply.status, error: reply.errorCode)
    }

    public func verifySignInCode(email: String, code: String) async -> SignInResult {
        await signInStep("/api/auth/email-code/verify", ["email": email, "code": code])
    }

    /// Signs in with a Google ID token; `nonce` is the one this sign-in asked Google to put in it.
    public func googleSignIn(idToken: String, nonce: String) async -> SignInResult {
        await signInStep("/api/auth/google", ["id_token": idToken, "nonce": nonce])
    }

    /// Signs in with an Apple identity token. `nonce` is the raw one this sign-in made (Apple's token carries its SHA-256); `name` is what Apple
    /// gave the app on the first authorization (Apple never repeats it); `authorizationCode` lets the server revoke the token at account deletion.
    public func appleSignIn(idToken: String, nonce: String, authorizationCode: String?, name: String?) async -> SignInResult {
        var body: [String: Any] = ["id_token": idToken, "nonce": nonce]
        if let authorizationCode, !authorizationCode.isEmpty { body["authorization_code"] = authorizationCode }
        if let name, !name.isEmpty { body["name"] = name }
        return await signInStep("/api/auth/apple", body)
    }

    /// Create your account: the person accepted these Terms and Privacy Policy versions and is 18 or older.
    public func signUp(ticket: String, name: String, legal: LegalDocuments) async -> SignInResult {
        await signInStep("/api/auth/signup", [
            "signup_ticket": ticket,
            "name": name,
            "accepted": ["terms_version": legal.terms.version, "privacy_version": legal.privacy.version],
            "age_confirmed": true,
        ])
    }

    /// Accepts new Terms (spec 007 US4); nil on success, otherwise the failure.
    public func acceptTerms(token: String, legal: LegalDocuments) async -> SignInFailure? {
        let reply = await send("POST", "/api/auth/legal/accept", token: token,
                               json: ["terms_version": legal.terms.version, "privacy_version": legal.privacy.version])
        return reply.status == 204 ? nil : SignInFailure(status: reply.status, error: reply.errorCode)
    }

    /// Whether the account must accept new Terms before going on; nil when offline or on error.
    public func termsAcceptanceRequired(token: String) async -> Bool? {
        let reply = await send("GET", "/api/me", token: token)
        guard reply.isSuccess else { return nil }
        let legal = reply.object?["legal"] as? [String: Any]
        return legal?["acceptance_required"] as? Bool ?? false
    }

    private func signInStep(_ path: String, _ body: [String: Any]) async -> SignInResult {
        let reply = await send("POST", path, json: body)
        guard reply.isSuccess, let object = reply.object else {
            return .failed(SignInFailure(status: reply.status, error: reply.errorCode))
        }
        if object["status"] as? String == "signup_required" {
            guard let ticket = object["signup_ticket"] as? String else {
                return .failed(SignInFailure(status: reply.status, error: "invalid_response"))
            }
            return .signupRequired(ticket: ticket, email: object["email"] as? String ?? "", name: object["name"] as? String)
        }
        guard let parsed: AuthTokenResponse = reply.decode() else {
            return .failed(SignInFailure(status: reply.status, error: "invalid_response"))
        }
        return .signedIn(PhonePairResponse(
            token: parsed.accessToken,
            refreshToken: parsed.refreshToken,
            userId: parsed.userId,
            displayName: parsed.displayName,
            needsName: parsed.displayName?.isEmpty ?? true,
            householdId: parsed.householdId
        ))
    }

    // MARK: Account

    /// Revokes this phone's refresh token (sign-out). Best effort.
    @discardableResult
    public func logout(refreshToken: String) async -> Bool {
        await send("POST", "/api/auth/logout", json: ["refresh_token": refreshToken], renew: false).isSuccess
    }

    /// Renames the account (shown on phones and TVs).
    public func setDisplayName(token: String, name: String) async -> Bool {
        await send("POST", "/api/account/name", token: token, json: ["displayName": name]).isSuccess
    }

    /// The signed-in account's email address; nil when offline.
    public func accountEmail(token: String) async -> String? {
        let reply = await send("GET", "/api/auth/email", token: token)
        return reply.isSuccess ? reply.object?["email"] as? String : nil
    }

    /// Deletes the account, confirmed with a sign-in code mailed to its address (spec 007 FR-015).
    ///
    /// The request goes out without token renewal: that would resend a wrong code after every 401 and count twice
    /// against the 15-minute sign-in lock. An expired access token (401 `unauthorized`, answered before the code is
    /// looked at) is renewed here instead, once, and the request is repeated. The code is only ever in the request
    /// body; nothing here logs it.
    public func deleteAccount(token: String, code: String) async -> AccountDeletionResult {
        let first = await postDeleteAccount(token: token, code: code)
        guard first == .sessionExpired, tokens != nil else { return first }
        guard let fresh = await renewedAccessToken(stale: token) else { return first }
        return await postDeleteAccount(token: fresh, code: code)
    }

    private func postDeleteAccount(token: String, code: String) async -> AccountDeletionResult {
        let reply = await send("POST", "/api/account/delete", token: token, json: ["code": code], renew: false)
        switch reply.status {
        case nil: return .offline
        case 204: return .deleted
        case 429: return .rateLimited
        case 401: return reply.errorCode == "invalid_credentials" ? .wrongCode : .sessionExpired
        default: return .failed
        }
    }

    /// A cheap authenticated call; its 401 makes the client renew the token. Nil when it did not change.
    private func renewedAccessToken(stale: String) async -> String? {
        _ = await send("GET", "/api/me", token: stale)
        return tokens?.accountToken.flatMap { $0.isEmpty || $0 == stale ? nil : $0 }
    }

    // MARK: Devices

    /// Registers this phone. `hardwareId` is a stable per-install id; the server stores only a keyed hash.
    public func registerPhoneDevice(token: String, hardwareId: String? = nil) async -> PhoneDeviceResponse? {
        var body: [String: Any] = [:]
        if let hardwareId { body["hardware_id"] = hardwareId }
        let reply = await send("POST", "/api/devices/phone", token: token, json: body)
        return reply.isSuccess ? reply.decode() : nil
    }

    /// Whether this phone's registered device still exists, unrevoked, in the signed-in household.
    public func phoneDeviceStatus(token: String, deviceId: String) async -> PhoneDeviceStatus {
        let reply = await send("GET", "/api/me", token: token)
        guard reply.isSuccess, let devices = reply.object?["devices"] as? [[String: Any]] else { return .unknown }
        let device = devices.first { $0["id"] as? String == deviceId && $0["kind"] as? String == "phone" }
        let revoked = (device?["revoked_at"] as? String).map { !$0.isEmpty } ?? false
        return device != nil && !revoked ? .registered : .missing
    }

    /// Revokes a device for the whole household (it is signed out and loses LAN access).
    @discardableResult
    public func revokeDevice(id: String, token: String) async -> Bool {
        await send("DELETE", "/api/devices/\(id)", token: token).isSuccess
    }
}
