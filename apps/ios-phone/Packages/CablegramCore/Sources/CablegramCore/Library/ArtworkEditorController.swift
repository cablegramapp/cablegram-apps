import Foundation
import Observation

/// What the editor needs from the platform: preview frames of the video, validated cover bytes, cleanup.
public protocol ArtworkMedia: Sendable {
    /// Up to `count` frame files for `item`, in a cache folder owned by `session`.
    func extractPreviewFrames(_ item: LibraryItem, count: Int, session: Int64) async -> [String]
    /// Writes cover bytes into the session's cache folder and returns the file path; throws when the bytes are not an image.
    func stageArtwork(session: Int64, revision: Int64, bytes: Data) throws -> String
    func discardArtworkPreviews(session: Int64)
    func isValidImage(_ url: URL) -> Bool
}

/// Owns a single editor session; late responses never publish into another item or query (Android `ArtworkEditorController`).
@MainActor @Observable
public final class ArtworkEditorController {
    public private(set) var draft: ArtworkDraft?
    public var lookupAvailable: Bool = true

    private let store: LibraryStore
    private let media: any ArtworkMedia
    private let catalog: @Sendable () -> CatalogClient
    private let token: @Sendable () -> String?
    private let onSaved: @MainActor (LibraryItem) -> Void
    private var generation: Int64 = 0
    private var lookupRevision: Int64 = 0
    private var searchTask: Task<Void, Never>?
    private var sessionTasks: [Task<Void, Never>] = []

    public init(store: LibraryStore, media: any ArtworkMedia, catalog: @escaping @Sendable () -> CatalogClient,
                token: @escaping @Sendable () -> String?, onSaved: @escaping @MainActor (LibraryItem) -> Void) {
        self.store = store
        self.media = media
        self.catalog = catalog
        self.token = token
        self.onSaved = onSaved
    }

    public func open(_ item: LibraryItem) {
        close()
        generation += 1
        let session = generation
        draft = ArtworkDraft(base: item, session: session)
        let media = self.media
        sessionTasks.append(Task { [weak self] in
            let frames = await media.extractPreviewFrames(item, count: 4, session: session)
            guard let self, self.draft?.session == session else { return }
            self.draft?.frames = frames
            self.draft?.loadingFrames = false
        })
    }

    public func update(_ transform: (inout ArtworkDraft) -> Void) {
        guard var changed = draft, !changed.saving else { return }
        transform(&changed)
        changed.duplicate = hasDuplicateEpisode(store.list(), draft: changed)
        draft = changed
    }

    public func updateQuery(_ value: String) {
        searchTask?.cancel()
        lookupRevision += 1
        // Previous selected details/cover remain a valid draft; only the search result is invalidated.
        update { $0.query = String(value.prefix(500)); $0.searching = false; $0.candidate = nil; $0.catalogPath = nil; $0.message = nil }
    }

    public func requestClose() {
        guard let current = draft, !current.saving else { return }
        if current.changed { update { $0.discardRequested = true } } else { close() }
    }

    public func close() {
        let session = draft?.session
        let tasks = sessionTasks
        sessionTasks = []
        tasks.forEach { $0.cancel() }
        if let session {
            let media = self.media
            Task.detached {
                for task in tasks { await task.value }
                media.discardArtworkPreviews(session: session)
            }
        }
        generation += 1
        lookupRevision += 1
        draft = nil
    }

    public func search() {
        guard lookupAvailable else { return }
        guard let current = draft, !current.query.trimmingCharacters(in: .whitespaces).isEmpty, !current.saving else { return }
        searchTask?.cancel()
        lookupRevision += 1
        let revision = lookupRevision, session = current.session
        let active: @MainActor () -> Bool = { [weak self] in self?.draft?.session == session && self?.lookupRevision == revision }
        update { $0.searching = true; $0.message = nil; $0.candidate = nil; $0.catalogPath = nil }
        let task = Task { [weak self] in
            guard let self else { return }
            do {
                let api = catalog()
                let resolved = try await api.resolveTitle(query: current.query.trimmingCharacters(in: .whitespaces), token: token(), strict: true, itemId: current.base.catalogItemId)
                guard active() else { return }
                guard resolved.found, let match = resolved.metadata, !match.title.isEmpty, match.title.caseInsensitiveCompare("Unknown") != .orderedSame else {
                    update { $0.searching = false; $0.message = "No matching movie or series found. Try another title or keep your current cover." }
                    return
                }
                let preview = try await api.enrich(query: match.title, token: token(), itemId: current.base.catalogItemId, mediaType: match.mediaType,
                                                   year: match.year, imdbId: match.imdbId, strict: true)
                await showCandidate(api, preview, session: session, revision: revision, active: active)
            } catch is CancellationError {
                return
            } catch {
                guard active() else { return }
                let status = (error as? CatalogClient.CatalogError)?.status
                let message = status == 401 ? "Sign in again to search for covers. Your edits are kept."
                    : status == nil ? "Couldn't connect. You can still edit details and choose a video frame."
                    : "Couldn't search right now. Try again. Your edits are kept."
                update { $0.searching = false; $0.message = message }
            }
        }
        searchTask = task
        sessionTasks.append(task)
    }

