import Foundation

/// Security-scoped bookmarks for files and folders the person picked in Files (the iOS counterpart of Android's persisted
/// SAF grants). Items refer to a bookmark as `bm:<id>`; the bookmark data stays out of the library index and the server.
final class BookmarkStore: @unchecked Sendable {
    private let directory: URL
    private let lock = NSLock()

    init(root: URL) {
        directory = root.appendingPathComponent("bookmarks", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    static func uri(for id: String) -> String { "bm:\(id)" }
    static func id(fromURI uri: String) -> String? { uri.hasPrefix("bm:") ? String(uri.dropFirst(3)) : nil }

    /// Stores a bookmark for `url` (which must be readable now, inside its security scope) and returns its id.
    func save(_ url: URL) throws -> String {
        let data = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        let id = UUID().uuidString.lowercased()
        try lock.withLock { try data.write(to: directory.appendingPathComponent(id)) }
        return id
    }

    /// Resolves a bookmark; `stale` means the system moved the file and a fresh bookmark should be stored.
    func resolve(_ id: String) -> (url: URL, stale: Bool)? {
        guard let data = lock.withLock({ try? Data(contentsOf: directory.appendingPathComponent(id)) }) else { return nil }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else { return nil }
        return (url, stale)
    }

    func remove(_ id: String) { lock.withLock { try? FileManager.default.removeItem(at: directory.appendingPathComponent(id)) } }

    /// Runs `body` with the bookmark's URL inside its security scope; nil when it no longer resolves.
    func withAccess<T>(_ id: String, _ body: (URL) throws -> T) rethrows -> T? {
        guard let (url, stale) = resolve(id) else { return nil }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        if stale, let data = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
            lock.withLock { try? data.write(to: directory.appendingPathComponent(id)) }
        }
        return try body(url)
    }
}

/// Runs `body` with `url`'s security scope open (async version of `withAccess`).
func withScopedAccess<T: Sendable>(_ url: URL, _ body: @Sendable () async -> T) async -> T {
    let scoped = url.startAccessingSecurityScopedResource()
    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
    return await body()
}
