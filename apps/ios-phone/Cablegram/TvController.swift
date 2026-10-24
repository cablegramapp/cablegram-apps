import CablegramCore
import Foundation
import Observation
import UIKit

/// The TV side of Android's `PhoneViewModel`: pairing, the TVs of the account, "Play on TV", the remote, and approvals.
@MainActor @Observable
final class TvController {
    private(set) var tvs: [PairedTv]
    private(set) var paired: Bool
    private(set) var tvName: String
    private(set) var tvVolumes: [String: Double] = [:]
    var tvVolume: Double { tvVolumes[tvName] ?? 0.5 }
    var addingTv = false
    /// The code the owner just entered or scanned, waiting for "Is this your TV?" (spec 004 US8).
    var pendingPair: (pin: String, name: String)?
    private(set) var nowPlaying: LibraryItem?
    private(set) var paused = false
    private(set) var remoteStatus: String?
    private(set) var startingTitle: String?
    private(set) var pendingApprovals: [PendingApproval] = []
    /// Nil until loaded, and while the server can't be reached.
    private(set) var profileLock: Bool?
    private(set) var sharedTvIdleMinutes: Int?
    private(set) var working = false
    /// True while the app is on screen (the approvals poll is quick then).
    var appVisible = false
    /// Cast devices found on the network while the app or the picker is visible; empty when Cast is off or hidden.
    private(set) var castRoutes: [CastRoute] = []
    private(set) var castPickerOpen = false

    @ObservationIgnored private let pairing: PairingStore
    @ObservationIgnored private let catalog: @Sendable () -> CatalogClient
    @ObservationIgnored private let store: LibraryStore
    @ObservationIgnored private let items: () -> [LibraryItem]
    @ObservationIgnored private let setStatus: (String?) -> Void
    @ObservationIgnored private let selectItem: (LibraryItem) -> Void
    @ObservationIgnored private let ensurePhoneDevice: () async -> Void
    @ObservationIgnored private let syncLibrary: () async -> Void
    @ObservationIgnored private let onPairedChanged: (Bool) -> Void
    /// Called whenever the list of paired TVs changes, so the media server accepts exactly the TVs that are paired (CAB-37).
    @ObservationIgnored var onTvsChanged: () -> Void = {}
    @ObservationIgnored private var householdTvsCache: [MeDevice]?
    @ObservationIgnored private var commandGeneration = 0
    @ObservationIgnored private var titleStartToken = 0
    @ObservationIgnored private var toggleInFlight = false
    @ObservationIgnored private var playingTvDeviceId: String?
    @ObservationIgnored private var ownCommandAt = Date.distantPast
    @ObservationIgnored private var followTask: Task<Void, Never>?
    @ObservationIgnored private var approvalTask: Task<Void, Never>?
    @ObservationIgnored private var seenApprovals = Set<String>()
    @ObservationIgnored private var castSender: CastSender?
    @ObservationIgnored private var castVisible = false
    @ObservationIgnored private var pickerTitle: LibraryItem?
    @ObservationIgnored private var pickerDiscoveryTask: Task<Void, Never>?
    @ObservationIgnored private var castRoutesTask: Task<Void, Never>?
    @ObservationIgnored private var castPlaybackOwned = false
    @ObservationIgnored private var lastCastDeviceStatus: CastDeviceStatus?

    init(pairing: PairingStore, catalog: @escaping @Sendable () -> CatalogClient, store: LibraryStore, items: @escaping () -> [LibraryItem],
         setStatus: @escaping (String?) -> Void, selectItem: @escaping (LibraryItem) -> Void, ensurePhoneDevice: @escaping () async -> Void,
         syncLibrary: @escaping () async -> Void, onPairedChanged: @escaping (Bool) -> Void) {
        self.pairing = pairing; self.catalog = catalog; self.store = store; self.items = items; self.setStatus = setStatus
        self.selectItem = selectItem; self.ensurePhoneDevice = ensurePhoneDevice; self.syncLibrary = syncLibrary; self.onPairedChanged = onPairedChanged
        tvs = pairing.tvs
        paired = !pairing.tvs.isEmpty
        tvName = pairing.tvName
    }

    private var token: String? { pairing.accountToken.flatMap { $0.isEmpty ? nil : $0 } }
    private func text(_ key: String, _ args: CVarArg...) -> String { fillPlaceholders(L(key), args) }

