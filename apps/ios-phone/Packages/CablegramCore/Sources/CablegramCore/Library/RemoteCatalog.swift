import Foundation

/// A video source of a catalog title (Android `RemoteCatalogSource`).
public struct RemoteCatalogSource: Codable, Sendable, Equatable {
    public var id: String?
    public var kind: String
    public var originIdentity: String?
    public var originFilename: String?
    public var stableSourceKey: String?
    public var phoneLocation: String?
    public var archiveState: String?
    public var isPrivate: Bool
    public var canonicalUrl: String?
    public var sourceFingerprint: String?
    public var servingDeviceId: String?
    public var availability: String?

    public init(id: String? = nil, kind: String = "phone_local", originIdentity: String? = nil, originFilename: String? = nil,
                stableSourceKey: String? = nil, phoneLocation: String? = nil, archiveState: String? = nil, isPrivate: Bool = false,
                canonicalUrl: String? = nil, sourceFingerprint: String? = nil, servingDeviceId: String? = nil, availability: String? = nil) {
        self.id = id; self.kind = kind; self.originIdentity = originIdentity; self.originFilename = originFilename
        self.stableSourceKey = stableSourceKey; self.phoneLocation = phoneLocation; self.archiveState = archiveState
        self.isPrivate = isPrivate; self.canonicalUrl = canonicalUrl; self.sourceFingerprint = sourceFingerprint
        self.servingDeviceId = servingDeviceId; self.availability = availability
    }

    enum CodingKeys: String, CodingKey {
        case id, kind, availability
        case originIdentity = "origin_identity", originFilename = "origin_filename", stableSourceKey = "stable_source_key"
        case phoneLocation = "phone_location", archiveState = "archive_state", isPrivate = "private", canonicalUrl = "canonical_url"
        case sourceFingerprint = "source_fingerprint", servingDeviceId = "serving_device_id"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id)
        kind = try c.decodeIfPresent(String.self, forKey: .kind) ?? "phone_local"
        originIdentity = try c.decodeIfPresent(String.self, forKey: .originIdentity)
        originFilename = try c.decodeIfPresent(String.self, forKey: .originFilename)
        stableSourceKey = try c.decodeIfPresent(String.self, forKey: .stableSourceKey)
        phoneLocation = try c.decodeIfPresent(String.self, forKey: .phoneLocation)
        archiveState = try c.decodeIfPresent(String.self, forKey: .archiveState)
        isPrivate = try c.decodeIfPresent(Bool.self, forKey: .isPrivate) ?? false
        canonicalUrl = try c.decodeIfPresent(String.self, forKey: .canonicalUrl)
        sourceFingerprint = try c.decodeIfPresent(String.self, forKey: .sourceFingerprint)
        servingDeviceId = try c.decodeIfPresent(String.self, forKey: .servingDeviceId)
        availability = try c.decodeIfPresent(String.self, forKey: .availability)
    }
}

/// A title as the household catalog reports it (Android `RemoteCatalogItem`, `GET /api/catalog/items`).
public struct RemoteCatalogItem: Codable, Sendable, Equatable {
    public var id: String
    public var title: String?
    public var durationSeconds: Int?
    public var posterUrl: String?
    public var mediaType: String?
    public var year: Int?
    public var overview: String?
    public var userMetadataFields: Set<String>
    public var metadataRevision: Int
    public var genres: [String]
    public var tmdbId: Int?
    public var seriesIdentity: String?
    public var seasonNumber: Int?
    public var episodeNumber: Int?
    public var matchStatus: String?
    public var createdAt: String?
    public var sources: [RemoteCatalogSource]
    public var channelTmdb: ChannelTmdb?

    public init(id: String, title: String? = nil, durationSeconds: Int? = nil, posterUrl: String? = nil, mediaType: String? = nil,
                year: Int? = nil, overview: String? = nil, userMetadataFields: Set<String> = [], metadataRevision: Int = 0,
                genres: [String] = [], tmdbId: Int? = nil, seriesIdentity: String? = nil, seasonNumber: Int? = nil,
                episodeNumber: Int? = nil, matchStatus: String? = nil, createdAt: String? = nil, sources: [RemoteCatalogSource] = [],
                channelTmdb: ChannelTmdb? = nil) {
        self.id = id; self.title = title; self.durationSeconds = durationSeconds; self.posterUrl = posterUrl; self.mediaType = mediaType
        self.year = year; self.overview = overview; self.userMetadataFields = userMetadataFields; self.metadataRevision = metadataRevision
        self.genres = genres; self.tmdbId = tmdbId; self.seriesIdentity = seriesIdentity; self.seasonNumber = seasonNumber
        self.episodeNumber = episodeNumber; self.matchStatus = matchStatus; self.createdAt = createdAt; self.sources = sources
        self.channelTmdb = channelTmdb
    }

    enum CodingKeys: String, CodingKey {
        case id, title, year, overview, genres, sources
        case durationSeconds = "duration_seconds", posterUrl = "poster_url", mediaType = "media_type"
        case userMetadataFields = "user_metadata_fields", metadataRevision = "metadata_revision", tmdbId = "tmdb_id"
        case seriesIdentity = "series_identity", seasonNumber = "season_number", episodeNumber = "episode_number"
        case matchStatus = "match_status", createdAt = "created_at", channelTmdb = "channel_tmdb"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decodeIfPresent(String.self, forKey: .title)
        durationSeconds = try c.decodeIfPresent(Int.self, forKey: .durationSeconds)
        posterUrl = try c.decodeIfPresent(String.self, forKey: .posterUrl)
        mediaType = try c.decodeIfPresent(String.self, forKey: .mediaType)
        year = try c.decodeIfPresent(Int.self, forKey: .year)
        overview = try c.decodeIfPresent(String.self, forKey: .overview)
        userMetadataFields = try c.decodeIfPresent(Set<String>.self, forKey: .userMetadataFields) ?? []
        metadataRevision = try c.decodeIfPresent(Int.self, forKey: .metadataRevision) ?? 0
        genres = try c.decodeIfPresent([String].self, forKey: .genres) ?? []
        tmdbId = try c.decodeIfPresent(Int.self, forKey: .tmdbId)
        seriesIdentity = try c.decodeIfPresent(String.self, forKey: .seriesIdentity)
        seasonNumber = try c.decodeIfPresent(Int.self, forKey: .seasonNumber)
        episodeNumber = try c.decodeIfPresent(Int.self, forKey: .episodeNumber)
        matchStatus = try c.decodeIfPresent(String.self, forKey: .matchStatus)
        createdAt = try c.decodeIfPresent(String.self, forKey: .createdAt)
        sources = try c.decodeIfPresent([RemoteCatalogSource].self, forKey: .sources) ?? []
        channelTmdb = try c.decodeIfPresent(ChannelTmdb.self, forKey: .channelTmdb)
    }
}

struct RemoteCatalogResponse: Decodable {
    var items: [RemoteCatalogItem]

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        items = try c.decodeIfPresent([RemoteCatalogItem].self, forKey: .items) ?? []
    }

    enum CodingKeys: String, CodingKey { case items }
}
