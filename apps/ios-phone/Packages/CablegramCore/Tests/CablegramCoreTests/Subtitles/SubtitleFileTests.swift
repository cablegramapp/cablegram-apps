import Compression
import Foundation
import Testing
@testable import CablegramCore

// Ports of `LocalSubtitleFileTest.kt`, `SavedSubtitleTimingTest.kt` and the parsing part of `LocalSubtitleDiscoveryTest.kt`.

private let srt = "1\r\n00:00:01,500 --> 00:00:03,000\r\nHello <i>world</i>\r\nSecond line\r\n"

private func parse(_ name: String, _ text: String, _ language: String = "en") throws -> LocalSubtitleFile {
    try LocalSubtitleFile.parse(name: name, bytes: Data(text.utf8), language: language)
}

private func rejected(_ code: String, _ block: () throws -> Void) {
    do { try block(); Issue.record("Expected \(code)") } catch let e as LocalSubtitleError { #expect(e.code == code) } catch { Issue.record("\(error)") }
}

@Suite struct LocalSubtitleFileTests {
    @Test func srtBecomesAnExplicitUnverifiedTrackWithoutFilePath() throws {
        let file = try parse("My private filename.SRT", srt)
        #expect(file.cues == [SubtitleCue(start: 1.5, end: 3, text: "Hello world\nSecond line")])
        let context = SubtitleSourceContext(sourceId: "source-id", identity: "source-version", filename: "film.mp4", title: "Film")
        let selected = try file.selected(source: context, language: "en")
        #expect(selected.provider == "local"); #expect(selected.identity == "source-version")
        #expect(selected.confidence == "Unverified"); #expect(!selected.automatic)
        #expect(selected.offset == 0); #expect(selected.scale == 1)
        #expect(selected.providerRef.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil)
        #expect(try parse("renamed.srt", srt).reference == file.reference)
        let payload = String(decoding: try JSONEncoder().encode(selected), as: UTF8.self)
        #expect(!payload.contains("private filename")); #expect(!payload.contains("file://"))
    }

    @Test func vttKeepsUnicodeAndIgnoresCommentsAndStyles() throws {
        let file = try parse("dialogue.vtt", "\u{FEFF}WEBVTT\n\nNOTE\n00:00:00.000 --> 00:00:01.000\nDo not display\n\nSTYLE\n::cue { color: lime; }\n\ncue-id\n00:01.250 --> 00:03.500 align:start\nسلام دنیا\n日本語\n")
        #expect(file.cues == [SubtitleCue(start: 1.25, end: 3.5, text: "سلام دنیا\n日本語")])
    }

    @Test func utf16AndLegacyPersianFilesAreDecoded() throws {
        let text = "1\n00:00:01,000 --> 00:00:02,000\nسلام\n"
        let utf16 = Data([0xff, 0xfe]) + text.data(using: .utf16LittleEndian)!
        #expect(try LocalSubtitleFile.parse(name: "fa.srt", bytes: utf16, language: "fa").cues.first?.text == "سلام")
        let windows1256 = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(0x0506))
        #expect(try LocalSubtitleFile.parse(name: "fa.srt", bytes: text.data(using: windows1256)!, language: "fa").cues.first?.text == "سلام")
    }

    @Test func wrongFormatEmptyMalformedAndBinaryDocumentsAreRefused() {
        rejected("local_subtitle_format") { _ = try parse("film.mp4", srt) }
        rejected("local_subtitle_format") { _ = try parse("file.srt", "PKnot-a-subtitle") }
        rejected("local_subtitle_format") { _ = try parse("file.srt", "[Script Info]\nDialogue: hello") }
        for text in ["", "not subtitles", "1\n00:00:03,000 --> 00:00:01,000\nBad\n", "1\n00:00:01,000 --> 00:00:02,000\nBad\u{0}\n"] {
            rejected("invalid_subtitle") { _ = try parse("file.srt", text) }
        }
        rejected("invalid_subtitle") { _ = try parse("file.vtt", srt) }
    }

    @Test func captionMarksWithAngleBracketsAreKeptAndControlCharactersDropped() throws {
        let file = try parse("cc.srt", "1\n00:00:01,000 --> 00:00:02,000\n>> NARRATOR: I <3 it\n\n2\n00:00:03,000 --> 00:00:04,000\nform\u{0c}feed\n")
        #expect(file.cues.map(\.text) == [">> NARRATOR: I <3 it", "formfeed"])
    }

    @Test func unknownLengthSourceIsBoundedAndCancellationIsObserved() throws {
        var consumed = 0
        rejected("local_subtitle_too_large") {
            _ = try LocalSubtitleFile.read(next: { n in consumed += n; return Data(repeating: 65, count: n) })
        }
        #expect(consumed == LocalSubtitleFile.maxBytes + 1)
        var served = false
        #expect(try LocalSubtitleFile.read(next: { _ in defer { served = true }; return served ? Data() : Data(srt.utf8) }) == Data(srt.utf8))
        struct Cancelled: Error {}
        #expect(throws: Cancelled.self) { _ = try LocalSubtitleFile.read(next: { Data(repeating: 65, count: $0) }, checkCancelled: { throw Cancelled() }) }
    }

    @Test func serializedCueLimitIsCheckedBeforeUpload() {
        // Valid source text can expand past the wire limit after JSON escaping and cue metadata.
        let body = String(repeating: "\\", count: 3000)
        let text = (1...400).map { "\($0)\n00:00:01,000 --> 00:00:02,000\n\(body)" }.joined(separator: "\n\n")
        #expect(text.utf8.count < LocalSubtitleFile.maxBytes)
        rejected("local_subtitle_too_large") { _ = try parse("expanded.srt", text) }
    }

    @Test func readsAFileFromDisk() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("sub-\(UUID().uuidString).srt")
        try Data(srt.utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        #expect(try LocalSubtitleFile.read(url: url) == Data(srt.utf8))
        rejected("local_subtitle_unreadable") { _ = try LocalSubtitleFile.read(url: url.appendingPathExtension("missing")) }
    }
}

