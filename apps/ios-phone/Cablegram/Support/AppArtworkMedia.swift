import AVFoundation
import CablegramCore
import UIKit

/// Frames, covers and cache folders for the artwork editor (Android `LibraryStore.extractPreviewFrames`/`stageArtwork`).
struct AppArtworkMedia: ArtworkMedia {
    let store: LibraryStore
    /// Where the file of an item that is not copied into the app can be read (phase 4: Photos, bookmarked folders).
    var sourceURL: @Sendable (LibraryItem) -> URL? = { _ in nil }

    private func folder(_ session: Int64) -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("artwork-editor-\(session)", isDirectory: true)
    }

    func extractPreviewFrames(_ item: LibraryItem, count: Int, session: Int64) async -> [String] {
        let local = store.videoFile(item)
        guard let url = FileManager.default.fileExists(atPath: local.path) ? local : sourceURL(item) else { return [] }
        let asset = AVURLAsset(url: url)
        guard let seconds = try? await asset.load(.duration).seconds, seconds.isFinite else { return [] }
        var rng = SystemRandomNumberGenerator()
        let times = previewFrameTimesUs(durationMs: Int64(seconds * 1000), count: count, using: &rng)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 480, height: 480)
        let directory = folder(session)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var paths: [String] = []
        for (index, us) in times.enumerated() {
            guard let (cgImage, _) = try? await generator.image(at: CMTime(value: us, timescale: 1_000_000)),
                  let data = UIImage(cgImage: cgImage).jpegData(compressionQuality: 0.7) else { continue }
            let out = directory.appendingPathComponent("\(item.id)-frame-\(index).jpg")
            if (try? data.write(to: out)) != nil { paths.append(out.path) }
        }
        return paths
    }

    func stageArtwork(session: Int64, revision: Int64, bytes: Data) throws -> String {
        guard !bytes.isEmpty, UIImage(data: bytes) != nil else { throw MetadataValidationError(reason: "Invalid cover image") }
        let directory = folder(session)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("catalog-\(revision).jpg")
        try bytes.write(to: file)
        return file.path
    }

    func discardArtworkPreviews(session: Int64) { try? FileManager.default.removeItem(at: folder(session)) }

    func isValidImage(_ url: URL) -> Bool { UIImage(contentsOfFile: url.path) != nil }
}
