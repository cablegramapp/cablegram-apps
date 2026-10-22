import Foundation

/// Only explicit cover choices change artwork. Opening an editor keeps the current cover.
public enum CoverChoice: Sendable, Equatable {
    case keep
    case frame(path: String)
    case catalog(path: String, url: String)
}

public struct ArtworkDraft: Sendable, Equatable {
    public var base: LibraryItem
    public var session: Int64
    public var title: String
    public var year: String
    public var mediaType: String
    public var overview: String
    public var cover: CoverChoice = .keep
    public var query: String
    public var frames: [String] = []
    public var loadingFrames = true
    public var searching = false
    public var saving = false
    public var message: String?
    public var candidate: CatalogMetadata?
    public var catalogPath: String?
    public var useCatalogDetails = false
    public var acceptedMatch: CatalogMetadata?
    public var season: String
    public var episode: String
    public var keepBoth = false
    public var duplicate = false
    public var discardRequested = false

    public init(base: LibraryItem, session: Int64) {
        self.base = base
        self.session = session
        title = base.title
        year = base.year.map(String.init) ?? ""
        mediaType = base.mediaType
        overview = base.overview ?? ""
        query = base.title
        season = base.seasonNumber.map(String.init) ?? ""
        episode = base.episodeNumber.map(String.init) ?? ""
    }

    private func normalized(_ text: String?) -> String? {
        let t = text?.trimmingCharacters(in: .whitespacesAndNewlines)
        return t?.isEmpty == false ? t : nil
    }

    public var changed: Bool {
        title.trimmingCharacters(in: .whitespacesAndNewlines) != base.title || Int(year) != base.year || mediaType != base.mediaType ||
            normalized(overview) != normalized(base.overview) || cover != .keep || useCatalogDetails ||
            Int(season) != base.seasonNumber || Int(episode) != base.episodeNumber
    }

    public var valid: Bool {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let yearOk = year.isEmpty || Int(year).map { (1...9999).contains($0) } == true
        let episodeOk = mediaType != "tv" || !useCatalogDetails || ((Int(season) ?? -1) >= 0 && (Int(episode) ?? 0) > 0)
        return !t.isEmpty && t.count <= 500 && yearOk && episodeOk && (!duplicate || keepBoth)
    }
}

/// Merge against the current item, keeping concurrent changes to untouched fields.
public func commitArtworkDraft(current: LibraryItem, draft: ArtworkDraft) throws -> LibraryItem {
    var base = draft.base
    let trimmed = base.overview?.trimmingCharacters(in: .whitespacesAndNewlines)
    base.overview = trimmed?.isEmpty == false ? trimmed : nil
    var edited = try commitManualMetadataDraft(current: current, base: base, title: draft.title, year: Int(draft.year),
                                               mediaType: draft.mediaType, overview: draft.overview)
    guard draft.useCatalogDetails else { return edited }
    guard let match = draft.acceptedMatch else { throw MetadataValidationError(reason: "acceptedMatch") }
    edited.tmdbId = match.tmdbId
    edited.catalogIdentityUserSelected = true
    edited.genres = match.genres
    edited.seasonNumber = draft.mediaType == "tv" ? Int(draft.season) : nil
    edited.episodeNumber = draft.mediaType == "tv" ? Int(draft.episode) : nil
    return edited
}

/// Structured episode identity survives normalized display titles.
public func hasDuplicateEpisode(_ items: [LibraryItem], draft: ArtworkDraft) -> Bool {
    guard let match = draft.acceptedMatch, let season = Int(draft.season), let episode = Int(draft.episode) else { return false }
    return draft.useCatalogDetails && draft.mediaType == "tv" && items.contains {
        $0.id != draft.base.id && $0.mediaType == "tv" &&
            (match.tmdbId != nil ? $0.tmdbId == match.tmdbId : $0.title.caseInsensitiveCompare(match.title) == .orderedSame) &&
            $0.seasonNumber == season && $0.episodeNumber == episode
    }
}
