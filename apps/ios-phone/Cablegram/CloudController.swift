import CablegramCore
import Foundation
import Observation
import UIKit

enum CloudSheet { case none, setup, confirm, oversize, manage, connectR2, freeUp, freeUpConfirm }

struct PhoneStorageStats { var total: Int64; var free: Int64; var media: Int64
    var used: Int64 { max(total - free, 0) }
}

/// The Save to Cloud and Storage state of Android's `PhoneViewModel` (cloud sheets, connect flows, saves, free up space).
@MainActor @Observable
final class CloudController {
    private(set) var storage: StorageStatusResponse?
    var cloudSheet: CloudSheet = .none
    private(set) var saveTargetId: String?
    private(set) var freeUpTargetId: String?
    var removeCloudCopyTarget: LibraryItem?
    var transferCardDismissed = false
    private(set) var r2ConnectError: String?
    private(set) var googleConnectError: String?
    var googleSignInNoteOpen = false
    private(set) var working = false
    private(set) var phoneStorage = PhoneStorageStats(total: 0, free: 0, media: 0)
    var wifiOnlyTransfers: Bool { didSet { pairing.wifiOnlyTransfers = wifiOnlyTransfers } }

    @ObservationIgnored private let pairing: PairingStore
    @ObservationIgnored private let store: LibraryStore
    @ObservationIgnored private let catalog: @Sendable () -> CatalogClient
    @ObservationIgnored private let manager: CloudTransferManager
    @ObservationIgnored private let webAuth = WebAuth()
    @ObservationIgnored private let items: () -> [LibraryItem]
    @ObservationIgnored private let setStatus: (String?) -> Void
    @ObservationIgnored private let showTab: (PhoneTab) -> Void
    @ObservationIgnored private let refresh: () -> Void
    @ObservationIgnored private let syncLibrary: () async -> Void

    init(pairing: PairingStore, store: LibraryStore, catalog: @escaping @Sendable () -> CatalogClient, manager: CloudTransferManager,
         items: @escaping () -> [LibraryItem], setStatus: @escaping (String?) -> Void, showTab: @escaping (PhoneTab) -> Void,
         refresh: @escaping () -> Void, syncLibrary: @escaping () async -> Void) {
        self.pairing = pairing; self.store = store; self.catalog = catalog; self.manager = manager; self.items = items
        self.setStatus = setStatus; self.showTab = showTab; self.refresh = refresh; self.syncLibrary = syncLibrary
        wifiOnlyTransfers = pairing.wifiOnlyTransfers
        manager.onFinished = { [weak self] in self?.savesFinished() }
    }

    private var token: String? { pairing.accountToken.flatMap { $0.isEmpty ? nil : $0 } }
    private var yourOwnStorage: String { L("ui_your_own_storage_cc541c") }

    var cloudConnected: Bool { storage?.connection?.status == "active" }
    var saveTarget: LibraryItem? { saveTargetId.flatMap { id in items().first { $0.id == id } } }
    var freeUpTarget: LibraryItem? { freeUpTargetId.flatMap { id in items().first { $0.id == id } } }
    /// The title being saved now (the transfer card shows it).
    var activeSave: LibraryItem? { items().first { $0.transferStatus == transferSaving } }
    var saveDestinationName: String { saveDestination(storage, yourOwnStorage: yourOwnStorage) }
    var cloudAvailable: Int64 { cloudAvailableBytes(items(), unlimited: cloudConnected) }

    func text(_ t: LocalizedText) -> String { fillPlaceholders(L(t.key), t.args) }

    // MARK: Storage status

    func refreshStorage() {
        refreshPhoneStorage()
        guard let token else { return }
        Task { storage = await catalog().storageStatus(token: token) }
    }

    func refreshPhoneStorage() {
        let values = try? URL.homeDirectory.resourceValues(forKeys: [.volumeTotalCapacityKey, .volumeAvailableCapacityForImportantUsageKey])
        phoneStorage = PhoneStorageStats(total: Int64(values?.volumeTotalCapacity ?? 0), free: values?.volumeAvailableCapacityForImportantUsage ?? 0,
                                         media: libraryMediaBytes(items()))
    }

    // MARK: Connecting

    func beginConnectR2() {
        r2ConnectError = nil
        cloudSheet = .connectR2
        showTab(.storage)
    }

