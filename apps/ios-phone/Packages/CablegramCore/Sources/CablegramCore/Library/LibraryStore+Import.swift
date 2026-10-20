import Foundation

extension LibraryStore {
    public enum SharedImportResult: Sendable {
        case imported(LibraryItem)
        case dropped(displayName: String)
    }

    /// Lists a file by reference (nothing is copied): the title keeps playing from where it is. Returns nil when that
    /// source is already in the library. The app attaches a cover frame afterwards (`setPosterFromFrame`).
    public func indexExternal(uri: String, displayName: String, sizeBytes: Int64?, durationSeconds: Int?) -> LibraryItem? {
        guard !list().contains(where: { $0.sourceUri == uri }) else { return nil }
        let id = UUID().uuidString.lowercased()
        let ext = displayName.contains(".") ? String(displayName.split(separator: ".").last!) : "mp4"
        let stem = displayName.contains(".") ? String(displayName[..<displayName.lastIndex(of: ".")!]) : displayName
        let item = LibraryItem(
            id: id, title: firstCatalogHint(displayName) ?? stem, filename: displayName, fileName: "\(id).\(ext)",
            durationSeconds: durationSeconds, fileSizeBytes: sizeBytes, genres: ["Personal Videos"], mediaType: guessMediaType(displayName),
            importedAt: isoTimestamp(), sourceUri: uri, copied: false, storageState: storageLocal, artworkOrigin: artworkPlaceholder)
        add(item)
        return item
    }

    /// Uses `frame` (JPEG bytes) as the cover chosen by the app, e.g. a frame of the video, and bumps the version.
    @discardableResult
    public func setPosterFromFrame(_ id: String, jpeg: Data) -> LibraryItem? {
        guard var item = get(id) else { return nil }
        let relative = "posters/\(id).jpg"
        guard (try? jpeg.write(to: root.appendingPathComponent(relative), options: .atomic)) != nil else { return nil }
        item.posterPath = relative
        item.posterVersion += 1
        item.artworkOrigin = artworkFrame
        update(item)
        return item
    }

    // MARK: Copying a shared file in

    private func queueSharedImport(uri: String, displayName: String) -> PendingSharedImport {
        let pending = PendingSharedImport(id: UUID().uuidString.lowercased(), uri: uri, displayName: displayName, createdAt: isoTimestamp())
        var file = snapshot()
        file.pendingSharedImports.append(pending)
        writeSnapshot(file)
        return pending
    }

    private func completeSharedImport(_ id: String) {
        var file = snapshot()
        file.pendingSharedImports.removeAll { $0.id == id }
        writeSnapshot(file)
    }

    /// Records the import before copying, so a process death mid-copy is picked up by `resumeSharedImports`. A failure
    /// that is reported to the caller drops the record again. `source` is a file the app can read right now.
    public func importFile(from source: URL, displayName: String, metadata: CatalogMetadata? = nil, probe: (URL) -> ImportProbe = { _ in ImportProbe() }) throws -> LibraryItem {
        let pending = queueSharedImport(uri: source.absoluteString, displayName: displayName)
        markImporting(pending.id, true)
        defer { markImporting(pending.id, false); completeSharedImport(pending.id) }
        return try copyShared(pending, from: source, metadata: metadata, probe: probe)
    }

    /// What the app learned about a copied file (duration, resolution, a cover frame, an embedded title).
    public struct ImportProbe: Sendable {
        public var durationSeconds: Int?
        public var resolution: String?
        public var embeddedTitle: String?
        public var posterJPEG: Data?
        public init(durationSeconds: Int? = nil, resolution: String? = nil, embeddedTitle: String? = nil, posterJPEG: Data? = nil) {
            self.durationSeconds = durationSeconds
            self.resolution = resolution
            self.embeddedTitle = embeddedTitle
            self.posterJPEG = posterJPEG
        }
    }