    /// Shows a looked-up title as the candidate, with its cover staged for "Use this cover".
    private func showCandidate(_ api: CatalogClient, _ preview: CatalogMetadata?, session: Int64, revision: Int64, active: @MainActor () -> Bool) async {
        guard active() else { return }
        guard let preview, preview.matchStatus == "matched" else {
            update { $0.searching = false; $0.message = "No catalog details found for that match. Try another title." }
            return
        }
        var path: String?
        if let url = preview.posterUrl, let bytes = await api.downloadBytes(url: url) { path = try? media.stageArtwork(session: session, revision: revision, bytes: bytes) }
        guard active() else { return }
        update {
            $0.searching = false
            $0.candidate = preview
            $0.catalogPath = path
            $0.message = path == nil ? "The cover couldn't be loaded. Search again to retry, or keep your current cover." : nil
        }
    }

    /// The TMDB title the Telegram channel suggests (CAB-60), looked up by its id only now that the person asked for it.
    public func useChannelSuggestion() {
        guard let current = draft, let suggestion = current.base.channelTmdb, !current.saving else { return }
        searchTask?.cancel()
        lookupRevision += 1
        let revision = lookupRevision, session = current.session
        let active: @MainActor () -> Bool = { [weak self] in self?.draft?.session == session && self?.lookupRevision == revision }
        update { $0.searching = true; $0.message = nil; $0.candidate = nil; $0.catalogPath = nil }
        let task = Task { [weak self] in
            guard let self else { return }
            do {
                let api = catalog()
                let preview = try await api.enrich(query: current.base.title, token: token(), itemId: current.base.catalogItemId, mediaType: suggestion.type,
                                                   seasonNumber: suggestion.season, episodeNumber: suggestion.episode, strict: true, tmdbId: suggestion.id)
                await showCandidate(api, preview, session: session, revision: revision, active: active)
            } catch is CancellationError {
                return
            } catch {
                guard active() else { return }
                let offline = (error as? CatalogClient.CatalogError)?.status == nil
                update { $0.searching = false; $0.message = offline ? "Couldn't connect. Try again." : "Couldn't load the suggestion right now. Try again." }
            }
        }
        searchTask = task
        sessionTasks.append(task)
    }

    public func useDetails() {
        guard let current = draft, let match = current.candidate else { return }
        let episodes = store.list().filter {
            $0.id != current.base.id && $0.mediaType == "tv" &&
                (match.tmdbId != nil ? $0.tmdbId == match.tmdbId : $0.title.caseInsensitiveCompare(match.title) == .orderedSame)
        }
        let last = episodes.filter { $0.seasonNumber != nil && $0.episodeNumber != nil }
            .max { ($0.seasonNumber!, $0.episodeNumber!) < ($1.seasonNumber!, $1.episodeNumber!) }
        let fromFile = current.base.filename.range(of: #"(?i)S(\d{1,2})E(\d{1,3})"#, options: .regularExpression).map { String(current.base.filename[$0]) }
        let fileNumbers = fromFile.map { $0.dropFirst().split(whereSeparator: { $0 == "E" || $0 == "e" }).compactMap { Int($0) } } ?? []
        update {
            $0.title = match.title
            $0.year = match.year.map(String.init) ?? ""
            $0.mediaType = match.mediaType
            $0.overview = match.overview ?? ""
            $0.useCatalogDetails = true
            $0.acceptedMatch = match
            $0.season = fileNumbers.count == 2 ? String(fileNumbers[0]) : (match.seasonNumber ?? current.base.seasonNumber ?? last?.seasonNumber).map(String.init) ?? ""
            $0.episode = fileNumbers.count == 2 ? String(fileNumbers[1]) : (match.episodeNumber ?? current.base.episodeNumber ?? last?.episodeNumber.map { $0 + 1 }).map(String.init) ?? ""
            $0.message = match.mediaType == "tv" ? "Check season and episode before saving. Season 0 is for specials."
                : "Catalog details are in your draft. Your cover stays unchanged until you choose one."
        }
    }

    public func save() {
        guard let current = draft, current.changed, current.valid, !current.saving, !current.searching else { return }
        searchTask?.cancel()
        lookupRevision += 1
        var saving = current
        saving.saving = true
        saving.message = nil
        draft = saving
        let store = self.store, media = self.media
        Task { [weak self] in
            do {
                let saved = try await Task.detached { try store.saveArtworkDraft(current) { media.isValidImage($0) } }.value
                guard let self, self.draft?.session == current.session else { return }
                self.close()
                self.onSaved(saved)
            } catch {
                guard let self, self.draft?.session == current.session else { return }
                var failed = current
                failed.message = "Changes couldn't be saved. Try again."
                self.draft = failed
            }
        }
    }
}
