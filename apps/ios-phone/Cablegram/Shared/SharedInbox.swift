import Foundation

/// Where the Share Extension leaves videos for the app (App Group container, `incoming/`). Compiled into both targets.
/// Each shared video is one file `<uuid>.<ext>` plus `<uuid>.json` with the name it had when it was shared.
enum SharedInbox {
    static let groupId = "group.app.cablegram.phone"

    static var directory: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupId)?.appendingPathComponent("incoming", isDirectory: true)
    }

    struct Entry: Codable { var displayName: String; var file: String }

    /// Called by the extension: copies `source` in and records its display name. Returns false when the group is missing.
    @discardableResult
    static func add(copying source: URL, displayName: String) -> Bool {
        guard let directory else { return false }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let id = UUID().uuidString.lowercased()
        let ext = source.pathExtension.isEmpty ? "mp4" : source.pathExtension
        let file = "\(id).\(ext)"
        do {
            try FileManager.default.copyItem(at: source, to: directory.appendingPathComponent(file + ".part"))
            try FileManager.default.moveItem(at: directory.appendingPathComponent(file + ".part"), to: directory.appendingPathComponent(file))
            try JSONEncoder().encode(Entry(displayName: displayName, file: file)).write(to: directory.appendingPathComponent("\(id).json"), options: .atomic)
            return true
        } catch {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(file + ".part"))
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(file))
            return false
        }
    }

    /// Called by the app: the finished shares waiting to be imported.
    static func pending() -> [(entry: Entry, url: URL, manifest: URL)] {
        guard let directory, let names = try? FileManager.default.contentsOfDirectory(atPath: directory.path) else { return [] }
        sweepOrphans(in: directory, names: names)
        return names.filter { $0.hasSuffix(".json") }.compactMap { name in
            let manifest = directory.appendingPathComponent(name)
            guard let data = try? Data(contentsOf: manifest), let entry = try? JSONDecoder().decode(Entry.self, from: data) else { return nil }
            let url = directory.appendingPathComponent(entry.file)
            return FileManager.default.fileExists(atPath: url.path) ? (entry, url, manifest) : nil
        }
    }

    /// A video or `.part` file with no manifest, older than an hour: the extension was killed between the copy and the manifest.
    private static func sweepOrphans(in directory: URL, names: [String]) {
        var referenced = Set<String>()
        for name in names where name.hasSuffix(".json") {
            if let data = try? Data(contentsOf: directory.appendingPathComponent(name)), let entry = try? JSONDecoder().decode(Entry.self, from: data) { referenced.insert(entry.file) }
        }
        for name in names where !name.hasSuffix(".json") && !referenced.contains(name) {
            let url = directory.appendingPathComponent(name)
            let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date()
            if Date().timeIntervalSince(modified) > 3600 { try? FileManager.default.removeItem(at: url) }
        }
    }

    static func remove(_ item: (entry: Entry, url: URL, manifest: URL)) {
        try? FileManager.default.removeItem(at: item.url)
        try? FileManager.default.removeItem(at: item.manifest)
    }
}
