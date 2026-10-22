import SwiftUI
import UniformTypeIdentifiers

/// The system Files picker: a folder (kept as a security-scoped bookmark) or video files.
struct DocumentPicker: UIViewControllerRepresentable {
    enum Mode { case folder, videos }

    let mode: Mode
    let onPick: ([URL]) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: mode == .folder ? [.folder] : [.movie, .video, .mpeg4Movie, .quickTimeMovie],
                                                    asCopy: false)
        picker.allowsMultipleSelection = mode == .videos
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: ([URL]) -> Void
        init(_ onPick: @escaping ([URL]) -> Void) { self.onPick = onPick }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { onPick(urls) }
    }
}
