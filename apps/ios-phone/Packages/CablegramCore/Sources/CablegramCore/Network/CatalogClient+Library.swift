import Foundation

/// Library endpoints of `CatalogClient.kt`: the household catalog, metadata edits, covers and source reconciliation.
extension CatalogClient {
    public struct CatalogError: Error, Equatable { public var status: Int? }

    /// Hides or renames nothing: just the title (Android `patchTitle`).
    public func patchTitle(token: String, itemId: String, title: String) async -> Bool {
        await send("PATCH", "/api/catalog/items/\(itemId)", token: token, json: ["title": title]).isSuccess
    }

    /// Sends one pending metadata edit. The server answers with the saved item (200) or the current one (409).
    public func patchMetadata(token: String, item: LibraryItem) async -> MetadataSyncResult {
        guard let catalogId = item.catalogItemId, let editId = item.metadataEditId else { return .failed }
        var body: [String: Any] = ["expected_revision": item.metadataRevision, "edit_id": editId]
        for field in item.pendingMetadataFields {
            switch field {
            case "title": body["title"] = item.title
            case "year": body["year"] = item.year.map { $0 as Any } ?? NSNull()
            case "overview": body["overview"] = item.overview.map { $0 as Any } ?? NSNull()
            case "mediaType": body["media_type"] = item.mediaType
            default: break
            }
        }
        let reply = await send("PATCH", "/api/catalog/items/\(catalogId)", token: token, json: body)
        if reply.isSuccess {
            guard let wire = reply.object, wire["metadata_revision"] as? Int != nil, wire["user_metadata_fields"] != nil,
                  let saved: RemoteCatalogItem = reply.decode() else { return .failed }
            let sent = Set(item.pendingMetadataFields.map { $0 == "mediaType" ? "media_type" : $0 })
            if saved.id != catalogId || saved.metadataRevision <= item.metadataRevision || !saved.userMetadataFields.isSuperset(of: sent) { return .failed }
            return .saved(saved)
        }
        if reply.status == 409, let current = reply.object?["current"],
           let data = try? JSONSerialization.data(withJSONObject: current),
           let remote = try? JSONDecoder().decode(RemoteCatalogItem.self, from: data) {
            return .conflict(remote)
        }
        return .failed
    }

    private func isWeakLocalTitle(_ item: LibraryItem) -> Bool {
        let stem = item.filename.contains(".") ? String(item.filename[..<item.filename.lastIndex(of: ".")!]) : item.filename
        let filenameTitle = stem.trimmingCharacters(in: .whitespaces)
        return item.title.trimmingCharacters(in: .whitespaces).isEmpty || item.title == filenameTitle || item.title == item.filename
    }

    /// Flow 5: push one catalog item (with fingerprint); used by resumable sync. `onImported` gets the catalog id.
    public func pushLibraryItem(token: String, item: LibraryItem, deviceId: String? = nil, onImported: (String) -> Void = { _ in }) async -> Bool {
        var body: [String: Any] = [
            "origin_filename": item.filename.isEmpty ? item.title : item.filename,
            "origin_identity": item.id,
            "private": item.isPrivate,
        ]
        if !item.pendingMetadataFields.contains("title") && !isWeakLocalTitle(item) { body["title"] = item.title }
        // Echoing the server's own upload URL back would clear the uploaded image.
        if let url = item.posterUrl, !isHouseholdPosterUrl(url) { body["poster_url"] = url }
        if item.mediaType == "tv" && item.catalogIdentityUserSelected {
            if let tmdb = item.tmdbId { body["series_identity"] = "tmdb:\(tmdb)" }
            // The API accepts positive seasons; special season 0 remains a local choice.
            if let season = item.seasonNumber, season > 0 { body["season_number"] = season }
            if let episode = item.episodeNumber, episode > 0 { body["episode_number"] = episode }
        }
        if let duration = item.durationSeconds { body["duration_seconds"] = duration }
        if !item.pendingMetadataFields.contains("mediaType") { body["media_type"] = item.mediaType }
        if !item.genres.isEmpty { body["genres"] = item.genres }
        if let uri = item.sourceUri {
            body["stable_source_key"] = uri
            body["phone_location"] = uri
        }
        if let fingerprint = item.fingerprint { body["source_fingerprint"] = fingerprint }
        if let deviceId, !deviceId.isEmpty { body["serving_device_id"] = deviceId }
        let reply = await send("POST", "/api/catalog/items", token: token, json: body)
        guard reply.isSuccess || reply.status == 409, let id = reply.object?["id"] as? String else { return false }
        onImported(id)
        return true
    }

    public func pushLibrary(token: String, items: [LibraryItem], deviceId: String? = nil) async -> Bool {
        var all = true
        for item in items where await !pushLibraryItem(token: token, item: item, deviceId: deviceId) { all = false }
        return all
    }

    /// CAB-29: called only for a cover the person chose to save to the household.
    public func uploadPoster(token: String, itemId: String, bytes: Data) async -> Bool {
        await send("POST", "/api/catalog/items/\(itemId)/poster", token: token, json: ["poster_data": bytes.base64EncodedString()]).isSuccess
    }

    public func removePoster(token: String, itemId: String) async -> Bool {
        await send("DELETE", "/api/catalog/items/\(itemId)/poster", token: token).isSuccess
    }

    /// Tells the server which phone sources are reachable now, so unreachable ones are archived, never deleted.
    public func reconcileSources(token: String, sources: [LibraryItem], deviceId: String? = nil) async -> Bool {
        var body: [String: Any] = [:]
        if let deviceId { body["serving_device_id"] = deviceId }
        body["present_sources"] = sources.map { item -> [String: Any] in
            var source: [String: Any] = [:]
            if let uri = item.sourceUri {
                source["stable_source_key"] = uri
                source["phone_location"] = uri
            }
            if let fingerprint = item.fingerprint { source["source_fingerprint"] = fingerprint }
            return source
        }
        return await send("POST", "/api/catalog/sources/reconcile", token: token, json: body).isSuccess
    }

    /// The household catalog. With `failOnError` a failed request throws; otherwise it returns an empty list.
    public func fetchCatalog(token: String, failOnError: Bool = false) async throws -> [RemoteCatalogItem] {
        let reply = await send("GET", "/api/catalog/items", token: token)
        guard reply.isSuccess, let parsed: RemoteCatalogResponse = reply.decode() else {
            if failOnError { throw CatalogError(status: reply.status) }
            return []
        }
        return parsed.items
    }

    /// A cover image from an https URL (restored titles carry only the catalog poster URL).
    public func downloadBytes(url: String) async -> Data? {
        guard url.hasPrefix("https://"), let target = URL(string: url) else { return nil }
        guard let (data, response) = try? await session.data(from: target), (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return data
    }
}
