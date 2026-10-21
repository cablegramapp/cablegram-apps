import AuthenticationServices
import CryptoKit
import Foundation
import Security

/// What the Apple sheet gave back.
struct AppleIdResult: Equatable {
    var idToken: String
    /// The raw nonce; Apple's token carries its SHA-256.
    var nonce: String
    var authorizationCode: String?
    /// Only on the first authorization: Apple never gives the name again.
    var name: String?
}

/// "Sign in with Apple": the system button asks for the name and email, with a fresh nonce whose SHA-256 Apple puts inside the identity token
/// and the server checks (App Store guideline 4.8: offered whenever another sign-in service is).
enum AppleSignInRequest {
    /// 24 random bytes, base64url without padding (the same shape as the Google nonce).
    static func randomNonce() -> String {
        var bytes = [UInt8](repeating: 0, count: 24)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func hashed(_ nonce: String) -> String { SHA256.hash(data: Data(nonce.utf8)).map { String(format: "%02x", $0) }.joined() }

    static func configure(_ request: ASAuthorizationAppleIDRequest, nonce: String) {
        request.requestedScopes = [.fullName, .email]
        request.nonce = hashed(nonce)
    }

    /// The sheet's answer as the app uses it; nil when Apple returned no identity token.
    static func result(from authorization: ASAuthorization, nonce: String) -> AppleIdResult? {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let tokenData = credential.identityToken, let token = String(data: tokenData, encoding: .utf8), !token.isEmpty else { return nil }
        let code = credential.authorizationCode.flatMap { String(data: $0, encoding: .utf8) }
        let name = credential.fullName.flatMap { PersonNameComponentsFormatter().string(from: $0).trimmingCharacters(in: .whitespaces) }.flatMap { $0.isEmpty ? nil : $0 }
        return AppleIdResult(idToken: token, nonce: nonce, authorizationCode: code, name: name)
    }

    /// The person closed the sheet: nothing to report.
    static func wasCancelled(_ error: Error) -> Bool { (error as? ASAuthorizationError)?.code == .canceled }
}
