import Foundation

// Port of the data classes in `SmartSubtitles.kt` and `LocalSubtitleDiscovery.kt`. Field names are the wire names the control
// plane's `/api/subtitles` routes use (the same ones Android sends), so Codable's defaults are kept.

public struct SubtitlePreferences: Codable, Sendable, Equatable {
    public var languages: [String]
    public var sdh: String
    public var includeForced: Bool
    public var autoSelect: Bool

    public init(languages: [String] = ["fa", "en", "de"], sdh: String = "normal", includeForced: Bool = true, autoSelect: Bool = false) {
        self.languages = languages; self.sdh = sdh; self.includeForced = includeForced; self.autoSelect = autoSelect
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        languages = try c.decodeIfPresent([String].self, forKey: .languages) ?? ["fa", "en", "de"]
        sdh = try c.decodeIfPresent(String.self, forKey: .sdh) ?? "normal"
        includeForced = try c.decodeIfPresent(Bool.self, forKey: .includeForced) ?? true
        autoSelect = try c.decodeIfPresent(Bool.self, forKey: .autoSelect) ?? false
    }
}

public struct SubtitleCue: Codable, Sendable, Equatable {
    public var start: Double
    public var end: Double
    public var text: String
    public init(start: Double, end: Double, text: String) { self.start = start; self.end = end; self.text = text }
}

public struct SubtitleAlignment: Codable, Sendable, Equatable {
    public var verified: Bool
    public var offset: Double
    public var scale: Double
    public var reason: String
    public var correlation: Double
    public var samples: Int

    public init(verified: Bool = false, offset: Double = 0, scale: Double = 1, reason: String = "", correlation: Double = 0, samples: Int = 0) {
        self.verified = verified; self.offset = offset; self.scale = scale; self.reason = reason; self.correlation = correlation; self.samples = samples
    }
}

public struct SubtitleMatch: Codable, Sendable, Equatable {
    public var id: String
    public var language: String
    public var confidence: String
    public var alignment: SubtitleAlignment?
    public var cues: [SubtitleCue]?
    public var error: String?
    public var provider: String
    public var releaseName: String
    public var explanations: [String]
    public var forced: Bool
    public var score: Double

    public init(id: String, language: String, confidence: String = "Unverified", alignment: SubtitleAlignment? = nil, cues: [SubtitleCue]? = nil,
                error: String? = nil, provider: String = "", releaseName: String = "", explanations: [String] = [], forced: Bool = false, score: Double = 0) {
        self.id = id; self.language = language; self.confidence = confidence; self.alignment = alignment; self.cues = cues; self.error = error
        self.provider = provider; self.releaseName = releaseName; self.explanations = explanations; self.forced = forced; self.score = score
    }
}

public struct SubtitleSelection: Codable, Sendable, Equatable {
    public var id: String
    public var language: String
    public var confidence: String
    public init(id: String, language: String = "", confidence: String = "Unverified") { self.id = id; self.language = language; self.confidence = confidence }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        language = try c.decodeIfPresent(String.self, forKey: .language) ?? ""
        confidence = try c.decodeIfPresent(String.self, forKey: .confidence) ?? "Unverified"
    }
}

public struct SubtitleProviderFailure: Codable, Sendable, Equatable {
    public var provider: String
    public var code: String
    public var retryAfter: Int?
    public init(provider: String = "", code: String, retryAfter: Int? = nil) { self.provider = provider; self.code = code; self.retryAfter = retryAfter }
}

public struct SubtitleDiscovery: Sendable, Equatable {
    public var id: String
    public var sourceId: String
    public var candidates: [SubtitleMatch]
    public var status: String
    public var errors: [SubtitleProviderFailure]
    public var selected: SubtitleSelection?
    public var existingLanguages: [String]

    public init(id: String, sourceId: String, candidates: [SubtitleMatch] = [], status: String, errors: [SubtitleProviderFailure] = [],
                selected: SubtitleSelection? = nil, existingLanguages: [String] = []) {
        self.id = id; self.sourceId = sourceId; self.candidates = candidates; self.status = status; self.errors = errors
        self.selected = selected; self.existingLanguages = existingLanguages
    }
}

/// One stretch of "is someone speaking" at `step` seconds a bit (the audio check's fingerprint; off in the first release).
public struct SubtitleActivityWindow: Codable, Sendable, Equatable {
    public var start: Double
    public var step: Double
    public var bits: String
    public init(start: Double, step: Double = 0.1, bits: String) { self.start = start; self.step = step; self.bits = bits }
}

