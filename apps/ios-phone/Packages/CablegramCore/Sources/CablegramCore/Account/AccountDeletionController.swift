import Foundation
import Observation

/// Why the last attempt to delete the account did not delete it; the account is untouched in every case.
public enum AccountDeletionError: Sendable {
    case wrongCode, rateLimited, sessionExpired, offline, failed

    public var messageKey: String {
        switch self {
        case .wrongCode: "delete_account_error_wrong_code"
        case .rateLimited: "delete_account_error_rate_limited"
        case .sessionExpired: "delete_account_error_session_expired"
        case .offline: "delete_account_error_offline"
        case .failed: "delete_account_error_failed"
        }
    }
}

/// "Delete account" (App Store and Google Play require it in the app), confirmed with a sign-in code mailed to the
/// account's address (spec 007: accounts have no password). The code goes straight to `delete` and is never kept,
/// logged or stored here. Only when the server answers 204 does `wipeLocal` run; every failure leaves the person
/// signed in and able to try again.
@MainActor @Observable
public final class AccountDeletionController {
    /// True while the request runs: the confirm button stays disabled.
    public private(set) var running = false
    public private(set) var error: AccountDeletionError?

    private let token: @MainActor () -> String?
    private let delete: @MainActor (_ token: String, _ code: String) async -> AccountDeletionResult
    private let wipeLocal: @MainActor () async -> Void

    public init(
        token: @escaping @MainActor () -> String?,
        delete: @escaping @MainActor (_ token: String, _ code: String) async -> AccountDeletionResult,
        wipeLocal: @escaping @MainActor () async -> Void
    ) {
        self.token = token
        self.delete = delete
        self.wipeLocal = wipeLocal
    }

    /// Returns true when the account is gone and this phone has been signed out.
    public func submit(_ code: String) async -> Bool {
        if running || code.isEmpty { return false }
        running = true
        error = nil
        defer { running = false }
        let result: AccountDeletionResult
        if let accountToken = token(), !accountToken.isEmpty {
            result = await delete(accountToken, code)
        } else {
            result = .sessionExpired
        }
        switch result {
        case .deleted: error = nil
        case .wrongCode: error = .wrongCode
        case .rateLimited: error = .rateLimited
        case .sessionExpired: error = .sessionExpired
        case .offline: error = .offline
        case .failed: error = .failed
        }
        guard result == .deleted else { return false }
        await wipeLocal()
        return true
    }

    /// Leaving the confirmation screen forgets the last error.
    public func reset() { error = nil }
}
