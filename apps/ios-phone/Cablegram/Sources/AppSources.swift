import CablegramCore
import Foundation
import Photos

/// How sync and the editor reach the files behind library items: Photos assets (`ph:`) and bookmarked Files items (`bm:`).
struct AppSources: SourceInspector {
    let store: LibraryStore
    let bookmarks: BookmarkStore

    private func mtime(_ item: LibraryItem) -> Int64? {
        let local = store.videoFile(item)
        guard let date = try? local.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate else { return nil }
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    func hasSource(_ item: LibraryItem) -> Bool {
        if store.hasLocalBytes(item) { return true }
        guard let uri = item.sourceUri else { return false }
        if PhotoSource.identifier(fromURI: uri) != nil {
            return PHPhotoLibrary.authorizationStatus(for: .readWrite) != .denied && PhotoSource.asset(uri) != nil
        }
        if let id = BookmarkStore.id(fromURI: uri) {
            return bookmarks.withAccess(id) { FileManager.default.isReadableFile(atPath: $0.path) } ?? false
        }
        return false
    }

    /// name + size + observed length/modified time: the Android formula, so a reinstall recognises the same file again.
    func fingerprint(_ item: LibraryItem) -> String? {
        guard let size = item.fileSizeBytes else { return nil }
        return sourceFingerprintOf(name: item.filename, size: size, mtime: store.hasLocalBytes(item) ? (mtime(item) ?? 0) : size)
    }

    /// Only files already selected into this library; never scans Photos or Files.
    func selectedVideosByFingerprint() -> [String: DeviceVideo] {
        var result: [String: DeviceVideo] = [:]
        for item in store.list() where !item.householdOnly && !item.copied && item.sourceKind == "phone_local" {
            guard let uri = item.sourceUri, let size = item.fileSizeBytes, size > 0, hasSource(item) else { continue }
            result[sourceFingerprintOf(name: item.filename, size: size, mtime: size)] = DeviceVideo(uri: uri, name: item.filename, size: size)
        }
        return result
    }

    /// A URL the app can read now, with its security scope open until `release` is called.
    func open(_ item: LibraryItem) -> (url: URL, release: () -> Void)? {
        if store.hasLocalBytes(item) { return (store.videoFile(item), {}) }
        guard let uri = item.sourceUri, let id = BookmarkStore.id(fromURI: uri), let (url, _) = bookmarks.resolve(id) else { return nil }
        let scoped = url.startAccessingSecurityScopedResource()
        return (url, { if scoped { url.stopAccessingSecurityScopedResource() } })
    }
}
