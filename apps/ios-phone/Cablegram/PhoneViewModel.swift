import CablegramCore
import AVFoundation
import Observation
import SwiftUI
import Photos
import UIKit

/// Port of `PhoneViewModel.kt`. It grows with the phases; for now it owns the account, the welcome tour and the
/// delete-account flow. State and action names follow the Android class.
@MainActor @Observable
final class PhoneViewModel {
    let config: AppConfiguration
    let pairing: PairingStore

    var tab: PhoneTab = .library
    var status: String?
    var busy = false

    private(set) var needsAccount: Bool
    private(set) var termsRequired = false
    /// Decided by the server: a blank local cache alone must not send a signed-in account back to the naming screen.
    private(set) var needsName = false
    private(set) var householdName: String
    private(set) var welcomeTourOpen: Bool
    private(set) var legacyRemote: Bool
    private(set) var castConnect: Bool

    // Library
    private(set) var items: [LibraryItem] = []
    private(set) var collections: [UserCollection] = []
    var searchQuery = ""
    private(set) var librarySyncState: LibrarySyncState = .idle
    private(set) var prepareStep: String?
    private(set) var prepareProgress: Double = 0
    private(set) var selectedId: String?
    private(set) var openSeriesKey: String?
    var editingMetadata = false
    var collectionsDialogOpen = false
    var collectionDraft = ""
    var deleteTarget: LibraryItem?
    var paired: Bool { tv.paired }
    var tvName: String { tv.tvName }

    // Import (Android Browse tab)
    private(set) var browseSource: BrowseSource = .none
    private(set) var browseEntries: [BrowseEntry] = []
    private(set) var browseCrumbs: [BrowseCrumb] = []
    private(set) var browseLoading = false
    var selectedBrowseIds: Set<String> = []
    private(set) var folders: [IndexedFolder] = []
    private(set) var recentlyAddedIds: [String] = []
    private(set) var pendingPrivacyItem: LibraryItem?
    private(set) var photosStatus: PHAuthorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    var importDestination: String { didSet { pairing.importDestination = importDestination } }
    var transferWhileCharging: Bool { didSet { pairing.transferWhileCharging = transferWhileCharging } }
    var syncLibraryToApi: Bool { didSet { pairing.syncLibraryToApi = syncLibraryToApi } }
    @ObservationIgnored private var pendingPrivacyItems: [LibraryItem] = []
    @ObservationIgnored private var browseFolderId: String?
    @ObservationIgnored let bookmarks: BookmarkStore
    @ObservationIgnored let store: LibraryStore
    private(set) var artworkEditor: ArtworkEditorController!
    private(set) var cloud: CloudController!
    private(set) var tv: TvController!
    @ObservationIgnored private var syncing = false

    // Delete account
    private(set) var deletingAccount = false
    private(set) var deletionCodeSentTo: String?
    private(set) var deletionCodeFailed = false

    @ObservationIgnored private(set) var signIn: SignInController!
    @ObservationIgnored private(set) var accountDeletion: AccountDeletionController!
    @ObservationIgnored private let google: GoogleSignInService

