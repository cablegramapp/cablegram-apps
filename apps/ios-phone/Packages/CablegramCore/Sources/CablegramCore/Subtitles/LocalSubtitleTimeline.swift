import Compression
import CoreFoundation
import Foundation

/// Regex helpers with Kotlin's `Regex` semantics (ICU syntax is the same for everything the subtitle code uses).
enum Rx {
    static func make(_ pattern: String, _ options: NSRegularExpression.Options = []) -> NSRegularExpression {
        // The patterns are literals in this file; a typo should fail loudly in tests.
        try! NSRegularExpression(pattern: pattern, options: options)
    }

    static func range(_ text: String) -> NSRange { NSRange(text.startIndex..., in: text) }

    static func contains(_ regex: NSRegularExpression, in text: String) -> Bool { regex.firstMatch(in: text, range: range(text)) != nil }

    static func matchesEntirely(_ regex: NSRegularExpression, _ text: String) -> Bool {
        guard let m = regex.firstMatch(in: text, range: range(text)) else { return false }
        return m.range == range(text)
    }

    static func replacing(_ regex: NSRegularExpression, in text: String, with template: String) -> String {
        regex.stringByReplacingMatches(in: text, range: range(text), withTemplate: template)
    }

    /// Kotlin `String.split(Regex)`: keeps empty pieces, including trailing ones.
    static func split(_ regex: NSRegularExpression, _ text: String) -> [String] {
        var pieces: [String] = []
        var from = text.startIndex
        for m in regex.matches(in: text, range: range(text)) {
            guard let r = Range(m.range, in: text) else { continue }
            pieces.append(String(text[from..<r.lowerBound]))
            from = r.upperBound
        }
        pieces.append(String(text[from...]))
        return pieces
    }

    static func group(_ m: NSTextCheckingResult, _ index: Int, in text: String) -> String? {
        let r = m.range(at: index)
        return r.location == NSNotFound ? nil : Range(r, in: text).map { String(text[$0]) }
    }
}

