import CablegramCore
import UserNotifications

/// The "Stream to your TV over mobile data?" notification of `RelayConsent.ask`, with Allow this time / Always allow / Don't allow.
enum RelayConsentNotice {
    static let category = "relay.consent"
    static let allowOnce = "relay.allow-once"
    static let allowAlways = "relay.allow-always"
    static let deny = "relay.deny"
    private static let identifier = "relay-consent"

    static var categoryDefinition: UNNotificationCategory {
        UNNotificationCategory(identifier: category, actions: [
            UNNotificationAction(identifier: allowOnce, title: L("ui_allow_this_time_0a5d24"), options: []),
            UNNotificationAction(identifier: allowAlways, title: L("ui_always_allow_977618"), options: []),
            UNNotificationAction(identifier: deny, title: L("ui_don_t_allow_9803bd"), options: [.destructive]),
        ], intentIdentifiers: [])
    }

    static func ask() {
        let content = UNMutableNotificationContent()
        content.title = L("ui_stream_to_your_tv_over_mobile_data_25ced7")
        content.body = L("ui_your_tv_can_t_reach_this_phone_on_wi_fi_streaming_thro_8de181")
        content.categoryIdentifier = category
        content.interruptionLevel = .timeSensitive
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
    }

    /// Applies the owner's answer; true when `action` was one of ours.
    @discardableResult
    static func apply(_ action: String, pairing: PairingStore) -> Bool {
        switch action {
        case allowOnce: pairing.relayMobileAllowedUntil = Date().timeIntervalSince1970 + RelayConsent.allowOnceSeconds
        case allowAlways: pairing.relayMobileData = .always
        case deny: pairing.relayMobileAllowedUntil = 0
        default: return false
        }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [identifier])
        return true
    }
}
