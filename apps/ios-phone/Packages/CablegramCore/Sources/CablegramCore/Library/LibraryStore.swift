import Foundation

/// A video file found on the phone (Android `DeviceVideo`); on iOS `uri` is a bookmark or asset identifier.
public struct DeviceVideo: Sendable, Equatable {
    public var uri: String
    public var name: String
    public var size: Int64
    public init(uri: String, name: String, size: Int64) {
        self.uri = uri
        self.name = name
        self.size = size
    }
}

public func isoTimestamp(_ date: Date = Date()) -> String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter.string(from: date)
}

/// The phone's library on disk (Android `LibraryStore`): one JSON index written atomically, plus `videos/`, `posters/`
/// and `cloud/` folders. Paths stored in items (`posterPath`) are relative to `root`, because the app container's
/// absolute path changes between installs and updates on iOS. Importing files (copy, probe, index) arrives with phase 4.
public final class LibraryStore: @unchecked Sendable {
    public let root: URL
    private let lock = NSRecursiveLock()
    private let fm = FileManager.default
    /// Called after every write to the index, so screens can redraw when a background transfer finishes (CAB-51).
    public var onWrite: (@Sendable () -> Void)?

    public init(root: URL) {
        self.root = root
        for folder in ["", "videos", "posters", "cloud"] {
            try? fm.createDirectory(at: root.appendingPathComponent(folder), withIntermediateDirectories: true)
        }
    }

