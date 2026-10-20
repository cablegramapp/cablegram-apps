import UIKit
import UserNotifications

/// Receives the system's relaunch for finished background uploads, so their completion handler can be called.
final class AppDelegate: NSObject, UIApplicationDelegate {
    private let notificationHandler = ApprovalNotificationHandler()

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = notificationHandler
        Approvals.registerCategories()
        return true
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        if identifier == BackgroundUploadTransport.sessionIdentifier { BackgroundUploadTransport.backgroundCompletion = completionHandler } else { completionHandler() }
    }
}
