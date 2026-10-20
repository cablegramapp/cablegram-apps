import AuthenticationServices
import UIKit

/// Google forbids sign-in inside an embedded web view; `ASWebAuthenticationSession` is the system browser sheet, so it is
/// allowed (Android uses a Custom Tab). The server's sign-in ends on `cablegram://storage?provider=…&result=…`.
@MainActor
final class WebAuth: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    /// Opens `url` and returns the `cablegram://` URL the flow ends on, or nil when the person closed the sheet.
    func start(url: URL) async -> URL? {
        await withCheckedContinuation { continuation in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "cablegram") { callback, _ in continuation.resume(returning: callback) }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            self.session = session
            if !session.start() { continuation.resume(returning: nil) }
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?.keyWindow ?? ASPresentationAnchor()
    }
}
