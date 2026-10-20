import CablegramCore
import SwiftUI

/// The timing of a saved subtitle can be adjusted at any time, not only right after choosing or attaching it: the same preview, shift
/// buttons, two-point sync and live adjustment on the TV, starting from the timing saved now (Android `SavedSubtitleTimingButton`).
struct SavedSubtitleTimingButton: View {
    let viewModel: PhoneViewModel
    let item: LibraryItem
    let enabled: Bool
    @State private var open = false

    var body: some View {
        Button { open = true } label: { Text(key: "ui_adjust_subtitle_timing_6be7cc") }
            .buttonStyle(OutlinedButtonStyle()).disabled(!enabled).accessibilityIdentifier("detail_adjust_subtitle_timing")
            .fullScreenCover(isPresented: $open) { SavedSubtitleTimingScreen(viewModel: viewModel, item: item) { open = false } }
    }
}

private struct SavedSubtitleTimingScreen: View {
    let viewModel: PhoneViewModel
    let item: LibraryItem
    let onClose: () -> Void
    @State private var timing: SavedSubtitleTiming?
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(action: onClose) { Text(key: "ui_done_11a676") }.buttonStyle(AccentTextButtonStyle())
            Text(key: "ui_subtitle_timing_5a6158").font(PhoneFont.titleLarge).foregroundStyle(.white)
            if let error { Text(error).foregroundStyle(Color.phoneMuted) }
            else if let timing {
                ScrollView {
                    AdjustContent(viewModel: viewModel, item: item, match: SubtitleMatch(id: timing.id, language: timing.language, cues: timing.cues, provider: "saved"),
                                  subtitleId: timing.id, start: (timing.offset, timing.scale)) { viewModel.subtitles.refreshStatus(item); onClose() }
                }
            } else { Text(key: "ui_opening_the_subtitle_2629bd").foregroundStyle(Color.phoneMuted) }
        }
        .padding(.horizontal, 20).padding(.vertical, 8)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .phoneScreenBackground()
        .task(id: item.id) {
            guard let token = viewModel.subtitles.accountToken else { error = savedTimingError(nil); return }
            do {
                let list = try JSONDecoder().decode(SavedSubtitleList.self, from: Data(try await viewModel.subtitles.request("playback/item/\(item.id)", token: token).utf8))
                guard let saved = list.subtitles.first(where: { !$0.trackId.isEmpty }) else { throw LocalSubtitleError("subtitle_not_found") }
                timing = try JSONDecoder().decode(SavedSubtitleTiming.self, from: Data(try await viewModel.subtitles.request("\(saved.trackId)/timing", token: token).utf8))
            } catch { self.error = savedTimingError((error as? LocalSubtitleError)?.code) }
        }
    }
}
