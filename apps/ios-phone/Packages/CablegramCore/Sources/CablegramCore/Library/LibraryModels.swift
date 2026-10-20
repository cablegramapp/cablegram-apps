import Foundation

public let storageLocal = "local"
public let storageCloud = "cloud"
public let storageBoth = "both"
public let transferIdle = "idle"
public let transferSaving = "saving"
public let transferFailed = "failed"
/// Marks a failed transfer as a "Save to Telegram" one (kept in `webTransferError`), so Retry goes to Telegram.
public let telegramSaveFailed = "telegram_save"
public let artworkCatalog = "catalog"
public let artworkFrame = "frame"
public let artworkUserImage = "user_image"
public let artworkPlaceholder = "placeholder"
public let artworkLegacy = "legacy"
public let cablegramCloudCapBytes: Int64 = 5 * 1024 * 1024 * 1024

public enum LibrarySyncState: Sendable { case idle, running, completed, failed }

/// A TMDB reference found in the Telegram library channel for one video, e.g. a bot's `#movie_1313826`.
public struct ChannelTmdb: Codable, Sendable, Equatable {
    public var type: String
    public var id: Int
    public var season: Int?
    public var episode: Int?

    public init(type: String, id: Int, season: Int? = nil, episode: Int? = nil) {
        self.type = type
        self.id = id
        self.season = season
        self.episode = episode
    }

    enum CodingKeys: String, CodingKey { case type = "tmdb_type", id = "tmdb_id", season = "season_number", episode = "episode_number" }
}

public struct UserCollection: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public init(id: String, name: String) {
        self.id = id
        self.name = name
    }
}

/// A folder the person added to the library (Android `IndexedFolder`; on iOS a security-scoped bookmark, phase 4).
public struct IndexedFolder: Codable, Sendable, Equatable {
    public var uri: String
    public var name: String
    public var volumeId: String?
    public init(uri: String, name: String, volumeId: String? = nil) {
        self.uri = uri
        self.name = name
        self.volumeId = volumeId
    }
}

/// A shared file whose copy into the library has begun. `id` becomes the library item id, so a resume cannot register it twice.
public struct PendingSharedImport: Codable, Sendable, Equatable {
    public var id: String
    public var uri: String
    public var displayName: String
    public var createdAt: String
    public init(id: String, uri: String, displayName: String, createdAt: String) {
        self.id = id
        self.uri = uri
        self.displayName = displayName
        self.createdAt = createdAt
    }
}

/// What the phone persists about its library (Android `LibraryFile`, minus web imports, which stay off in this release).
public struct LibraryFile: Codable, Sendable, Equatable {
    /// Account this library syncs with; another account's library is set aside, not merged.
    public var householdId: String?
    /// Titles the person removed from this phone's list; not restored again.
    public var dismissedRemoteIds: [String]
    public var items: [LibraryItem]
    public var collections: [UserCollection]
    public var folders: [IndexedFolder]
    public var pendingSharedImports: [PendingSharedImport]
    /// Telegram titles the phone already asked about, so "Find details" never repeats for them.
    public var promptedTelegramIds: [String]

    public init(householdId: String? = nil, dismissedRemoteIds: [String] = [], items: [LibraryItem] = [], collections: [UserCollection] = [],
                folders: [IndexedFolder] = [], pendingSharedImports: [PendingSharedImport] = [], promptedTelegramIds: [String] = []) {
        self.householdId = householdId
        self.dismissedRemoteIds = dismissedRemoteIds
        self.items = items
        self.collections = collections
        self.folders = folders
        self.pendingSharedImports = pendingSharedImports
        self.promptedTelegramIds = promptedTelegramIds
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        householdId = try c.decodeIfPresent(String.self, forKey: .householdId)
        dismissedRemoteIds = try c.decodeIfPresent([String].self, forKey: .dismissedRemoteIds) ?? []
        items = try c.decodeIfPresent([LibraryItem].self, forKey: .items) ?? []
        collections = try c.decodeIfPresent([UserCollection].self, forKey: .collections) ?? []
        folders = try c.decodeIfPresent([IndexedFolder].self, forKey: .folders) ?? []
        pendingSharedImports = try c.decodeIfPresent([PendingSharedImport].self, forKey: .pendingSharedImports) ?? []
        promptedTelegramIds = try c.decodeIfPresent([String].self, forKey: .promptedTelegramIds) ?? []
    }
}

