import CryptoKit
import Foundation

struct LocalSubtitleCandidate: Sendable, Equatable {
    var id: String
    var provider: String
    var ref: String
    var download: String
    var language: String
    var release: String
    var file: String
    var fps: Double? = nil
    var hearing = false
    var forced = false
    var hash = false
    var tmdbId: Int? = nil
    var season: Int? = nil
    var episode: Int? = nil
    var pack = false
    var popularity = 0.0
    var rating = 0.0
}

// JSON helpers with the semantics of Kotlin's `JsonPrimitive.contentOrNull`: numbers and booleans read as their text, anything else as "".
private func str(_ object: [String: Any], _ key: String) -> String {
    switch object[key] {
    case let s as String: return s
    case let n as NSNumber:
        if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? "true" : "false" }
        let d = n.doubleValue
        return d == d.rounded() && abs(d) < 1e15 ? String(Int64(d)) : String(d)
    default: return ""
    }
}
private func num(_ object: [String: Any], _ key: String) -> Double? { Double(str(object, key)) }
private func flag(_ object: [String: Any], _ key: String) -> Bool { ["true", "1"].contains(str(object, key)) }
private func obj(_ object: [String: Any], _ key: String) -> [String: Any] { object[key] as? [String: Any] ?? [:] }
private func list(_ object: [String: Any], _ key: String) -> [[String: Any]] { (object[key] as? [Any])?.compactMap { $0 as? [String: Any] } ?? [] }
private func int(_ value: Double?) -> Int? { value.flatMap { $0.isFinite ? Int($0) : nil } }

/// Shared by every provider call: no cookies, cache or credentials store, HTTPS only, and no redirect is ever followed.
private final class NoRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

public func providerSession() -> URLSession {
    let config = URLSessionConfiguration.ephemeral
    config.urlCache = nil
    config.httpCookieStorage = nil
    config.httpShouldSetCookies = false
    config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
    config.timeoutIntervalForRequest = 12
    config.timeoutIntervalForResource = 15
    return URLSession(configuration: config, delegate: NoRedirects(), delegateQueue: nil)
}

/// State of one discovery session that must be readable without waiting on its actor: whether it was closed, and the work to stop.
private final class SessionGate: @unchecked Sendable {
    private let lock = NSLock()
    private var isClosed = false
    private var tasks: [UUID: Task<Void, Never>] = [:]
    var closed: Bool { lock.withLock { isClosed } }

    func register(_ id: UUID, _ task: Task<Void, Never>) -> Bool {
        let stop = lock.withLock { () -> Bool in if isClosed { return true }; tasks[id] = task; return false }
        if stop { task.cancel() }
        return !stop
    }

    func unregister(_ id: UUID) { lock.withLock { _ = tasks.removeValue(forKey: id) } }

    func close() -> [Task<Void, Never>] {
        lock.withLock { isClosed = true; defer { tasks = [:] }; return Array(tasks.values) }
    }
}