    // MARK: Pairing

    func startAddTv() { addingTv = true; setStatus(nil) }
    func cancelAddTv() { addingTv = false; setStatus(nil) }

    /// A PIN typed, a QR code scanned, or a universal link opened. The owner then says whether this is their own TV.
    func applyPairUri(_ uri: String, webOrigins: Set<String>) {
        guard let parsed = parsePairCode(uri, webOrigins: webOrigins) else {
            setStatus(L("ui_enter_the_6_digit_pin_shown_on_the_tv_or_tap_refresh_c_52ae0e"))
            return
        }
        pendingPair = parsed
    }

    func cancelPendingPair() { pendingPair = nil }

    /// Pairs after the owner said whether this is their own TV or a temporary one.
    func confirmPair(_ trust: TvTrust) {
        guard let parsed = pendingPair else { return }
        pendingPair = nil
        pairWithTrust(parsed, trust)
    }

    private func pairWithTrust(_ parsed: (pin: String, name: String), _ trust: TvTrust) {
        guard !working else { return }
        working = true
        Task {
            defer { working = false }
            let result = await catalog().pairPhone(pin: parsed.pin, existingToken: pairing.accountToken, trust: trust)
            guard let claim = result.claim else {
                switch result.error {
                // CAB-62: the code often runs out while a new person creates their account.
                case "pin_expired": setStatus(L("ui_that_code_expired_scan_the_new_code_on_the_tv_or_type__1c6e7a"))
                case "invalid_pin": setStatus(L("ui_that_code_didn_t_match_check_the_code_on_the_tv_and_tr_630194"))
                default: setStatus(L("ui_couldn_t_connect_the_tv_check_your_connection_and_try__c92806"))
                }
                return
            }
            pairing.addTv(pin: parsed.pin, name: parsed.name, trust: trust)
            // Attach the per-device LAN capability (R-1), the TV's only LAN credential.
            if let capability = claim.lanCapability { pairing.attachCapability(pin: parsed.pin, capability: capability) }
            if let deviceId = claim.deviceId { pairing.attachDeviceId(pin: parsed.pin, deviceId: deviceId) }
            tvs = pairing.tvs
            onTvsChanged()
            addingTv = false
            tvName = pairing.tvName
            paired = true
            onPairedChanged(true)
            await ensurePhoneDevice()
            // Pairing the same TV again replaces its old record on the server; drop it here now, not a minute later.
            await syncHouseholdTvs()
            setStatus(nil)
            await syncLibrary()
        }
    }

