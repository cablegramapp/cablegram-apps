import CablegramCore
import Foundation
import GoogleSignIn
import Security
import UIKit

/// What the Google sheet gave back (Android `GoogleIdResult`).
enum GoogleIdResult {
    case token(idToken: String, nonce: String)
    /// The person closed the sheet: nothing to report.
    case cancelled
    /// A String Catalog key for the message to show.
    case failed(String)
}

/// "Continue with Google": the Google Sign-In SDK with a fresh nonce that the server checks inside the ID token.
/// `serverClientId` is our web client; the iOS client id comes from Info.plist (`GIDClientID`, Local.xcconfig).
@MainActor
final class GoogleSignInService {
    init() {}

    func idToken(serverClientId: String) async -> GoogleIdResult {
        guard let clientId = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String, !clientId.isEmpty else {
            return .failed("ui_google_sign_in_isn_t_available_right_now_use_an_email__d91f26")
        }
        guard let presenter = Self.topViewController() else {
            return .failed("ui_google_sign_in_isn_t_available_right_now_use_an_email__d91f26")
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientId, serverClientID: serverClientId)
        let nonce = Self.randomNonce()
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter, hint: nil, additionalScopes: nil, nonce: nonce)
            guard let token = result.user.idToken?.tokenString else {
                return .failed("ui_google_didn_t_return_an_account_try_again_or_use_an_em_555820")
            }
            return .token(idToken: token, nonce: nonce)
        } catch let error as NSError where error.domain == kGIDSignInErrorDomain && error.code == GIDSignInError.canceled.rawValue {
            return .cancelled
        } catch {
            return .failed("ui_google_sign_in_isn_t_available_right_now_use_an_email__d91f26")
        }
    }

    /// 24 random bytes, base64url without padding (same shape as Android).
    private static func randomNonce() -> String {
        var bytes = [UInt8](repeating: 0, count: 24)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
        var top = scene?.keyWindow?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

enum GoogleSignInURLHandler {
    /// Google's sign-in returns through the app's reversed-client-id URL scheme.
    static func handle(_ url: URL) { _ = GIDSignIn.sharedInstance.handle(url) }
}
