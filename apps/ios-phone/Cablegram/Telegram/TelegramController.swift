import CablegramCore
import Foundation
import Observation

/// Whether the link works from this phone. `notHere`: the household is linked (from another phone, or before a reinstall) but this
/// phone has no Telegram session.
enum TelegramHealth { case unknown, ok, channelLost, signedOut, notHere }

/// The Telegram parts of Android's `PhoneViewModel`: connect once on the phone, then videos shared into the private "Cablegram library"
/// channel appear in Cablegram (spec 004 US1).
@MainActor @Observable
final class TelegramController {
    /// The household's Telegram link as the control plane knows it (nil until loaded).
    private(set) var link: TelegramLinkInfo?
    var sheetOpen = false
    /// Shown in the sheet when linking fails after Telegram signed in (e.g. another account is linked).
    var message: String?
    /// True while the library channel is being opened and the link saved.
    private(set) var finishing = false
    private(set) var health: TelegramHealth = .unknown
    /// Whether Cablegram just created the channel (nil: unknown).
    private(set) var channelCreated: Bool?
    /// Continue was chosen on "Before you connect" in this app run (or Telegram was already running).
    var started = false
    private(set) var communityChannels: CommunityChannels?
    private(set) var libraryBots: LibraryBotRecommendations?
    private(set) var botHubOnly = false
    private(set) var folderBusy = false
    private(set) var folderMessage: String?
    /// True while this sign-in waits for a Home TV to approve it.
    private(set) var viaTv = false

    @ObservationIgnored let pairing: PairingStore
    @ObservationIgnored private let catalog: @Sendable () -> CatalogClient
    @ObservationIgnored let statusSetter: (String?) -> Void
    var setStatus: (String?) -> Void { statusSetter }
    @ObservationIgnored let store: LibraryStore
    @ObservationIgnored let media: AppMediaProvider
    @ObservationIgnored let refresh: () -> Void
    @ObservationIgnored let didRemove: (LibraryItem) -> Void
    @ObservationIgnored let syncLibrary: () async -> Void
    /// The phone's Telegram work for TVs: sign-ins to approve, the password a TV asked for, the channel sync, ending due sessions.
    let work: TelegramTvWork
    /// Titles hidden from the library, for Import → Telegram → Hidden videos; nil until opened.
    var hiddenTitles: [TelegramTombstone]?
    var showHidden = false
    @ObservationIgnored let deletions: TelegramDeletionQueue
    @ObservationIgnored var deletionsRunning = false
    @ObservationIgnored var saves = Set<String>()
    @ObservationIgnored private var finishJob: Task<Void, Never>?
    @ObservationIgnored private var folderJob: Task<Void, Never>?
    @ObservationIgnored private var botsJob: Task<Void, Never>?
    @ObservationIgnored private var communitySaveJob: Task<Void, Never>?
    @ObservationIgnored private var communityLibraryId: Int64?
    @ObservationIgnored private var communityUserId: Int64?
    @ObservationIgnored private var communitySession: TelegramSession?
    @ObservationIgnored private var healthJob: Task<Void, Never>?

    init(pairing: PairingStore, catalog: @escaping @Sendable () -> CatalogClient, store: LibraryStore, media: AppMediaProvider, root: URL, appVisible: @escaping () -> Bool,
         setStatus: @escaping (String?) -> Void, refresh: @escaping () -> Void, didRemove: @escaping (LibraryItem) -> Void, syncLibrary: @escaping () async -> Void) {
        self.pairing = pairing; self.catalog = catalog; self.statusSetter = setStatus; self.store = store; self.media = media; self.refresh = refresh
        self.didRemove = didRemove; self.syncLibrary = syncLibrary
        work = TelegramTvWork(pairing: pairing, catalog: catalog, appVisible: appVisible)
        deletions = TelegramDeletionQueue(file: root.appendingPathComponent("telegram-deletions.json"))
        PhoneTelegram.configure(pairing: pairing, catalog: catalog)
        work.onCatalogChanged = { [weak self] in Task { @MainActor in self?.refresh() } }
        onLinked = { [weak self] in self?.work.start() }
        onUnlinked = { [weak self] in self?.work.stop() }
    }

