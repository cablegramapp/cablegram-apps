import CryptoKit
import Foundation

private enum Pattern {
    static let vttHeader = Rx.make(#"^WEBVTT(?:[ \t][^\n]*)?(?:\n|$)"#)
    static let scriptInfo = Rx.make(#"(?im)^\s*\[Script Info]|^Dialogue:"#)
    static let vttBlocks = Rx.make(#"\n[ \t]*\n"#)
    static let vttMetadata = Rx.make(#"^(NOTE(?:[ \t]|$)|STYLE$|REGION$)"#)
    static let language = Rx.make(#"^[a-z]{2}$"#)
}

/// A transient attachment: neither its document location nor its filename enters household storage.
public struct LocalSubtitleFile: Sendable, Equatable {
    public let reference: String
    public let cues: [SubtitleCue]

    public static let maxBytes = 2_000_000

    public func selected(source: SubtitleSourceContext, language: String) throws -> SelectedSubtitleTrack {
        guard Rx.matchesEntirely(Pattern.language, language) else { throw LocalSubtitleError("invalid_subtitle") }
        return SelectedSubtitleTrack(sourceId: source.sourceId, identity: source.identity, language: language, provider: "local", providerRef: reference,
                                     cues: cues, offset: 0, scale: 1, forced: false, confidence: "Unverified")
    }

    /// Reads at most `maxBytes` from a source of unknown length: `next` returns up to the asked number of bytes, empty at the end.
    public static func read(next: (Int) throws -> Data, checkCancelled: () throws -> Void = {}) throws -> Data {
        var out = Data()
        while true {
            try checkCancelled()
            let chunk = try next(min(8192, maxBytes + 1 - out.count))
            if chunk.isEmpty { break }
            if out.count + chunk.count > maxBytes { throw LocalSubtitleError("local_subtitle_too_large") }
            out.append(chunk)
        }
        return out
    }

    public static func read(url: URL, checkCancelled: () throws -> Void = {}) throws -> Data {
        guard let handle = try? FileHandle(forReadingFrom: url) else { throw LocalSubtitleError("local_subtitle_unreadable") }
        defer { try? handle.close() }
        return try read(next: { try handle.read(upToCount: $0) ?? Data() }, checkCancelled: checkCancelled)
    }

    public static func parse(name: String, bytes: Data, language: String) throws -> LocalSubtitleFile {
        let ext = name.contains(".") ? String(name[name.index(after: name.lastIndex(of: ".")!)...]).lowercased() : ""
        guard ext == "srt" || ext == "vtt" else { throw LocalSubtitleError("local_subtitle_format") }
        if bytes.count > maxBytes { throw LocalSubtitleError("local_subtitle_too_large") }
        // Local attachment accepts a single text document, never an archive or provider response.
        if bytes.count >= 2, bytes[bytes.startIndex] == 0x50, bytes[bytes.startIndex + 1] == 0x4b { throw LocalSubtitleError("local_subtitle_format") }
        var text = try LocalSubtitleTimeline.decode(bytes, language: language, season: nil, episode: nil)
        if text.hasPrefix("\u{FEFF}") { text.removeFirst() }
        text = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        if ext == "vtt", !Rx.contains(Pattern.vttHeader, in: text) { throw LocalSubtitleError("invalid_subtitle") }
        if Rx.contains(Pattern.scriptInfo, in: text) { throw LocalSubtitleError("local_subtitle_format") }
        // WebVTT comments and style/region blocks are metadata, not spoken cues.
        let content = ext == "vtt"
            ? Rx.split(Pattern.vttBlocks, text).filter { block in
                let first = block.drop { $0.isWhitespace }.prefix { $0 != "\n" }
                return !Rx.contains(Pattern.vttMetadata, in: String(first))
            }.joined(separator: "\n\n")
            : text
        let cues = try LocalSubtitleTimeline.parse(content)
        // A stray "<" or ">" (">> NARRATOR:", "<3") is ordinary caption text; the server shows it safely.
        if cues.contains(where: { cue in cue.start < 0 || cue.text.unicodeScalars.contains { $0.value < 32 && $0 != "\t" && $0 != "\n" && $0 != "\r" } }) {
            throw LocalSubtitleError("invalid_subtitle")
        }
        if (try? JSONEncoder().encode(cues).count) ?? Int.max > maxBytes { throw LocalSubtitleError("local_subtitle_too_large") }
        let reference = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        return LocalSubtitleFile(reference: reference, cues: cues)
    }
}
