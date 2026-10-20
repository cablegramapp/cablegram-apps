import Foundation

/// What the library shows as one poster: a film, or a whole series (all its episodes together), as the TV does.
public enum LibraryEntry: Sendable, Equatable, Identifiable {
    case film(LibraryItem)
    case series(key: String, episodes: [LibraryItem])

    public var id: String { key }

    public var key: String {
        switch self {
        case .film(let item): item.id
        case .series(let key, _): key
        }
    }

    public var title: String {
        switch self {
        case .film(let item): item.title
        // The show name is the title of its first episode, as on the TV.
        case .series(_, let episodes): episodes[0].title
        }
    }

    /// The item whose poster, progress and menu the card uses: the episode in progress, else the first with a poster,
    /// else the first.
    public var lead: LibraryItem {
        switch self {
        case .film(let item): return item
        case .series(_, let episodes):
            if let watching = episodes.max(by: { $0.positionSeconds < $1.positionSeconds }), watching.positionSeconds > 0 { return watching }
            return episodes.first { $0.posterPath != nil || $0.posterUrl != nil } ?? episodes[0]
        }
    }

    public var episodeCount: Int {
        if case .series(_, let episodes) = self { return episodes.count }
        return 0
    }

    public var seasonCount: Int {
        guard case .series(_, let episodes) = self else { return 0 }
        return Set(episodes.compactMap(\.seasonNumber)).count
    }
}

/// The same key the TV uses: one series per TMDB id, or per title when it has none.
public func seriesKey(_ item: LibraryItem) -> String {
    item.tmdbId.map { "tmdb:\($0)" } ?? "title:\(item.title.trimmingCharacters(in: .whitespaces).lowercased())"
}

/// Season, then episode, then import time; unknown positions sort last.
public func episodeOrder(_ a: LibraryItem, _ b: LibraryItem) -> Bool {
    let keyA = (a.seasonNumber ?? .max, a.episodeNumber ?? .max, a.importedAt)
    let keyB = (b.seasonNumber ?? .max, b.episodeNumber ?? .max, b.importedAt)
    return keyA < keyB
}

/// Films stay as they are; the episodes of one series collapse into a single series entry placed where its first
/// episode was, so the shelf order (recently added, continue watching…) is kept.
public func groupIntoEntries(_ items: [LibraryItem]) -> [LibraryEntry] {
    let episodesByKey = Dictionary(grouping: items.filter { $0.mediaType == "tv" }, by: seriesKey)
    var placed = Set<String>()
    return items.compactMap { item in
        if item.mediaType != "tv" { return .film(item) }
        let key = seriesKey(item)
        guard placed.insert(key).inserted else { return nil }
        return .series(key: key, episodes: episodesByKey[key]!.sorted(by: episodeOrder))
    }
}

/// "S1 · E3" for an episode, or just "E3", or nothing when its position is unknown.
public func episodeBadge(_ item: LibraryItem) -> String? {
    if let season = item.seasonNumber, let episode = item.episodeNumber { return "S\(season) · E\(episode)" }
    if let episode = item.episodeNumber { return "E\(episode)" }
    return nil
}