    /// At launch and after signing in: a phone that linked Telegram resumes serving TVs and syncing the channel.
    func resume() {
        guard token != nil, PhoneTelegram.wasLinked else { return }
        work.start()
        loadLink()
        processDeletions()
    }

    private var token: String? { pairing.accountToken.flatMap { $0.isEmpty ? nil : $0 } }
    func catalogClient() -> CatalogClient { catalog() }
    func text(_ key: String, _ args: CVarArg...) -> String { fillPlaceholders(L(key), args) }

    /// This household is linked to Telegram AND this phone holds a session of its own (so it can read and save to it).
    var signedInHere: Bool { link?.linked == true && PhoneTelegram.wasLinked }
    /// Linked, but not from this phone: not connected here.
    var notHere: Bool { link?.linked == true && (health == .notHere || !PhoneTelegram.wasLinked) }
    /// The Telegram TV account name or a placeholder for "Connected as …".
    var accountName: String { link?.displayName ?? L("ui_your_account_17a7e5") }

    /// A Home TV that can approve this phone's Telegram login: paired, known to the server, and not temporary.
    var homeTv: PairedTv? { pairing.tvs.last { $0.deviceId != nil && !$0.trust.temporary } }

    // MARK: Link and health

    func loadLink() {
        guard let token else { return }
        Task {
            link = await catalog().telegramLink(token: token) ?? link
            checkHealth()
        }
    }

    /// Is the link still usable from this phone? The owner may have left the channel, deleted it, or ended Cablegram's session in
    /// Telegram → Devices. Rechecked each time Import is opened.
    func checkHealth() {
        guard link?.linked == true else { return }
        guard PhoneTelegram.wasLinked else {
            // Linked from another phone: this one has no session to check with, so it is not connected here. Showing "Connected" and
            // opening the channel would send the owner to whatever Telegram account the Telegram app has.
            health = .notHere
            return
        }
        healthJob?.cancel()
        healthJob = Task {
            // Keep the session warm: it also approves TV logins.
            let session = PhoneTelegram.session()
            guard let settled = await session.first(timeout: 30, where: { state in
                switch state { case .ready, .needsPhoneNumber, .signedOut, .failed: true; default: false }
            }) else { return }
            guard case .ready = settled else { health = .signedOut; return }
            guard let chatId = link?.chatId, await session.isLibraryAvailable(chatId) else { health = .channelLost; return }
            // A channel linked before the logo existed gets it if it has no photo.
            _ = try? await session.openLibrary(knownChatId: chatId, allowCreate: false, logoPath: PhoneTelegram.logoPath())
            health = .ok
        }
    }

    /// The owner left or deleted the channel: make a new "Cablegram library" and point the household at it.
    func recreateChannel() {
        guard let token, let session = PhoneTelegram.existing() else { return }
        Task {
            guard case let .ready(user) = session.state else { health = .signedOut; return }
            do {
                guard let library = try await session.openLibrary(knownChatId: nil, allowCreate: true, logoPath: PhoneTelegram.logoPath()) else {
                    throw TgError(code: 0, name: "Telegram didn't create the channel")
                }
                let result = try await catalog().putTelegramLink(token: token, telegramUserId: user.id, displayName: user.displayName, libraryChatId: library.chatId, phoneDeviceId: pairing.phoneDeviceId)
                if let updated = result.link { link = updated }
                channelCreated = library.created
                health = .ok
                organizeFolder()
                setStatus(L(library.created ? "ui_created_a_new_cablegram_library_channel_in_telegram_aef43c" : "ui_found_your_cablegram_library_channel_in_telegram_again_a824e9"))
            } catch {
                setStatus(text("ui_couldn_t_create_the_channel_9f0c99", (error as? TgError)?.name ?? L("ui_unknown_error_3e4443")))
            }
        }
    }

    /// Telegram ended this phone's session: sign in again without disconnecting the household.
    func signInAgain() {
        health = .unknown
        clearRecommendations()
        // The old session is dead; start clean, then finish like a first link (same account → same link).
        PhoneTelegram.forget()
        connect()
    }

