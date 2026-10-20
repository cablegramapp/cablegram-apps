import CablegramCore
import UserNotifications

/// Local notifications for TV requests (Android `PrivateApprovals`): each new request gets Allow / Not now actions. There is no push
/// in this release, so they appear only while the app runs; the in-app card is the main path.
enum Approvals {
    static let playbackCategory = "approval.playback"
    static let profileCategory = "approval.profile"
    static let allowAction = "approval.allow"
    static let denyAction = "approval.deny"

    static func registerCategories() {
        func category(_ id: String, allow: String) -> UNNotificationCategory {
            UNNotificationCategory(identifier: id, actions: [
                UNNotificationAction(identifier: allowAction, title: allow, options: []),
                UNNotificationAction(identifier: denyAction, title: L("ui_not_now_a0e63d"), options: []),
            ], intentIdentifiers: [])
        }
        UNUserNotificationCenter.current().setNotificationCategories([
            category(playbackCategory, allow: L("ui_allow_once_168511")), category(profileCategory, allow: L("ui_allow_e213c1")),
            RelayConsentNotice.categoryDefinition,
        ])
    }

    static func notify(_ approval: PendingApproval) {
        let tv = (approval.tvName ?? "").isEmpty ? L("ui_your_tv_f04452") : approval.tvName!
        let content = UNMutableNotificationContent()
        switch approval.kind {
        case .profile:
            content.title = L("ui_wants_to_open_5ee092", tv, approval.title ?? L("ui_a_profile_4913b3"))
            content.body = approval.sharedTv ? L("ui_other_accounts_use_this_tv_too_allow_it_only_if_you_ar_7a66a8") : L("ui_allow_it_only_if_you_are_at_that_tv_2f7976")
            content.categoryIdentifier = profileCategory
        case .playback:
            content.title = L("ui_wants_to_play_a_private_title_b90ffa", tv)
            content.body = approval.title ?? L("ui_a_private_title_is_waiting_for_your_approval_43ca2a")
            content.categoryIdentifier = playbackCategory
        }
        content.userInfo = ["attempt_id": approval.attemptId, "kind": approval.kind.rawValue]
        content.interruptionLevel = .timeSensitive
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "approval-\(approval.attemptId)", content: content, trigger: nil))
    }

    static func cancel(_ attemptId: String) {
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: ["approval-\(attemptId)"])
        center.removePendingNotificationRequests(withIdentifiers: ["approval-\(attemptId)"])
    }
}

/// Handles Allow / Not now from an approval notification.
final class ApprovalNotificationHandler: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    @MainActor static var respond: ((PendingApproval, Bool) -> Void)?
    /// Answers the mobile-data question of the relay (Allow this time / Always allow / Don't allow).
    @MainActor static var relayAnswer: ((String) -> Void)?

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if response.notification.request.content.categoryIdentifier == RelayConsentNotice.category {
            let action = response.actionIdentifier
            await MainActor.run { Self.relayAnswer?(action) }
            return
        }
        let info = response.notification.request.content.userInfo
        guard let attemptId = info["attempt_id"] as? String else { return }
        let kind = ApprovalKind(rawValue: info["kind"] as? String ?? "") ?? .playback
        let allow: Bool
        switch response.actionIdentifier {
        case Approvals.allowAction: allow = true
        case Approvals.denyAction: allow = false
        default: return // tapping the notification just opens the app, where the same request is a card
        }
        await MainActor.run { Self.respond?(PendingApproval(attemptId: attemptId, kind: kind), allow) }
    }

    /// While the app is open the card shows the request; no second banner.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions { [] }
}
