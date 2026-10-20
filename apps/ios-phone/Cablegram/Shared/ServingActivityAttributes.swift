import ActivityKit
import Foundation

/// The Lock Screen / Dynamic Island "Serving to <TV>" Live Activity. The app writes the already translated texts, so the widget
/// extension needs no string catalog.
struct ServingActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var title: String
        var detail: String
        var streams: Int
    }
}
