import Foundation

// Port of `ChannelHints.kt` (CAB-60).

/// A TMDB title named for one library video: `uniqueId` is the video's Telegram file id (file_unique_id).
public struct TgTmdbHint: Sendable, Equatable {
    public var uniqueId: String
    public var tmdbType: String
    public var tmdbId: Int
    public var season: Int?
    public var episode: Int?
    public init(uniqueId: String, tmdbType: String, tmdbId: Int, season: Int? = nil, episode: Int? = nil) {
        self.uniqueId = uniqueId; self.tmdbType = tmdbType; self.tmdbId = tmdbId; self.season = season; self.episode = episode
    }
}

/// TMDB references in the library channel. A message that mentions a library video as `#<file_unique_id>` and names one TMDB title
/// (`#movie_<id>`, `#tv_<id>`, `#tv_<id>_s02e03`, or a themoviedb.org link) describes that video, and so does a TMDB reference in the
/// video's own caption. Bots and people both post these; nothing here depends on who did or on how the message is laid out. Several
/// different titles in one message are ambiguous and ignored.
public enum ChannelHints {
    private struct Reference: Hashable { var type: String; var id: Int; var season: Int?; var episode: Int? }

    private static let tag = try! NSRegularExpression(pattern: #"#(movie|tv)_(\d{1,10})(?:_s(\d{1,4})e(\d{1,5}))?(?![A-Za-z0-9_])"#, options: .caseInsensitive)
    private static let link = try! NSRegularExpression(pattern: #"themoviedb\.org/(movie|tv)/(\d{1,10})(?:-[^\s/?#]*)?(?:/season/(\d{1,4})(?:/episode/(\d{1,5}))?)?"#, options: .caseInsensitive)
    private static let mention = try! NSRegularExpression(pattern: #"#([A-Za-z0-9_-]{8,64})"#)

    private static func groups(_ match: NSTextCheckingResult, in text: String) -> [String] {
        (1...4).map { index in Range(match.range(at: index), in: text).map { String(text[$0]) } ?? "" }
    }

    private static func reference(_ text: String) -> Reference? {
        let whole = NSRange(text.startIndex..., in: text)
        var found = Set<Reference>()
        for match in tag.matches(in: text, range: whole) + link.matches(in: text, range: whole) {
            let g = groups(match, in: text)
            guard let id = Int(g[1]), id > 0 else { continue }
            let tv = g[0].lowercased() == "tv"
            found.insert(Reference(type: tv ? "tv" : "movie", id: id, season: tv && !g[3].isEmpty ? Int(g[2]) : nil, episode: tv && !g[2].isEmpty ? Int(g[3]) : nil))
        }
        // The same title written twice (a tag and a link) is one; two different titles are a guess not worth making.
        let titles = Set(found.map { "\($0.type):\($0.id)" })
        guard titles.count == 1 else { return nil }
        return found.first { $0.season != nil } ?? found.first
    }

    /// The hints in `message` for videos in `known` (file ids); other hashtags are never sent anywhere.
    public static func from(_ message: TgMessage, known: Set<String>) -> [TgTmdbHint] {
        let text = message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? (message.video?.caption ?? "") : message.text
        guard let ref = reference(text) else { return [] }
        var ids: [String] = []
        if let own = message.video?.file.uniqueId, !own.isEmpty { ids.append(own) }
        for match in mention.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            if let range = Range(match.range(at: 1), in: text) { let id = String(text[range]); if known.contains(id), !ids.contains(id) { ids.append(id) } }
        }
        return ids.map { TgTmdbHint(uniqueId: $0, tmdbType: ref.type, tmdbId: ref.id, season: ref.season, episode: ref.episode) }
    }
}
