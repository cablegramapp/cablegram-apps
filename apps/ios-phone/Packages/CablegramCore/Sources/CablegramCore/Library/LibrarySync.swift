import Foundation

/// How a sync reaches the files on the phone. The app target implements it (Photos assets, bookmarked folders);
/// until phase 4 `NoSources` says nothing is reachable.
public protocol SourceInspector: Sendable {
    /// Checks the original phone source without copying it into Cablegram storage.
    func hasSource(_ item: LibraryItem) -> Bool
    /// Stable source fingerprint = name + size + observed length/modified time of the original source.
    func fingerprint(_ item: LibraryItem) -> String?
    /// Files already selected into this library, by fingerprint; never scans shared storage.
    func selectedVideosByFingerprint() -> [String: DeviceVideo]
}

public struct NoSources: SourceInspector {
    public init() {}
    public func hasSource(_ item: LibraryItem) -> Bool { false }
    public func fingerprint(_ item: LibraryItem) -> String? { nil }
    public func selectedVideosByFingerprint() -> [String: DeviceVideo] { [:] }
}

/// Same formula as Android `fingerprintOf`: a Java-style `String.hashCode` of the lower-cased name in the high 32 bits,
/// xor of size and shifted modified time below, printed as signed hex.
public func sourceFingerprintOf(name: String, size: Int64, mtime: Int64) -> String {
    let namePart = name.lowercased().trimmingCharacters(in: .whitespaces)
    var hash: Int32 = 0
    for unit in namePart.utf16 { hash = hash &* 31 &+ Int32(unit) }
    let value = (Int64(hash) << 32) | (size ^ (mtime << 7))
    return "sha:" + String(value, radix: 16)
}

public struct LibrarySyncError: Error, Equatable {
    public enum Kind: Equatable, Sendable { case sourceAvailability, item(title: String), metadata }
    public var kind: Kind
}

/// Port of `PhoneViewModel.syncLibrary`: fingerprint the sources, reconcile, push each item with a checkpoint, send pending
/// metadata edits, then merge the household catalog into the library. A failure mid-sync keeps every already synced item
/// done; the next run resumes from the remaining ones.
public struct LibrarySync: Sendable {
    public var store: LibraryStore
    public var client: CatalogClient
    public var token: String
    public var phoneDeviceId: String?
    public var sources: any SourceInspector
    /// Reports "Syncing <title>" progress (0...1).
    public var progress: @Sendable (String?, Double) -> Void = { _, _ in }

    public init(store: LibraryStore, client: CatalogClient, token: String, phoneDeviceId: String?, sources: any SourceInspector = NoSources()) {
        self.store = store
        self.client = client
        self.token = token
        self.phoneDeviceId = phoneDeviceId
        self.sources = sources
    }

    public func run() async throws {
        let before = store.list()
        // Attach fingerprints (cheap); reuse previously computed values so a re-run never recomputes unchanged items.
        let fingerprinted = before.map { item -> LibraryItem in
            var i = item
            if i.fingerprint == nil { i.fingerprint = sources.fingerprint(item) }
            return i
        }
        for item in fingerprinted {
            let prior = before.first { $0.id == item.id }
            if item.fingerprint != nil && prior?.fingerprint != item.fingerprint {
                store.updateItem(item.id) { var i = $0; i.fingerprint = item.fingerprint; return i }
            }
        }
        let phoneItems = fingerprinted.filter { $0.sourceKind == "phone_local" }
        let present = phoneItems.filter { sources.hasSource($0) || store.hasLocalBytes($0) }
        let presentIds = Set(present.map(\.id))
        // Loss of a file grant, disconnected storage, or an offline source changes availability; it must never delete the catalog.
        for item in phoneItems {
            let available = presentIds.contains(item.id)
            if item.sourceAvailable != available { store.updateItem(item.id) { var i = $0; i.sourceAvailable = available; return i } }
        }
        guard await client.reconcileSources(token: token, sources: present, deviceId: phoneDeviceId) else {
            throw LibrarySyncError(kind: .sourceAvailability)
        }
        // Only reachable phone sources may be reattached by an import. Unavailable titles stay cached and archived on the server.
        for (index, item) in present.enumerated() {
            progress(item.title, Double(index + 1) / Double(present.count))
            let pushed = await client.pushLibraryItem(token: token, item: item, deviceId: phoneDeviceId) { [store] in store.setCatalogIdentity(item.id, catalogId: $0) }
            let artwork = pushed ? await syncHouseholdArtwork(item.id) : false
            if !pushed || !artwork { throw LibrarySyncError(kind: .item(title: item.title)) }
        }
        progress(nil, 0)
        var metadataFailed = false
        // Edits are persisted independently of source availability and survive process death.
        for item in store.list() where !item.pendingMetadataFields.isEmpty {
            if item.metadataConflict { metadataFailed = true; continue }
            let result = await client.patchMetadata(token: token, item: item)
            store.acceptMetadata(sent: item, result: result)
            if case .saved = result {} else { metadataFailed = true }
        }
        let remotes = try await client.fetchCatalog(token: token, failOnError: true)
        let relinked = await merge(remotes)
        // Restored titles carry only the catalog poster URL; the library draws local files.
        for item in store.list() where item.posterPath == nil && item.posterUrl?.hasPrefix("https://") == true {
            if let bytes = await client.downloadBytes(url: item.posterUrl!), let relative = store.writeCatalogPoster(item.id, bytes: bytes) {
                store.updateItem(item.id) { var i = $0; i.posterPath = relative; return i }
            }
        }
        // Re-linked files become this phone's sources right away, so the TV can play them.
        for item in relinked {
            store.updateItem(item.id) { var i = $0; i.fingerprint = item.fingerprint; return i }
            _ = await client.pushLibraryItem(token: token, item: store.get(item.id) ?? item, deviceId: phoneDeviceId) { [store] in store.setCatalogIdentity(item.id, catalogId: $0) }
            _ = await syncHouseholdArtwork(item.id)
        }
        if metadataFailed { throw LibrarySyncError(kind: .metadata) }
    }

