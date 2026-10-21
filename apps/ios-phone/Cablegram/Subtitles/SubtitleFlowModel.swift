import CablegramCore
import Foundation
import Observation

/// Find Subtitles for one title: local analysis, one search, the choice, then the corrections (Android `SubtitleFlow`).
enum SubtitleStage {
    case setup
    case searching
    case results(SubtitleDiscovery)
    case adjust(SubtitleDiscovery, SubtitleMatch, String)
    case failed(String)
}

/// Language choices offered when the person wants another language than their preferences.
let moreSubtitleLanguages = ["en", "fa", "de", "fr", "es", "ar", "tr", "ru", "it"]

/// The audio check (speech sync) is off in the first release (CAB-77, CAB-80), on Android and here. Its audio decoding is not ported yet:
/// matches are never claimed verified and the timing is checked in the preview.
let subtitleSpeechSync = false

@MainActor @Observable
final class SubtitleFlowModel {
    private(set) var stage = SubtitleStage.searching
    var preferences = SubtitlePreferences()
    private(set) var progress = L("ui_looking_at_your_video_15f7c9")
    private(set) var requestedLanguage: String?
    private(set) var analysisNote: String?
    /// Everything fetched during this visit stays here: switching language is a local filter, not a new request.
    private(set) var others: SubtitleDiscovery?
    private(set) var loadingOthers = false
    private(set) var othersNote: String?
    private(set) var analysing = false
    private(set) var fetchedMb = 0.0

    let item: LibraryItem
    let engine: LocalSubtitleDiscovery
    private let controller: SubtitleController
    private let openingRevision: Int
    private var job: Task<Void, Never>?
    private var activeInput: (any SubtitleMediaInput)?
    private var analysis: SubtitleTechnical??
    private var closed = false

    init(controller: SubtitleController, item: LibraryItem) {
        self.controller = controller
        self.item = item
        openingRevision = controller.credentialRevision
        engine = controller.newSession()
    }

    var token: String? { controller.accountToken }

    func start() {
        job?.cancel(); activeInput?.close(); analysis = nil; others = nil
        job = Task {
            if let token, let loaded = try? await controller.request("preferences", token: token),
               let value = try? JSONDecoder().decode(SubtitlePreferences.self, from: Data(loaded.utf8)) { preferences = value }
            if controller.credentialRevision == openingRevision { search() } else { stage = .setup }
        }
    }

    func cancelSearch() { job?.cancel(); activeInput?.close() }

    func close() {
        closed = true
        job?.cancel(); activeInput?.close()
        controller.release(engine)
    }

    func showSetup() { cancelSearch(); stage = .setup }
    func fail(_ message: String) { stage = .failed(message) }
    func cancelled() { cancelSearch(); stage = .failed(errorMessage("cancelled")) }
    func backToResults(_ discovery: SubtitleDiscovery) { stage = .results(discovery) }
    func selected(_ discovery: SubtitleDiscovery, _ match: SubtitleMatch, _ id: String) {
        controller.refreshStatus(item)
        stage = .adjust(discovery, match, id)
    }

    /// The video is read once per visit; a language change reuses what was learned from it.
    private func ensureAnalysis() async -> SubtitleTechnical? {
        if let analysis { return analysis }
        analysing = true
        let ticker = Task { while !Task.isCancelled { fetchedMb = Double(activeInput?.bytesFetched ?? 0) / 1_048_576; try? await Task.sleep(for: .milliseconds(500)) } }
        defer { analysing = false; ticker.cancel() }
        progress = item.sourceKind == "telegram" ? L("ui_opening_your_video_from_telegram_0372e4") : L("ui_looking_at_your_video_15f7c9")
        let technical: SubtitleTechnical?
        do {
            let input = try await controller.openVideo(item)
            activeInput = input
            // Reading a Telegram video is data the person pays for: cap the whole check and each section of it.
            input.limitAnalysis(totalBytes: 160 * 1024 * 1024, sectionBytes: 64 * 1024 * 1024)
            technical = await SubtitleVideo.probe(input)
            activeInput = nil
            input.close()
        } catch {
            analysisNote = (error as? VideoUnavailable)?.message ?? L("ui_your_video_couldn_t_be_opened_so_matches_can_t_be_chec_0a5097")
            technical = nil
        }
        if Task.isCancelled { return nil }
        analysis = .some(technical)
        return technical
    }