    // MARK: Connecting

    /// A first connection needs consent; an existing session resumes its unfinished household link.
    func openSheet() {
        message = nil
        sheetOpen = true
        // TDLib outlives this controller's screens. After reopening the app, Ready alone does not mean the household link was saved.
        if PhoneTelegram.existing() != nil || PhoneTelegram.wasLinked { connect() }
    }

    /// The owner chose Continue on "Before you connect": start TDLib and the sign-in steps.
    func connect() {
        message = nil
        sheetOpen = true
        started = true
        if finishJob != nil, finishing { return }
        let session = PhoneTelegram.session()
        if link?.linked == true, health == .ok, PhoneTelegram.wasLinked {
            if let userId = link?.userId { prepareCommunityChannels(session, userId: userId) }
            return
        }
        finishJob?.cancel()
        finishJob = Task {
            // Once per attempt: the link PUT is idempotent for the same account and 409 for another.
            guard case let .ready(user)? = await session.first(timeout: 24 * 3600, where: { if case .ready = $0 { true } else { false } }) else { return }
            await finishLink(session, user: user)
        }
    }

    /// "Use the QR code on your TV instead": the phone asks Telegram for a QR login and hands its link to a Home TV that is already
    /// signed in, which approves it. Nothing but the login link goes through Cablegram, and only to a TV the owner controls.
    func connectViaTv() {
        guard let tv = homeTv, let tvId = tv.deviceId, let token else { return }
        guard let phoneId = pairing.phoneDeviceId else {
            sheetOpen = true
            message = L("ui_this_phone_isn_t_registered_with_your_household_yet_co_bd64f3")
            return
        }
        message = nil
        sheetOpen = true
        started = true
        viaTv = true
        let session = PhoneTelegram.session()
        finishJob?.cancel()
        finishJob = Task {
            // Never wait forever. A session already asking for a code, a password or failing can't switch to a QR login; the sheet
            // shows that step instead.
            let began = await session.first(timeout: 20, where: { if case .starting = $0 { false } else { true } })
            switch began {
            case .needsPhoneNumber?: session.startQrLogin()
            case .waitingForApproval?, .ready?: break
            default:
                viaTv = false
                if began == nil { message = L("ui_telegram_didn_t_start_check_your_connection_and_try_ag_f28228") }
                return
            }
            // TDLib replaces the login token about every 30 s; each new link goes to the TV.
            let client = catalog()
            let poster = Task { @MainActor in
                var last: String?
                while !Task.isCancelled {
                    if case let .waitingForApproval(link) = session.state, link != last { last = link; _ = await client.postPhoneLogin(token: token, loginLink: link, tvDeviceId: tvId, phoneDeviceId: phoneId) }
                    try? await Task.sleep(nanoseconds: 500_000_000)
                }
            }
            let progressed = await session.first(timeout: Self.phoneLoginWait, where: { switch $0 { case .ready, .needsPassword, .failed: true; default: false } })
            poster.cancel()
            guard let progressed else {
                viaTv = false
                message = text("ui_didn_t_answer_open_cablegram_on_the_tv_and_check_setti_5aa87a", tv.name) + L("ui_or_connect_with_your_phone_number_instead_216b05")
                return
            }
            viaTv = false
            let ready: TgUser?
            switch progressed {
            case let .ready(user): ready = user
            // Two-step verification: the sheet shows the password step; wait for the owner, not forever.
            case .needsPassword:
                if case let .ready(user)? = await session.first(timeout: 600, where: { if case .ready = $0 { true } else { false } }) { ready = user } else { ready = nil }
            default: ready = nil   // Failed: the sheet shows Telegram's message
            }
            guard let ready else { return }
            await finishLink(session, user: ready)
        }
    }

    static let phoneLoginWait: Double = 90