/// Alternative metadata the catalog returned for a title (Android `CatalogMetadata`).
public struct CatalogMetadata: Codable, Sendable, Equatable {
    public var title: String
    public var mediaType: String = "movie"
    public var year: Int?
    public var overview: String?
    public var genres: [String] = []
    public var matchStatus: String = "unmatched"
    public var posterUrl: String?
    public var backdropUrl: String?
    public var tmdbId: Int?
    public var seasonNumber: Int?
    public var episodeNumber: Int?
    public var variants: [String] = []
    public var confidence: Double?
    public var cast: [String] = []
    public var director: String?
    public var imdbId: String?

    public init(title: String, mediaType: String = "movie", year: Int? = nil, overview: String? = nil, genres: [String] = [],
                matchStatus: String = "unmatched", posterUrl: String? = nil, tmdbId: Int? = nil, seasonNumber: Int? = nil,
                episodeNumber: Int? = nil) {
        self.title = title
        self.mediaType = mediaType
        self.year = year
        self.overview = overview
        self.genres = genres
        self.matchStatus = matchStatus
        self.posterUrl = posterUrl
        self.tmdbId = tmdbId
        self.seasonNumber = seasonNumber
        self.episodeNumber = episodeNumber
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = try c.decode(String.self, forKey: .title)
        mediaType = try c.decodeIfPresent(String.self, forKey: .mediaType) ?? "movie"
        year = try c.decodeIfPresent(Int.self, forKey: .year)
        overview = try c.decodeIfPresent(String.self, forKey: .overview)
        genres = try c.decodeIfPresent([String].self, forKey: .genres) ?? []
        matchStatus = try c.decodeIfPresent(String.self, forKey: .matchStatus) ?? "unmatched"
        posterUrl = try c.decodeIfPresent(String.self, forKey: .posterUrl)
        backdropUrl = try c.decodeIfPresent(String.self, forKey: .backdropUrl)
        tmdbId = try c.decodeIfPresent(Int.self, forKey: .tmdbId)
        seasonNumber = try c.decodeIfPresent(Int.self, forKey: .seasonNumber)
        episodeNumber = try c.decodeIfPresent(Int.self, forKey: .episodeNumber)
        variants = try c.decodeIfPresent([String].self, forKey: .variants) ?? []
        confidence = try c.decodeIfPresent(Double.self, forKey: .confidence)
        cast = try c.decodeIfPresent([String].self, forKey: .cast) ?? []
        director = try c.decodeIfPresent(String.self, forKey: .director)
        imdbId = try c.decodeIfPresent(String.self, forKey: .imdbId)
    }
}

private let videoSuffix = try! NSRegularExpression(
    pattern: #"\.(?:mkv|mp4|mov|avi|m4v|webm|mpeg|mpg|ts|m2ts|wmv|flv|3gp|ogv)$"#, options: .caseInsensitive)

private func matches(_ pattern: String, _ text: String, caseInsensitive: Bool = false) -> Bool {
    text.range(of: pattern, options: caseInsensitive ? [.regularExpression, .caseInsensitive] : .regularExpression) != nil
}

/// A filename that names a capture or a hash, not a title (Android `isWeakCatalogLabel`).
public func isWeakCatalogLabel(_ value: String?) -> Bool {
    let text = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if text.isEmpty { return true }
    let stem = videoSuffix.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: "")
        .trimmingCharacters(in: .whitespacesAndNewlines)
    if stem.count < 3 { return true }
    if stem.filter(\.isLetter).count < 3 { return true }
    if matches(#"^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$"#, stem) { return true }
    if matches(#"^[0-9a-fA-F]{12,}$"#, stem) { return true }
    if matches(#"^(AgA|BAA|AQA)[A-Za-z0-9_-]{8,}$"#, stem) { return true }
    if matches(#"^[0-9]+_[0-9]+"#, stem) { return true }
    // Camera-generated names identify a capture time, not the video's title (Pixel: PXL_yyyyMMdd_HHmmssSSS).
    if matches(#"^(PXL|MVIMG|VID|IMG)[-_]?[0-9]{8}[-_]?[0-9]{6,}.*$"#, stem, caseInsensitive: true) { return true }
    if matches(#"^(video|audio|document|photo|image|img|vid)[-_].*"#, stem, caseInsensitive: true) { return true }
    if matches(#"^(personal\s+video|video|vid|movie|clip|file|telegram|tg|download|media)(\s+[0-9]+)?$"#, stem, caseInsensitive: true) { return true }
    return false
}

public func firstCatalogHint(_ labels: String?...) -> String? {
    labels.compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
        .first { !$0.isEmpty && !isWeakCatalogLabel($0) }
}

public func needsTitleInput(_ labels: String?...) -> Bool {
    labels.compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.first { !$0.isEmpty && !isWeakCatalogLabel($0) } == nil
}
