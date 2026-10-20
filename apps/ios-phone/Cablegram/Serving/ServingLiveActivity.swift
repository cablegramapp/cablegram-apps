import ActivityKit
import Foundation

/// The Lock Screen "Serving to <TV>" Live Activity (widget extension `ServingWidget`). It starts while the app is on screen,
/// because the system refuses to start one from the background, and ends when serving stops.
@MainActor
final class ServingLiveActivityController {
    private var activity: Activity<ServingActivityAttributes>?

    func update(title: String, detail: String, streams: Int) {
        let content = ActivityContent(state: ServingActivityAttributes.ContentState(title: title, detail: detail, streams: streams), staleDate: nil)
        if let activity {
            nonisolated(unsafe) let running = activity
            Task { await running.update(content) }
        } else if ActivityAuthorizationInfo().areActivitiesEnabled {
            activity = try? Activity.request(attributes: ServingActivityAttributes(), content: content)
        }
    }

    /// A Live Activity left behind by a process the system killed would stay on the Lock Screen for hours.
    func endStale() {
        Task.detached { for stale in Activity<ServingActivityAttributes>.activities { await stale.end(nil, dismissalPolicy: .immediate) } }
    }

    func end() {
        guard let activity else { return }
        self.activity = nil
        nonisolated(unsafe) let ending = activity
        Task { await ending.end(nil, dismissalPolicy: .immediate) }
    }
}