    /// The control plane is the source of truth for the account's TVs: TVs paired by another phone or before a reinstall appear here,
    /// revoked ones disappear. Offline keeps the local list.
    func syncHouseholdTvs() async {
        guard let token, let server = await catalog().householdTvs(token: token) else { return }
        householdTvsCache = server
        let activeIds = Set(server.map(\.id))
        let local = pairing.tvs
        let kept = local.filter { $0.deviceId == nil || activeIds.contains($0.deviceId!) }
        let known = Set(kept.compactMap(\.deviceId))
        // The "pin" of an adopted TV is a random value no TV knows, so it can never act as a LAN credential; the TV's capability is
        // verified through the control plane instead.
        let adopted = server.filter { !known.contains($0.id) }.map { tv in
            PairedTv(pin: "household-\(UUID().uuidString.lowercased())", name: tv.displayName.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } ?? "TV",
                     deviceId: tv.id, trust: tv.trust)
        }
        // Trust follows the server: the end time may have been extended from another phone.
        let trustById = Dictionary(uniqueKeysWithValues: server.map { ($0.id, $0.trust) })
        let nameById = Dictionary(uniqueKeysWithValues: server.map { ($0.id, $0.displayName ?? "") })
        let refreshed = kept.map { tv -> PairedTv in
            var t = tv
            if let id = tv.deviceId, let trust = trustById[id] { t.trust = trust }
            // A TV paired without a name shows as "TV" until the server's name (the TV's own model name, or the one the owner gave it
            // on another phone) is taken over.
            if let id = tv.deviceId, tv.name == "TV", let name = nameById[id], !name.trimmingCharacters(in: .whitespaces).isEmpty { t.name = name }
            return t
        }
        let merged = adopted + refreshed // the selected (last) TV stays selected
        if merged == local { return }
        let wasPaired = paired
        pairing.tvs = merged
        tvs = merged
        onTvsChanged()
        tvName = pairing.tvName
        paired = !merged.isEmpty
        if paired != wasPaired { onPairedChanged(paired) }
        if !paired { nowPlaying = nil }
    }

    func unpair(_ tv: PairedTv? = nil) {
        guard let current = tv.flatMap({ candidate in pairing.tvs.contains(candidate) ? candidate : nil }) ?? pairing.tvs.last else { return }
        guard let deviceId = current.deviceId, !deviceId.isEmpty, let token else {
            // Legacy pairing without a device id: nothing to revoke server-side.
            forgetTv(current)
            return
        }
        Task {
            working = true
            // Revoke on the server first: forgetting the TV only locally left it signed in with full library access.
            let revoked = await catalog().revokeDevice(id: deviceId, token: token)
            working = false
            if revoked { forgetTv(current) } else { setStatus(text("ui_couldn_t_reach_cablegram_to_remove_check_your_connecti_8e61be", current.name)) }
        }
    }

    private func forgetTv(_ tv: PairedTv) {
        pairing.removeTv(pin: tv.pin)
        tvs = pairing.tvs
        onTvsChanged()
        if pairing.tvs.isEmpty {
            paired = false
            nowPlaying = nil
            paused = false
            onPairedChanged(false)
            setStatus(L("ui_tv_removed_scan_a_pin_when_you_want_one_again_ee2752"))
        } else {
            tvName = pairing.tvName
            setStatus(text("ui_removed_this_tv_still_paired_4ec21c", "\(pairing.tvs.count)"))
        }
    }

    /// "Rename" under Your TVs: saved on the server first, so every phone shows the new name.
    func renameTv(_ tv: PairedTv, to name: String) {
        let trimmed = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        guard !trimmed.isEmpty, let token, let id = tv.deviceId else { return }
        Task {
            if await catalog().renameDevice(token: token, deviceId: id, name: trimmed) {
                pairing.renameTv(pin: tv.pin, name: trimmed)
                tvs = pairing.tvs
                tvName = pairing.tvName
            } else {
                setStatus(L("ui_couldn_t_reach_cablegram_check_your_connection_and_try_de82d8"))
            }
        }
    }

    /// Extends a temporary TV's end time by a day, or turns direct Telegram on or off for it.
    func updateTvTrust(_ tv: PairedTv, _ trust: TvTrust) {
        guard let token, let id = tv.deviceId else { return }
        Task {
            if await catalog().setTvTrust(token: token, tvDeviceId: id, trust: trust) { await syncHouseholdTvs() }
            else { setStatus(text("ui_couldn_t_update_direct_telegram_needs_a_link_older_tha_894844", tv.name)) }
        }
    }

    func selectTv(_ tv: PairedTv) {
        guard tv != pairing.tvs.last, pairing.tvs.contains(tv) else { return }
        pairing.tvs = pairing.tvs.filter { $0.pin != tv.pin } + [tv]
        tvs = pairing.tvs
        tvName = pairing.tvName
        nowPlaying = nil
        paused = false
        remoteStatus = nil
    }

    /// Everything this phone holds about TVs, when the account signs out or is deleted.
    /// The title being played was removed from the library.
    func clearNowPlaying() { nowPlaying = nil }

    func reset() {
        dismissCastPicker(); castPlaybackOwned = false
        followTask?.cancel(); approvalTask?.cancel(); approvalTask = nil
        tvs = []; paired = false; nowPlaying = nil; paused = false; remoteStatus = nil; pendingApprovals = []
        addingTv = false; pendingPair = nil; householdTvsCache = nil; seenApprovals = []
    }

    // MARK: Play on TV and the remote

    // MARK: Cast (Android `PhoneViewModel`'s Cast part)

    /// The app came to the front or left it: Cast devices are looked for only while it is on screen.
    func setCastVisible(_ visible: Bool) {
        castVisible = visible
        if pairing.castConnect, castSender == nil, CastSender.configured {
            let sender = CastSender()
            sender.onDevice = { [weak self] status, routeId in
                guard let self else { return }
                lastCastDeviceStatus = status
                // The server's household list and existing pairing bound this untrusted hint.
                if householdTvsCache?.contains(where: { $0.id == status.deviceId }) == true {
                    pairing.attachCastDeviceId(deviceId: status.deviceId, castDeviceId: routeId)
                    tvs = pairing.tvs
                }
            }
            sender.onPlayback = { [weak self] isPaused, terminal in
                guard let self, castPlaybackOwned else { return }
                if terminal { nowPlaying = nil; paused = false; castPlaybackOwned = false } else if let isPaused { paused = isPaused }
            }
            castSender = sender
            castRoutesTask = Task { [weak self] in
                while !Task.isCancelled {
                    if let self, let routes = self.castSender?.routes, routes != self.castRoutes { self.castRoutes = routes }
                    try? await Task.sleep(for: .milliseconds(400))
                }
            }
        }
        castSender?.setVisible(visible)
        if !visible { dismissCastPicker() }
    }

    func updateCastConnect(_ value: Bool) {
        if !value {
            dismissCastPicker(); castRoutesTask?.cancel(); castRoutesTask = nil
            castSender?.release(); castSender = nil; castRoutes = []; castPlaybackOwned = false
        } else { setCastVisible(castVisible) }
    }

    func dismissCastPicker() {
        pickerDiscoveryTask?.cancel(); pickerDiscoveryTask = nil
        castPickerOpen = false; pickerTitle = nil
        castSender?.setVisible(castVisible)
    }

    func chooseCastRoute(_ route: CastRoute) {
        guard castRoutes.contains(route), let title = pickerTitle else { return }
        dismissCastPicker()
        startTitleOnTv(title, route: route)
    }

    func playOnTv(_ item: LibraryItem) {
        pickerDiscoveryTask?.cancel()
        guard let sender = castSender, pairing.castConnect, sender.available, paired else { startTitleOnTv(item, route: nil); return }
        if let route = matchingCastRoute(pairing.tvs.last, castRoutes) { startTitleOnTv(item, route: route); return }
        pickerDiscoveryTask = Task {
            sender.scanForPicker()
            // Give discovery a bounded chance; no route keeps the existing relay behavior.
            var routes = sender.routes
            for _ in 0..<30 where routes.isEmpty && !Task.isCancelled { try? await Task.sleep(for: .milliseconds(100)); routes = sender.routes }
            if Task.isCancelled { return }
            if !useCastForTitle(enabled: pairing.castConnect, senderAvailable: sender.available, routeAvailable: !routes.isEmpty) {
                sender.setVisible(castVisible)
                startTitleOnTv(item, route: nil)
            } else if let matching = matchingCastRoute(pairing.tvs.last, routes) {
                sender.setVisible(castVisible)
                startTitleOnTv(item, route: matching)
            } else {
                castRoutes = routes
                pickerTitle = item; castPickerOpen = true
            }
        }
    }

    private func startTitleOnTv(_ item: LibraryItem, route: CastRoute?) {
        guard paired else {
            selectItem(item)
            startAddTv()
            setStatus(text("ui_connect_a_tv_to_play_fd22ec", item.title))
            return
        }
        Task {
            let castUsable = pairing.castConnect && castSender?.available == true
            let effectiveRoute = castUsable ? route : nil
            // Only show "now playing" once the TV confirmed that playback started.
            titleStartToken += 1
            let token = titleStartToken
            castPlaybackOwned = false
            let sent = await sendCommand("play", videoId: item.id, wait: .titleStart, castRoute: effectiveRoute, castTitle: item) { [self] tv in
                startingTitle = item.title
                setStatus(text("ui_starting_on_bc52ee", item.title, tv))
            }
            if titleStartToken == token { startingTitle = nil }
            guard let sent else { setStatus(remoteStatus); return }
            // A later command owns the status line; this one only applies what the TV confirmed.
            if sent.latest { setStatus(remoteStatus) }
            guard sent.latest, titleStartToken == token, sent.outcome.applies else { return }
            if effectiveRoute != nil, castSender?.playbackTerminated != false { return }
            castPlaybackOwned = effectiveRoute != nil
            playingTvDeviceId = sent.deviceId
            nowPlaying = item
            paused = effectiveRoute != nil && castSender?.playbackPaused == true
            store.setWatchProgress(item.id, positionSeconds: max(item.positionSeconds, 1))
            if sent.latest {
                let line = sent.outcome == .confirmed ? text("ui_playing_on_8af952", item.title, sent.tvName) : text("ui_sent_to_not_confirmed_by_tv_8cbf56", item.title, sent.tvName)
                setStatus(line)
            }
            startFollowing()
        }
    }

    func skipSeconds(_ delta: Int) { Task { _ = await sendCommand("seek", arguments: ["seconds": delta]) } }

    func togglePlayPause() {
        guard !toggleInFlight else { return }
        toggleInFlight = true
        // A play command without a video ID resumes the current player.
        let command = paused ? "play" : "pause"
        Task {
            defer { toggleInFlight = false }
            guard let outcome = await sendCommand(command)?.outcome else { return }
            if outcome.applies { paused = command == "pause" }
        }
    }

    func remoteStop() {
        Task {
            guard let outcome = await sendCommand("stop")?.outcome else { return }
            // "not_applicable": the TV had nothing to stop (the title already ended there), so nothing plays either way.
            if !outcome.applies, outcome != .rejected(reason: "not_applicable") { return }
            nowPlaying = nil
            paused = false
        }
    }

    func remoteNavigate(_ direction: String) {
        guard ["up", "down", "left", "right"].contains(direction) else { return }
        Task { _ = await sendCommand("move", arguments: ["direction": direction]) }
    }

    func remoteSelect() { Task { _ = await sendCommand("select") } }
    func remoteVolume(_ level: Int) {
        let clamped = min(max(level, 0), 100)
        tvVolumes[tvName] = Double(clamped) / 100.0
        Task { _ = await sendCommand("volume", arguments: ["level": clamped]) }
    }

    /// Shifts the subtitle on the TV that is playing this title, relative to the timing it loaded (positive shows it later).
    func nudgeSubtitleOnTv(delayMs: Int64) {
        // The TV takes at most ten minutes either way; a larger difference would be refused without a word.
        Task { _ = await sendCommand("subtitle_delay", arguments: ["delay_ms": min(max(delayMs, -600_000), 600_000)]) }
    }

    struct SentCommand { var tvName: String; var outcome: Outcome; var latest: Bool; var deviceId: String? }

    /// Sends a command and waits for the TV's outcome, narrating it in `remoteStatus` while it is the newest command. Returns nil when
    /// the control service did not accept the command. `onAccepted` runs once the service accepted it, before the wait, and only
    /// while it is still the newest.
    private func sendCommand(_ command: String, videoId: String? = nil, arguments: [String: any Sendable] = [:], wait: ConfirmationWait = .control(),
                             castRoute: CastRoute? = nil, castTitle: LibraryItem? = nil, onAccepted: ((String) -> Void)? = nil) async -> SentCommand? {
        commandGeneration += 1
        let generation = commandGeneration
        func report(_ line: String) { if generation == commandGeneration { remoteStatus = line } }
        // A finished command's result stays long enough to read, then clears if nothing newer replaced it.
        func reportFinal(_ line: String) {
            report(line)
            Task {
                try? await Task.sleep(for: .seconds(6))
                if generation == commandGeneration && remoteStatus == line { remoteStatus = nil }
            }
        }
        report(L("ui_sending_b8ed52"))
        let client = catalog()
        let castTarget = castRoute != nil ? pairing.tvs.last?.deviceId : nil
        if castRoute != nil, castTarget == nil { reportFinal(L("ui_choose_a_paired_tv_in_settings_0b8448")); return nil }
        var payload = arguments
        if castRoute != nil, let castTitle {
            payload["castLaunch"] = true
            payload["title"] = String(castTitle.title.prefix(160))
            payload["senderName"] = String((pairing.displayName.isEmpty ? L("ui_your_phone_807163") : pairing.displayName).prefix(80))
        }
        let result = await TvCommandSender.send(pairing: pairing, client: client, command: command, videoId: videoId, arguments: payload, household: householdTvsCache, castTargetDeviceId: castTarget)
        guard case .sent(let sentName, var commandId, let token, let switchedFrom, var deviceId) = result else {
            if case .failed(let message) = result { reportFinal(message) }
            return nil
        }
        if switchedFrom != nil {
            // The command went to the TV that is on, which is now the current TV.
            tvs = pairing.tvs
            self.tvName = pairing.tvName
        }
        if let from = switchedFrom {
            report(text("ui_isn_t_on_so_on_5fb016", from, L(command == "play" && videoId != nil ? "ui_command_starting" : "ui_command_sending"), sentName))
        } else {
            report(command == "play" && videoId != nil ? text("ui_starting_on_12d09e", sentName) : text("ui_sent_to_29ea5e", sentName))
        }
        // Only the newest command may touch what the screens show.
        if generation == commandGeneration { onAccepted?(sentName) }
        if let castRoute, let castTitle, let castTarget, let castSender {
            do {
                lastCastDeviceStatus = nil
                let status = try await castSender.load(routeId: castRoute.deviceId, commandId: commandId, targetDeviceId: castTarget, videoId: castTitle.id,
                                                       title: castTitle.title, poster: castTitle.posterUrl) ?? lastCastDeviceStatus
                let household = await client.householdTvs(token: token)
                if let household { householdTvsCache = household }
                // A normal first LOAD must also persist its route mapping when discovery began before the household cache was populated.
                // Fresh membership still bounds the hint.
                if let status, household?.contains(where: { $0.id == status.deviceId }) == true {
                    pairing.attachCastDeviceId(deviceId: status.deviceId, castDeviceId: castRoute.deviceId)
                    tvs = pairing.tvs
                }
                let retry = wrongTvRetry(status, paired: pairing.tvs, householdIds: Set((household ?? []).map(\.id)), alreadyRetried: false)
                if status?.wrongTv == true {
                    guard let retry, let retryId = retry.deviceId else { reportFinal(L("ui_that_cast_device_isn_t_your_selected_tv_choose_a_paire_45fa19")); return nil }
                    guard case .sent(_, let again, _, _, let againDevice) = await TvCommandSender.send(pairing: pairing, client: client, command: command, videoId: videoId, arguments: payload,
                                                                                                   household: household, castTargetDeviceId: retryId) else { reportFinal(L("ui_tv_changed_choose_a_tv_and_try_again_69312c")); return nil }
                    commandId = again; deviceId = againDevice
                    let retried = try await castSender.load(routeId: castRoute.deviceId, commandId: again, targetDeviceId: retryId, videoId: castTitle.id, title: castTitle.title, poster: castTitle.posterUrl)
                    if retried?.wrongTv == true { reportFinal(L("ui_tv_changed_choose_a_tv_and_try_again_69312c")); return nil }
                    selectTv(retry)
                    pairing.attachCastDeviceId(deviceId: retryId, castDeviceId: castRoute.deviceId)
                    tvs = pairing.tvs
                }
            } catch is CancellationError { return nil }
            catch { reportFinal((error as? LocalizedError)?.errorDescription ?? L("ui_could_not_open_the_tv_try_again_344ff8")); return nil }
        }
        let outcome = await TvCommandSender.confirm(client: client, commandId: commandId, token: token, deviceId: deviceId, wait: wait)
        if generation == commandGeneration && (outcome == .confirmed || outcome == .unconfirmable) {
            if command == "volume", let level = arguments["level"] as? Int {
                tvVolumes[sentName] = Double(min(max(level, 0), 100)) / 100.0
            }
        }
        switch outcome {
        case .confirmed: reportFinal(text("ui_done_on_623a01", sentName))
        case .rejected(let reason): reportFinal(rejectionMessage(reason, tvName: sentName))
        case .timedOut: reportFinal(text("ui_didn_t_confirm_check_the_tv_e508b0", sentName))
        case .unconfirmable: reportFinal(L("ui_sent_not_confirmed_by_tv_feac62"))
        case .unreachable: reportFinal(text("ui_isn_t_responding_open_cablegram_on_it_choose_who_s_wat_f5df22", sentName))
        }
        ownCommandAt = Date()
        return SentCommand(tvName: sentName, outcome: outcome, latest: generation == commandGeneration, deviceId: deviceId)
    }

    /// CAB-57: the remote follows the TV (a title ended or paused there) instead of the last command this phone sent.
    func startFollowing() {
        guard followTask == nil || followTask?.isCancelled == true else { return }
        followTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(tvFollowPollMs))
                guard let self, let current = self.nowPlaying, self.appVisible else { if self?.nowPlaying == nil { break } else { continue } }
                guard let deviceId = self.playingTvDeviceId ?? self.pairing.tvs.last?.deviceId, let token = self.token else { break }
                guard let report = await self.catalog().tvPlayback(token: token, deviceId: deviceId) else { continue }
                guard self.nowPlaying != nil else { break }
                if let vol = report.volume, (0...100).contains(vol) {
                    self.tvVolumes[self.tvName] = Double(vol) / 100.0
                }
                switch followTv(report, nowPlayingId: current.id, sinceOwnCommandMs: Int64(Date().timeIntervalSince(self.ownCommandAt) * 1000)) {
                case .keep: break
                case .stopped: self.nowPlaying = nil; self.paused = false
                case .playing(let isPaused): if self.paused != isPaused { self.paused = isPaused }
                case .other(let videoId, let originId, let isPaused):
                    let other = self.items().first { $0.id == videoId || $0.id == originId }
                    self.nowPlaying = other
                    self.paused = other != nil && isPaused
                }
            }
            self?.followTask = nil
        }
    }

    // MARK: Approvals (private titles, profile lock)

    /// Polls for what TVs wait for: quick while the app is on screen, slow otherwise. The in-app card reads `pendingApprovals`; each new
    /// request also produces a local notification with Allow and Not now.
    func startApprovalWatch(notify: @escaping (PendingApproval) -> Void, cancelNotification: @escaping (String) -> Void) {
        guard approvalTask == nil else { return }
        approvalTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                if let token = self.token {
                    let pending = await self.catalog().fetchAllPendingApprovals(token: token)
                    self.pendingApprovals = pending
                    // Notify only new attempts; an approved, denied or expired attempt stays out.
                    for approval in pending where self.seenApprovals.insert(approval.attemptId).inserted { notify(approval) }
                    // Withdrawn, expired, or answered elsewhere: drop the stale notification so it cannot be approved late.
                    let live = Set(pending.map(\.attemptId))
                    for gone in self.seenApprovals.subtracting(live) { cancelNotification(gone); self.seenApprovals.remove(gone) }
                } else {
                    self.pendingApprovals = []
                }
                try? await Task.sleep(for: .milliseconds(approvalPollDelayMs(appVisible: self.appVisible)))
            }
        }
    }

    func stopApprovalWatch() { approvalTask?.cancel(); approvalTask = nil }

    func refreshPendingApprovals() {
        guard let token else { pendingApprovals = []; return }
        Task { pendingApprovals = await catalog().fetchAllPendingApprovals(token: token) }
    }

    /// Approves or denies from inside the phone app (or a notification action).
    @discardableResult
    func respondToApproval(_ approval: PendingApproval, approve: Bool, cancelNotification: (String) -> Void) -> Task<Void, Never>? {
        guard let token else { return nil }
        cancelNotification(approval.attemptId)
        pendingApprovals.removeAll { $0.attemptId == approval.attemptId }
        return Task {
            let profile = approval.kind == .profile
            let what = approval.title ?? L("ui_the_profile_31580c")
            let ok = await catalog().answerApproval(token: token, approval: approval, allow: approve)
            if ok {
                let tv = approval.tvName ?? L("ui_the_tv_adfd45")
                setStatus(profile && approve ? text("ui_can_open_3f1941", tv, what) : profile ? text("ui_won_t_open_b1de63", tv, what)
                          : approve ? L("ui_playback_allowed_for_this_attempt_2fcc98") : L("ui_playback_denied_270488"))
            } else {
                let reason = L("ui_the_request_was_not_accepted_refresh_and_try_again_35aa7e")
                setStatus(profile && approve ? text("ui_couldnt_allow_profile", what, reason) : profile ? text("ui_couldnt_deny_profile", what, reason)
                          : approve ? text("ui_couldnt_allow_playback", reason) : text("ui_couldnt_deny_playback", reason))
                refreshPendingApprovals()
            }
        }
    }

    // MARK: Lock account on TVs

    func loadProfileLock() {
        guard let token else { return }
        Task {
            profileLock = await catalog().profileLock(token: token)
            sharedTvIdleMinutes = await catalog().sharedTvIdleMinutes(token: token)
        }
    }

    func updateProfileLock(_ on: Bool) {
        guard let token else { return }
        Task { if let saved = await catalog().setProfileLock(token: token, on: on) { profileLock = saved } else { setStatus(L("ui_something_went_wrong_try_again_in_a_moment_156a12")) } }
    }

    func updateSharedTvIdleMinutes(_ minutes: Int) {
        guard let token else { return }
        Task { if let saved = await catalog().setSharedTvIdleMinutes(token: token, minutes: minutes) { sharedTvIdleMinutes = saved } }
    }
}
