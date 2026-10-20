import CablegramCore
import Foundation
import Observation

/// TVs this phone just sent the Telegram password to. Typing the password for a TV is the owner agreeing that it signs in, so its
/// sign-in that follows within two minutes is approved without a second "Let <TV> use your Telegram?". Only for the TV the server named
/// in that request, never by name; held in memory only.
@MainActor
enum RecentTvPasswords {
    static let window: TimeInterval = 120
    private static var sentAt: [String: Date] = [:]

    static func sent(_ tvDeviceId: String, now: Date = Date()) { sentAt[tvDeviceId] = now }

    static func covers(_ tvDeviceId: String, now: Date = Date()) -> Bool {
        guard let at = sentAt[tvDeviceId] else { return false }
        if (0..<window).contains(now.timeIntervalSince(at)) { return true }
        sentAt[tvDeviceId] = nil
        return false
    }

    /// Used up by the approval it allowed: a later sign-in of that TV asks again.
    static func used(_ tvDeviceId: String) { sentAt[tvDeviceId] = nil }
}

/// How long until the next look for TV sign-ins. Quick while Cablegram is in front (the password screen opens by itself then) and for two
/// minutes after a TV asked for something, since its next step follows within seconds; 5 s otherwise.
func tvSignInPollDelay(appVisible: Bool, sinceActivity: TimeInterval) -> TimeInterval {
    appVisible || sinceActivity < 120 ? 1.5 : 5
}

