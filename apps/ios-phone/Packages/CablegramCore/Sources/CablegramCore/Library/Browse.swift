import Foundation

public enum BrowseSource: Sendable { case none, roots, folder, photos, cloud }

public enum StorageRootKind: Sendable { case phone, attached, cloud }

public struct StorageRoot: Sendable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var kind: StorageRootKind
    public var detail: String
    public var cloudKey: String?
    public init(id: String, name: String, kind: StorageRootKind, detail: String, cloudKey: String? = nil) {
        self.id = id; self.name = name; self.kind = kind; self.detail = detail; self.cloudKey = cloudKey
    }
}

/// One row of the Import browser: a folder, a video, or another file (Android `BrowseEntry`). `uri` identifies the
/// source: `bm:<id>` for a file or folder reached through a security-scoped bookmark, `ph:<localIdentifier>` for a
/// Photos video.
public struct BrowseEntry: Sendable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var isDirectory: Bool
    public var isVideo: Bool
    public var sizeBytes: Int64?
    public var mime: String
    public var uri: String?
    public var durationSeconds: Int?
    public init(id: String, name: String, isDirectory: Bool, isVideo: Bool, sizeBytes: Int64? = nil, mime: String = "", uri: String? = nil,
                durationSeconds: Int? = nil) {
        self.id = id; self.name = name; self.isDirectory = isDirectory; self.isVideo = isVideo; self.sizeBytes = sizeBytes
        self.mime = mime; self.uri = uri; self.durationSeconds = durationSeconds
    }
}

public struct BrowseCrumb: Sendable, Equatable {
    public var name: String
    public var path: String?
    public init(name: String, path: String? = nil) {
        self.name = name
        self.path = path
    }
}

public let videoExtensions: Set<String> = ["mp4", "mkv", "mov", "avi", "m4v", "webm", "ts", "m2ts", "wmv", "flv", "mpeg", "mpg", "3gp", "ogv"]

private let nonVideoExtensions: Set<String> = [
    "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "mp3", "m4a", "aac", "flac", "wav", "ogg", "pdf", "txt", "json", "xml", "html", "apk",
]

private let hiddenBrowseNames: Set<String> = ["android", "lost+found", "thumbnails", ".thumbnails", ".trashed", "recycle bin"]

public func looksLikeVideo(_ name: String) -> Bool {
    guard name.contains(".") else { return false }
    return videoExtensions.contains(name.split(separator: ".").last!.lowercased())
}

public func isVideoFile(name: String, mime: String) -> Bool {
    let type = mime.lowercased()
    if type.hasPrefix("video/") || type == "application/vnd.rn-realmedia" { return true }
    return looksLikeVideo(name)
}

public func isHiddenBrowseName(_ name: String) -> Bool {
    let stem = name.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased()
    return hiddenBrowseNames.contains(stem) || stem.hasPrefix(".")
}

public func canAddAsVideo(_ entry: BrowseEntry) -> Bool {
    if entry.isDirectory { return false }
    if entry.isVideo { return true }
    let type = entry.mime.lowercased()
    if type.hasPrefix("image/") || type.hasPrefix("audio/") || type.hasPrefix("text/") || type == "application/pdf" { return false }
    let ext = entry.name.contains(".") ? entry.name.split(separator: ".").last!.lowercased() : ""
    if nonVideoExtensions.contains(ext) { return false }
    // Unknown/generic mime: only recognise known video extensions.
    if type.hasPrefix("video/") || type == "application/vnd.rn-realmedia" { return true }
    return videoExtensions.contains(ext)
}

/// Browse lists video files only (Flow 2, legacy parity); `videosOnly` never weakens that filter.
public func visibleBrowseEntries(_ entries: [BrowseEntry], videosOnly: Bool) -> [BrowseEntry] {
    entries.filter { !isHiddenBrowseName($0.name) }
        .filter { $0.isDirectory || $0.isVideo || canAddAsVideo($0) }
        .sorted { a, b in
            if a.isDirectory != b.isDirectory { return a.isDirectory }
            return a.name.lowercased() < b.name.lowercased()
        }
}

/// "S01E02" or "season" in the name marks a series; everything else is a film.
public func guessMediaType(_ name: String) -> String {
    let lower = name.lowercased()
    return lower.range(of: #"s\d{1,2}e\d{1,2}"#, options: .regularExpression) != nil || lower.contains("season") ? "tv" : "movie"
}

/// A file in the videos folder, as the sweep sees it.
public struct VideoFileInfo: Sendable, Equatable {
    public var name: String
    public var lastModified: Int64
    public init(name: String, lastModified: Int64) {
        self.name = name
        self.lastModified = lastModified
    }
}

/// Files younger than this are left alone: something may still be writing them.
private let sweepGraceMs: Int64 = 60_000

/// Names to delete from the videos folder: `.part` files and files no library row points to. A name in `importing`
/// (or a file changed within the last minute) is never touched.
public func orphanedVideoFiles(_ files: [VideoFileInfo], referenced: Set<String>, importing: Set<String>, now: Int64) -> [String] {
    files.filter { file in
        let final = file.name.hasSuffix(".part") ? String(file.name.dropLast(5)) : file.name
        let kept = importing.contains(final) || now - file.lastModified < sweepGraceMs || (!file.name.hasSuffix(".part") && referenced.contains(file.name))
        return !kept
    }.map(\.name)
}

public enum SharedImportResume: Sendable { case finish, copy, drop }

/// What to do with a shared import a previous run left unfinished.
public func sharedImportResume(alreadyRegistered: Bool, sourceReadable: Bool) -> SharedImportResume {
    if alreadyRegistered { return .finish }
    return sourceReadable ? .copy : .drop
}
