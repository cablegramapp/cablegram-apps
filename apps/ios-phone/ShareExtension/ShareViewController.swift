import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// "Share to Cablegram" for videos (Android `ShareReceiverActivity`): copies each video into the shared App Group inbox;
/// the app adds them to the library the next time it opens.
final class ShareViewController: UIViewController {
    private let model = ShareModel()

    override func viewDidLoad() {
        super.viewDidLoad()
        let host = UIHostingController(rootView: ShareView(model: model) { [weak self] in self?.finish() })
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
        Task { await receive() }
    }

    private func finish() { extensionContext?.completeRequest(returningItems: nil) }

    private func receive() async {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
            .filter { $0.hasItemConformingToTypeIdentifier(UTType.movie.identifier) }
        guard !providers.isEmpty else { model.state = .nothing; return }
        var added = 0
        for provider in providers {
            if await load(provider) { added += 1 }
        }
        model.state = added > 0 ? .done(added) : .failed
    }

    /// Copies one shared video straight into the App Group inbox. The system deletes the file it hands over as soon as the closure
    /// returns, so the copy happens inside it (a second copy in the temporary folder would double the space a share needs).
    private func load(_ provider: NSItemProvider) async -> Bool {
        await withCheckedContinuation { continuation in
            provider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                guard let url else { continuation.resume(returning: false); return }
                let name = provider.suggestedName.map { name in url.pathExtension.isEmpty || name.hasSuffix("." + url.pathExtension) ? name : name + "." + url.pathExtension } ?? url.lastPathComponent
                continuation.resume(returning: SharedInbox.add(copying: url, displayName: name))
            }
        }
    }
}

@MainActor
final class ShareModel: ObservableObject {
    enum State { case working, done(Int), failed, nothing }
    @Published var state: State = .working
}

struct ShareView: View {
    @ObservedObject var model: ShareModel
    let done: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            switch model.state {
            case .working: ProgressView(); Text(NSLocalizedString("ui_adding_to_library_7ca806", value: "Adding to library", comment: ""))
            case .done: Image(systemName: "checkmark.circle.fill").font(.system(size: 44)).foregroundStyle(.green)
                Text(NSLocalizedString("ui_video_added_08c7ee", value: "Video added", comment: ""))
            case .failed: Text(NSLocalizedString("ui_could_not_import_that_file_3e92a3", value: "Could not import that file.", comment: ""))
            case .nothing: Text(NSLocalizedString("ui_that_share_did_not_include_a_video_or_web_link_496fff", value: "That share did not include a video.", comment: ""))
            }
            Button(NSLocalizedString("ui_done_11a676", value: "Done", comment: ""), action: done).buttonStyle(.borderedProminent).tint(Color(red: 0.957, green: 0.741, blue: 0.459))
        }
        .padding(32).frame(maxWidth: .infinity, maxHeight: .infinity).background(Color(red: 0.063, green: 0.067, blue: 0.078))
        .preferredColorScheme(.dark)
    }
}