    /// Sends the owner's R2 account ID, bucket and keys once; the server verifies them with a real write.
    func connectR2Storage(accountId: String, bucket: String, accessKeyId: String, secret: String) {
        guard let token else { r2ConnectError = L("ui_pair_a_tv_first_so_cablegram_can_attach_storage_to_thi_cf1a07"); return }
        Task {
            working = true
            r2ConnectError = nil
            defer { working = false }
            let result = await catalog().connectR2(token: token, accountId: r2AccountIdFrom(accountId) ?? accountId.trimmingCharacters(in: .whitespaces).lowercased(),
                                                  bucket: bucket.trimmingCharacters(in: .whitespaces), accessKeyId: accessKeyId.trimmingCharacters(in: .whitespaces),
                                                  secret: secret.trimmingCharacters(in: .whitespaces))
            if let error = result.error {
                r2ConnectError = r2ConnectMessage(error)
            } else {
                setStatus(L("ui_connected_to_your_r2_storage_eb0a33"))
                // Back to the Storage page, which now shows the bucket; the guide does not stay on top.
                cloudSheet = .none
                showTab(.storage)
                refreshStorage()
            }
        }
    }

    /// Asks the server for Google's sign-in URL and opens it in the system browser sheet; the link back lands in `applyStorageReturn`.
    func connectGoogle() {
        guard let token else { googleConnectError = L("ui_pair_a_tv_first_so_cablegram_can_attach_storage_to_thi_cf1a07"); return }
        Task {
            working = true
            googleConnectError = nil
            let start = await catalog().connectGoogle(token: token)
            working = false
            guard let urlString = start.authorizeUrl, let url = URL(string: urlString) else {
                googleConnectError = text(googleStartMessage(start.error ?? "failed"))
                return
            }
            setStatus(L("ui_finish_signing_in_with_google_then_come_back_ecbd97"))
            if let callback = await webAuth.start(url: url) { applyStorageReturn(callback.absoluteString) }
        }
    }

    /// The Google sign-in ended: `cablegram://storage?provider=…&result=…`.
    func applyStorageReturn(_ uri: String) {
        guard let ret = parseStorageReturn(uri) else { return }
        showTab(.storage)
        cloudSheet = .manage
        // Any app or page can open this link, so "connected" is said only once the server confirms it.
        guard ret.result == "connected" else {
            let message = text(storageReturnMessage(ret, status: storage, yourOwnStorage: yourOwnStorage))
            setStatus(message)
            // A missed permission box is shown on the sheet itself, beside the button to try again.
            googleConnectError = signInNeedsAnotherTry(ret) ? message : nil
            refreshStorage()
            return
        }
        googleConnectError = nil
        guard let token else { return }
        Task {
            let confirmed = await catalog().storageStatus(token: token)
            storage = confirmed
            if confirmed?.connection?.provider == ret.provider, confirmed?.connection?.status == "active" {
                setStatus(text(storageReturnMessage(ret, status: confirmed, yourOwnStorage: yourOwnStorage)))
            }
        }
    }

    func disconnectStorage() {
        guard let token else { return }
        let provider = storage?.connection?.provider
        let named = storage
        Task {
            working = true
            defer { working = false }
            if await catalog().disconnectStorage(token: token) {
                setStatus(text(disconnectMessage(provider, status: named, yourOwnStorage: yourOwnStorage)))
                refreshStorage()
            } else {
                setStatus(L("ui_could_not_disconnect_storage_a23494"))
            }
        }
    }

    // MARK: Saving

    func beginSaveToCloud(_ item: LibraryItem) {
        saveTargetId = item.id
        transferCardDismissed = false
        // Saves go only to the household's own storage (CAB-38): without one, the sheet leads to connecting it.
        cloudSheet = cloudConnected ? .confirm : .setup
    }

    func openOwnCloudSetup() { cloudSheet = .manage; showTab(.storage) }

    func confirmSaveToCloud() {
        guard let item = saveTarget else { return }
        cloudSheet = .none
        enqueueSave(item)
    }

    func chooseAnotherStorage() { cloudSheet = .setup }

    func dismissCloudSheet() { cloudSheet = .none; freeUpTargetId = nil }

