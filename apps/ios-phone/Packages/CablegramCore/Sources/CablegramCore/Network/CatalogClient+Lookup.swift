import Foundation

public struct TitleSuggestion: Codable, Sendable, Equatable {
    public var title: String
    public var year: Int?
    public var mediaType: String
    public var source: String
    public init(title: String, year: Int? = nil, mediaType: String = "movie", source: String = "library", maxSeason: Int? = nil, maxEpisode: Int? = nil) {
        self.title = title; self.year = year; self.mediaType = mediaType; self.source = source; self.maxSeason = maxSeason; self.maxEpisode = maxEpisode
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = try c.decode(String.self, forKey: .title)
        year = try? c.decodeIfPresent(Int.self, forKey: .year)
        mediaType = (try? c.decodeIfPresent(String.self, forKey: .mediaType)) ?? "movie"
        source = (try? c.decodeIfPresent(String.self, forKey: .source)) ?? "library"
        maxSeason = try? c.decodeIfPresent(Int.self, forKey: .maxSeason)
        maxEpisode = try? c.decodeIfPresent(Int.self, forKey: .maxEpisode)
    }
    private enum CodingKeys: String, CodingKey { case title, year, mediaType, source, maxSeason, maxEpisode }
    /// Highest known season for this series title in the local library.
    public var maxSeason: Int?
    public var maxEpisode: Int?
}

public struct TitleResolveResponse: Sendable, Equatable {
    public var found: Bool
    public var metadata: CatalogMetadata?
    public init(found: Bool = false, metadata: CatalogMetadata? = nil) {
        self.found = found
        self.metadata = metadata
    }
}

extension CharacterSet {
    /// What Java's `URLEncoder` leaves alone: `&`, `+`, `=` and the like are escaped so a title such as "Tom & Jerry" or "C++" survives.
    static let queryValueAllowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
}

private struct SuggestResponse: Decodable {
    var suggestions: [TitleSuggestion]
    init(from decoder: Decoder) throws {
        // One malformed suggestion must not empty the whole list (kotlinx defaults: mediaType "movie", source "library").
        var list = try decoder.container(keyedBy: CodingKeys.self).nestedUnkeyedContainer(forKey: .suggestions)
        var items: [TitleSuggestion] = []
        while !list.isAtEnd { if let item = try? list.decode(TitleSuggestion.self) { items.append(item) } else { _ = try? list.decode(Discard.self) } }
        suggestions = items
    }
    private enum CodingKeys: String, CodingKey { case suggestions }
    private struct Discard: Decodable {}
}
private struct MatchResponse: Decodable { var metadata: CatalogMetadata }
private struct ResolveResponse: Decodable { var found: Bool?; var metadata: CatalogMetadata? }

/// Catalog lookups used by the artwork editor and title prompts (Android `suggestTitles`, `enrich`, `resolveTitle`).
extension CatalogClient {
    public func suggestTitles(query: String, token: String?) async -> [TitleSuggestion] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let token, !token.isEmpty, trimmed.count >= 2,
              let encoded = trimmed.addingPercentEncoding(withAllowedCharacters: .queryValueAllowed) else { return [] }
        let reply = await send("GET", "/api/library/suggest?q=\(encoded)", token: token)
        return reply.isSuccess ? (reply.decode(SuggestResponse.self)?.suggestions ?? []) : []
    }

    /// Looks a title up in the catalog. With `strict` a failed request throws `CatalogError`; otherwise it returns nil.
    public func enrich(query: String, token: String?, itemId: String?, mediaType: String? = nil, seasonNumber: Int? = nil,
                       episodeNumber: Int? = nil, year: Int? = nil, imdbId: String? = nil, strict: Bool = false,
                       tmdbId: Int? = nil) async throws -> CatalogMetadata? {
        var body: [String: Any] = ["query": query]
        if let itemId, !itemId.isEmpty { body["itemId"] = itemId }
        if let mediaType, !mediaType.isEmpty { body["mediaType"] = mediaType }
        if let seasonNumber { body["seasonNumber"] = seasonNumber }
        if let episodeNumber { body["episodeNumber"] = episodeNumber }
        if let year { body["year"] = year }
        if let imdbId, !imdbId.isEmpty { body["imdbId"] = imdbId }
        if let tmdbId { body["tmdbId"] = tmdbId }
        let reply = await send("POST", "/api/library/enrich", token: token, json: body)
        guard reply.isSuccess else {
            if strict { throw CatalogError(status: reply.status) }
            return nil
        }
        return reply.decode(MatchResponse.self)?.metadata
    }

    /// `itemId` is the household item the search is for: the server then keeps a title still taken from a Telegram caption
    /// or file name away from the AI (CAB-42).
    public func resolveTitle(query: String, token: String?, strict: Bool = false, itemId: String? = nil) async throws -> TitleResolveResponse {
        var body: [String: Any] = ["query": query]
        if let itemId { body["item_id"] = itemId }
        let reply = await send("POST", "/api/library/resolve-title", token: token, json: body)
        guard reply.isSuccess else {
            if strict { throw CatalogError(status: reply.status) }
            return TitleResolveResponse(found: false)
        }
        let parsed = reply.decode(ResolveResponse.self)
        return TitleResolveResponse(found: parsed?.found ?? false, metadata: parsed?.metadata)
    }
}