private enum Pattern {
    static let episode = Rx.make(#"(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]*e(\d{1,3})(?!\d)|(?<![a-z0-9])(\d{1,2})x(\d{2,3})(?!\d)"#)
    static let extensionSuffix = Rx.make(#"\.[a-z0-9]{2,4}$"#)
    static let subtitleEntry = Rx.make(#"(?i)\.(srt|vtt|ass|ssa)$"#)
    static let scriptInfo = Rx.make(#"(?im)^\s*\[Script Info]|^Dialogue:"#)
    static let blockSplit = Rx.make(#"\n\s*\n"#)
    static let arrow = Rx.make(#"\s*-->\s*"#)
    static let tags = Rx.make(#"<[^>]*>"#)
    static let assBraces = Rx.make(#"\{[^}]*\}"#)
    static let assNewline = Rx.make(#"\\[Nn]"#)
    static let assDrawing = Rx.make(#"\{[^}]*\\p[1-9]"#)
    static let timeSrt = Rx.make(#"^(?:(\d{1,3}):)?(\d{2}):(\d{2})[,.](\d{3})$"#)
    static let timeAss = Rx.make(#"^(\d+):(\d{2}):(\d{2})[.](\d{1,3})$"#)
}

/// All archive work is in memory. Only the uniquely selected episode is inflated.
public enum LocalSubtitleTimeline {
    private static let limit = 2_000_000
    private static func invalid() -> LocalSubtitleError { LocalSubtitleError("invalid_subtitle") }

    public static func episode(_ name: String) -> (season: Int, episode: Int)? {
        let last = name.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? name
        guard let m = Pattern.episode.firstMatch(in: last, range: Rx.range(last)) else { return nil }
        if let s = Rx.group(m, 1, in: last), !s.isEmpty { return (Int(s) ?? 0, Int(Rx.group(m, 2, in: last) ?? "") ?? 0) }
        return (Int(Rx.group(m, 3, in: last) ?? "") ?? 0, Int(Rx.group(m, 4, in: last) ?? "") ?? 0)
    }

    /// The one archive entry that names this episode, or nil when none or several do.
    public static func pickEpisode(_ names: [String], season: Int?, episode: Int?) -> Int? {
        guard let episode else { return nil }
        let s = season.map { "0*\($0)" } ?? #"\d{1,2}"#
        let tiers = [
            "s\(s)[ ._-]*e0*\(episode)(?!\\d)",
            "(?<!\\d)\(s)x0*\(episode)(?!\\d)",
            "(?<![a-z0-9])e(?:p|pisode)?[ ._-]*0*\(episode)(?!\\d)",
            "(?<![a-z0-9])0*\(episode)(?![a-z0-9])",
        ]
        for tier in tiers {
            let regex = Rx.make(tier)
            let found = names.indices.filter { i in
                var n = names[i].split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? names[i]
                n = n.split(separator: "\\", omittingEmptySubsequences: false).last.map(String.init) ?? n
                n = Rx.replacing(Pattern.extensionSuffix, in: n.lowercased(), with: "")
                let named = Self.episode(n)
                return (season == nil || named == nil || named!.season == season) && Rx.contains(regex, in: n)
            }
            if found.count == 1 { return found[0] }
            if found.count > 1 { return nil }
        }
        return nil
    }

    // MARK: Archives and text encodings

    private static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data { crc = crcTable[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8) }
        return crc ^ 0xFFFF_FFFF
    }

    private static let crcTable: [UInt32] = (0..<256).map { n in
        var c = UInt32(n)
        for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    private static func inflate(_ data: Data) -> Data? {
        // Compression's ZLIB is raw DEFLATE (RFC 1951), which is what a zip entry holds.
        let capacity = limit + 1
        var out = [UInt8](repeating: 0, count: capacity)
        let written = data.withUnsafeBytes { source -> Int in
            guard let base = source.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return compression_decode_buffer(&out, capacity, base, data.count, nil, COMPRESSION_ZLIB)
        }
        guard written > 0, written <= limit else { return nil }
        return Data(out[0..<written])
    }

    public static func decode(_ bytes: Data, language: String, season: Int?, episode: Int?) throws -> String {
        if bytes.isEmpty || bytes.count > limit { throw invalid() }
        var content = bytes
        if bytes.count >= 2, bytes[bytes.startIndex] == 0x50, bytes[bytes.startIndex + 1] == 0x4b {
            content = try unzip(bytes, season: season, episode: episode)
        }
        return try text(content, language: language)
    }

    private static func unzip(_ bytes: Data, season: Int?, episode: Int?) throws -> Data {
        let b = [UInt8](bytes)
        func u16(_ at: Int) throws -> Int { guard at >= 0, at + 2 <= b.count else { throw invalid() }; return Int(b[at]) | Int(b[at + 1]) << 8 }
        func u32(_ at: Int) throws -> Int64 {
            guard at >= 0, at + 4 <= b.count else { throw invalid() }
            return Int64(b[at]) | Int64(b[at + 1]) << 8 | Int64(b[at + 2]) << 16 | Int64(b[at + 3]) << 24
        }
        var end = b.count - 22
        while end >= max(0, b.count - 65557) {
            if try u32(end) == 0x06054b50 { break }
            end -= 1
        }
        if end < 0 || end < b.count - 65557 { throw invalid() }
        let disk = try u16(end + 4), directoryDisk = try u16(end + 6)
        if disk != 0 || directoryDisk != 0 { throw invalid() }
        let count = try u16(end + 10)
        if count > 400 { throw invalid() }
        var at = Int(try u32(end + 16))

        struct Entry { var name: String; var flags: Int; var method: Int; var size: Int64; var raw: Int64; var local: Int64; var crc: Int64 }
        var entries: [Entry] = []
        for _ in 0..<count {
            if try u32(at) != 0x02014b50 { throw invalid() }
            let n = try u16(at + 28)
            let next = Int64(at) + 46 + Int64(n) + Int64(try u16(at + 30)) + Int64(try u16(at + 32))
            if next > Int64(end) || next < Int64(at) { throw invalid() }
            guard at + 46 + n <= b.count else { throw invalid() }
            let name = String(decoding: b[(at + 46)..<(at + 46 + n)], as: UTF8.self)
            if name.hasPrefix("/") || name.contains("\0") || name.split(whereSeparator: { $0 == "/" || $0 == "\\" }).contains("..") { throw invalid() }
            if Rx.contains(Pattern.subtitleEntry, in: name) {
                entries.append(Entry(name: name, flags: try u16(at + 8), method: try u16(at + 10), size: try u32(at + 20), raw: try u32(at + 24),
                                     local: try u32(at + 42), crc: try u32(at + 16)))
            }
            at = Int(next)
        }
        let chosen: Entry
        if entries.count == 1 {
            let named = Self.episode(entries[0].name)
            if let episode, let named, named.episode != episode || (season != nil && named.season != season) {
                throw LocalSubtitleError("ambiguous_subtitle_archive")
            }
            chosen = entries[0]
        } else {
            guard let index = pickEpisode(entries.map(\.name), season: season, episode: episode) else { throw LocalSubtitleError("ambiguous_subtitle_archive") }
            chosen = entries[index]
        }
        if chosen.flags & 1 != 0 || chosen.size > Int64(limit) || chosen.raw > Int64(limit) || chosen.local > Int64(b.count - 30) { throw invalid() }
        let local = Int(chosen.local)
        if try u32(local) != 0x04034b50 { throw invalid() }
        let start = Int64(local) + 30 + Int64(try u16(local + 26)) + Int64(try u16(local + 28))
        if start + chosen.size > Int64(b.count) || start < 0 { throw invalid() }
        let data = Data(b[Int(start)..<Int(start + chosen.size)])
        let content: Data
        switch chosen.method {
        case 0: content = data
        case 8: guard let inflated = inflate(data) else { throw invalid() }; content = inflated
        default: throw invalid()
        }
        if Int64(content.count) != chosen.raw || Int64(crc32(content)) != chosen.crc { throw invalid() }
        return content
    }

    private static func text(_ content: Data, language: String) throws -> String {
        let bytes = [UInt8](content)
        if bytes.count >= 2 {
            if bytes[0] == 0xFF, bytes[1] == 0xFE, let s = String(data: Data(bytes.dropFirst(2)), encoding: .utf16LittleEndian) { return "\u{FEFF}" + s }
            if bytes[0] == 0xFE, bytes[1] == 0xFF, let s = String(data: Data(bytes.dropFirst(2)), encoding: .utf16BigEndian) { return "\u{FEFF}" + s }
        }
        if let s = String(data: content, encoding: .utf8) { return s }
        // Old files carry their language's Windows code page. These never fail to decode, like Java's lenient decoders.
        // Core Foundation's code page numbers: 0x0500 Latin 1 (1252), 0x0501 Central European (1250), 0x0502 Cyrillic, 0x0503 Greek,
        // 0x0504 Turkish, 0x0505 Hebrew, 0x0506 Arabic, 0x0507 Baltic, 0x0508 Vietnamese, 0x041D Thai.
        let code: UInt32
        switch language {
        case "fa", "ar", "ur", "ku": code = 0x0506
        case "ru", "uk", "bg", "sr", "mk", "be": code = 0x0502
        case "el": code = 0x0503
        case "tr", "az": code = 0x0504
        case "he": code = 0x0505
        case "pl", "cs", "sk", "hu", "ro", "hr", "sl", "bs": code = 0x0501
        case "lt", "lv", "et": code = 0x0507
        case "vi": code = 0x0508
        case "th": code = 0x041D
        default: code = 0x0500
        }
        if let s = CFStringCreateWithBytes(nil, bytes, bytes.count, CFStringEncoding(code), false) { return s as String }
        return String(String.UnicodeScalarView(bytes.map { Unicode.Scalar($0) }))
    }

    // MARK: Cues

    private static func time(_ value: String, ass: Bool = false) -> Double? {
        let v = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let m = (ass ? Pattern.timeAss : Pattern.timeSrt).firstMatch(in: v, range: Rx.range(v)) else { return nil }
        let minutes = Int(Rx.group(m, 2, in: v) ?? "") ?? 99
        let seconds = Int(Rx.group(m, 3, in: v) ?? "") ?? 99
        if minutes > 59 || seconds > 59 { return nil }
        let hours = Double(Rx.group(m, 1, in: v) ?? "") ?? 0
        var millis = Rx.group(m, 4, in: v) ?? "0"
        while millis.count < 3 { millis += "0" }
        return hours * 3600 + Double(minutes) * 60 + Double(seconds) + (Double(millis) ?? 0) / 1000
    }

    public static func parse(_ text: String) throws -> [SubtitleCue] {
        if text.utf8.count > limit || text.contains("\0") { throw invalid() }
        var cues: [SubtitleCue] = []
        var skipped = 0
        func add(_ start: Double?, _ end: Double?, _ body: String) throws {
            // Tags go; control characters (a stray form feed in a provider file) would fail the whole save.
            let stripped = Rx.replacing(Pattern.tags, in: body, with: "")
            let kept = String(String.UnicodeScalarView(stripped.unicodeScalars.filter { $0.value >= 32 || $0 == "\t" || $0 == "\n" }))
            let clean = kept.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let start, let end, end > start, end <= 86400, end - start <= 120, !clean.isEmpty, clean.utf16.count <= 4000 else { skipped += 1; return }
            cues.append(SubtitleCue(start: start, end: end, text: clean))
            if cues.count > 20000 { throw invalid() }
        }
        var normalized = text
        if normalized.hasPrefix("\u{FEFF}") { normalized.removeFirst() }
        normalized = normalized.replacingOccurrences(of: "\r", with: "")

        if Rx.contains(Pattern.scriptInfo, in: normalized) {
            var events = false
            var fields = ["layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"]
            for raw in normalized.split(separator: "\n", omittingEmptySubsequences: false) {
                let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                if line.hasPrefix("[") { events = line.caseInsensitiveCompare("[events]") == .orderedSame; continue }
                if !events { continue }
                if line.lowercased().hasPrefix("format:") {
                    fields = line.drop { $0 != ":" }.dropFirst().split(separator: ",", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
                    continue
                }
                guard line.lowercased().hasPrefix("dialogue:"), fields.last == "text" else { continue }
                let rest = line.drop { $0 != ":" }.dropFirst().drop { $0.isWhitespace }
                let parts = splitLimited(String(rest), separator: ",", limit: fields.count)
                func field(_ name: String) -> String {
                    guard let i = fields.firstIndex(of: name), i < parts.count else { return "" }
                    return parts[i]
                }
                let source = field("text")
                if Rx.contains(Pattern.assDrawing, in: source) { continue }
                var body = Rx.replacing(Pattern.assBraces, in: source, with: "")
                body = Rx.replacing(Pattern.assNewline, in: body, with: "\n")
                body = body.replacingOccurrences(of: "\\h", with: " ")
                try add(time(field("start"), ass: true), time(field("end"), ass: true), body)
            }
        } else {
            for block in Rx.split(Pattern.blockSplit, normalized) {
                let lines = block.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
                guard let i = lines.firstIndex(where: { $0.contains("-->") }) else { continue }
                let parts = Rx.split(Pattern.arrow, lines[i].trimmingCharacters(in: .whitespacesAndNewlines))
                if parts.count != 2 { skipped += 1; continue }
                let endText = parts[1].split(separator: " ", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? parts[1]
                try add(time(parts[0]), time(endText), lines.dropFirst(i + 1).joined(separator: "\n"))
            }
        }
        if cues.isEmpty || skipped > cues.count { throw invalid() }
        // Stable by position: equal start times keep file order.
        return cues.enumerated().sorted { ($0.element.start, $0.offset) < ($1.element.start, $1.offset) }.map(\.element)
    }

    /// Kotlin `split(',', limit = n)`: at most `limit` pieces, the last holding the rest.
    private static func splitLimited(_ text: String, separator: Character, limit: Int) -> [String] {
        text.split(separator: separator, maxSplits: max(0, limit - 1), omittingEmptySubsequences: false).map(String.init)
    }
}
