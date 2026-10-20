import CablegramCore
import Observation
import SwiftUI
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
    /// Telegram first-run notices and the like arrive with their phases; the library only needs the TV name for its header.
    private(set) var paired = false
    private(set) var tvName = "TV"

    @ObservationIgnored let store: LibraryStore
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
        store.onWrite = { [weak self] in Task { @MainActor in self?.refresh() } }
        refresh()
        if needsAccount { loadSignInOptions() } else { checkTerms() }
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
        if let id = selectedId, !items.contains(where: { $0.id == id }) { selectedId = nil }
    }

    var selected: LibraryItem? { selectedId.flatMap { id in items.first { $0.id == id } } }

    /// The series whose episodes are open, from the current items.
    var shownSeries: LibraryEntry? {
        guard let key = openSeriesKey else { return nil }
        return groupIntoEntries(items).first { $0.key == key && $0.episodeCount > 0 }
    }

    func openItem(_ item: LibraryItem) { selectedId = item.id; editingMetadata = false }
    func closeItem() { selectedId = nil; editingMetadata = false }
    func openSeries(_ entry: LibraryEntry) { openSeriesKey = entry.key }
    func closeSeries() { openSeriesKey = nil }

    /// Leaving for another tab must not leave a title page or a series page covering it.
    func selectTab(_ tab: PhoneTab) {
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
        var sync = LibrarySync(store: store, client: catalog(), token: token, phoneDeviceId: pairing.phoneDeviceId)
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
        case .tv: tab = .settings          // Connect a TV arrives with phase 5
        case .telegram: tab = .browse      // Telegram sheet arrives with phase 7
        case .drive: tab = .storage        // Cloud sheet arrives with phase 4
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
        // "Signed in. Scan the TV PIN to pair." returns with the pairing screen in phase 5.
        status = result.needsName ? L("ui_choose_an_account_name_to_continue_8eba6d") : nil
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