    func retrySave(_ item: LibraryItem) {
        store.updateItem(item.id) { var i = $0; i.transferStatus = transferIdle; i.webTransferJobId = nil; i.webTransferError = nil; return i }
        refresh()
        beginSaveToCloud(store.get(item.id) ?? item)
    }

    func enqueueSave(_ item: LibraryItem) {
        store.updateItem(item.id) { var i = $0; i.transferStatus = transferSaving; i.uploadBytes = 0; i.uploadTotal = item.fileSizeBytes; return i }
        refresh()
        manager.enqueue(item.id, destination: saveDestinationName)
    }

    /// Titles left "saving" when the app was killed carry on from what the server holds.
    func resumeSaves() { manager.resumeInterrupted(destination: saveDestinationName) }

    func runSaveInBackground() { transferCardDismissed = true }

    private func savesFinished() {
        refresh()
        refreshStorage()
        Task { await syncLibrary() }
    }

    // MARK: Removing copies

    func askRemoveCloudCopy(_ item: LibraryItem) { if canRemoveOwnCloudCopy(item) { removeCloudCopyTarget = item } }

    /// Deletes just the cloud copy; the video stays on the phone and the title keeps its other sources.
    func removeCloudCopy(_ item: LibraryItem) {
        removeCloudCopyTarget = nil
        guard let sourceId = item.ownCloudSourceId, let token else { return }
        let destination = saveDestinationName
        Task {
            working = true
            defer { working = false }
            let result = await catalog().removeOwnCloudSource(token: token, sourceId: sourceId)
            if result.error == nil || result.error == "not_found" {
                store.updateItem(item.id) { var i = $0; i.ownCloudCopy = false; i.ownCloudSourceId = nil; return i }
                refresh()
                refreshStorage()
            }
            setStatus(removeCopyMessage(result, destination: destination))
        }
    }

    func openFreeUp() { cloudSheet = .freeUp }

    func askFreeUp(_ item: LibraryItem) { freeUpTargetId = item.id; cloudSheet = .freeUpConfirm }

    func confirmFreeUp(_ item: LibraryItem) {
        removeLocalCopy(item)
        freeUpTargetId = nil
        cloudSheet = .none
    }

    func removeLocalCopy(_ item: LibraryItem) {
        guard canRemoveLocalCopy(item) else { setStatus(L("ui_keep_a_cloud_copy_before_removing_the_phone_file_ec15b7")); return }
        store.removeLocalCopy(item.id)
        // Only off the phone now: the phone file is gone, so this phone no longer serves it.
        store.updateItem(item.id) { var i = $0; i.sourceAvailable = false; return i }
        refresh()
        setStatus(item.ownCloudCopy ? L("ui_removed_the_phone_copy_stays_in_f4b19c", item.title, saveDestinationName) : L("ui_removed_the_phone_copy_stays_in_telegram_1677cc", item.title))
    }

    func keepOnPhone(_ item: LibraryItem) {
        if !item.copied { downloadFromCloud(item) } else { setStatus(L("ui_is_already_on_this_phone_417a80", item.title)) }
        cloudSheet = .none
        freeUpTargetId = nil
    }

    /// Brings a copy back to this phone from the household's own storage.
    func downloadFromCloud(_ item: LibraryItem) {
        guard let token else { return }
        Task {
            setStatus(L("ui_downloading_b81bc3", item.title))
            guard let read = await catalog().ownCloudReadUrl(token: token, originIdentity: item.id), let url = URL(string: read.url) else {
                setStatus(cloudDownloadMessage(404)); return
            }
            var request = URLRequest(url: url)
            for (name, value) in read.headers { request.setValue(value, forHTTPHeaderField: name) }
            do {
                let (temp, response) = try await URLSession.shared.download(for: request)
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                guard (200...299).contains(code) else { setStatus(cloudDownloadMessage(code)); return }
                let destination = store.videoFile(item)
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: temp, to: destination)
                let size = (try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init)
                store.updateItem(item.id) { var i = $0; i.copied = true; i.fileSizeBytes = size ?? i.fileSizeBytes; i.sourceAvailable = true
                    i.storageState = i.cloudObjectPresent ? storageBoth : storageLocal; return i }
                refresh()
                setStatus(L("ui_downloaded_3b7cff", item.title))
            } catch {
                setStatus(cloudDownloadMessage(0))
            }
        }
    }
}