    init(config: AppConfiguration, pairing: PairingStore, google: GoogleSignInService = GoogleSignInService(),
         applyDebugSeed: ((LibraryStore) -> Void)? = nil) {
        self.config = config
        self.pairing = pairing
        self.google = google
        let libraryStore = LibraryStore(root: PhoneViewModel.libraryRoot())
        // Debug builds only: tests start from a seeded account and library (see DebugAccountSeed).
        applyDebugSeed?(libraryStore)
        needsAccount = (pairing.accountToken ?? "").isEmpty
        householdName = pairing.displayName
        welcomeTourOpen = !pairing.welcomeTourSeen
        legacyRemote = pairing.legacyRemote
        castConnect = pairing.castConnect
        store = libraryStore
        bookmarks = BookmarkStore(root: PhoneViewModel.libraryRoot().deletingLastPathComponent())
        importDestination = pairing.importDestination
        transferWhileCharging = pairing.transferWhileCharging
        syncLibraryToApi = pairing.syncLibraryToApi
        let makeClient: @Sendable () -> CatalogClient = { [pairing] in
            CatalogClient(baseURL: URL(string: pairing.apiBaseUrl) ?? AppConfiguration.defaultApiBase, tokens: pairing)
        }
        signIn = SignInController(client: makeClient, signedIn: { [weak self] in await self?.completeSignIn($0) })
        accountDeletion = AccountDeletionController(
            token: { [weak self] in self?.pairing.accountToken },
            delete: { token, code in await makeClient().deleteAccount(token: token, code: code) },
            wipeLocal: { [weak self] in await self?.wipeAfterAccountDeletion() }
        )
        catalog = makeClient
        artworkEditor = ArtworkEditorController(store: libraryStore, media: AppArtworkMedia(store: libraryStore, sourceURL: { [bookmarks = self.bookmarks] item in item.sourceUri.flatMap(BookmarkStore.id(fromURI:)).flatMap { bookmarks.resolve($0)?.url } }), catalog: makeClient,
                                                token: { [pairing] in pairing.accountToken }) { [weak self] _ in
            self?.refresh()
            self?.status = L("ui_changes_saved_16bf14")
            Task { await self?.syncLibrary() }
        }
        let appSources = AppSources(store: libraryStore, bookmarks: bookmarks)
        let manager = CloudTransferManager(store: libraryStore, sources: appSources, catalog: makeClient, token: { [pairing] in pairing.accountToken },
                                           transport: BackgroundUploadTransport(allowsCellular: { [pairing] in !pairing.wifiOnlyTransfers }))
        cloud = CloudController(pairing: pairing, store: libraryStore, catalog: makeClient, manager: manager, items: { [weak self] in self?.items ?? [] },
                                setStatus: { [weak self] in self?.status = $0 }, showTab: { [weak self] in self?.tab = $0 },
                                refresh: { [weak self] in self?.refresh() }, syncLibrary: { [weak self] in await self?.syncLibrary() })
        tv = TvController(pairing: pairing, catalog: makeClient, store: libraryStore, items: { [weak self] in self?.items ?? [] },
                          setStatus: { [weak self] in self?.status = $0 }, selectItem: { [weak self] in self?.openItem($0) },
                          ensurePhoneDevice: { [weak self] in await self?.ensurePhoneDevice() }, syncLibrary: { [weak self] in await self?.syncLibrary() },
                          onPairedChanged: { _ in /* the LAN server starts and stops with the first and last TV in phase 6 */ })
        store.onWrite = { [weak self] in Task { @MainActor in self?.refresh() } }
        refresh()
        recoverSharedImports()
        importSharedInbox()
        cloud.refreshStorage()
        cloud.resumeSaves()
        if needsAccount { loadSignInOptions() } else { checkTerms(); Task { await tv.syncHouseholdTvs() } }
        startApprovalWatchIfSignedIn()
    }

    /// Everything TVs wait for appears as a card and a notification while the phone is signed in (no push: only while the app runs).
    func startApprovalWatchIfSignedIn() {
        guard !needsAccount else { return }
        tv.startApprovalWatch(notify: { Approvals.notify($0) }, cancelNotification: { Approvals.cancel($0) })
    }

    /// `Application Support/library`: not user-visible, backed up with the device, kept across updates.
    private static func libraryRoot() -> URL {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("library", isDirectory: true)
    }

    @ObservationIgnored private(set) var catalog: @Sendable () -> CatalogClient = { CatalogClient() }

    // MARK: Library

    func refresh() {
        items = store.list()
        collections = store.collections()
        folders = store.folders()
        if let id = selectedId, !items.contains(where: { $0.id == id }) { selectedId = nil }
    }

    var selected: LibraryItem? { selectedId.flatMap { id in items.first { $0.id == id } } }