@Suite struct SavedSubtitleTimingTests {
    @Test func aSavedSubtitleReopensWithTheTimingItPlaysWithNow() throws {
        let body = #"{"id":"s1","language":"nl","offset":1.5,"scale":1.001,"cues":[{"start":10.0,"end":12.0,"text":"Hallo"}],"extra":true}"#
        let timing = try JSONDecoder().decode(SavedSubtitleTiming.self, from: Data(body.utf8))
        #expect(timing.offset == 1.5); #expect(timing.scale == 1.001)
        #expect(timing.cues == [SubtitleCue(start: 10, end: 12, text: "Hallo")])
    }

    @Test func aChangedVideoOrARemovedSubtitleSaysWhatToDo() {
        #expect(savedTimingError("source_identity_changed").contains("attach the subtitle again"))
        #expect(savedTimingError("subtitle_not_found").contains("isn't saved"))
        #expect(savedTimingError(nil).contains("try again"))
    }

    @Test func missingFieldsTakeTheirDefaults() throws {
        #expect(try JSONDecoder().decode(SubtitlePreferences.self, from: Data("{}".utf8)) == SubtitlePreferences())
        #expect(try JSONDecoder().decode(SavedSubtitleList.self, from: Data(#"{"subtitles":[{"language":"en"}]}"#.utf8)).subtitles == [SavedSubtitle(language: "en")])
    }
}