    private func context() async throws -> SubtitleSourceContext {
        guard let token else { throw LocalSubtitleError("network_unavailable") }
        let body = String(decoding: try JSONEncoder().encode(["identity": item.id]), as: UTF8.self)
        return try JSONDecoder().decode(SubtitleSourceContext.self, from: Data(try await controller.request("source-context", token: token, method: "POST", body: body).utf8))
    }

    /// The one search of this visit: the person's preferred languages.
    func search() {
        guard let token else { stage = .failed(errorMessage(nil)); return }
        if !engine.configured { stage = .setup; return }
        cancelSearch(); requestedLanguage = nil; others = nil; othersNote = nil; stage = .searching
        job = Task {
            do {
                let technical = await ensureAnalysis()
                try Task.checkCancellation()
                progress = L("ui_searching_for_subtitles_5b7af2")
                let context = try await context()
                var discovery = try await engine.discover(context, preferences, technical: technical, fingerprint: nil)
                let best = discovery.candidates.first { $0.cues != nil && $0.error == nil }
                if preferences.autoSelect, let best, let alignment = best.alignment, alignment.verified, best.score >= 90, !best.forced, alignment.correlation >= 0.8, alignment.samples >= 4 {
                    let body = String(decoding: try JSONEncoder().encode(try await engine.selected(best, automatic: true)), as: UTF8.self)
                    discovery.selected = try JSONDecoder().decode(SubtitleSelection.self, from: Data(try await controller.request("selected-track", token: token, method: "POST", body: body).utf8))
                }
                if discovery.selected != nil { controller.refreshStatus(item) }
                if !closed { stage = .results(discovery) }
            } catch is CancellationError {
            } catch { if !Task.isCancelled, !closed { stage = .failed(errorMessage((error as? LocalSubtitleError)?.code)) } }
        }
    }

    /// A preferred language is already in the first results. The first other language fetches every other one at once.
    func selectLanguage(_ code: String?) {
        requestedLanguage = code; othersNote = nil
        guard let code, !preferences.languages.contains(code), others == nil, !loadingOthers else { return }
        loadingOthers = true
        Task {
            defer { loadingOthers = false }
            do {
                let technical = await ensureAnalysis()
                let languages = [code] + moreSubtitleLanguages.filter { $0 != code && !preferences.languages.contains($0) }
                var wider = preferences
                wider.languages = languages; wider.autoSelect = false
                others = try await engine.discover(try await context(), wider, technical: technical, fingerprint: nil)
            } catch is CancellationError {
            } catch { othersNote = errorMessage((error as? LocalSubtitleError)?.code) }
        }
    }

    func savePreferences(_ updated: SubtitlePreferences) {
        preferences = updated
        guard let token else { return }
        Task { _ = try? await controller.request("preferences", token: token, method: "PUT", body: String(decoding: try JSONEncoder().encode(updated), as: UTF8.self)) }
    }

    /// Saves the choice (checking a candidate against the video first when it wasn't) and moves on to the timing.
    func choose(_ match: SubtitleMatch, in discovery: SubtitleDiscovery) async throws {
        guard let token else { throw LocalSubtitleError("network_unavailable") }
        let prepared = match.cues != nil ? match : try await engine.prepare(match)
        let body = String(decoding: try JSONEncoder().encode(try await engine.selected(prepared)), as: UTF8.self)
        let saved = try JSONDecoder().decode(SubtitleSelection.self, from: Data(try await controller.request("selected-track", token: token, method: "POST", body: body).utf8))
        selected(discovery, prepared, saved.id)
    }
}