    /// The series whose episodes are open, from the current items.
    var shownSeries: LibraryEntry? {
        guard let key = openSeriesKey else { return nil }
        return groupIntoEntries(items).first { $0.key == key && $0.episodeCount > 0 }
    }

    func findDetailsAndArtwork(_ item: LibraryItem) { artworkEditor.open(item) }

    func openItem(_ item: LibraryItem) { selectedId = item.id; editingMetadata = false }
    func closeItem() { selectedId = nil; editingMetadata = false }
    func openSeries(_ entry: LibraryEntry) { openSeriesKey = entry.key }
    func closeSeries() { openSeriesKey = nil }

    /// Leaving for another tab must not leave a title page or a series page covering it.
    private func startAddTvFromTour() { tv.startAddTv() }

    func selectTab(_ tab: PhoneTab) {
        // A cloud sheet, a title page and a series page are full-screen overlays: leaving for another tab must not leave them covering it.
        cloud.dismissCloudSheet()
        closeItem()
        closeSeries()
        self.tab = tab
    }

    func askDelete(_ item: LibraryItem) { deleteTarget = item }

    func removeFromLibrary(_ item: LibraryItem) {
        store.removeFromLibrary(item.id)
        deleteTarget = nil
        if selectedId == item.id { selectedId = nil }
        refresh()
    }

    func deleteFilePermanently(_ item: LibraryItem) {
        store.deleteFile(item.id)
        deleteTarget = nil
        if selectedId == item.id { selectedId = nil }
        refresh()
    }

    func toggleCollection(_ item: LibraryItem, _ collectionId: String) {
        let current = store.get(item.id)?.collectionIds ?? item.collectionIds
        store.setItemCollections(item.id, collectionIds: current.contains(collectionId) ? current.filter { $0 != collectionId } : current + [collectionId])
        refresh()
    }

    func createCollection() {
        let name = collectionDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        store.addCollection(name)
        collectionDraft = ""
        refresh()
    }

    func deleteCollection(_ id: String) {
        store.deleteCollection(id)
        refresh()
    }

    func saveMetadata(base: LibraryItem, title: String, year: Int?, mediaType: String, overview: String) {
        do {
            try store.editMetadataDraft(base: base, title: title, year: year, mediaType: mediaType, overview: overview)
            editingMetadata = false
            refresh()
            Task { await syncLibrary() }
        } catch {
            status = L("ui_something_went_wrong_try_again_in_a_moment_156a12")
        }
    }

    func syncNow() { Task { await syncLibrary(force: true) } }

    /// Flow 5: fingerprint, reconcile, push each item with a checkpoint, send pending edits, merge the household catalog.
    func syncLibrary(force: Bool = false) async {
        guard !syncing, let token = pairing.accountToken, !token.isEmpty else { return }
        syncing = true
        librarySyncState = .running
        defer { syncing = false; prepareStep = nil; prepareProgress = 0 }
        await ensurePhoneDevice()
        var sync = LibrarySync(store: store, client: catalog(), token: token, phoneDeviceId: pairing.phoneDeviceId, sources: AppSources(store: store, bookmarks: bookmarks))
        sync.progress = { [weak self] title, fraction in Task { @MainActor in self?.prepareStep = title.map { L("ui_syncing_6c2de6", $0) }; self?.prepareProgress = fraction } }
        do {
            try await sync.run()
            refresh()
            librarySyncState = .completed
        } catch {
            refresh()
            librarySyncState = .failed
        }
    }

    /// CAB-29: "Save artwork to household", after the person confirmed the explanation.
    func saveArtworkToHousehold(_ item: LibraryItem) {
        guard let current = store.get(item.id), canSaveArtworkToHousehold(current) else { return }
        store.updateItem(item.id) { var i = $0; i.householdArtworkVersion = i.posterVersion; return i }
        refresh()
        applyHouseholdArtwork(item.id, done: L("ui_cover_saved_to_your_household_762369"), pending: L("ui_the_cover_will_be_saved_to_your_household_on_the_next__87cda3"))
    }