/// The server accepts Telegram error names only (`^[A-Z0-9_ ]{1,80}$`).
func telegramErrorName(_ raw: String) -> String {
    let cleaned = raw.uppercased().replacingOccurrences(of: "[^A-Z0-9_ ]", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
    return cleaned.isEmpty ? "UNKNOWN" : String(cleaned.prefix(80))
}

/// What the phone does for Telegram while the app runs, on the phone that linked it (Android `TelegramTvApprovalWatcher`, which runs in
/// the foreground service): signs paired TVs in to the household's Telegram with this phone's session, hands out the two-step password a
/// TV asked for, keeps the catalog in step with the library channel, and ends TV sessions that are due.
@MainActor @Observable
final class TelegramTvWork {
    /// TVs waiting for the owner's "Let <TV> use your Telegram?".
    private(set) var pendingLogins: [PendingTvLogin] = []
    /// A TV that Telegram asks for the two-step password: the owner types it on this phone.
    private(set) var passwordRequest: PendingTvPasswordRequest?

    @ObservationIgnored private let pairing: PairingStore
    @ObservationIgnored private let catalog: @Sendable () -> CatalogClient
    @ObservationIgnored private let appVisible: () -> Bool
    @ObservationIgnored private var approvalsTask: Task<Void, Never>?
    @ObservationIgnored private var trustTask: Task<Void, Never>?
    @ObservationIgnored private var libraryTask: Task<Void, Never>?
    @ObservationIgnored private var dismissedPasswords = Set<String>()
    /// Called after the library channel changed the catalog, so the screens refresh.
    @ObservationIgnored var onCatalogChanged: (@Sendable () -> Void)?

    init(pairing: PairingStore, catalog: @escaping @Sendable () -> CatalogClient, appVisible: @escaping () -> Bool) {
        self.pairing = pairing; self.catalog = catalog; self.appVisible = appVisible
    }

    private var token: String? { pairing.accountToken.flatMap { $0.isEmpty ? nil : $0 } }

    func start() {
        guard PhoneTelegram.wasLinked else { return }
        startLibrarySync()
        startTrustWork()
        guard approvalsTask == nil else { return }
        approvalsTask = Task { [weak self] in
            var asked = Set<String>()
            var twoStepReportedAt = Date.distantPast
            var activeAt = Date.distantPast
            while !Task.isCancelled {
                guard let self else { return }
                if let token, PhoneTelegram.wasLinked {
                    let client = catalog()
                    // First, and never behind an approval that waits for this phone's session: a TV that wants the password shows its
                    // prompt until the phone screen opens.
                    let passwords = await client.pendingTvPasswordRequests(token: token)
                    if let passwords {
                        let open = passwords.first { !dismissedPasswords.contains($0.requestId) }
                        if passwordRequest?.requestId != open?.requestId { passwordRequest = open }
                    }
                    if Date().timeIntervalSince(twoStepReportedAt) > 600, await reportTwoStep(token) { twoStepReportedAt = Date() }
                    let pending = await client.pendingTvLogins(token: token) ?? []
                    if !pending.isEmpty || !(passwords ?? []).isEmpty { activeAt = Date() }
                    var waiting: [PendingTvLogin] = []
                    for request in pending {
                        // A TV this phone just typed the password for was already allowed by that.
                        let justTyped = RecentTvPasswords.covers(request.tvDeviceId)
                        if PhoneTelegram.autoApproveTvs || justTyped {
                            if await Self.approve(client: client, token: token, request: request), justTyped { RecentTvPasswords.used(request.tvDeviceId) }
                        } else {
                            waiting.append(request)
                            asked.insert(request.tvDeviceId + request.loginLink)
                        }
                    }
                    if waiting != pendingLogins { pendingLogins = waiting }
                }
                try? await Task.sleep(nanoseconds: UInt64(tvSignInPollDelay(appVisible: appVisible(), sinceActivity: Date().timeIntervalSince(activeAt)) * 1_000_000_000))
            }
        }
    }

    func stop() {
        approvalsTask?.cancel(); approvalsTask = nil
        trustTask?.cancel(); trustTask = nil
        libraryTask?.cancel(); libraryTask = nil
        pendingLogins = []
        passwordRequest = nil
    }

    /// The owner closed the password screen without sending (the request stays open on the TV until it expires).
    func dismissPasswordRequest(_ requestId: String) {
        dismissedPasswords.insert(requestId)
        if passwordRequest?.requestId == requestId { passwordRequest = nil }
    }

    /// TVs collect the password before signing in when the account has one (no "incomplete login attempt").
    private func reportTwoStep(_ token: String) async -> Bool {
        let session = PhoneTelegram.session()
        guard case .ready = session.state, let twoStep = try? await session.api.passwordState().hasPassword else { return false }
        return await catalog().putTelegramTwoStep(token: token, twoStep: twoStep)
    }

    // MARK: Answers from the owner

    /// Allow approves the request the user was shown, never another one the server lists.
    func allow(_ request: PendingTvLogin) {
        guard let token else { return }
        pendingLogins.removeAll { $0.requestId == request.requestId }
        let client = catalog()
        Task {
            // The TV may have posted a fresher link since the card was drawn; approve the current one of the same request. Never another
            // request: the user allowed this one only.
            guard let current = await client.pendingTvLogins(token: token)?.first(where: { $0.requestId == request.requestId }) else { return }
            _ = await Self.approve(client: client, token: token, request: current)
        }
    }

    func deny(_ request: PendingTvLogin) {
        guard let token else { return }
        pendingLogins.removeAll { $0.requestId == request.requestId }
        let client = catalog()
        Task { _ = await client.postTvLoginResult(token: token, requestId: request.requestId, outcome: "denied") }
    }

    func cancelPassword(_ requestId: String) {
        guard let token else { return }
        dismissPasswordRequest(requestId)
        let client = catalog()
        Task { _ = await client.cancelTvPassword(token: token, requestId: requestId) }
    }

    // MARK: Approving and ending sessions

    /// Approves one TV login with the phone's session and reports the outcome (no secrets). False: not tried yet.
    static func approve(client: CatalogClient, token: String, request: PendingTvLogin) async -> Bool {
        let session = PhoneTelegram.session()
        guard await session.first(timeout: 20, where: { if case .ready = $0 { true } else { false } }) != nil else {
            return false   // stays pending; the TV keeps posting fresh links until the phone is ready
        }
        // Verified approval: only a Cablegram session is accepted, and it is identified exactly, so the phone can end that TV's session
        // later even when the TV is off, and never another one.
        let outcome = await session.approveTvLogin(link: request.loginLink, ownApiId: session.clientApiId)
        var error: String?
        if case let .failure(failure) = outcome { error = telegramErrorName(failure.name) }
        _ = await client.postTvLoginResult(token: token, requestId: request.requestId, outcome: error == nil ? "approved" : "failed", error: error)
        if case let .success(approved) = outcome, let identified = approved.session {
            _ = await client.putTvTelegramSession(token: token, tvDeviceId: request.tvDeviceId, telegramSessionId: identified.id)
        }
        return true
    }

    /// Ends every TV session the control plane lists as due (TV removed, signed out, expired, Telegram disconnected).
    static func endDueSessions(client: CatalogClient, token: String) async {
        guard let due = await client.dueTvTelegramSessions(token: token), !due.isEmpty else { return }
        let session = PhoneTelegram.session()
        guard await session.first(timeout: 20, where: { if case .ready = $0 { true } else { false } }) != nil else { return }
        for item in due {
            guard let id = Int64(item.telegramSessionId) else { continue }
            do {
                let live = try await session.api.activeSessions()
                if !live.contains(where: { $0.id == id }) {
                    _ = await client.reportTvSessionEnded(token: token, tvDeviceId: item.tvDeviceId, outcome: "already_gone")
                } else {
                    try await session.api.terminateSession(id)
                    _ = await client.reportTvSessionEnded(token: token, tvDeviceId: item.tvDeviceId, outcome: "terminated")
                }
            } catch let error as TgError {
                // Stays due and is retried on the next round.
                _ = await client.reportTvSessionEnded(token: token, tvDeviceId: item.tvDeviceId, outcome: "failed", error: telegramErrorName(error.name))
            } catch {}
        }
    }

    /// Every minute: end due TV sessions.
    private func startTrustWork() {
        guard trustTask == nil else { return }
        trustTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                if let token, PhoneTelegram.wasLinked { await Self.endDueSessions(client: catalog(), token: token) }
                try? await Task.sleep(nanoseconds: 60_000_000_000)
            }
        }
    }

    /// Keeps the household catalog in step with the library channel from the phone too, so a video shared into the channel appears without
    /// a TV being on. Titles the phone registers carry the caption or file name; a title matched on the phone is never overwritten by this.
    private func startLibrarySync() {
        guard libraryTask == nil else { return }
        libraryTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let token = self.token
                let client = catalog()
                let chatId = (token != nil && PhoneTelegram.wasLinked) ? await client.telegramLink(token: token!)?.chatId : nil
                guard PhoneTelegram.wasLinked, let chatId else { try? await Task.sleep(nanoseconds: 30_000_000_000); continue }
                let session = PhoneTelegram.session()
                guard await session.first(timeout: 30, where: { if case .ready = $0 { true } else { false } }) != nil else { try? await Task.sleep(nanoseconds: 30_000_000_000); continue }
                let pairing = self.pairing
                let changed = self.onCatalogChanged
                let sync = TelegramLibrarySync(
                    api: session.api, chatId: chatId,
                    register: { video in if let token = pairing.accountToken { try await client.registerTelegramVideo(token: token, video: video) } },
                    reconcile: { keys in if let token = pairing.accountToken { try? await client.reconcileTelegramVideos(token: token, present: keys) } },
                    onChanged: { changed?() },
                    hints: { found in if let token = pairing.accountToken { try await client.postTelegramHints(token: token, hints: found) } })
                _ = try? await sync.fullScan()
                await sync.watch().value
            }
        }
    }
}
