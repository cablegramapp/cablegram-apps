import Foundation

/// One title in the phone's library. Port of Android `LibraryItem`: same fields and defaults. Decoding tolerates
/// missing keys (old files, partial server replies) exactly like kotlinx.serialization with defaults.
public struct LibraryItem: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    public var title: String
    public var filename: String
    public var fileName: String
    public var durationSeconds: Int?
    public var fileSizeBytes: Int64?
    public var genres: [String]
    public var year: Int?
    public var overview: String?
    public var posterPath: String?
    public var posterVersion: Int
    public var posterUrl: String?
    public var mediaType: String
    public var enhanced: Bool
    public var importedAt: String
    public var sourceUri: String?
    public var sourceKind: String
    public var canonicalUrl: String?
    public var copied: Bool
    public var sourceAvailable: Bool?
    public var storageState: String
    public var fingerprint: String?
    public var positionSeconds: Int
    public var lastWatchedAt: String?
    public var collectionIds: [String]
    public var resolution: String?
    public var downloadBytes: Int64?
    public var downloadTotal: Int64?
    public var uploadBytes: Int64?
    public var uploadTotal: Int64?
    public var transferStatus: String
    public var webTransferJobId: String?
    public var webTransferError: String?
    public var transferBytesPerSec: Int64
    public var cloudObjectPresent: Bool
    public var telegramCopy: Bool
    public var telegramFileKey: String?
    public var ownCloudCopy: Bool
    public var ownCloudSourceId: String?
    public var tmdbId: Int?
    public var channelTmdb: ChannelTmdb?
    public var catalogIdentityUserSelected: Bool
    public var seasonNumber: Int?
    public var episodeNumber: Int?
    public var isPrivate: Bool
    public var householdOnly: Bool
    public var userMetadataFields: Set<String>
    public var catalogItemId: String?
    public var metadataRevision: Int
    public var pendingMetadataFields: Set<String>
    public var metadataEditId: String?
    public var metadataConflict: Bool
    public var artworkOrigin: String
    public var artworkUserSelected: Bool
    public var householdArtworkVersion: Int?
    public var householdArtworkUploadedVersion: Int?

    public init(
        id: String,
        title: String,
        filename: String,
        fileName: String? = nil,
        durationSeconds: Int? = nil,
        fileSizeBytes: Int64? = nil,
        genres: [String] = [],
        year: Int? = nil,
        overview: String? = nil,
        posterPath: String? = nil,
        posterVersion: Int = 0,
        posterUrl: String? = nil,
        mediaType: String = "movie",
        enhanced: Bool = false,
        importedAt: String,
        sourceUri: String? = nil,
        sourceKind: String = "phone_local",
        canonicalUrl: String? = nil,
        copied: Bool = true,
        sourceAvailable: Bool? = nil,
        storageState: String = storageLocal,
        fingerprint: String? = nil,
        positionSeconds: Int = 0,
        lastWatchedAt: String? = nil,
        collectionIds: [String] = [],
        resolution: String? = nil,
        downloadBytes: Int64? = nil,
        downloadTotal: Int64? = nil,
        uploadBytes: Int64? = nil,
        uploadTotal: Int64? = nil,
        transferStatus: String = transferIdle,
        webTransferJobId: String? = nil,
        webTransferError: String? = nil,
        transferBytesPerSec: Int64 = 0,
        cloudObjectPresent: Bool = false,
        telegramCopy: Bool = false,
        telegramFileKey: String? = nil,
        ownCloudCopy: Bool = false,
        ownCloudSourceId: String? = nil,
        tmdbId: Int? = nil,
        channelTmdb: ChannelTmdb? = nil,
        catalogIdentityUserSelected: Bool = false,
        seasonNumber: Int? = nil,
        episodeNumber: Int? = nil,
        isPrivate: Bool = false,
        householdOnly: Bool = false,
        userMetadataFields: Set<String> = [],
        catalogItemId: String? = nil,
        metadataRevision: Int = 0,
        pendingMetadataFields: Set<String> = [],
        metadataEditId: String? = nil,
        metadataConflict: Bool = false,
        artworkOrigin: String = artworkLegacy,
        artworkUserSelected: Bool = false,
        householdArtworkVersion: Int? = nil,
        householdArtworkUploadedVersion: Int? = nil
    ) {
        self.id = id
        self.title = title
        self.filename = filename
        self.fileName = fileName ?? filename
        self.durationSeconds = durationSeconds
        self.fileSizeBytes = fileSizeBytes
        self.genres = genres
        self.year = year
        self.overview = overview
        self.posterPath = posterPath
        self.posterVersion = posterVersion
        self.posterUrl = posterUrl
        self.mediaType = mediaType
        self.enhanced = enhanced
        self.importedAt = importedAt
        self.sourceUri = sourceUri
        self.sourceKind = sourceKind
        self.canonicalUrl = canonicalUrl
        self.copied = copied
        self.sourceAvailable = sourceAvailable
        self.storageState = storageState
        self.fingerprint = fingerprint
        self.positionSeconds = positionSeconds
        self.lastWatchedAt = lastWatchedAt
        self.collectionIds = collectionIds
        self.resolution = resolution
        self.downloadBytes = downloadBytes
        self.downloadTotal = downloadTotal
        self.uploadBytes = uploadBytes
        self.uploadTotal = uploadTotal
        self.transferStatus = transferStatus
        self.webTransferJobId = webTransferJobId
        self.webTransferError = webTransferError
        self.transferBytesPerSec = transferBytesPerSec
        self.cloudObjectPresent = cloudObjectPresent
        self.telegramCopy = telegramCopy
        self.telegramFileKey = telegramFileKey
        self.ownCloudCopy = ownCloudCopy
        self.ownCloudSourceId = ownCloudSourceId
        self.tmdbId = tmdbId
        self.channelTmdb = channelTmdb
        self.catalogIdentityUserSelected = catalogIdentityUserSelected
        self.seasonNumber = seasonNumber
        self.episodeNumber = episodeNumber
        self.isPrivate = isPrivate
        self.householdOnly = householdOnly
        self.userMetadataFields = userMetadataFields
        self.catalogItemId = catalogItemId
        self.metadataRevision = metadataRevision
        self.pendingMetadataFields = pendingMetadataFields
        self.metadataEditId = metadataEditId
        self.metadataConflict = metadataConflict
        self.artworkOrigin = artworkOrigin
        self.artworkUserSelected = artworkUserSelected
        self.householdArtworkVersion = householdArtworkVersion
        self.householdArtworkUploadedVersion = householdArtworkUploadedVersion
    }

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case filename
        case fileName
        case durationSeconds
        case fileSizeBytes
        case genres
        case year
        case overview
        case posterPath
        case posterVersion
        case posterUrl
        case mediaType
        case enhanced
        case importedAt
        case sourceUri
        case sourceKind
        case canonicalUrl
        case copied
        case sourceAvailable
        case storageState
        case fingerprint
        case positionSeconds
        case lastWatchedAt
        case collectionIds
        case resolution
        case downloadBytes
        case downloadTotal
        case uploadBytes
        case uploadTotal
        case transferStatus
        case webTransferJobId
        case webTransferError
        case transferBytesPerSec
        case cloudObjectPresent
        case telegramCopy
        case telegramFileKey
        case ownCloudCopy
        case ownCloudSourceId
        case tmdbId
        case channelTmdb
        case catalogIdentityUserSelected
        case seasonNumber
        case episodeNumber
        case isPrivate = "private"
        case householdOnly
        case userMetadataFields
        case catalogItemId
        case metadataRevision
        case pendingMetadataFields
        case metadataEditId
        case metadataConflict
        case artworkOrigin
        case artworkUserSelected
        case householdArtworkVersion
        case householdArtworkUploadedVersion
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decode(String.self, forKey: .title)
        filename = try c.decode(String.self, forKey: .filename)
        fileName = try c.decodeIfPresent(String.self, forKey: .fileName) ?? filename
        durationSeconds = try c.decodeIfPresent(Int.self, forKey: .durationSeconds)
        fileSizeBytes = try c.decodeIfPresent(Int64.self, forKey: .fileSizeBytes)
        genres = try c.decodeIfPresent([String].self, forKey: .genres) ?? []
        year = try c.decodeIfPresent(Int.self, forKey: .year)
        overview = try c.decodeIfPresent(String.self, forKey: .overview)
        posterPath = try c.decodeIfPresent(String.self, forKey: .posterPath)
        posterVersion = try c.decodeIfPresent(Int.self, forKey: .posterVersion) ?? 0
        posterUrl = try c.decodeIfPresent(String.self, forKey: .posterUrl)
        mediaType = try c.decodeIfPresent(String.self, forKey: .mediaType) ?? "movie"
        enhanced = try c.decodeIfPresent(Bool.self, forKey: .enhanced) ?? false
        importedAt = try c.decode(String.self, forKey: .importedAt)
        sourceUri = try c.decodeIfPresent(String.self, forKey: .sourceUri)
        sourceKind = try c.decodeIfPresent(String.self, forKey: .sourceKind) ?? "phone_local"
        canonicalUrl = try c.decodeIfPresent(String.self, forKey: .canonicalUrl)
        copied = try c.decodeIfPresent(Bool.self, forKey: .copied) ?? true
        sourceAvailable = try c.decodeIfPresent(Bool.self, forKey: .sourceAvailable)
        storageState = try c.decodeIfPresent(String.self, forKey: .storageState) ?? storageLocal
        fingerprint = try c.decodeIfPresent(String.self, forKey: .fingerprint)
        positionSeconds = try c.decodeIfPresent(Int.self, forKey: .positionSeconds) ?? 0
        lastWatchedAt = try c.decodeIfPresent(String.self, forKey: .lastWatchedAt)
        collectionIds = try c.decodeIfPresent([String].self, forKey: .collectionIds) ?? []
        resolution = try c.decodeIfPresent(String.self, forKey: .resolution)
        downloadBytes = try c.decodeIfPresent(Int64.self, forKey: .downloadBytes)
        downloadTotal = try c.decodeIfPresent(Int64.self, forKey: .downloadTotal)
        uploadBytes = try c.decodeIfPresent(Int64.self, forKey: .uploadBytes)
        uploadTotal = try c.decodeIfPresent(Int64.self, forKey: .uploadTotal)
        transferStatus = try c.decodeIfPresent(String.self, forKey: .transferStatus) ?? transferIdle
        webTransferJobId = try c.decodeIfPresent(String.self, forKey: .webTransferJobId)
        webTransferError = try c.decodeIfPresent(String.self, forKey: .webTransferError)
        transferBytesPerSec = try c.decodeIfPresent(Int64.self, forKey: .transferBytesPerSec) ?? 0
        cloudObjectPresent = try c.decodeIfPresent(Bool.self, forKey: .cloudObjectPresent) ?? false
        telegramCopy = try c.decodeIfPresent(Bool.self, forKey: .telegramCopy) ?? false
        telegramFileKey = try c.decodeIfPresent(String.self, forKey: .telegramFileKey)
        ownCloudCopy = try c.decodeIfPresent(Bool.self, forKey: .ownCloudCopy) ?? false
        ownCloudSourceId = try c.decodeIfPresent(String.self, forKey: .ownCloudSourceId)
        tmdbId = try c.decodeIfPresent(Int.self, forKey: .tmdbId)
        channelTmdb = try c.decodeIfPresent(ChannelTmdb.self, forKey: .channelTmdb)
        catalogIdentityUserSelected = try c.decodeIfPresent(Bool.self, forKey: .catalogIdentityUserSelected) ?? false
        seasonNumber = try c.decodeIfPresent(Int.self, forKey: .seasonNumber)
        episodeNumber = try c.decodeIfPresent(Int.self, forKey: .episodeNumber)
        isPrivate = try c.decodeIfPresent(Bool.self, forKey: .isPrivate) ?? false
        householdOnly = try c.decodeIfPresent(Bool.self, forKey: .householdOnly) ?? false
        userMetadataFields = try c.decodeIfPresent(Set<String>.self, forKey: .userMetadataFields) ?? []
        catalogItemId = try c.decodeIfPresent(String.self, forKey: .catalogItemId)
        metadataRevision = try c.decodeIfPresent(Int.self, forKey: .metadataRevision) ?? 0
        pendingMetadataFields = try c.decodeIfPresent(Set<String>.self, forKey: .pendingMetadataFields) ?? []
        metadataEditId = try c.decodeIfPresent(String.self, forKey: .metadataEditId)
        metadataConflict = try c.decodeIfPresent(Bool.self, forKey: .metadataConflict) ?? false
        artworkOrigin = try c.decodeIfPresent(String.self, forKey: .artworkOrigin) ?? artworkLegacy
        artworkUserSelected = try c.decodeIfPresent(Bool.self, forKey: .artworkUserSelected) ?? false
        householdArtworkVersion = try c.decodeIfPresent(Int.self, forKey: .householdArtworkVersion)
        householdArtworkUploadedVersion = try c.decodeIfPresent(Int.self, forKey: .householdArtworkUploadedVersion)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(title, forKey: .title)
        try c.encode(filename, forKey: .filename)
        try c.encode(fileName, forKey: .fileName)
        try c.encodeIfPresent(durationSeconds, forKey: .durationSeconds)
        try c.encodeIfPresent(fileSizeBytes, forKey: .fileSizeBytes)
        try c.encode(genres, forKey: .genres)
        try c.encodeIfPresent(year, forKey: .year)
        try c.encodeIfPresent(overview, forKey: .overview)
        try c.encodeIfPresent(posterPath, forKey: .posterPath)
        try c.encode(posterVersion, forKey: .posterVersion)
        try c.encodeIfPresent(posterUrl, forKey: .posterUrl)
        try c.encode(mediaType, forKey: .mediaType)
        try c.encode(enhanced, forKey: .enhanced)
        try c.encode(importedAt, forKey: .importedAt)
        try c.encodeIfPresent(sourceUri, forKey: .sourceUri)
        try c.encode(sourceKind, forKey: .sourceKind)
        try c.encodeIfPresent(canonicalUrl, forKey: .canonicalUrl)
        try c.encode(copied, forKey: .copied)
        try c.encodeIfPresent(sourceAvailable, forKey: .sourceAvailable)
        try c.encode(storageState, forKey: .storageState)
        try c.encodeIfPresent(fingerprint, forKey: .fingerprint)
        try c.encode(positionSeconds, forKey: .positionSeconds)
        try c.encodeIfPresent(lastWatchedAt, forKey: .lastWatchedAt)
        try c.encode(collectionIds, forKey: .collectionIds)
        try c.encodeIfPresent(resolution, forKey: .resolution)
        try c.encodeIfPresent(downloadBytes, forKey: .downloadBytes)
        try c.encodeIfPresent(downloadTotal, forKey: .downloadTotal)
        try c.encodeIfPresent(uploadBytes, forKey: .uploadBytes)
        try c.encodeIfPresent(uploadTotal, forKey: .uploadTotal)
        try c.encode(transferStatus, forKey: .transferStatus)
        try c.encodeIfPresent(webTransferJobId, forKey: .webTransferJobId)
        try c.encodeIfPresent(webTransferError, forKey: .webTransferError)
        try c.encode(transferBytesPerSec, forKey: .transferBytesPerSec)
        try c.encode(cloudObjectPresent, forKey: .cloudObjectPresent)
        try c.encode(telegramCopy, forKey: .telegramCopy)
        try c.encodeIfPresent(telegramFileKey, forKey: .telegramFileKey)
        try c.encode(ownCloudCopy, forKey: .ownCloudCopy)
        try c.encodeIfPresent(ownCloudSourceId, forKey: .ownCloudSourceId)
        try c.encodeIfPresent(tmdbId, forKey: .tmdbId)
        try c.encodeIfPresent(channelTmdb, forKey: .channelTmdb)
        try c.encode(catalogIdentityUserSelected, forKey: .catalogIdentityUserSelected)
        try c.encodeIfPresent(seasonNumber, forKey: .seasonNumber)
        try c.encodeIfPresent(episodeNumber, forKey: .episodeNumber)
        try c.encode(isPrivate, forKey: .isPrivate)
        try c.encode(householdOnly, forKey: .householdOnly)
        try c.encode(userMetadataFields, forKey: .userMetadataFields)
        try c.encodeIfPresent(catalogItemId, forKey: .catalogItemId)
        try c.encode(metadataRevision, forKey: .metadataRevision)
        try c.encode(pendingMetadataFields, forKey: .pendingMetadataFields)
        try c.encodeIfPresent(metadataEditId, forKey: .metadataEditId)
        try c.encode(metadataConflict, forKey: .metadataConflict)
        try c.encode(artworkOrigin, forKey: .artworkOrigin)
        try c.encode(artworkUserSelected, forKey: .artworkUserSelected)
        try c.encodeIfPresent(householdArtworkVersion, forKey: .householdArtworkVersion)
        try c.encodeIfPresent(householdArtworkUploadedVersion, forKey: .householdArtworkUploadedVersion)
    }
}