    func keepArtworkOnPhone(_ item: LibraryItem) {
        store.updateItem(item.id) { var i = $0; i.householdArtworkVersion = nil; return i }
        refresh()
        applyHouseholdArtwork(item.id, done: L("ui_cover_removed_from_your_household_a7304d"), pending: L("ui_the_cover_will_be_removed_from_your_household_on_the_n_135e1a"))
    }

    private func applyHouseholdArtwork(_ id: String, done: String, pending: String) {
        let token = pairing.accountToken
        Task {
            var ok = false
            if let token, store.get(id)?.catalogItemId != nil {
                ok = await LibrarySync(store: store, client: catalog(), token: token, phoneDeviceId: pairing.phoneDeviceId).syncHouseholdArtwork(id)
            }
            status = ok ? done : pending
            refresh()
        }
    }

    // MARK: Import (Browse)

    func ensureBrowseRoot() {
        browseSource = .roots
        browseEntries = []
        browseCrumbs = []
        browseFolderId = nil
        selectedBrowseIds = []
    }

    /// The Photos root: asks for access, lists the videos the app may see.
    func openPhotos() {
        Task {
            let access = await PhotoSource.requestAccess()
            photosStatus = access
            guard access == .authorized || access == .limited else {
                status = L("ios_photos_access_off")
                return
            }
            browseSource = .photos
            browseCrumbs = [BrowseCrumb(name: L("ios_photos"))]
            selectedBrowseIds = []
            browseLoading = true
            browseEntries = await Task.detached { PhotoSource.videoEntries() }.value
            browseLoading = false
        }
    }

    /// The person picked a folder in Files: remember it (as a security-scoped bookmark) and open it.
    func addBrowseFolder(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let id = try? bookmarks.save(url) else {
            status = L("ui_folder_access_could_not_be_saved_choose_the_folder_aga_68a028")
            return
        }
        store.rememberFolder(uri: BookmarkStore.uri(for: id), name: url.lastPathComponent)
        refresh()
        openBrowseFolder(IndexedFolder(uri: BookmarkStore.uri(for: id), name: url.lastPathComponent))
    }

    func openBrowseFolder(_ folder: IndexedFolder) {
        guard let id = BookmarkStore.id(fromURI: folder.uri) else { return }
        browseSource = .folder
        browseFolderId = id
        browseCrumbs = [BrowseCrumb(name: folder.name, path: "")]
        status = nil
        reloadBrowse()
    }

    func removeBrowseFolder(_ folder: IndexedFolder) {
        if let id = BookmarkStore.id(fromURI: folder.uri) { bookmarks.remove(id) }
        store.forgetFolder(folder.uri)
        refresh()
        status = L("ui_removed_from_selected_folders_cb882a", folder.name)
        if browseFolderId == BookmarkStore.id(fromURI: folder.uri) { ensureBrowseRoot() }
    }

    func openBrowseEntry(_ entry: BrowseEntry) {
        if entry.isDirectory {
            selectedBrowseIds = []
            browseCrumbs.append(BrowseCrumb(name: entry.name, path: entry.id))
            reloadBrowse()
        } else if selectedBrowseIds.contains(entry.id) {
            selectedBrowseIds.remove(entry.id)
        } else {
            selectedBrowseIds.insert(entry.id)
        }
    }

    func selectAllBrowseFiles() {
        let ids = Set(visibleBrowseEntries(browseEntries, videosOnly: true).filter { !$0.isDirectory }.map(\.id))
        selectedBrowseIds = !ids.isEmpty && selectedBrowseIds.isSuperset(of: ids) ? [] : ids
    }

    func browseUp() {
        selectedBrowseIds = []
        if browseSource == .photos || browseSource == .cloud || browseCrumbs.count <= 1 { ensureBrowseRoot(); return }
        browseCrumbs.removeLast()
        reloadBrowse()
    }

