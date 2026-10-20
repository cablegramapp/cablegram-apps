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

    // Delete account
    private(set) var deletingAccount = false
    private(set) var deletionCodeSentTo: String?
    private(set) var deletionCodeFailed = false

    @ObservationIgnored private(set) var signIn: SignInController!
    @ObservationIgnored private(set) var accountDeletion: AccountDeletionController!
    @ObservationIgnored private let google: GoogleSignInService

    init(config: AppConfiguration, pairing: PairingStore, google: GoogleSignInService = GoogleSignInService()) {
        self.config = config
        self.pairing = pairing
        self.google = google
        needsAccount = (pairing.accountToken ?? "").isEmpty
        householdName = pairing.displayName
        welcomeTourOpen = !pairing.welcomeTourSeen
        legacyRemote = pairing.legacyRemote
        castConnect = pairing.castConnect
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
        if needsAccount { loadSignInOptions() } else { checkTerms() }
    }

    @ObservationIgnored private(set) var catalog: @Sendable () -> CatalogClient = { CatalogClient() }

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
        await ensurePhoneDevice()
        // "Signed in. Scan the TV PIN to pair." returns with the pairing screen in phase 5.
        status = result.needsName ? L("ui_choose_an_account_name_to_continue_8eba6d") : nil
        checkTerms()
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
        clearSignedInState()
        deletingAccount = false
        deletionCodeSentTo = nil
        busy = false
        status = L("account_deleted_message")
    }
}

/// The welcome tour's pages, in order.
enum TourStep: String, CaseIterable { case welcome, tv, telegram, drive, subtitles, ready }