/// Builds zip archives: stored (method 0) or deflated (method 8).
func zipArchive(_ entries: [(String, String)], deflate: Bool = false) -> Data {
    func le(_ v: Int, _ bytes: Int) -> Data { Data((0..<bytes).map { UInt8((v >> (8 * $0)) & 0xff) }) }
    func crc(_ d: Data) -> Int {
        var c: UInt32 = 0xFFFF_FFFF
        for b in d { c ^= UInt32(b); for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 } }
        return Int(c ^ 0xFFFF_FFFF)
    }
    var body = Data(), central = Data()
    for (name, text) in entries {
        let raw = Data(text.utf8), nameBytes = Data(name.utf8)
        var packed = raw
        if deflate {
            var out = [UInt8](repeating: 0, count: raw.count + 1024)
            let n = raw.withUnsafeBytes { compression_encode_buffer(&out, out.count, $0.bindMemory(to: UInt8.self).baseAddress!, raw.count, nil, COMPRESSION_ZLIB) }
            packed = Data(out[0..<n])
        }
        let method = deflate ? 8 : 0
        let offset = body.count
        let checksum = crc(raw)
        var local = Data()
        local += le(0x04034b50, 4); local += le(20, 2); local += le(0, 2); local += le(method, 2); local += le(0, 4)
        local += le(checksum, 4); local += le(packed.count, 4); local += le(raw.count, 4); local += le(nameBytes.count, 2); local += le(0, 2)
        local += nameBytes; local += packed
        body += local
        var entry = Data()
        entry += le(0x02014b50, 4); entry += le(20, 2); entry += le(20, 2); entry += le(0, 2); entry += le(method, 2); entry += le(0, 4)
        entry += le(checksum, 4); entry += le(packed.count, 4); entry += le(raw.count, 4); entry += le(nameBytes.count, 2); entry += le(0, 2)
        entry += le(0, 2); entry += le(0, 2); entry += le(0, 2); entry += le(0, 4); entry += le(offset, 4); entry += nameBytes
        central += entry
    }
    var end = Data()
    end += le(0x06054b50, 4); end += le(0, 2); end += le(0, 2); end += le(entries.count, 2); end += le(entries.count, 2)
    end += le(central.count, 4); end += le(body.count, 4); end += le(0, 2)
    return body + central + end
}

@Suite struct SubtitleTimelineTests {
    private let text = "1\n00:00:10,000 --> 00:00:12,000\nHello\n"

    @Test func archiveSafetyExactEpisodeAssAndAmbiguity() throws {
        let ass = "[Script Info]\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:10.00,0:00:12.00,Default,,0,0,0,,{\\i1}Hello\\Nthere"
        for deflate in [false, true] {
            let decoded = try LocalSubtitleTimeline.decode(zipArchive([("S01E01.srt", text), ("S01E06.ass", ass)], deflate: deflate), language: "en", season: 1, episode: 6)
            #expect(try LocalSubtitleTimeline.parse(decoded).first?.text == "Hello\nthere")
        }
        let bad: [Data] = [
            zipArchive([("S01E06.a.srt", text), ("S01E06.b.srt", text)]),
            zipArchive([("../S01E06.srt", text)]),
            zipArchive([("S01E06.srt", String(repeating: "x", count: 2_000_001))]),
            Data([0x50, 0x4b]),
        ]
        for archive in bad { #expect(throws: LocalSubtitleError.self) { _ = try LocalSubtitleTimeline.decode(archive, language: "en", season: 1, episode: 6) } }
        #expect(LocalSubtitleTimeline.pickEpisode(["S02E06.srt", "S01E01.srt"], season: 1, episode: 6) == nil)
        #expect(LocalSubtitleTimeline.pickEpisode(["Show.S01E05.srt", "Show.S01E06.srt"], season: 1, episode: 6) == 1)
    }

    @Test func aCorruptedEntryFailsItsChecksum() throws {
        var archive = zipArchive([("film.srt", text)])
        archive[40] ^= 0xff   // inside the stored text
        #expect(throws: LocalSubtitleError.self) { _ = try LocalSubtitleTimeline.decode(archive, language: "en", season: nil, episode: nil) }
    }

    @Test func episodeNames() {
        #expect(LocalSubtitleTimeline.episode("dir/Show.S03E12.mkv")?.episode == 12)
        #expect(LocalSubtitleTimeline.episode("Show.3x12.mkv")?.season == 3)
        #expect(LocalSubtitleTimeline.episode("Film.2020.mkv") == nil)
    }
}
