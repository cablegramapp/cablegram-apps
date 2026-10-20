import UIKit

/// Receives the system's relaunch for finished background uploads, so their completion handler can be called.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        if identifier == BackgroundUploadTransport.sessionIdentifier { BackgroundUploadTransport.backgroundCompletion = completionHandler } else { completionHandler() }
    }
}
