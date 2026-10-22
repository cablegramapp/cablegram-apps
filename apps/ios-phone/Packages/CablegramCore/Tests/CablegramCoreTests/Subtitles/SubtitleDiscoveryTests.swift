import Foundation
import Testing
@testable import CablegramCore

/// Answers every provider request from `handler`; nothing leaves the process.
final class ProviderMock: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var handler: (@Sendable (URLRequest) throws -> (Int, Data))?
    nonisolated(unsafe) static var began: (@Sendable () -> Void)?

    static var session: URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ProviderMock.self]
        return URLSession(configuration: config)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.began?()
        do {
            let (code, body) = try Self.handler!(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: "HTTP/1.1", headerFields: ["Retry-After": "2"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

/// The requests a test saw, in order.
final class Seen: @unchecked Sendable {
    private let lock = NSLock()
    private var all: [URLRequest] = []
    func add(_ r: URLRequest) { lock.withLock { all.append(r) } }
    var requests: [URLRequest] { lock.withLock { all } }
}

private func query(_ r: URLRequest, _ name: String) -> String? {
    URLComponents(url: r.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == name }?.value
}

@Suite(.serialized) struct LocalSubtitleDiscoveryTests {
    private let context = SubtitleSourceContext(sourceId: "source", identity: "version", filename: "Film.2020.1080p.mkv", title: "Film", year: 2020, tmdbId: 123, duration: 3600)
    private let srt = "1\n00:00:10,000 --> 00:00:12,000\nHello\n"

    private func subdl(key: String = "secret-canary", _ handler: @escaping @Sendable (URLRequest) throws -> (Int, Data)) -> LocalSubtitleDiscovery {
        ProviderMock.handler = handler
        return LocalSubtitleDiscovery(credentials: ["subdl": PersonalSubtitleCredentials(key: key)], session: ProviderMock.session)
    }

    private func search(_ url: String = "/subtitle/123-456.zip?api_key=secret-canary") -> Data {
        Data(#"{"status":true,"results":[{"tmdb_id":123}],"subtitles":[{"url":"\#(url)","language":"English","name":"Film.srt","release_name":"Film.2020.1080p"}]}"#.utf8)
    }

    @Test func noKeysNeverCallsProvider() async throws {
        ProviderMock.handler = { _ in Issue.record("unexpected provider call"); return (500, Data()) }
        let engine = LocalSubtitleDiscovery(credentials: [:], session: ProviderMock.session)
        #expect(try await engine.discover(context, SubtitlePreferences(), technical: nil, fingerprint: nil).status == "providers_not_configured")
        engine.close()
    }

    @Test func phoneRequestsStripSecretsAndUploadOnlyChosenTrack() async throws {
        let seen = Seen(), text = srt, found = search()
        let engine = subdl { r in seen.add(r); return (200, r.url!.host == "api.subdl.com" ? found : Data(text.utf8)) }
        let result = try await engine.discover(context, SubtitlePreferences(languages: ["en"]), technical: nil, fingerprint: nil)
        let requests = seen.requests
        #expect(requests.count == 2); #expect(query(requests[0], "api_key") == "secret-canary")
        #expect(requests[1].url!.query == nil); #expect(requests[1].value(forHTTPHeaderField: "x-api-key") == "secret-canary")
        let match = try #require(result.candidates.first); #expect(result.candidates.count == 1); #expect(match.alignment?.verified == false)
        let track = try await engine.selected(match)
        let selected = String(decoding: try JSONEncoder().encode(track), as: UTF8.self)
        for secret in ["secret-canary", "fingerprint", "technical", "download", "api_key"] { #expect(!selected.contains(secret), "\(secret)") }
        #expect(track.providerRef == "123-456")
        engine.close()
        await #expect(throws: CancellationError.self) { _ = try await engine.selected(match) }
    }

    @Test func invalidQuotaMalformedAndUnsafeProviderFailuresAreBounded() async throws {
        for (code, expected) in [(401, "invalid_credentials"), (402, "quota_exhausted"), (429, "provider_rate_limited"), (500, "provider_unavailable")] {
            let engine = subdl { _ in (code, Data("credential-bearing-provider-error".utf8)) }
            let found = try await engine.discover(context, SubtitlePreferences(), technical: nil, fingerprint: nil)
            #expect(found.errors.first?.code == expected); #expect(found.errors.count == 1)
            engine.close()
        }
        for body in [Data("not json".utf8), Data(String(repeating: "x", count: 2_000_001).utf8), search("https://evil.example/subtitle/1.zip")] {
            let engine = subdl { _ in (200, body) }
            let found = try await engine.discover(context, SubtitlePreferences(), technical: nil, fingerprint: nil)
            #expect(found.candidates.isEmpty); #expect(found.errors.count == 1)
            engine.close()
        }
    }

    @Test func remainingLanguagesSearchOnceAndUncheckedCandidateDownloadsOnOpen() async throws {
        let counts = Counts(), text = srt
        let listing = Data(#"{"status":true,"subtitles":[\#((1...8).map { #"{"url":"/subtitle/\#($0).zip?api_key=secret-canary","language":"English","name":"Film.srt"}"# }.joined(separator: ","))]}"#.utf8)
        let engine = subdl { r in
            if r.url!.host == "api.subdl.com" { counts.search(query(r, "languages")); return (200, listing) }
            counts.download(); return (200, Data(text.utf8))
        }
        let prefs = SubtitlePreferences(languages: ["en"])
        let found = try await engine.discover(context, prefs, technical: nil, fingerprint: nil)
        #expect(counts.searches == 1); #expect(counts.downloads == 5)
        let unchecked = try #require(found.candidates.first { $0.cues == nil })
        let prepared = try await engine.prepare(unchecked)
        #expect(counts.downloads == 6); #expect(prepared.cues != nil)
        _ = try await engine.prepare(unchecked); #expect(counts.downloads == 6)
        var other = prefs; other.languages = ["fr", "de"]
        _ = try await engine.discover(context, other, technical: nil, fingerprint: nil)
        #expect(counts.languages == ["EN", "FR,DE"])
        var changed = context; changed.identity = "replaced"
        await #expect(throws: LocalSubtitleError.self) { _ = try await engine.discover(changed, prefs, technical: nil, fingerprint: nil) }
        engine.close()
    }

    @Test func opensubtitlesRequiresPersonalLoginAndUsesReturnedAllowlistedHost() async throws {
        let seen = Seen(), text = srt
        ProviderMock.handler = { r in
            seen.add(r)
            switch r.url!.path {
            case "/api/v1/login": return (200, Data(#"{"token":"personal-token","base_url":"vip-api.opensubtitles.com"}"#.utf8))
            case "/api/v1/subtitles": return (200, Data(#"{"data":[{"attributes":{"language":"en","files":[{"file_id":123,"file_name":"f.srt"}]}}]}"#.utf8))
            case "/api/v1/download": return (200, Data(#"{"link":"https://dl.opensubtitles.com/download/fixture?token=download-secret"}"#.utf8))
            default: return (200, Data(text.utf8))
            }
        }
        let engine = LocalSubtitleDiscovery(credentials: ["opensubtitles": PersonalSubtitleCredentials(key: "os-key", username: "me", password: "my-password")], session: ProviderMock.session)
        let found = try await engine.discover(context, SubtitlePreferences(languages: ["en"]), technical: nil, fingerprint: nil)
        let requests = seen.requests
        #expect(requests.count == 4); #expect(requests[0].url!.host == "api.opensubtitles.com")
        #expect(requests[1].url!.host == "vip-api.opensubtitles.com"); #expect(requests[2].value(forHTTPHeaderField: "Authorization") == "Bearer personal-token")
        #expect(requests[3].value(forHTTPHeaderField: "Authorization") == nil); #expect(requests[3].value(forHTTPHeaderField: "Api-Key") == nil)
        let track = try await engine.selected(try #require(found.candidates.first))
        #expect(!String(decoding: try JSONEncoder().encode(track), as: UTF8.self).contains("token"))
        engine.close()
    }

    @Test func providerCancellationPropagatesAndCloseCancelsWork() async throws {
        let began = Flag()
        ProviderMock.began = { began.raise() }
        defer { ProviderMock.began = nil }
        let engine = subdl { _ in Thread.sleep(forTimeInterval: 0.3); throw URLError(.timedOut) }
        let context = self.context
        let task = Task { try await engine.discover(context, SubtitlePreferences(), technical: nil, fingerprint: nil) }
        for _ in 0..<200 where !began.raised { try await Task.sleep(nanoseconds: 10_000_000) }
        #expect(began.raised)
        engine.close()
        await #expect(throws: CancellationError.self) { _ = try await task.value }
    }

    @Test func rankingPrefersTheEpisodeAndTheLanguageOrder() async {
        let engine = LocalSubtitleDiscovery(credentials: [:], session: ProviderMock.session)
        let episode = SubtitleSourceContext(sourceId: "s", identity: "i", filename: "Show.S01E02.1080p.mkv", title: "Show", tmdbId: 5, season: 1, episode: 2)
        let right = LocalSubtitleCandidate(id: "a", provider: "subdl", ref: "1", download: "", language: "en", release: "Show.S01E02.1080p", file: "", tmdbId: 5, season: 1, episode: 2)
        var wrong = right; wrong.episode = 3
        let prefs = SubtitlePreferences(languages: ["en"])
        let good = await engine.rank(right, episode, prefs, nil), bad = await engine.rank(wrong, episode, prefs, nil)
        #expect(good.confidence == "Likely Match"); #expect(bad.confidence == "Poor Match"); #expect(good.score > bad.score)
        engine.close()
    }
}

final class Counts: @unchecked Sendable {
    private let lock = NSLock()
    private var s = 0, d = 0
    private var l: [String?] = []
    func search(_ language: String?) { lock.withLock { s += 1; l.append(language) } }
    func download() { lock.withLock { d += 1 } }
    var searches: Int { lock.withLock { s } }
    var downloads: Int { lock.withLock { d } }
    var languages: [String?] { lock.withLock { l } }
}

final class Flag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    func raise() { lock.withLock { value = true } }
    var raised: Bool { lock.withLock { value } }
}