    private func reloadBrowse() {
        selectedBrowseIds = []
        guard browseSource == .folder, let folderId = browseFolderId else { return }
        let relative = browseCrumbs.last?.path ?? ""
        browseLoading = true
        let bookmarks = self.bookmarks
        Task {
            let entries = await Task.detached { () -> [BrowseEntry]? in
                bookmarks.withAccess(folderId) { root -> [BrowseEntry] in
                    let directory = relative.isEmpty ? root : root.appendingPathComponent(relative)
                    let keys: [URLResourceKey] = [.isDirectoryKey, .fileSizeKey, .contentTypeKey]
                    let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles])) ?? []
                    return urls.map { url in
                        let values = try? url.resourceValues(forKeys: Set(keys))
                        let isDir = values?.isDirectory == true
                        let mime = values?.contentType?.preferredMIMEType ?? ""
                        let relativePath = (relative.isEmpty ? "" : relative + "/") + url.lastPathComponent
                        return BrowseEntry(id: relativePath, name: url.lastPathComponent, isDirectory: isDir,
                                           isVideo: !isDir && isVideoFile(name: url.lastPathComponent, mime: mime),
                                           sizeBytes: values?.fileSize.map(Int64.init), mime: mime)
                    }
                }
            }.value
            browseLoading = false
            if let entries { browseEntries = entries } else {
                status = L("ui_folder_access_is_unavailable_choose_the_folder_again_2e4497")
                browseEntries = []
            }
        }
    }

    /// Files picked one by one in Files (not through a folder).
    func addPickedFiles(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        Task {
            var refused = 0
            var entries: [BrowseEntry] = []
            for url in urls {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                guard let id = try? bookmarks.save(url) else { refused += 1; continue }
                let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init)
                entries.append(BrowseEntry(id: BookmarkStore.uri(for: id), name: url.lastPathComponent, isDirectory: false,
                                           isVideo: looksLikeVideo(url.lastPathComponent), sizeBytes: size, uri: BookmarkStore.uri(for: id)))
            }
            await addBrowseFileEntries(entries.filter(\.isVideo))
            if refused > 0 { status = L("ui_couldn_t_be_added_android_limits_how_many_f88239", refused, L("ui_video_d534be")) }
        }
    }

    func addSelectedBrowseFiles() {
        let chosen = visibleBrowseEntries(browseEntries, videosOnly: true).filter { selectedBrowseIds.contains($0.id) && !$0.isDirectory }
        guard !chosen.isEmpty else { return }
        selectedBrowseIds = []
        let source = browseSource, folderId = browseFolderId
        Task {
            var entries: [BrowseEntry] = []
            for entry in chosen {
                if source == .folder, let folderId {
                    // A bookmark per file keeps working when the folder bookmark is removed later.
                    let id: String? = bookmarks.withAccess(folderId) { root in try? bookmarks.save(root.appendingPathComponent(entry.id)) } ?? nil
                    if let id { var e = entry; e.uri = BookmarkStore.uri(for: id); entries.append(e) }
                } else {
                    entries.append(entry)
                }
            }
            await addBrowseFileEntries(entries)
        }
    }

    private func addBrowseFileEntries(_ files: [BrowseEntry]) async {
        guard !files.isEmpty else { status = L("ui_pick_video_files_to_add_95b421"); return }
        var imported: [LibraryItem] = []
        for (index, entry) in files.enumerated() {
            prepareProgress = Double(index + 1) / Double(files.count)
            prepareStep = L("ui_adding_of_files_2dd9e6", index + 1, files.count)
            guard let uri = entry.uri, let created = store.indexExternal(uri: uri, displayName: entry.name, sizeBytes: entry.sizeBytes, durationSeconds: entry.durationSeconds) else { continue }
            imported.append(created)
            // An offline cover for indexed sources too; a failure here is not fatal.
            let jpeg: Data?
            if let asset = PhotoSource.asset(uri) { jpeg = await PhotoSource.posterJPEG(asset) }
            else if let id = BookmarkStore.id(fromURI: uri), let url = bookmarks.resolve(id)?.url { jpeg = await withScopedAccess(url) { await fileFrameJPEG(url) } }
            else { jpeg = nil }
            if let jpeg { store.setPosterFromFrame(created.id, jpeg: jpeg) }
            if created.durationSeconds == nil, let id = BookmarkStore.id(fromURI: uri), let url = bookmarks.resolve(id)?.url {
                let seconds = await withScopedAccess(url) { try? await AVURLAsset(url: url).load(.duration).seconds }
                if let seconds, seconds.isFinite { store.updateItem(created.id) { var i = $0; i.durationSeconds = Int(seconds); return i } }
            }
            refresh()
        }
        prepareStep = nil
        prepareProgress = 0
        status = imported.isEmpty ? L("ui_those_files_are_already_in_your_library_9b2db9") : L("ui_added_files_fb53a9", imported.count)
        recentlyAddedIds = imported.map(\.id)
        selectTab(.library)
        pendingPrivacyItems.append(contentsOf: imported.compactMap { store.get($0.id) })
        showNextPrivacyPrompt()
        Task { await syncLibrary() }
    }

    /// Videos shared into Cablegram from other apps (Share Extension) are copied into the library here, when the app is open.
    func importSharedInbox() {
        let pending = SharedInbox.pending()
        guard !pending.isEmpty, !busy else { return }
        Task {
            busy = true
            defer { busy = false }
            var imported: [LibraryItem] = []
            let store = self.store
            for share in pending {
                status = L("ui_copying_26b256", share.entry.displayName)
                let item = await Task.detached { try? store.importFile(from: share.url, displayName: share.entry.displayName) { url in
                    // The probe runs off the main thread on a file that is already local, so reading the duration directly is fine.
                    let seconds = AVURLAsset(url: url).duration.seconds
                    return LibraryStore.ImportProbe(durationSeconds: seconds.isFinite ? Int(seconds) : nil)
                } }.value
                SharedInbox.remove(share)
                if let item {
                    if let jpeg = await fileFrameJPEG(store.videoFile(item)) { store.setPosterFromFrame(item.id, jpeg: jpeg) }
                    imported.append(store.get(item.id) ?? item)
                }
            }
            refresh()
            if imported.isEmpty { status = L("ui_could_not_import_that_file_3e92a3"); return }
            recentlyAddedIds = imported.map(\.id)
            status = L(imported.count == 1 ? "ui_video_added_08c7ee" : "ui_videos_added_3d5b7a")
            selectTab(.library)
            pendingPrivacyItems.append(contentsOf: imported)
            showNextPrivacyPrompt()
            Task { await syncLibrary() }
        }
    }

    func dismissAddedVideos() { recentlyAddedIds = [] }

    private func showNextPrivacyPrompt() {
        pendingPrivacyItem = pendingPrivacyItems.isEmpty ? nil : pendingPrivacyItems.removeFirst()
        if pendingPrivacyItem != nil { status = L("ui_should_this_require_phone_approval_92bae5") }
    }

    func choosePrivacy(_ isPrivate: Bool) {
        guard var item = pendingPrivacyItem else { return }
        pendingPrivacyItem = nil
        item.isPrivate = isPrivate
        store.update(item)
        refresh()
        Task { await syncLibrary() }
        showNextPrivacyPrompt()
        if pendingPrivacyItem == nil { status = L("ui_videos_added_3d5b7a") }
    }

    /// Cleans up after a process death mid-import, then finishes any shared import it left unfinished.
    func recoverSharedImports() {
        Task {
            let store = self.store
            let results = await Task.detached { store.sweepVideos(); return store.resumeSharedImports() }.value
            guard !results.isEmpty else { return }
            refresh()
            let dropped = results.compactMap { if case .dropped(let name) = $0 { name } else { nil } }
            status = dropped.isEmpty
                ? L("ui_finished_importing_6d8eef", results.compactMap { if case .imported(let i) = $0 { i.title } else { nil } }.joined(separator: ", "))
                : L("ui_could_not_finish_importing_the_file_is_no_longer_avail_63e50e", dropped.joined(separator: ", "))
        }
    }

    // MARK: Preferences

    func updateCastConnect(_ value: Bool) {
        pairing.castConnect = value
        castConnect = value
    }

    func updateLegacyRemote(_ value: Bool) {
        pairing.legacyRemote = value
        legacyRemote = value
        if !value && tab == .remote { tab = .library }
    }

    // MARK: Welcome tour

    func closeWelcomeTour() {
        pairing.welcomeTourSeen = true
        welcomeTourOpen = false
    }

    func replayWelcomeTour() { welcomeTourOpen = true }

    /// A tour page's setup button: leave the tour and land on the screen that does the setup.
    func startFromTour(_ step: TourStep) {
        closeWelcomeTour()
        switch step {
        case .tv: startAddTvFromTour()
        case .telegram: tab = .browse      // Telegram sheet arrives with phase 7
        case .drive: tab = .storage; cloud.cloudSheet = .manage
        case .subtitles: tab = .settings   // Subtitle accounts arrive with phase 8
        case .welcome, .ready: tab = .browse
        }
    }

    // MARK: Sign-in

    func loadSignInOptions() {
        Task { await signIn.load() }
    }

    func requestSignInCode(_ email: String) { Task { await signIn.requestCode(email: email) } }
    func resendSignInCode() { Task { await signIn.resendCode() } }
    func verifySignInCode(_ code: String) { Task { await signIn.verifyCode(code) } }
    func createAccount(_ name: String) { Task { await signIn.createAccount(name: name) } }

    func signInWithGoogle() {
        guard let clientId = signIn.methods?.google?.clientId else { return }
        Task {
            switch await google.idToken(serverClientId: clientId) {
            case .token(let token, let nonce): await signIn.google(idToken: token, nonce: nonce)
            case .failed(let key): signIn.message = StatusMessage(key)
            case .cancelled: break
            }
        }
    }

    /// The server returned an account (Google, an email code, or Create your account).
    private func completeSignIn(_ result: PhonePairResponse) async {
        pairing.accountToken = result.token
        pairing.refreshToken = result.refreshToken
        householdName = result.displayName ?? ""
        pairing.displayName = householdName
        needsAccount = false
        needsName = result.needsName
        // Another account's library is set aside (and restored if it signs in again), never synced into this one.
        let setAside = result.householdId.map { store.bindHousehold($0) } ?? false
        refresh()
        await ensurePhoneDevice()
        if tv.paired {
            // Signing in again after an expired session: the TVs stay paired.
            status = result.needsName ? L("ui_choose_an_account_name_to_continue_8eba6d") : L("ui_signed_in_again_00397a")
            tv.addingTv = false
        } else {
            status = result.needsName ? L("ui_choose_an_account_name_to_continue_8eba6d") : L("ui_signed_in_scan_the_tv_pin_to_pair_14c21b")
            // A TV scanned before signing in goes straight to "Is this your TV?" (CAB-62); otherwise pairing comes next.
            tv.addingTv = !result.needsName && tv.pendingPair == nil
        }
        await tv.syncHouseholdTvs()
        if setAside { status = L("ui_signed_in_the_previous_account_s_library_is_kept_aside_45bf31") }
        checkTerms()
        Task { await syncLibrary() }
    }

    /// A stable per-install id; the server stores only a keyed hash, used to keep the free relay allowance per device.
    private var hardwareId: String? { UIDevice.current.identifierForVendor?.uuidString }

    private func ensurePhoneDevice() async {
        guard let token = pairing.accountToken else { return }
        if let known = pairing.phoneDeviceId, !known.isEmpty {
            // The id may belong to another account or have been revoked: re-register only when the server says so.
            if await catalog().phoneDeviceStatus(token: token, deviceId: known) != .missing { return }
            pairing.phoneDeviceId = nil
        }
        if let device = await catalog().registerPhoneDevice(token: token, hardwareId: hardwareId) {
            pairing.phoneDeviceId = device.deviceId
        }
    }

    // MARK: Terms (spec 007 US4)

    func acceptTerms() {
        guard let token = pairing.accountToken, !busy else { return }
        Task {
            busy = true
            defer { busy = false }
            guard let docs = await catalog().legalDocuments() else {
                status = L("ui_can_t_reach_cablegram_check_your_connection_0f7f38")
                return
            }
            if let failed = await catalog().acceptTerms(token: token, legal: docs) {
                status = L(failed.messageKey)
                if failed.error == "terms_outdated" { await signIn.load() }
            } else {
                termsRequired = false
                status = nil
            }
        }
    }

    /// New Terms are checked on start and after each sign-in; offline (nil) never blocks the app.
    private func checkTerms() {
        guard let token = pairing.accountToken else { return }
        Task {
            if await catalog().termsAcceptanceRequired(token: token) == true {
                await signIn.load()
                termsRequired = true
            }
        }
    }

    // MARK: Account

    func saveHouseholdName(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        guard let token = pairing.accountToken, !token.isEmpty else {
            status = L("ui_sign_in_first_then_name_the_household_57a8fb")
            return
        }
        Task {
            busy = true
            status = L("ui_saving_23e392")
            defer { busy = false }
            guard await catalog().setDisplayName(token: token, name: trimmed) else {
                status = L("ui_could_not_save_that_name_check_the_api_server_in_setti_1d5712")
                return
            }
            pairing.displayName = trimmed
            householdName = trimmed
            needsName = false
            status = nil
        }
    }

    func signOut() {
        guard !busy else { return }
        Task {
            busy = true
            let client = catalog()
            let token = pairing.accountToken
            let phoneId = pairing.phoneDeviceId
            // End the session and unregister this phone on the server; best effort offline.
            if let token, !token.isEmpty, let phoneId, !phoneId.isEmpty { await client.revokeDevice(id: phoneId, token: token) }
            if let refresh = pairing.refreshToken { await client.logout(refreshToken: refresh) }
            clearSignedInState()
            busy = false
            status = L("ui_signed_out_your_videos_stay_on_this_phone_73ffd6")
        }
    }

    private func clearSignedInState() {
        pairing.clearAccount()
        tv.reset()
        items = []
        selectedId = nil
        openSeriesKey = nil
        householdName = ""
        tab = .library
        needsName = false
        needsAccount = true
        termsRequired = false
        signIn.reset()
        loadSignInOptions()
    }

    // MARK: Delete account

    func openDeleteAccount() {
        accountDeletion.reset()
        deletionCodeSentTo = nil
        deletionCodeFailed = false
        deletingAccount = true
    }

    /// Mails a sign-in code to the account's address; it confirms the deletion (spec 007 FR-015).
    func requestDeletionCode() {
        guard let token = pairing.accountToken, !busy else { return }
        Task {
            busy = true
            defer { busy = false }
            let email = await catalog().accountEmail(token: token)
            let failed: SignInFailure?
            if let email { failed = await catalog().requestSignInCode(email: email, forDeletion: true) } else { failed = nil }
            deletionCodeFailed = email == nil || failed != nil
            if !deletionCodeFailed { deletionCodeSentTo = email }
        }
    }

    func closeDeleteAccount() {
        if accountDeletion.running { return }
        accountDeletion.reset()
        deletingAccount = false
        deletionCodeSentTo = nil
    }

    func deleteAccount(_ code: String) {
        Task { _ = await accountDeletion.submit(code) }
    }

    /// The server deleted the account: sign out completely and forget everything tied to it on this phone.
    private func wipeAfterAccountDeletion() async {
        store.forgetHousehold()
        clearSignedInState()
        refresh()
        deletingAccount = false
        deletionCodeSentTo = nil
        busy = false
        status = L("account_deleted_message")
    }
}

/// The welcome tour's pages, in order.
enum TourStep: String, CaseIterable { case welcome, tv, telegram, drive, subtitles, ready }