public struct SubtitleFingerprint: Codable, Sendable, Equatable {
    public var version: Int
    public var windows: [SubtitleActivityWindow]
    public init(version: Int = 1, windows: [SubtitleActivityWindow]) { self.version = version; self.windows = windows }
}

public struct SubtitleTechnical: Codable, Sendable, Equatable {
    public var hash: String?
    public var size: Int64?
    public var duration: Double?
    public var fps: Double?
    public var codec: String?
    public var resolution: String?
    public var audioLanguages: [String]
    public var embeddedLanguages: [String]

    public init(hash: String? = nil, size: Int64? = nil, duration: Double? = nil, fps: Double? = nil, codec: String? = nil, resolution: String? = nil,
                audioLanguages: [String] = [], embeddedLanguages: [String] = []) {
        self.hash = hash; self.size = size; self.duration = duration; self.fps = fps; self.codec = codec; self.resolution = resolution
        self.audioLanguages = audioLanguages; self.embeddedLanguages = embeddedLanguages
    }
}

public struct SubtitleSourceContext: Codable, Sendable, Equatable {
    public var sourceId: String
    public var identity: String
    public var filename: String
    public var title: String
    public var year: Int?
    public var tmdbId: Int?
    public var season: Int?
    public var episode: Int?
    public var duration: Double?

    public init(sourceId: String, identity: String, filename: String, title: String, year: Int? = nil, tmdbId: Int? = nil, season: Int? = nil,
                episode: Int? = nil, duration: Double? = nil) {
        self.sourceId = sourceId; self.identity = identity; self.filename = filename; self.title = title; self.year = year; self.tmdbId = tmdbId
        self.season = season; self.episode = episode; self.duration = duration
    }
}

/// What the phone uploads once the person picked a subtitle: the chosen track only, never a credential or the search.
public struct SelectedSubtitleTrack: Codable, Sendable, Equatable {
    public var sourceId: String
    public var identity: String
    public var language: String
    public var provider: String
    public var providerRef: String
    public var cues: [SubtitleCue]
    public var offset: Double
    public var scale: Double
    public var forced: Bool
    public var confidence: String
    public var automatic: Bool

    public init(sourceId: String, identity: String, language: String, provider: String, providerRef: String, cues: [SubtitleCue], offset: Double,
                scale: Double, forced: Bool, confidence: String, automatic: Bool = false) {
        self.sourceId = sourceId; self.identity = identity; self.language = language; self.provider = provider; self.providerRef = providerRef
        self.cues = cues; self.offset = offset; self.scale = scale; self.forced = forced; self.confidence = confidence; self.automatic = automatic
    }
}

public struct SavedSubtitle: Codable, Sendable, Equatable {
    public var language: String
    public var trackId: String
    public init(language: String, trackId: String = "") { self.language = language; self.trackId = trackId }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        language = try c.decode(String.self, forKey: .language)
        trackId = try c.decodeIfPresent(String.self, forKey: .trackId) ?? ""
    }
}

public struct SavedSubtitleList: Codable, Sendable, Equatable {
    public var subtitles: [SavedSubtitle]
    public init(subtitles: [SavedSubtitle] = []) { self.subtitles = subtitles }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        subtitles = try c.decodeIfPresent([SavedSubtitle].self, forKey: .subtitles) ?? []
    }
}

/// A saved subtitle's original cues and the timing it plays with now (`GET /api/subtitles/:id/timing`).
public struct SavedSubtitleTiming: Codable, Sendable, Equatable {
    public var id: String
    public var language: String
    public var offset: Double
    public var scale: Double
    public var cues: [SubtitleCue]
    public init(id: String, language: String, offset: Double, scale: Double, cues: [SubtitleCue]) {
        self.id = id; self.language = language; self.offset = offset; self.scale = scale; self.cues = cues
    }
}

public struct LocalSubtitleError: Error, Equatable, Sendable {
    public let code: String
    public let retryAfter: Int?
    public init(_ code: String, retryAfter: Int? = nil) { self.code = code; self.retryAfter = retryAfter }
}

/// A title whose bytes can't be read for subtitle matching; the message is shown to the person as is.
public struct VideoUnavailable: Error, Sendable { public let message: String; public init(_ message: String) { self.message = message } }
