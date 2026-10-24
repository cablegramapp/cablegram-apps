import Foundation
import Observation

/// Where the person is in signing in (spec 007).
public enum SignInStep: Equatable, Sendable {
    /// Continue with Google, or an email for a code.
    case start
    case code(email: String)
    /// The email is proven but has no account: name, plus one unticked box for 18+ and the Terms.
    case createAccount(ticket: String, email: String, name: String)
    /// Device join: waiting for another phone to approve.
    case joinWait(requestId: String, shortCode: String, pollSecret: String)
}

/// A line to show; the key is a String Catalog key.
public struct StatusMessage: Equatable, Sendable {
    public var key: String
    public init(_ key: String) { self.key = key }
}

/// Sign-in with Google or an email code, and Create your account. Nothing about a new person is stored on the server
/// before `createAccount`; `signedIn` runs once the server returned an account. Port of `SignInController`.
@MainActor @Observable
public final class SignInController {
    public private(set) var step: SignInStep = .start
    /// Nil until loaded, and while the server can't be reached: then only the email code is offered.
    public private(set) var methods: SignInMethods?
    public private(set) var legal: LegalDocuments?
    public private(set) var capabilities: ServerCapabilities?
    public private(set) var busy = false
    /// What went wrong in the last step, ready to show; nil when nothing did.
    public var message: StatusMessage?

    public var features: ServerFeatures { capabilities?.features ?? .hostedDefault }
    public var deviceMode: Bool {
        guard let caps = capabilities else { return false }
        if caps.edition == "community" { return true }
        if caps.auth.setupCode || caps.auth.deviceCode { return true }
        if !caps.auth.emailCode && caps.auth.google == nil && caps.auth.apple == nil { return true }
        return false
    }

    private let client: @Sendable () -> CatalogClient
    private let signedIn: @MainActor (PhonePairResponse) async -> Void
    private var joinPollTask: Task<Void, Never>?

    public init(client: @escaping @Sendable () -> CatalogClient, signedIn: @escaping @MainActor (PhonePairResponse) async -> Void) {
        self.client = client
        self.signedIn = signedIn
    }

    /// The documents to link to: the server's, or the public pages when it can't be reached.
    public var documents: LegalDocuments { legal ?? .fallback }

    /// Bumped by `serverChanged`, so an answer from the previous server can't overwrite the new one's.
    private var generation = 0

    /// The server changed: forget what the previous one offered, including a sign-in in progress.
    public func serverChanged() {
        generation += 1
        capabilities = nil
        methods = nil
        legal = nil
        message = nil
        step = .start
        reset()
    }

    public func load() async {
        let loading = generation
        let c = client()
        async let caps = c.serverCapabilities()
        async let m = c.signInMethods()
        async let l = c.legalDocuments()
        let (nextCaps, nextMethods, nextLegal) = await (caps, m, l)
        guard loading == generation else { return }
        capabilities = nextCaps ?? capabilities
        methods = nextMethods ?? methods
        legal = nextLegal ?? legal
    }

    public func claimSetupCode(_ code: String) async {
        let clean = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.count == 6 else {
            message = StatusMessage("ui_that_code_isn_t_right_check_the_email_and_try_again_38ed4a")
            return
        }
        await run {
            let result = await client().claimSetupCode(code: clean)
            switch result {
            case .failed(let failure):
                switch failure.error {
                case "invalid_setup_code":
                    message = StatusMessage("ui_that_code_isn_t_right_check_the_email_and_try_again_38ed4a")
                case "setup_code_expired":
                    message = StatusMessage("ui_that_code_expired_or_was_tried_too_often_ask_for_a_new_0aa93e")
                case "too_many_setup_attempts", "rate_limited":
                    message = StatusMessage("ui_too_many_tries_wait_a_while_and_try_again_114288")
                default:
                    if failure.status == 429 {
                        message = StatusMessage("ui_too_many_tries_wait_a_while_and_try_again_114288")
                    } else {
                        await handle(result)
                    }
                }
            default:
                await handle(result)
            }
        }
    }

    public func requestJoin(deviceName: String = "iPhone") async {
        await run {
            guard let created = await client().createJoinRequest(deviceName: deviceName) else {
                message = StatusMessage("ui_can_t_reach_cablegram_check_your_connection_0f7f38")
                return
            }
            step = .joinWait(requestId: created.id, shortCode: created.shortCode, pollSecret: created.pollSecret)
            startJoinPolling(id: created.id, pollSecret: created.pollSecret)
        }
    }

