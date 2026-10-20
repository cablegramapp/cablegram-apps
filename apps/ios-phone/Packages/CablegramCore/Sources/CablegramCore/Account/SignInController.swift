import Foundation
import Observation

/// Where the person is in signing in (spec 007).
public enum SignInStep: Equatable, Sendable {
    /// Continue with Google, or an email for a code.
    case start
    case code(email: String)
    /// The email is proven but has no account: name, plus one unticked box for 18+ and the Terms.
    case createAccount(ticket: String, email: String, name: String)
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
    public private(set) var busy = false
    /// What went wrong in the last step, ready to show; nil when nothing did.
    public var message: StatusMessage?

    private let client: @Sendable () -> CatalogClient
    private let signedIn: @MainActor (PhonePairResponse) async -> Void

    public init(client: @escaping @Sendable () -> CatalogClient, signedIn: @escaping @MainActor (PhonePairResponse) async -> Void) {
        self.client = client
        self.signedIn = signedIn
    }

    /// The documents to link to: the server's, or the public pages when it can't be reached.
    public var documents: LegalDocuments { legal ?? .fallback }

    public func load() async {
        let c = client()
        async let m = c.signInMethods()
        async let l = c.legalDocuments()
        methods = await m ?? methods
        legal = await l ?? legal
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
        step = .start
        message = nil
    }

    /// Signed out, or the account was deleted: start over.
    public func reset() {
        step = .start
        message = nil
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