    /// Telegram is signed in: open or create the library channel and record the link (no secrets).
    private func finishLink(_ session: TelegramSession, user: TgUser) async {
        guard let token else { return }
        finishing = true
        defer { finishing = false }
        do {
            let client = catalog(), knownChat = link?.chatId, phoneId = pairing.phoneDeviceId
            let work = Task { @MainActor () throws -> (TgLibrary, TelegramLinkResult) in
                guard let library = try await session.openLibrary(knownChatId: knownChat, allowCreate: true, logoPath: PhoneTelegram.logoPath()) else {
                    throw TgError(code: 0, name: "Could not open the Cablegram library channel")
                }
                return (library, try await client.putTelegramLink(token: token, telegramUserId: user.id, displayName: user.displayName, libraryChatId: library.chatId, phoneDeviceId: phoneId))
            }
            let (library, result) = try await Self.withDeadline(seconds: 60, work)
            channelCreated = library.created
            if let conflict = result.conflictName {
                message = text("ui_this_household_already_uses_the_telegram_account_of_7d8c2e", conflict) + L("ui_disconnect_it_first_or_sign_in_with_that_account_693723")
                session.logOut()
                PhoneTelegram.forget()
            } else {
                link = result.link
                health = .ok
                PhoneTelegram.setLinked(true)
                prepareCommunityChannels(session, userId: user.id)
                organizeFolder()
                setStatus(L("ui_telegram_connected_videos_you_share_into_cablegram_lib_8c4c5d"))
                onLinked?()
            }
        } catch is TimeoutError {
            message = L("ui_setting_up_your_telegram_library_took_too_long_check_y_927028")
        } catch is CancellationError {
            return
        } catch {
            message = text("ui_signed_in_to_telegram_but_setting_up_the_library_faile_297f32", (error as? TgError)?.name ?? L("ui_unknown_error_3e4443"))
        }
    }

    /// Called when Telegram was linked from this phone (starts the background work that approves TVs and syncs the channel).
    @ObservationIgnored var onLinked: (() -> Void)?

    private static func withDeadline<T: Sendable>(seconds: Double, _ task: Task<T, Error>) async throws -> T {
        try await withTimeout(seconds: seconds) { try await task.value }
    }

    /// Closing half-way keeps the step: reopening continues where the user left off.
    func closeSheet() {
        botHubOnly = false
        sheetOpen = false
        message = nil
    }

    /// Cancel operations tied to the old Telegram session before replacing or signing it out.
    private func clearRecommendations() {
        folderJob?.cancel()
        folderMessage = nil
        folderBusy = false
        botHubOnly = false
        botsJob?.cancel()
        libraryBots = nil
        communityLibraryId = nil
        communitySaveJob?.cancel()
        communityChannels = nil
        communityUserId = nil
        communitySession = nil
    }

    /// Logs this phone out of Telegram and deletes the local TDLib database, files and key.
    func wipeLocally() {
        clearRecommendations()
        healthJob?.cancel()
        PhoneTelegram.existing()?.logOut()
        PhoneTelegram.forget()
        link = TelegramLinkInfo(linked: false)
        health = .unknown
        started = false
        onUnlinked?()
    }

    @ObservationIgnored var onUnlinked: (() -> Void)?

    func disconnect() {
        guard let token else { return }
        Task {
            let client = catalog()
            guard await client.deleteTelegramLink(token: token) else {
                setStatus(L("ui_couldn_t_disconnect_telegram_check_your_connection_and_7a7e1e"))
                return
            }
            // End every TV's Telegram session while this phone can still do it: signing the phone out first would leave TVs that are
            // off or unplugged signed in to the account.
            await TelegramTvWork.endDueSessions(client: client, token: token)
            let unended = await client.dueTvTelegramSessions(token: token)?.count ?? -1
            wipeLocally()
            setStatus(L(unended == 0 ? "ui_telegram_disconnected_your_tvs_are_signed_out_of_teleg_5f064d" : "ui_telegram_disconnected_some_tvs_may_still_be_signed_in__3b14d3"))
        }
    }

    // MARK: Community channels, bots and the folder

