import Foundation

/// Mobile-data consent for relay streams (spec 003 US3), port of `RelayConsent`. The relay uploads video from the phone, so over
/// mobile data the owner decides: ask (default), always, never. "Allow this time" lasts three hours or until the phone is back
/// on Wi-Fi.
public enum RelayConsent {
    public static let allowOnceSeconds: TimeInterval = 3 * 3600

    /// nil = serve the relay request; otherwise the refusal. `ask` runs when the owner should be asked.
    public static func admit(network: String, policy: RelayMobileDataPolicy, allowedUntil: TimeInterval, now: TimeInterval = Date().timeIntervalSince1970,
                             ask: () -> Void) -> RelayRefusal? {
        guard network == "cellular" else { return nil }
        switch policy {
        case .always: return nil
        case .never: return .mobileDataNotAllowed
        case .ask:
            if now < allowedUntil { return nil }
            ask()
            return .mobileDataPending
        }
    }

    /// Whether a stored "allow this time" should be cleared because the phone left mobile data.
    public static func shouldResetAllowance(network: String, allowedUntil: TimeInterval) -> Bool { network != "cellular" && allowedUntil != 0 }
}
