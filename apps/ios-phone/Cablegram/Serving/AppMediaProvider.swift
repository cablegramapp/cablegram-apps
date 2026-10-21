import CablegramCore
import Foundation
import Photos

/// Finds the bytes of titles the app does not hold a copy of: a Files item through its bookmark, a Photos video exported once to
/// the caches folder (Photos has no random access to originals, and a TV seeks).
final class AppMediaProvider: LanMediaProvider, @unchecked Sendable {
    private let sources: AppSources
    private let exports: URL
    private let lock = NSLock()
    private var exporting: [String: Task<URL?, Never>] = [:]

    init(sources: AppSources) {
        self.sources = sources
        exports = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("served-photos", isDirectory: true)
        // Exports are copies from an earlier run; they are kept while serving starts and stops, and rebuilt on demand.
        try? FileManager.default.removeItem(at: exports)
        try? FileManager.default.createDirectory(at: exports, withIntermediateDirectories: true)
    }

    func openVideo(_ item: LibraryItem) async -> LanMediaFile? {
        guard let uri = item.sourceUri else { return nil }
        if BookmarkStore.id(fromURI: uri) != nil, let opened = sources.open(item) {
            guard let size = Self.size(opened.url) else { opened.release(); return nil }
            nonisolated(unsafe) let release = opened.release
            return LanMediaFile(url: opened.url, length: size, release: { release() })
        }
        if PhotoSource.identifier(fromURI: uri) != nil, let url = await exportPhoto(item, uri: uri), let size = Self.size(url) {
            return LanMediaFile(url: url, length: size)
        }
        return nil
    }

    /// Removes exported Photos videos (they are copies, rebuilt on demand).
    func clearExports() {
        try? FileManager.default.removeItem(at: exports)
        try? FileManager.default.createDirectory(at: exports, withIntermediateDirectories: true)
    }

    private static func size(_ url: URL) -> Int64? {
        (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size].flatMap { ($0 as? NSNumber)?.int64Value }
    }

    private func exportPhoto(_ item: LibraryItem, uri: String) async -> URL? {
        let target = exports.appendingPathComponent(item.id + "-" + item.filename.replacingOccurrences(of: "/", with: "_"))
        if let size = Self.size(target), size > 0 { return target }
        let task: Task<URL?, Never> = lock.withLock {
            if let running = exporting[item.id] { return running }
            let created = Task<URL?, Never> {
                guard let asset = PhotoSource.asset(uri),
                      let resource = PHAssetResource.assetResources(for: asset).first(where: { $0.type == .video || $0.type == .fullSizeVideo }) else { return nil }
                let partial = target.appendingPathExtension("part")
                try? FileManager.default.removeItem(at: partial)
                let options = PHAssetResourceRequestOptions()
                options.isNetworkAccessAllowed = true
                do {
                    try await PHAssetResourceManager.default().writeData(for: resource, toFile: partial, options: options)
                    try FileManager.default.moveItem(at: partial, to: target)
                    return target
                } catch {
                    try? FileManager.default.removeItem(at: partial)
                    return nil
                }
            }
            exporting[item.id] = created
            return created
        }
        let result = await task.value
        lock.withLock { exporting[item.id] = nil }
        return result
    }
}