    private func prepareCommunityChannels(_ session: TelegramSession, userId: Int64) {
        guard let chatId = link?.chatId else { return }
        if communityUserId == userId, communityLibraryId == chatId, communitySession === session { return }
        botsJob?.cancel()
        communityLibraryId = chatId
        communitySaveJob?.cancel()
        communityUserId = userId
        communitySession = session
        libraryBots = PhoneTelegram.libraryBotsCompleted(userId: userId, chatId: chatId) ? nil : makeBotReview(session, userId: userId, chatId: chatId, reviewAll: false)
        communityChannels = PhoneTelegram.communityChannelsCompleted(userId: userId) ? nil : CommunityChannels(api: session.api) { PhoneTelegram.completeCommunityChannels(userId: userId) }
    }

    private func makeBotReview(_ session: TelegramSession, userId: Int64, chatId: Int64, reviewAll: Bool) -> LibraryBotRecommendations {
        LibraryBotRecommendations(api: session.api, libraryChatId: chatId, handledBotIds: reviewAll ? [] : PhoneTelegram.handledLibraryBots(userId: userId, chatId: chatId),
                                  onDecision: { PhoneTelegram.recordLibraryBotDecision(userId: userId, chatId: chatId, botId: $0) },
                                  onCompleted: { PhoneTelegram.completeLibraryBots(userId: userId, chatId: chatId) })
    }

    /// Explicit checks always fetch current recommendations and let users reconsider skipped bots.
    func checkBotHub() {
        guard let link = link, link.linked, let userId = link.userId, let chatId = link.chatId, health == .ok, PhoneTelegram.wasLinked else { return }
        let session = PhoneTelegram.session()
        guard case let .ready(user) = session.state, user.id == userId else { openSheet(); return }
        let previous = botsJob
        previous?.cancel()
        botsJob = Task {
            // Finish cancellation before discovery replaces the API's verified bot ids.
            await previous?.value
            guard self.link?.userId == userId, self.link?.chatId == chatId, PhoneTelegram.existing() === session else { return }
            libraryBots = makeBotReview(session, userId: userId, chatId: chatId, reviewAll: true)
            botHubOnly = true
            message = nil
            sheetOpen = true
        }
    }

    func saveCommunityChannels() {
        guard let channels = communityChannels, !communitySaving else { return }
        communitySaving = true
        communitySaveJob = Task {
            await channels.save()
            organizeFolder()
            communitySaving = false
        }
    }

    @ObservationIgnored private var communitySaving = false

    func loadBots() {
        guard let bots = libraryBots, !botsRunning else { return }
        botsRunning = true
        botsJob = Task { await bots.load(); botsRunning = false }
    }

    func addBot() {
        guard let bots = libraryBots, !botsRunning else { return }
        botsRunning = true
        botsJob = Task {
            let before = bots.state.added
            await bots.addCurrent()
            if bots.state.added > before { organizeFolder() }
            botsRunning = false
        }
    }

    @ObservationIgnored private var botsRunning = false

    /// Folder failures are retryable and never undo successful channel or bot membership changes.
    func organizeFolder() {
        guard let link, link.linked, let chatId = link.chatId, let userId = link.userId, let session = PhoneTelegram.existing(),
              health == .ok, PhoneTelegram.wasLinked, case let .ready(user) = session.state, user.id == userId else { return }
        let previous = folderJob
        previous?.cancel()
        folderJob = Task {
            await previous?.value
            folderBusy = true
            folderMessage = nil
            defer { folderBusy = false }
            let handled = Array(PhoneTelegram.handledLibraryBots(userId: userId, chatId: chatId))
            let api = session.api
            do {
                let result = try await withTimeout(seconds: 30) { try await api.organizeCablegramFolder(libraryChatId: chatId, reviewedBotUserIds: handled) }
                folderMessage = result.unlistedBots.isEmpty ? L("ui_cablegram_folder_updated_in_telegram_acc08b")
                    : text("ui_cablegram_folder_updated_in_telegram_to_include_open_t_515a3b", result.unlistedBots.joined(separator: ", "))
            } catch is TimeoutError {
                folderMessage = L("ui_updating_the_telegram_folder_took_too_long_tap_organiz_34b802")
            } catch is CancellationError {
                return
            } catch {
                folderMessage = L("ui_couldn_t_update_the_telegram_folder_check_your_connect_15069c")
            }
        }
    }
}
