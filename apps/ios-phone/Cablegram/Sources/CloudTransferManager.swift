import CablegramCore
import Foundation
import Photos
import UniformTypeIdentifiers
import UserNotifications

/// Saves titles to the household's own storage (Android `CloudTransferService`): one title at a time, parts and chunks go
/// through the background URLSession, and the library item carries the progress. There is no Cablegram-hosted cloud (CAB-38).
@MainActor
final class CloudTransferManager {
    private let store: LibraryStore
    private let sources: AppSources
    private let catalog: @Sendable () -> CatalogClient
    private let token: @Sendable () -> String?
    private let transport: any UploadTransport
    private var queue: [(id: String, destination: String)] = []
    private var running = false
    /// Called when a save ended, so the library can sync and the storage numbers refresh.
    var onFinished: (() -> Void)?

    init(store: LibraryStore, sources: AppSources, catalog: @escaping @Sendable () -> CatalogClient, token: @escaping @Sendable () -> String?, transport: any UploadTransport) {
        self.store = store
        self.sources = sources
        self.catalog = catalog
        self.token = token
        self.transport = transport
    }

    func isQueued(_ id: String) -> Bool { queue.contains { $0.id == id } }

    func enqueue(_ id: String, destination: String) {
        guard !isQueued(id) else { return }
        queue.append((id, destination))
        Task { _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert]) }
        if !running {
            running = true
            Task { await drain() }
        }
    }

    /// A save the system or the person ended while it was running is picked up again; the server resumes it from what it holds.
    func resumeInterrupted(destination: String) {
        for item in store.list() where item.transferStatus == transferSaving && !isQueued(item.id) { enqueue(item.id, destination: destination) }
    }

    private func drain() async {
        while !queue.isEmpty {
            let next = queue.removeFirst()
            await saveOne(next.id, destination: next.destination)
        }
        running = false
        onFinished?()
    }

    private func saveOne(_ id: String, destination: String) async {
        guard let item = store.get(id) else { return }
        store.updateItem(id) { var i = $0; i.transferStatus = transferSaving; i.uploadBytes = 0; i.uploadTotal = item.fileSizeBytes; return i }
        let started = Date()
        let throttle = ProgressThrottle()
        let store = self.store
        let progress: @Sendable (Int64, Int64) -> Void = { copied, total in
            guard throttle.shouldReport() else { return }
            let seconds = max(Int64(Date().timeIntervalSince(started)), 1)
            store.updateItem(id) { var i = $0; i.transferStatus = transferSaving; i.uploadBytes = copied; i.uploadTotal = total; i.transferBytesPerSec = copied / seconds; return i }
        }
        do {
            try await upload(item, progress: progress)
            notifyDone(item.title, ok: true, detail: L("ui_saved_to_dcdbf0", destination))
        } catch is CancellationError {
            store.updateItem(id) { var i = $0; i.transferStatus = transferFailed; return i }
        } catch {
            store.updateItem(id) { var i = $0; i.transferStatus = transferFailed; return i }
            notifyDone(item.title, ok: false, detail: (error as? R2ApiError)?.friendly(provider: destination) ?? (error as? UploadIOError)?.message)
        }
    }

    /// Streams the video to the household's own storage (R2 parts or Drive chunks, as the server says). The control plane
    /// verifies the stored size before it records the copy, so `ownCloudCopy` is only set for a copy that Free up space may rely on.
    private func upload(_ item: LibraryItem, progress: @escaping @Sendable (Int64, Int64) -> Void) async throws {
        guard let token = token(), !token.isEmpty else { throw UploadIOError("Sign in again to save to the cloud.") }
        let (file, cleanup) = try await sourceFile(item)
        defer { cleanup() }
        let ext = (item.filename as NSString).pathExtension.lowercased()
        let type = UTType(filenameExtension: ext)?.preferredMIMEType
        let contentType = type?.hasPrefix("video/") == true ? type! : "video/mp4"
        let done = try await OwnCloudUploader(api: catalog().ownCloudApi(token: token), transport: transport)
            .upload(originIdentity: item.id, fileName: item.filename, contentType: contentType, file: file, onProgress: progress)
        store.updateItem(item.id) { var i = $0
            i.ownCloudCopy = true
            // The catalog's id for the copy, so it can be removed at once without waiting for the next sync.
            i.ownCloudSourceId = done.sourceId
            i.transferStatus = transferIdle
            i.webTransferError = nil
            i.uploadBytes = done.bytes
            i.uploadTotal = done.bytes
            i.fileSizeBytes = i.fileSizeBytes ?? done.bytes
            return i
        }
    }

    /// A file the uploader can read: the copy in the app, the picked file inside its security scope, or a Photos video
    /// written to a temporary file (deleted afterwards).
    private func sourceFile(_ item: LibraryItem) async throws -> (URL, () -> Void) {
        if store.hasLocalBytes(item) { return (store.videoFile(item), {}) }
        guard let uri = item.sourceUri else { throw UploadIOError("Could not read that video") }
        if let id = BookmarkStore.id(fromURI: uri), let opened = sources.open(item) { _ = id; return (opened.url, opened.release) }
        if let asset = PhotoSource.asset(uri), let resource = PHAssetResource.assetResources(for: asset).first(where: { $0.type == .video || $0.type == .fullSizeVideo }) {
            let temp = FileManager.default.temporaryDirectory.appendingPathComponent("photos-\(item.id)-\(resource.originalFilename)")
            try? FileManager.default.removeItem(at: temp)
            let options = PHAssetResourceRequestOptions()
            options.isNetworkAccessAllowed = true
            try await PHAssetResourceManager.default().writeData(for: resource, toFile: temp, options: options)
            return (temp, { try? FileManager.default.removeItem(at: temp) })
        }
        throw UploadIOError("Could not read that video")
    }

    private func notifyDone(_ title: String, ok: Bool, detail: String?) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = ok ? (detail ?? L("ui_saved_to_cloud_694e20")) : (detail?.isEmpty == false ? detail! : L("ui_could_not_save_to_cloud_17d590"))
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "cloud-save-\(UUID().uuidString)", content: content, trigger: nil))
    }
}

/// Lets a progress report through at most every 400 ms: the library file is rewritten each time.
final class ProgressThrottle: @unchecked Sendable {
    private let lock = NSLock()
    private var last = Date.distantPast
    func shouldReport() -> Bool {
        lock.withLock {
            let now = Date()
            guard now.timeIntervalSince(last) >= 0.4 else { return false }
            last = now
            return true
        }
    }
}