    /// Uploads or withdraws a cover the person chose to save to the household (CAB-29).
    public func syncHouseholdArtwork(_ id: String) async -> Bool {
        guard let item = store.get(id), let catalogId = item.catalogItemId else { return true }
        switch householdArtworkStep(item) {
        case .none: return true
        case .upload:
            guard let file = store.posterFile(item), let bytes = try? Data(contentsOf: file) else { return true }
            let uploaded = await client.uploadPoster(token: token, itemId: catalogId, bytes: bytes)
            if uploaded { store.updateItem(id) { var i = $0; i.householdArtworkUploadedVersion = item.posterVersion; return i } }
            return uploaded
        case .remove:
            let removed = await client.removePoster(token: token, itemId: catalogId)
            if removed { store.updateItem(id) { var i = $0; i.householdArtworkUploadedVersion = nil; return i } }
            return removed
        }
    }

    /// Restores household titles this phone doesn't list (reinstall, new phone): re-links the files the person selected
    /// again by fingerprint, keeps the rest as "not on this phone". Returns the items that were re-linked.
    private func merge(_ remotes: [RemoteCatalogItem]) async -> [LibraryItem] {
        let deviceVideos = sources.selectedVideosByFingerprint()
        let dismissed = store.dismissedRemoteIds()
        var relinked: [LibraryItem] = []
        let alreadyAsked = store.promptedTelegramIds()
        var telegramAskIds: [String] = []
        let live: (RemoteCatalogSource, String) -> Bool = { $0.kind == $1 && $0.archiveState != "archived" }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plain = ISO8601DateFormatter()
        for remote in remotes where remote.sources.contains(where: { live($0, "telegram") }) {
            let created = remote.createdAt.flatMap { formatter.date(from: $0) ?? plain.date(from: $0) }
            if needsTelegramMatchPrompt(matchStatus: remote.matchStatus, createdAt: created, now: Date(), alreadyPrompted: alreadyAsked.contains(remote.id)) {
                let phoneCopy = store.get(remote.id).flatMap { $0.sourceKind != "telegram" ? $0 : nil }
                if phoneCopy == nil { telegramAskIds.append(remote.id) }
            }
        }
        store.pruneTelegram(remoteIds: Set(remotes.filter { $0.sources.contains { live($0, "telegram") } }.map(\.id)))
        for remote in remotes {
            // A title that also has (or had) a file on this phone stays a phone title: it must not appear a second time as
            // a Telegram title, nor after its phone copy was freed up.
            var phoneCopy = store.get(remote.id).flatMap { $0.sourceKind != "telegram" ? $0 : nil }
            if phoneCopy == nil {
                phoneCopy = remote.sources.lazy.compactMap { s -> LibraryItem? in
                    if s.kind == "telegram" || s.kind == "own_cloud" { return nil }
                    return s.originIdentity.flatMap { store.get($0) }.flatMap { $0.sourceKind != "telegram" ? $0 : nil }
                }.first
            }
            let livePhoneSource = remote.sources.contains { live($0, "phone_local") }
            if phoneCopy == nil && !livePhoneSource, let telegram = remote.sources.first(where: { live($0, "telegram") }) {
                store.registerTelegram(remote, source: telegram)
                continue
            }
            let hasCloudObject = remote.sources.contains { live($0, "cloud_object") }
            // A Telegram copy's identity (`tg:…`) is not a phone file id.
            let source = remote.sources.first { $0.kind != "telegram" && $0.kind != "own_cloud" && !($0.originIdentity ?? "").isEmpty }
            // A verified copy in the person's own R2 bucket. Dynamic: it clears when the bucket is disconnected.
            let hasOwnCloudCopy = remote.sources.contains { live($0, "own_cloud") && $0.availability != "unavailable" }
            let hasTelegramCopy = remote.sources.contains { live($0, "telegram") && $0.availability != "unavailable" }
            let local = source?.originIdentity.flatMap { store.get($0) }
                ?? remote.sources.lazy.compactMap { s in s.phoneLocation.flatMap { loc in store.list().first { $0.sourceUri == loc } } }.first
                ?? store.get(remote.id)
            if let local, local.id != remote.id {
                // A re-imported file now represents this title; its placeholder can go.
                store.dropPlaceholder(remote.id)
            }
            if let local, local.householdOnly, let print = local.fingerprint, let video = deviceVideos[print], var linked = store.relink(local.id, video: video) {
                linked.fingerprint = sources.fingerprint(linked)
                relinked.append(linked)
            }
            if let local {
                store.updateItem(local.id) { current in
                    var c = current
                    c.durationSeconds = remote.durationSeconds ?? local.durationSeconds
                    // What groups a series' episodes under one poster comes from the household catalog.
                    if !c.catalogIdentityUserSelected {
                        c.tmdbId = remote.tmdbId ?? remote.seriesIdentity.flatMap { Int($0.replacingOccurrences(of: "tmdb:", with: "")) } ?? c.tmdbId
                        c.seasonNumber = remote.seasonNumber ?? c.seasonNumber
                        c.episodeNumber = remote.episodeNumber ?? c.episodeNumber
                    }
                    // A household-saved cover comes back as the server's copy; this phone keeps its own file.
                    if catalogMayReplaceArtwork(local) {
                        c.posterUrl = remote.posterUrl.flatMap { isHouseholdPosterUrl($0) ? nil : $0 } ?? local.posterUrl
                    }
                    c.cloudObjectPresent = hasCloudObject || local.cloudObjectPresent
                    // Saved to Telegram (here or on another phone): the server knows the verified copy.
                    c.telegramCopy = hasTelegramCopy && local.sourceKind == "phone_local"
                    c.ownCloudCopy = hasOwnCloudCopy && local.sourceKind == "phone_local"
                    c.ownCloudSourceId = hasOwnCloudCopy && local.sourceKind == "phone_local" ? ownCloudSourceIdOf(remote.sources) : nil
                    c.storageState = hasCloudObject ? storageCloud : local.storageState
                    c.transferStatus = hasCloudObject ? transferIdle : local.transferStatus
                    return mergeManualMetadata(c, remote: remote)
                }
            } else if let phoneSource = remote.sources.first(where: { live($0, "phone_local") }), !dismissed.contains(remote.id) {
                // Web titles (kind "web") are not imported in this release (CAB-80).
                var restored = store.restoreFromHousehold(remote, source: phoneSource)
                if hasCloudObject { restored.cloudObjectPresent = true; store.update(restored) }
                if let print = phoneSource.sourceFingerprint, let video = deviceVideos[print], var linked = store.relink(restored.id, video: video) {
                    linked.fingerprint = sources.fingerprint(linked)
                    relinked.append(linked)
                }
            }
        }
        // The Telegram titles were registered above; remember the ones to ask about. Matching is available from item
        // details; sync never interrupts browsing.
        for id in telegramAskIds {
            if let item = store.get(id), item.sourceKind == "telegram" { store.markTelegramPrompted(item.id) }
        }
        return relinked
    }
}

/// The catalog id of a title's usable copy in the household's own storage, or nil.
public func ownCloudSourceIdOf(_ sources: [RemoteCatalogSource]) -> String? {
    sources.first { $0.kind == "own_cloud" && $0.archiveState != "archived" && $0.availability != "unavailable" }?.id
}