/// No disk cache, HTTP logging or persisted search results. Closing cancels every call.
public actor LocalSubtitleDiscovery {
    private var credentials: [String: PersonalSubtitleCredentials]
    private let onClose: @Sendable () -> Void
    private let session: URLSession
    private let gate = SessionGate()

    private var candidates: [String: LocalSubtitleCandidate] = [:]
    private var prepared: [(id: String, match: SubtitleMatch)] = []
    private var cooldown: [String: Date] = [:]
    private var osToken: String?
    private var osHost = "api.opensubtitles.com"
    private var fingerprint: SubtitleFingerprint?
    private var technical: SubtitleTechnical?
    private var source: SubtitleSourceContext?
    private var expires: Date?

    public init(credentials: [String: PersonalSubtitleCredentials], onClose: @escaping @Sendable () -> Void = {}) {
        self.init(credentials: credentials, onClose: onClose, session: providerSession())
    }

    init(credentials: [String: PersonalSubtitleCredentials], onClose: @escaping @Sendable () -> Void = {}, session: URLSession) {
        self.credentials = credentials; self.onClose = onClose; self.session = session; hasCredentials = !credentials.isEmpty
    }

    private nonisolated let hasCredentials: Bool
    public nonisolated var configured: Bool { hasCredentials && !gate.closed }

    /// Stops every running call and drops the credentials and everything fetched. Callable from anywhere, at any time.
    public nonisolated func close() {
        for task in gate.close() { task.cancel() }
        session.invalidateAndCancel()
        Task { await self.forget() }
        onClose()
    }

    private func forget() {
        credentials = [:]; osToken = nil; fingerprint = nil; technical = nil; source = nil; candidates = [:]; prepared = []
    }

    private func check() throws { if gate.closed { throw CancellationError() } }

    /// Runs `block` as a task the session can cancel when it closes.
    private nonisolated func work<T: Sendable>(_ block: @escaping @Sendable () async throws -> T) async throws -> T {
        if gate.closed { throw CancellationError() }
        let id = UUID()
        let box = ResultBox<T>()
        let task = Task { do { box.set(.success(try await block())) } catch { box.set(.failure(error)) } }
        guard gate.register(id, task) else { throw CancellationError() }
        defer { gate.unregister(id) }
        return try await withTaskCancellationHandler {
            await task.value
            return try box.get()
        } onCancel: { task.cancel() }
    }

    // MARK: HTTP

    private func bytes(_ url: URL, hosts: Set<String>, provider: String, headers: [String: String] = [:], body: String? = nil) async throws -> Data {
        try check(); try Task.checkCancellation()
        guard url.scheme == "https", let host = url.host?.lowercased(), hosts.contains(host), url.user == nil, url.password == nil else { throw LocalSubtitleError("unsafe_provider_url") }
        let now = Date()
        if let until = cooldown[provider], until > now { throw LocalSubtitleError("provider_rate_limited", retryAfter: Int(until.timeIntervalSince(now)) + 1) }
        var request = URLRequest(url: url)
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        if let body { request.httpMethod = "POST"; request.httpBody = Data(body.utf8); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        do {
            let (stream, response) = try await session.bytes(for: request)
            guard let http = response as? HTTPURLResponse else { throw LocalSubtitleError("provider_unavailable") }
            if [429, 406].contains(http.statusCode) {
                let retry = min(max(Int(http.value(forHTTPHeaderField: "Retry-After") ?? "") ?? 60, 1), 86400)
                cooldown[provider] = now.addingTimeInterval(Double(retry)); throw LocalSubtitleError("provider_rate_limited", retryAfter: retry)
            }
            if [401, 403].contains(http.statusCode) { throw LocalSubtitleError("invalid_credentials") }
            if http.statusCode == 402 { throw LocalSubtitleError("quota_exhausted") }
            guard (200...299).contains(http.statusCode) else { throw LocalSubtitleError("provider_unavailable") }
            if http.expectedContentLength > 2_000_000 { throw LocalSubtitleError("provider_response_too_large") }
            var out = Data()
            for try await byte in stream {
                if out.count % 8192 == 0 { try check(); try Task.checkCancellation() }
                if out.count >= 2_000_000 { throw LocalSubtitleError("provider_response_too_large") }
                out.append(byte)
            }
            return out
        } catch let error as LocalSubtitleError { throw error }
        catch is CancellationError { throw CancellationError() }
        catch {
            try Task.checkCancellation(); try check()
            if (error as? URLError)?.code == .cancelled { throw CancellationError() }
            throw LocalSubtitleError("network_unavailable")
        }
    }

    private func payload(_ url: URL, hosts: Set<String>, provider: String, headers: [String: String] = [:], body: String? = nil) async throws -> [String: Any] {
        let data = try await bytes(url, hosts: hosts, provider: provider, headers: headers, body: body)
        guard let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { throw LocalSubtitleError("provider_unavailable") }
        return object
    }

    private func osHeaders() -> [String: String] {
        var h = ["Api-Key": credentials["opensubtitles"]?.key ?? "", "User-Agent": "Cablegram v1.0"]
        if let osToken { h["Authorization"] = "Bearer \(osToken)" }
        return h
    }

    private func login() async throws {
        if osToken != nil { return }
        guard let c = credentials["opensubtitles"] else { throw LocalSubtitleError("invalid_credentials") }
        let body = String(decoding: try JSONSerialization.data(withJSONObject: ["username": c.username, "password": c.password]), as: UTF8.self)
        let p = try await payload(URL(string: "https://api.opensubtitles.com/api/v1/login")!, hosts: ["api.opensubtitles.com"], provider: "opensubtitles", headers: osHeaders(), body: body)
        var host = str(p, "base_url")
        if host.hasPrefix("https://") { host.removeFirst("https://".count) }
        while host.hasSuffix("/") { host.removeLast() }
        guard ["api.opensubtitles.com", "vip-api.opensubtitles.com"].contains(host), !str(p, "token").isEmpty else { throw LocalSubtitleError("invalid_credentials") }
        osHost = host; osToken = str(p, "token")
    }

    private static let languageNames = ["persian": "fa", "farsi": "fa", "english": "en", "german": "de", "french": "fr", "spanish": "es"]
    private static func language(_ v: String) -> String { languageNames[v.lowercased()] ?? v.lowercased() }
    private static let twoLetters = Rx.make(#"^[a-z]{2}$"#)
    private static let subdlPath = Rx.make(#"^/subtitle/([0-9]+(?:-[0-9]+)?)(?:\.zip)?$"#)
    private static let notFound = Rx.make(#"(?i)can.?t find|not found"#)

    nonisolated func normalizeSubdl(_ p: [String: Any]) throws -> [LocalSubtitleCandidate] {
        if !flag(p, "status") {
            if Rx.contains(Self.notFound, in: str(p, "error")) { return [] }
            throw LocalSubtitleError("provider_unavailable")
        }
        let base = URL(string: "https://dl.subdl.com")!
        let tmdb = int(num(list(p, "results").first ?? [:], "tmdb_id"))
        var out: [LocalSubtitleCandidate] = []
        for a in list(p, "subtitles").prefix(100) {
            guard let url = URL(string: str(a, "url"), relativeTo: base)?.absoluteURL else { continue }
            // Ignore every returned query parameter (SubDL echoes credentials), retaining only a numeric stable path.
            guard url.scheme == "https", url.host?.lowercased() == "dl.subdl.com", url.user == nil, url.password == nil else { throw LocalSubtitleError("unsafe_provider_url") }
            let path = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath ?? ""
            guard let m = Self.subdlPath.firstMatch(in: path, range: Rx.range(path)), let ref = Rx.group(m, 1, in: path) else { continue }
            let lang = Self.language(str(a, "language"))
            guard Rx.matchesEntirely(Self.twoLetters, lang) else { continue }
            var clean = URLComponents(url: url, resolvingAgainstBaseURL: false)!
            clean.query = nil; clean.fragment = nil
            out.append(LocalSubtitleCandidate(
                id: "subdl:\(ref)", provider: "subdl", ref: ref, download: clean.url!.absoluteString, language: lang,
                release: String(str(a, "release_name").prefix(300)), file: String(str(a, "name").prefix(300)), fps: num(a, "fps"), hearing: flag(a, "hi"),
                tmdbId: tmdb, season: int(num(a, "season")), episode: flag(a, "full_season") ? nil : int(num(a, "episode")), pack: flag(a, "full_season")))
        }
        return out
    }

    private func normalizeOs(_ p: [String: Any]) -> [LocalSubtitleCandidate] {
        list(p, "data").prefix(100).flatMap { d -> [LocalSubtitleCandidate] in
            let a = obj(d, "attributes"), f = obj(a, "feature_details")
            return list(a, "files").prefix(4).compactMap { file -> LocalSubtitleCandidate? in
                guard let id = num(file, "file_id").flatMap({ $0 > 0 && $0 < 1e15 ? Int64($0) : nil }) else { return nil }
                let ref = String(id)
                let lang = Self.language(str(a, "language"))
                guard Rx.matchesEntirely(Self.twoLetters, lang) else { return nil }
                return LocalSubtitleCandidate(
                    id: "opensubtitles:\(ref)", provider: "opensubtitles", ref: ref, download: ref, language: lang, release: String(str(a, "release").prefix(300)),
                    file: String(str(file, "file_name").prefix(300)), fps: num(a, "fps"), hearing: flag(a, "hearing_impaired"), forced: flag(a, "foreign_parts_only"),
                    hash: flag(a, "moviehash_match"), tmdbId: int(num(f, "tmdb_id")), season: int(num(f, "season_number")), episode: int(num(f, "episode_number")),
                    popularity: num(a, "download_count") ?? 0, rating: num(a, "ratings") ?? 0)
            }
        }
    }

    private func searchProvider(_ provider: String, _ m: SubtitleSourceContext, _ languages: [String], _ tech: SubtitleTechnical?) async throws -> [LocalSubtitleCandidate] {
        var params: [String: String] = [:]
        if provider == "subdl" {
            params = ["api_key": credentials[provider]?.key ?? "", "film_name": m.title, "file_name": m.filename, "type": m.episode != nil ? "tv" : "movie",
                      "languages": languages.map { $0.uppercased() }.joined(separator: ","), "subs_per_page": "30", "releases": "1"]
            if let t = m.tmdbId { params["tmdb_id"] = "\(t)" }
            if let y = m.year { params["year"] = "\(y)" }
            if let s = m.season { params["season_number"] = "\(s)" }
            if let e = m.episode { params["episode_number"] = "\(e)" }
            let url = Self.url("https://api.subdl.com/api/v1/subtitles", params)
            return try normalizeSubdl(await payload(url, hosts: ["api.subdl.com"], provider: provider)).filter { m.episode != nil || !$0.pack }
        }
        try await login()
        params["languages"] = languages.joined(separator: ",")
        if let hash = tech?.hash { params["moviehash"] = hash }
        if let t = m.tmdbId { params[m.episode != nil ? "parent_tmdb_id" : "tmdb_id"] = "\(t)" } else { params["query"] = m.title.lowercased() }
        if let s = m.season { params["season_number"] = "\(s)" }
        if let e = m.episode { params["episode_number"] = "\(e)" }
        if m.episode == nil, let y = m.year { params["year"] = "\(y)" }
        let url = Self.url("https://\(osHost)/api/v1/subtitles", params)
        return normalizeOs(try await payload(url, hosts: [osHost], provider: provider, headers: osHeaders()))
    }

    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    /// Query parameters sorted by name; everything but unreserved characters is escaped, so a title with "&" or "+" stays one value.
    private static func url(_ base: String, _ params: [String: String]) -> URL {
        let query = params.sorted { $0.key < $1.key }.map { key, value in
            (key.addingPercentEncoding(withAllowedCharacters: unreserved) ?? "") + "=" + (value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? "")
        }.joined(separator: "&")
        return URL(string: base + "?" + query)!
    }

    // MARK: Ranking

    private static func tokens(_ v: String) -> Set<String> {
        let trimmed = Rx.replacing(Rx.make(#"\.(mkv|mp4|srt|vtt|zip)$"#), in: v.lowercased(), with: "")
        return Set(trimmed.split { !($0.isASCII && ($0.isLetter || $0.isNumber)) }.map(String.init))
    }

    func rank(_ c: LocalSubtitleCandidate, _ m: SubtitleSourceContext, _ p: SubtitlePreferences, _ tech: SubtitleTechnical?) -> (score: Double, confidence: String) {
        let wrong = (m.tmdbId != nil && c.tmdbId != nil && m.tmdbId != c.tmdbId) || (m.season != nil && c.season != nil && m.season != c.season)
            || (m.episode != nil && c.episode != nil && m.episode != c.episode)
        let a = Self.tokens(m.filename), b = Self.tokens(c.release.isEmpty ? c.file : c.release)
        var score = wrong ? -200.0 : 0
        if c.hash, tech?.hash != nil { score += 100 }
        if m.tmdbId != nil, m.tmdbId == c.tmdbId { score += 35 }
        if m.episode != nil, c.episode == m.episode, c.season == m.season { score += 35 } else if m.episode != nil, c.pack, c.season == m.season { score += 25 }
        score += 35 * Double(a.intersection(b).count) / max(1, Double(a.union(b).count)) + 30 - Double(p.languages.firstIndex(of: c.language) ?? -1) * 10
        if let t = tech?.fps, let f = c.fps { score += abs(t - f) < 0.05 ? 8 : -8 }
        if p.sdh != "any" { score += c.hearing == (p.sdh == "prefer") ? 6 : -6 }
        if c.forced { score -= 10 }
        score += min(5, log10(1 + max(0, c.popularity))) + min(max(c.rating / 2, 0), 5)
        return (score, wrong ? "Poor Match" : score >= 55 ? "Likely Match" : "Unverified")
    }

    // MARK: Discovery

    public nonisolated func discover(_ context: SubtitleSourceContext, _ p: SubtitlePreferences, technical: SubtitleTechnical?, fingerprint: SubtitleFingerprint?) async throws -> SubtitleDiscovery {
        try await work { try await self.discoverInner(context, p, technical, fingerprint) }
    }

    public nonisolated func prepare(_ match: SubtitleMatch) async throws -> SubtitleMatch {
        try await work { try await self.prepareInternal(match) }
    }

    private func discoverInner(_ context: SubtitleSourceContext, _ p: SubtitlePreferences, _ tech: SubtitleTechnical?, _ print: SubtitleFingerprint?) async throws -> SubtitleDiscovery {
        try check()
        if let s = source, s.sourceId != context.sourceId || s.identity != context.identity { throw LocalSubtitleError("source_identity_changed") }
        source = context; technical = tech; fingerprint = print
        if expires == nil { expires = Date().addingTimeInterval(3600) }
        var errors: [SubtitleProviderFailure] = []
        var all: [LocalSubtitleCandidate] = []
        for provider in credentials.keys.sorted() {
            do { all += try await searchProvider(provider, context, p.languages, tech) }
            catch is CancellationError { throw CancellationError() }
            catch let e as LocalSubtitleError { errors.append(SubtitleProviderFailure(provider: provider, code: e.code, retryAfter: e.retryAfter)) }
            catch { errors.append(SubtitleProviderFailure(provider: provider, code: "provider_unavailable")) }
        }
        try Task.checkCancellation(); try check()
        var perLanguage: [String: Int] = [:]
        let ranked = all.filter { p.languages.contains($0.language) && (p.includeForced || !$0.forced) }
            .map { ($0, rank($0, context, p, tech)) }
            .enumerated().sorted { ($0.element.1.score, -Double($0.offset)) > ($1.element.1.score, -Double($1.offset)) }.map(\.element)
            .filter { c, _ in perLanguage[c.language, default: 0] += 1; return perLanguage[c.language]! <= 12 }
        var results = ranked.map { c, r -> SubtitleMatch in
            candidates[c.id] = c
            return SubtitleMatch(id: c.id, language: c.language, confidence: r.confidence, provider: c.provider, releaseName: c.release, forced: c.forced, score: r.score)
        }
        for i in 0..<min(5, results.count) where results[i].confidence != "Poor Match" { results[i] = try await prepareInternal(results[i]) }
        var seen = Set<String>()
        results.removeAll { match in
            guard let cues = match.cues, let data = try? JSONEncoder().encode(cues) else { return false }
            let digest = SHA256.hash(data: Data(match.language.utf8) + data).map { String(format: "%02x", $0) }.joined()
            return !seen.insert(digest).inserted
        }
        results = results.enumerated().sorted { l, r in
            let lv = l.element.alignment?.verified == true, rv = r.element.alignment?.verified == true
            if lv != rv { return lv }
            if l.element.score != r.element.score { return l.element.score > r.element.score }
            return l.offset < r.offset
        }.map(\.element)
        let best = results.first { $0.cues != nil && $0.error == nil && $0.confidence != "Poor Match" }
        let status = !configured ? "providers_not_configured"
            : results.isEmpty ? (errors.isEmpty ? "no_subtitles" : "provider_unavailable")
            : best?.alignment?.verified == true ? "verified" : "no_confident_match"
        return SubtitleDiscovery(id: UUID().uuidString.lowercased(), sourceId: context.sourceId, candidates: results, status: status, errors: errors, existingLanguages: tech?.embeddedLanguages ?? [])
    }

    private func prepareInternal(_ match: SubtitleMatch) async throws -> SubtitleMatch {
        if let done = prepared.first(where: { $0.id == match.id }) { return done.match }
        if let expires, Date() > expires { throw LocalSubtitleError("discovery_expired") }
        guard let c = candidates[match.id], let m = source else { throw LocalSubtitleError("discovery_expired") }
        do {
            let downloaded: Data
            if c.provider == "subdl" {
                guard let url = URL(string: c.download), let key = credentials[c.provider]?.key else { throw LocalSubtitleError("unsafe_provider_url") }
                downloaded = try await bytes(url, hosts: ["dl.subdl.com"], provider: c.provider, headers: ["x-api-key": key])
            } else {
                try await login()
                let body = String(decoding: try JSONSerialization.data(withJSONObject: ["file_id": Int64(c.ref) ?? 0, "sub_format": "srt"]), as: UTF8.self)
                let p = try await payload(URL(string: "https://\(osHost)/api/v1/download")!, hosts: [osHost], provider: c.provider, headers: osHeaders(), body: body)
                guard let url = URL(string: str(p, "link")) else { throw LocalSubtitleError("unsafe_provider_url") }
                downloaded = try await bytes(url, hosts: ["dl.opensubtitles.com", "www.opensubtitles.com", "vip.opensubtitles.com"], provider: c.provider)
            }
            let cues = try LocalSubtitleTimeline.parse(try LocalSubtitleTimeline.decode(downloaded, language: c.language, season: m.season, episode: m.episode))
            if c.language == "fa", !cues.contains(where: { $0.text.unicodeScalars.contains { (0x0600...0x06ff).contains($0.value) } }) { throw LocalSubtitleError("wrong_language_metadata") }
            let gate = self.gate
            let alignment = try LocalSubtitleSync.synchronize(cues, fingerprint: c.forced ? nil : fingerprint, duration: technical?.duration ?? m.duration) {
                try Task.checkCancellation(); if gate.closed { throw CancellationError() }
            }
            let confidence = alignment.reason == "different_cut" ? "Poor Match" : alignment.verified ? "Local Activity Match" : alignment.reason == "partial_match" ? "Likely Match" : match.confidence
            var done = match
            done.cues = cues; done.alignment = alignment; done.confidence = confidence
            try check()
            if prepared.count >= 24 { prepared.removeFirst() }
            prepared.append((c.id, done))
            return done
        } catch is CancellationError { throw CancellationError() }
        catch {
            var failed = match
            failed.error = (error as? LocalSubtitleError)?.code ?? "invalid_subtitle"
            return failed
        }
    }

    public func selected(_ match: SubtitleMatch, automatic: Bool = false) throws -> SelectedSubtitleTrack {
        try check()
        guard let c = candidates[match.id], let m = source else { throw LocalSubtitleError("discovery_expired") }
        guard match.error == nil, match.confidence != "Poor Match", let cues = match.cues, !cues.isEmpty else { throw LocalSubtitleError(match.error ?? "invalid_subtitle") }
        let (offset, scale) = initialShift(match)
        return SelectedSubtitleTrack(sourceId: m.sourceId, identity: m.identity, language: c.language, provider: c.provider, providerRef: c.ref, cues: cues,
                                     offset: offset, scale: scale, forced: c.forced, confidence: match.confidence, automatic: automatic)
    }
}

/// Carries a task's result out of a closure that cannot return it.
final class ResultBox<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Result<T, Error>?
    func set(_ result: Result<T, Error>) { lock.withLock { value = result } }
    func get() throws -> T {
        guard let value = lock.withLock({ value }) else { throw CancellationError() }
        return try value.get()
    }
}

// MARK: Credential check

/// What a provider said about credentials checked before they are saved.
public enum CredentialCheck: Equatable, Sendable {
    case accepted
    /// The provider couldn't be asked (offline, busy); the details are saved unchecked.
    case unchecked(String)
    /// The provider refused them; nothing is saved.
    case refused(String)
}

/// One small request with the typed details: a one-result SubDL search, or an OpenSubtitles sign-in (which uses no download).
/// Same client rules as discovery: HTTPS to the provider only, no redirects, nothing logged or cached.
public func checkSubtitleCredentials(provider: String, credentials: PersonalSubtitleCredentials, session: URLSession = providerSession()) async -> CredentialCheck {
    let name = provider == "opensubtitles" ? "OpenSubtitles" : "SubDL"
    var request: URLRequest
    if provider == "opensubtitles" {
        request = URLRequest(url: URL(string: "https://api.opensubtitles.com/api/v1/login")!)
        request.httpMethod = "POST"
        request.setValue(credentials.key, forHTTPHeaderField: "Api-Key")
        request.setValue("Cablegram v1.0", forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: ["username": credentials.username, "password": credentials.password])
    } else {
        var components = URLComponents(string: "https://api.subdl.com/api/v1/subtitles")!
        components.queryItems = [URLQueryItem(name: "api_key", value: credentials.key), URLQueryItem(name: "film_name", value: "Inception"),
                                 URLQueryItem(name: "type", value: "movie"), URLQueryItem(name: "subs_per_page", value: "1")]
        request = URLRequest(url: components.url!)
    }
    do {
        let (data, response) = try await session.data(for: request)
        return classifyCredentialResponse(provider: provider, code: (response as? HTTPURLResponse)?.statusCode ?? 0, body: String(decoding: data, as: UTF8.self))
    } catch {
        if Task.isCancelled { return .unchecked("") }
        return .unchecked("Couldn't reach \(name) to check it, so it was saved unchecked. A search will tell you if it's wrong.")
    }
}

/// Pure mapping of a provider's answer, kept apart so it can be tested without the network.
public func classifyCredentialResponse(provider: String, code: Int, body: String) -> CredentialCheck {
    let json = (try? JSONSerialization.jsonObject(with: Data(body.utf8))) as? [String: Any]
    if provider == "opensubtitles" {
        if code == 200, !str(json ?? [:], "token").trimmingCharacters(in: .whitespaces).isEmpty { return .accepted }
        switch code {
        case 401: return .refused("Wrong OpenSubtitles username or password.")
        case 403: return .refused("OpenSubtitles didn't accept this API key. Copy it again from the API page.")
        case 429, 406: return .unchecked("OpenSubtitles is busy, so it couldn't be checked. It was saved; a search will tell you if it's wrong.")
        default: return .unchecked("OpenSubtitles couldn't check it right now. It was saved; a search will tell you if it's wrong.")
        }
    }
    let status = json.map { str($0, "status") }
    let error = [json.map { str($0, "error") }, json.map { str($0, "message") }].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
    if code == 401 || code == 403 || Rx.contains(Rx.make(#"(?i)not.?authori[sz]ed|invalid.*(api|key)"#), in: error) {
        return .refused("SubDL didn't accept this key. Copy it again from the API page, and don't use your SubDL password.")
    }
    // A working key answers with results, or with "can't find" for a title it doesn't have.
    if code == 200, status == "true" || Rx.contains(Rx.make(#"(?i)can.?t find|not found"#), in: error) { return .accepted }
    if code == 429 { return .unchecked("SubDL is busy, so it couldn't be checked. It was saved; a search will tell you if it's wrong.") }
    return .unchecked("SubDL couldn't check it right now. It was saved; a search will tell you if it's wrong.")
}