    public var videosDir: URL { root.appendingPathComponent("videos", isDirectory: true) }
    public var postersDir: URL { root.appendingPathComponent("posters", isDirectory: true) }
    public var cloudDir: URL { root.appendingPathComponent("cloud", isDirectory: true) }
    private var indexFile: URL { root.appendingPathComponent("index.json") }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock(); defer { lock.unlock() }
        return try body()
    }

    // MARK: Reading

    public func snapshot() -> LibraryFile {
        locked {
            guard let data = try? Data(contentsOf: indexFile) else { return LibraryFile() }
            return (try? JSONDecoder().decode(LibraryFile.self, from: data)) ?? LibraryFile()
        }
    }

    public func list() -> [LibraryItem] { snapshot().items }
    public func collections() -> [UserCollection] { snapshot().collections }
    public func folders() -> [IndexedFolder] { snapshot().folders }
    public func get(_ id: String) -> LibraryItem? { list().first { $0.id == id } }
    public func dismissedRemoteIds() -> Set<String> { Set(snapshot().dismissedRemoteIds) }
    public func promptedTelegramIds() -> Set<String> { Set(snapshot().promptedTelegramIds) }

    public func videoFile(_ item: LibraryItem) -> URL { videosDir.appendingPathComponent(item.fileName) }
    public func cloudFile(_ item: LibraryItem) -> URL { cloudDir.appendingPathComponent(item.fileName) }

    /// The poster file if it exists on disk.
    public func posterFile(_ item: LibraryItem) -> URL? {
        guard let path = item.posterPath, !path.isEmpty else { return nil }
        // Staged editor covers are absolute cache paths; library covers are relative to the library root.
        let url = path.hasPrefix("/") ? URL(fileURLWithPath: path) : root.appendingPathComponent(path)
        return fm.fileExists(atPath: url.path) ? url : nil
    }

    public func hasLocalBytes(_ item: LibraryItem) -> Bool { fm.fileExists(atPath: videoFile(item).path) }
    public func hasCloudBytes(_ item: LibraryItem) -> Bool { fm.fileExists(atPath: cloudFile(item).path) }

    // MARK: Writing

    private func save(_ file: LibraryFile) {
        locked {
            let encoder = JSONEncoder()
            if let data = try? encoder.encode(file) { try? data.write(to: indexFile, options: .atomic) }
        }
        onWrite?()
    }

    public func update(_ item: LibraryItem) {
        locked {
            var file = snapshot()
            file.items = file.items.map { $0.id == item.id ? item : $0 }
            save(file)
        }
    }

    public func updateItem(_ id: String, _ transform: (LibraryItem) -> LibraryItem) {
        locked {
            var file = snapshot()
            file.items = file.items.map { $0.id == id ? transform($0) : $0 }
            save(file)
        }
    }

    /// Adds a new item to the index (used by imports, restores and Telegram registration).
    public func add(_ item: LibraryItem) {
        locked {
            var file = snapshot()
            file.items.append(item)
            save(file)
        }
    }

    // MARK: Households

    /// Binds the library to the signed-in account. Another account's library is set aside (never merged or synced into
    /// this one) and brought back when that account signs in. Returns true when a different library was set aside.
    @discardableResult
    public func bindHousehold(_ householdId: String) -> Bool {
        locked {
            let current = snapshot()
            if current.householdId == householdId { return false }
            guard let previous = current.householdId else {
                var bound = current
                bound.householdId = householdId
                save(bound)
                return false
            }
            let encoder = JSONEncoder()
            try? encoder.encode(current).write(to: root.appendingPathComponent("index-\(previous).json"), options: .atomic)
            let stash = root.appendingPathComponent("index-\(householdId).json")
            var next = (try? Data(contentsOf: stash)).flatMap { try? JSONDecoder().decode(LibraryFile.self, from: $0) }
                ?? LibraryFile(householdId: householdId)
            next.householdId = householdId
            save(next)
            try? fm.removeItem(at: stash)
            return !current.items.isEmpty
        }
    }

    /// The account was deleted: nothing tied to it may stay or sync into a later account. Videos that are files on this
    /// phone stay (they are the person's own); titles that only existed because of the account and its bookkeeping go.
    public func forgetHousehold() {
        locked {
            var file = snapshot()
            let previous = file.householdId
            file.householdId = nil
            file.dismissedRemoteIds = []
            file.promptedTelegramIds = []
            file.items = file.items.filter { !$0.householdOnly && $0.sourceKind != "telegram" }.map { var i = $0; i.telegramCopy = false; return i }
            save(file)
            if let previous { try? fm.removeItem(at: root.appendingPathComponent("index-\(previous).json")) }
        }
    }

    // MARK: Household catalog

    /// A household title without a file on this phone (restored after a reinstall / on a new phone).
    @discardableResult
    public func restoreFromHousehold(_ remote: RemoteCatalogItem, source: RemoteCatalogSource) -> LibraryItem {
        locked {
            if let existing = get(remote.id) { return existing }
            let name = nonBlank(source.originFilename) ?? remote.title ?? "Video"
            let ext = name.contains(".") ? String(name.split(separator: ".").last!) : "mp4"
            let item = LibraryItem(
                id: remote.id,
                title: nonBlank(remote.title) ?? (name.contains(".") ? String(name[..<name.lastIndex(of: ".")!]) : name),
                filename: name,
                fileName: "\(remote.id).\(ext)",
                durationSeconds: remote.durationSeconds,
                genres: remote.genres,
                year: remote.year,
                overview: remote.overview,
                posterUrl: remote.posterUrl,
                mediaType: remote.mediaType ?? "movie",
                importedAt: isoTimestamp(),
                copied: false,
                sourceAvailable: false,
                storageState: storageLocal,
                fingerprint: source.sourceFingerprint,
                tmdbId: remote.tmdbId,
                seasonNumber: remote.seasonNumber,
                episodeNumber: remote.episodeNumber,
                isPrivate: source.isPrivate,
                householdOnly: true,
                userMetadataFields: Set(remote.userMetadataFields.map { $0 == "media_type" ? "mediaType" : $0 }),
                catalogItemId: remote.id,
                metadataRevision: remote.metadataRevision,
                artworkOrigin: remote.posterUrl == nil ? artworkPlaceholder : artworkCatalog
            )
            add(item)
            return item
        }
    }

    /// A video in the household's Telegram channel: listed on the phone, played by the TV from Telegram.
    @discardableResult
    public func registerTelegram(_ remote: RemoteCatalogItem, source: RemoteCatalogSource) -> LibraryItem {
        locked {
            let name = nonBlank(source.originFilename) ?? remote.title ?? "Video"
            let existing = get(remote.id)
            var item = existing ?? LibraryItem(
                id: remote.id,
                title: name.contains(".") ? String(name[..<name.lastIndex(of: ".")!]) : name,
                filename: name,
                fileName: remote.id,
                importedAt: isoTimestamp(),
                sourceKind: "telegram",
                copied: false,
                storageState: storageCloud,
                householdOnly: false,
                artworkOrigin: remote.posterUrl == nil ? artworkPlaceholder : artworkCatalog
            )
            item.durationSeconds = remote.durationSeconds ?? existing?.durationSeconds
            item.genres = remote.genres
            item.tmdbId = remote.tmdbId
            item.seasonNumber = remote.seasonNumber
            item.episodeNumber = remote.episodeNumber
            item.channelTmdb = remote.channelTmdb
            item.posterUrl = remote.posterUrl ?? existing?.posterUrl
            item.sourceAvailable = source.availability != "unavailable"
            item.telegramFileKey = source.stableSourceKey.flatMap { $0.hasPrefix("tgfile:") ? $0 : nil } ?? existing?.telegramFileKey
            let merged = mergeManualMetadata(item, remote: remote)
            if existing == nil { add(merged) } else { update(merged) }
            return merged
        }
    }

    public func markTelegramPrompted(_ id: String) {
        locked {
            var file = snapshot()
            if !file.promptedTelegramIds.contains(id) {
                file.promptedTelegramIds.append(id)
                save(file)
            }
        }
    }

    /// Telegram titles that left the household catalog (hidden or deleted on another device) leave this list too.
    public func pruneTelegram(remoteIds: Set<String>) {
        locked {
            var file = snapshot()
            let kept = file.items.filter { !($0.sourceKind == "telegram" && !remoteIds.contains($0.id)) }
            if kept.count != file.items.count {
                file.items = kept
                save(file)
            }
        }
    }

    /// Points a restored title at the matching file found on this phone.
    @discardableResult
    public func relink(_ id: String, video: DeviceVideo) -> LibraryItem? {
        locked {
            guard var item = get(id) else { return nil }
            item.sourceUri = video.uri
            item.filename = video.name
            item.fileSizeBytes = video.size
            item.sourceAvailable = true
            item.householdOnly = false
            update(item)
            return item
        }
    }

    // MARK: Removing

    /// Removes a title from the list. A removed household title must not come back on the next sync.
    public func removeFromLibrary(_ id: String) {
        locked {
            var file = snapshot()
            let restored = file.items.first { $0.id == id }?.householdOnly == true
            file.items.removeAll { $0.id == id }
            if restored && !file.dismissedRemoteIds.contains(id) { file.dismissedRemoteIds.append(id) }
            save(file)
        }
    }

    /// Drops a restored placeholder that a re-imported file now represents (not a dismissal).
    public func dropPlaceholder(_ id: String) {
        locked {
            var file = snapshot()
            guard file.items.first(where: { $0.id == id })?.householdOnly == true else { return }
            file.items.removeAll { $0.id == id }
            save(file)
        }
    }

    public func deleteFile(_ id: String) {
        locked {
            guard let item = get(id) else { return }
            try? fm.removeItem(at: videoFile(item))
            try? fm.removeItem(at: cloudFile(item))
            if let poster = posterFile(item) { try? fm.removeItem(at: poster) }
            var file = snapshot()
            file.items.removeAll { $0.id == id }
            save(file)
        }
    }

    /// Frees the phone copy once a verified copy exists elsewhere (see `canRemoveLocalCopy`).
    @discardableResult
    public func removeLocalCopy(_ id: String) -> LibraryItem? {
        locked {
            guard var item = get(id) else { return nil }
            if !canRemoveLocalCopy(item) { return item }
            try? fm.removeItem(at: videoFile(item))
            item.copied = false
            item.storageState = storageCloud
            update(item)
            return item
        }
    }

    // MARK: Collections and folders

    @discardableResult
    public func addCollection(_ name: String) -> UserCollection {
        locked {
            let collection = UserCollection(id: UUID().uuidString.lowercased(), name: name.trimmingCharacters(in: .whitespacesAndNewlines))
            var file = snapshot()
            file.collections.append(collection)
            save(file)
            return collection
        }
    }

    public func deleteCollection(_ id: String) {
        locked {
            var file = snapshot()
            file.items = file.items.map { var i = $0; i.collectionIds.removeAll { $0 == id }; return i }
            file.collections.removeAll { $0.id == id }
            save(file)
        }
    }

    public func setItemCollections(_ itemId: String, collectionIds: [String]) {
        locked {
            guard var item = get(itemId) else { return }
            var seen = Set<String>()
            item.collectionIds = collectionIds.filter { seen.insert($0).inserted }
            update(item)
        }
    }

    public func rememberFolder(uri: String, name: String, volumeId: String? = nil) {
        locked {
            var file = snapshot()
            file.folders.removeAll { $0.uri == uri || (volumeId != nil && $0.volumeId == volumeId) }
            file.folders.append(IndexedFolder(uri: uri, name: name, volumeId: volumeId))
            save(file)
        }
    }

    public func forgetFolder(_ uri: String) {
        locked {
            var file = snapshot()
            file.folders.removeAll { $0.uri == uri }
            save(file)
        }
    }

    public func setWatchProgress(_ id: String, positionSeconds: Int) {
        locked {
            guard var item = get(id) else { return }
            item.positionSeconds = max(positionSeconds, 0)
            item.lastWatchedAt = isoTimestamp()
            update(item)
        }
    }

    // MARK: Metadata

    public func editMetadata(_ id: String, title: String, year: Int?, mediaType: String, overview: String?) throws {
        try locked {
            guard let item = get(id) else { return }
            update(try commitManualMetadata(item, title: title, year: year, mediaType: mediaType, overview: overview))
        }
    }

    public func editMetadataDraft(base: LibraryItem, title: String, year: Int?, mediaType: String, overview: String?) throws {
        try locked {
            guard let current = get(base.id) else { return }
            update(try commitManualMetadataDraft(current: current, base: base, title: title, year: year, mediaType: mediaType, overview: overview))
        }
    }

    public func setCatalogIdentity(_ id: String, catalogId: String) {
        updateItem(id) { var i = $0; i.catalogItemId = catalogId; return i }
    }

    public func acceptMetadata(sent: LibraryItem, result: MetadataSyncResult) {
        locked {
            guard var current = get(sent.id) else { return }
            switch result {
            case .saved(let remote): update(acknowledgeManualMetadata(current, sent: sent, remote: remote))
            case .conflict(let remote):
                if current.metadataEditId == sent.metadataEditId {
                    current.metadataConflict = true
                    current.metadataRevision = remote.metadataRevision
                    update(current)
                }
            case .failed: break
            }
        }
    }

    public func mergeRemoteMetadata(_ id: String, remote: RemoteCatalogItem) {
        updateItem(id) { mergeManualMetadata($0, remote: remote) }
    }

    /// Catalog details for a title, except fields the person set themselves. `posterFile` is relative to `root`.
    @discardableResult
    public func applyCatalog(_ id: String, metadata: CatalogMetadata, posterFile: String?) -> LibraryItem? {
        locked {
            guard var item = get(id) else { return nil }
            let mayReplaceArtwork = catalogMayReplaceArtwork(item)
            let mine = item.userMetadataFields
            if !mine.contains("title") { item.title = metadata.title.isEmpty ? item.title : metadata.title }
            if !mine.contains("year") { item.year = metadata.year ?? item.year }
            if !mine.contains("overview") { item.overview = metadata.overview ?? item.overview }
            item.genres = metadata.genres.isEmpty ? item.genres : metadata.genres
            if !mine.contains("mediaType") { item.mediaType = metadata.mediaType.isEmpty ? item.mediaType : metadata.mediaType }
            if !item.catalogIdentityUserSelected {
                item.tmdbId = metadata.tmdbId ?? item.tmdbId
                item.seasonNumber = metadata.seasonNumber ?? item.seasonNumber
                item.episodeNumber = metadata.episodeNumber ?? item.episodeNumber
            }
            if mayReplaceArtwork {
                item.posterPath = posterFile ?? item.posterPath
                item.posterUrl = metadata.posterUrl ?? item.posterUrl
                item.artworkOrigin = artworkCatalog
            }
            update(item)
            return item
        }
    }

    /// Writes a catalog poster and bumps `posterVersion`: same path, new bytes, so views must reload it.
    @discardableResult
    public func writeCatalogPoster(_ id: String, bytes: Data) -> String? {
        locked {
            guard var current = get(id), catalogMayReplaceArtwork(current) else { return nil }
            let relative = "posters/\(id).jpg"
            guard (try? bytes.write(to: root.appendingPathComponent(relative), options: .atomic)) != nil else { return nil }
            current.posterVersion += 1
            current.artworkOrigin = artworkCatalog
            current.artworkUserSelected = false
            update(current)
            return relative
        }
    }

    /// Saves an edited title: a versioned cover image plus one atomic index write keeps the old cover intact on failure.
    /// `isValidImage` checks that a chosen cover file decodes (the app passes a UIImage check).
    public func saveArtworkDraft(_ draft: ArtworkDraft, isValidImage: (URL) -> Bool) throws -> LibraryItem {
        try locked {
            guard draft.valid else { throw MetadataValidationError(reason: "draft") }
            guard let current = get(draft.base.id) else { throw MetadataValidationError(reason: "Video no longer exists") }
            guard !hasDuplicateEpisode(list(), draft: draft) || draft.keepBoth else { throw MetadataValidationError(reason: "Episode already exists") }
            var updated = try commitArtworkDraft(current: current, draft: draft)
            let source: URL?
            switch draft.cover {
            case .keep: source = nil
            case .frame(let path), .catalog(let path, _): source = URL(fileURLWithPath: path)
            }
            var committed: URL?
            do {
                if let source {
                    guard fm.fileExists(atPath: source.path), isValidImage(source) else { throw MetadataValidationError(reason: "Cover unavailable") }
                    let name = "\(current.id)-chosen-\(UUID().uuidString.lowercased()).jpg"
                    let target = postersDir.appendingPathComponent(name)
                    committed = target
                    try fm.copyItem(at: source, to: target)
                    updated.posterPath = "posters/\(name)"
                    if case .catalog(_, let url) = draft.cover { updated.posterUrl = url } else { updated.posterUrl = nil }
                    updated.posterVersion = current.posterVersion + 1
                    updated.artworkUserSelected = true
                    if case .catalog = draft.cover { updated.artworkOrigin = artworkCatalog } else { updated.artworkOrigin = artworkFrame }
                }
                update(updated)
                return updated
            } catch {
                if let committed { try? fm.removeItem(at: committed) }
                throw error
            }
        }
    }

    public func clearPosters() {
        locked {
            for url in (try? fm.contentsOfDirectory(at: postersDir, includingPropertiesForKeys: nil)) ?? [] { try? fm.removeItem(at: url) }
            var file = snapshot()
            file.items = file.items.map { var i = $0; i.posterPath = nil; return i }
            save(file)
        }
    }

    private func nonBlank(_ text: String?) -> String? {
        let t = text?.trimmingCharacters(in: .whitespacesAndNewlines)
        return t?.isEmpty == false ? t : nil
    }
}