    public func cancelJoin() {
        joinPollTask?.cancel()
        joinPollTask = nil
        step = .start
        message = nil
    }

    private func startJoinPolling(id: String, pollSecret: String) {
        joinPollTask?.cancel()
        joinPollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                guard let self, case .joinWait = self.step else { break }
                guard let poll = await self.client().pollJoinRequest(id: id, pollSecret: pollSecret) else { continue }
                if poll.status == "approved", let token = poll.accessToken {
                    await self.handle(.signedIn(PhonePairResponse(
                        token: token,
                        refreshToken: poll.refreshToken,
                        userId: poll.userId,
                        displayName: poll.displayName,
                        needsName: poll.displayName?.isEmpty ?? true,
                        householdId: poll.householdId,
                        deviceId: poll.deviceId
                    )))
                    break
                } else if poll.status == "rejected" {
                    self.message = StatusMessage("ui_the_request_was_not_accepted_refresh_and_try_again_35aa7e")
                    self.step = .start
                    break
                } else if poll.status == "expired" {
                    self.message = StatusMessage("ui_that_took_too_long_start_again_8a44f3")
                    self.step = .start
                    break
                }
            }
        }
    }

    public func requestCode(email: String) async {
        let address = email.trimmingCharacters(in: .whitespacesAndNewlines)
        await run {
            if let failed = await client().requestSignInCode(email: address) {
                message = StatusMessage(failed.messageKey)
            } else {
                step = .code(email: address)
            }
        }
    }

    public func resendCode() async {
        guard case .code(let email) = step else { return }
        await run {
            let failed = await client().requestSignInCode(email: email)
            message = StatusMessage(failed?.messageKey ?? "ui_a_new_code_is_on_its_way_8f5c29")
        }
    }

    public func verifyCode(_ code: String) async {
        guard case .code(let email) = step else { return }
        await run { await handle(client().verifySignInCode(email: email, code: code.trimmingCharacters(in: .whitespaces))) }
    }

    /// `idToken` came from Google for this sign-in's `nonce`.
    public func google(idToken: String, nonce: String) async {
        await run { await handle(client().googleSignIn(idToken: idToken, nonce: nonce)) }
    }

    /// `idToken` came from Apple for this sign-in's raw `nonce`; `name` is what Apple gave on the first authorization.
    public func apple(idToken: String, nonce: String, authorizationCode: String?, name: String?) async {
        await run { await handle(client().appleSignIn(idToken: idToken, nonce: nonce, authorizationCode: authorizationCode, name: name)) }
    }

    public func createAccount(name: String) async {
        guard case .createAccount(let ticket, _, _) = step else { return }
        await run {
            var docs = legal
            if docs == nil {
                docs = await client().legalDocuments()
                legal = docs
            }
            guard let docs else {
                message = StatusMessage("ui_can_t_reach_cablegram_check_your_connection_0f7f38")
                return
            }
            let result = await client().signUp(ticket: ticket, name: name.trimmingCharacters(in: .whitespaces), legal: docs)
            if case .failed(let failure) = result {
                switch failure.error {
                case "terms_outdated":
                    legal = await client().legalDocuments() ?? legal
                    message = StatusMessage("ui_the_terms_or_privacy_policy_just_changed_please_read_t_fa977a")
                case "invalid_signup_ticket":
                    step = .start
                    message = StatusMessage("ui_that_took_too_long_start_again_8a44f3")
                default:
                    message = StatusMessage(failure.messageKey)
                }
            } else {
                await handle(result)
            }
        }
    }

    public func back() {
        cancelJoin()
    }

    /// Signed out, or the account was deleted: start over.
    public func reset() {
        cancelJoin()
        busy = false
    }

    private func handle(_ result: SignInResult) async {
        switch result {
        case .signedIn(let account):
            await signedIn(account)
            step = .start
        case .signupRequired(let ticket, let email, let name):
            step = .createAccount(ticket: ticket, email: email, name: name ?? "")
        case .failed(let failure):
            message = StatusMessage(failure.messageKey)
        }
    }

    private func run(_ block: () async -> Void) async {
        if busy { return }
        busy = true
        message = nil
        await block()
        busy = false
    }
}
