import ActivityKit
import SwiftUI
import WidgetKit

@main
struct ServingWidgetBundle: WidgetBundle {
    var body: some Widget { ServingLiveActivity() }
}

/// "Serving to <TV>": shown while this iPhone is the library for a paired TV. The system cannot keep the app running in the
/// background, so the text asks the owner to come back to Cablegram.
struct ServingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: ServingActivityAttributes.self) { context in
            HStack(spacing: 12) {
                Image(systemName: context.state.streams > 0 ? "tv.and.mediabox" : "tv").font(.title2).foregroundStyle(Color(red: 0.95, green: 0.73, blue: 0.45))
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: context.state.title).font(.headline).lineLimit(1)
                    Text(verbatim: context.state.detail).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            .padding(16)
            .activityBackgroundTint(Color.black.opacity(0.85))
            .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Image(systemName: "tv").foregroundStyle(Color(red: 0.95, green: 0.73, blue: 0.45)) }
                DynamicIslandExpandedRegion(.center) { Text(verbatim: context.state.title).lineLimit(1) }
                DynamicIslandExpandedRegion(.bottom) { Text(verbatim: context.state.detail).font(.caption).foregroundStyle(.secondary) }
            } compactLeading: {
                Image(systemName: "tv").foregroundStyle(Color(red: 0.95, green: 0.73, blue: 0.45))
            } compactTrailing: {
                if context.state.streams > 0 { Text(verbatim: "\(context.state.streams)") }
            } minimal: {
                Image(systemName: "tv")
            }
        }
    }
}