    /// Copies to `<name>.part`, renames it into place in one step, then registers it.
    private func copyShared(_ pending: PendingSharedImport, from source: URL, metadata: CatalogMetadata?, probe: (URL) -> ImportProbe) throws -> LibraryItem {
        let ext = pending.displayName.contains(".") ? String(pending.displayName.split(separator: ".").last!) : "mp4"
        let storedName = "\(pending.id).\(ext.isEmpty ? "mp4" : ext)"
        let dest = videosDir.appendingPathComponent(storedName)
        let part = videosDir.appendingPathComponent("\(storedName).part")
        try? FileManager.default.removeItem(at: part)
        do {
            try FileManager.default.copyItem(at: source, to: part)
            try FileManager.default.moveItem(at: part, to: dest)
            let info = probe(dest)
            let size = (try? dest.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init)
            let stem = pending.displayName.contains(".") ? String(pending.displayName[..<pending.displayName.lastIndex(of: ".")!]) : pending.displayName
            let item = LibraryItem(
                id: pending.id,
                title: (metadata?.title.isEmpty == false ? metadata!.title : nil) ?? firstCatalogHint(info.embeddedTitle, pending.displayName) ?? stem,
                filename: pending.displayName, fileName: storedName, durationSeconds: info.durationSeconds, fileSizeBytes: size,
                genres: metadata?.genres ?? ["Personal Videos"], year: metadata?.year, overview: metadata?.overview,
                posterPath: nil, mediaType: metadata?.mediaType ?? guessMediaType(pending.displayName), importedAt: isoTimestamp(),
                sourceUri: pending.uri, copied: true, storageState: storageLocal, resolution: info.resolution,
                artworkOrigin: info.posterJPEG == nil ? artworkPlaceholder : artworkFrame)
            add(item)
            if let jpeg = info.posterJPEG { return setPosterFromFrame(item.id, jpeg: jpeg) ?? item }
            return item
        } catch {
            try? FileManager.default.removeItem(at: part)
            if get(pending.id) == nil { try? FileManager.default.removeItem(at: dest) }
            throw error
        }
    }

    /// Finishes shared imports a process death interrupted; one whose file can't be read any more is dropped.
    public func resumeSharedImports(probe: (URL) -> ImportProbe = { _ in ImportProbe() }) -> [SharedImportResult] {
        snapshot().pendingSharedImports.filter { !isImporting($0.id) }.map { pending in
            let registered = get(pending.id)
            let sourceURL = URL(string: pending.uri)
            let readable = registered == nil && sourceURL.map { FileManager.default.isReadableFile(atPath: $0.path) } == true
            defer { completeSharedImport(pending.id) }
            switch sharedImportResume(alreadyRegistered: registered != nil, sourceReadable: readable) {
            case .finish: return .imported(registered!)
            case .copy:
                if let sourceURL, let item = try? copyShared(pending, from: sourceURL, metadata: nil, probe: probe) { return .imported(item) }
                return .dropped(displayName: pending.displayName)
            case .drop: return .dropped(displayName: pending.displayName)
            }
        }
    }

    /// Deletes `.part` files and files in the videos folder that no library row points to (the current library and any
    /// set-aside one). Does nothing if a set-aside library can't be read.
    @discardableResult
    public func sweepVideos(now: Date = Date()) -> Int {
        var referenced = Set(list().map(\.fileName))
        let stashes = ((try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.lastPathComponent.hasPrefix("index-") && $0.pathExtension == "json" }
        for stash in stashes {
            guard let data = try? Data(contentsOf: stash), let file = try? JSONDecoder().decode(LibraryFile.self, from: data) else { return 0 }
            file.items.forEach { referenced.insert($0.fileName) }
        }
        let urls = (try? FileManager.default.contentsOfDirectory(at: videosDir, includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey])) ?? []
        let infos = urls.compactMap { url -> VideoFileInfo? in
            let values = try? url.resourceValues(forKeys: [.contentModificationDateKey, .isRegularFileKey])
            guard values?.isRegularFile == true else { return nil }
            return VideoFileInfo(name: url.lastPathComponent, lastModified: Int64((values?.contentModificationDate ?? .distantPast).timeIntervalSince1970 * 1000))
        }
        let doomed = orphanedVideoFiles(infos, referenced: referenced, importing: importingNames(), now: Int64(now.timeIntervalSince1970 * 1000))
        doomed.forEach { try? FileManager.default.removeItem(at: videosDir.appendingPathComponent($0)) }
        return doomed.count
    }
}
