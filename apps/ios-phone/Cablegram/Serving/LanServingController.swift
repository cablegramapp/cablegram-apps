import CablegramCore
import Foundation
import Observation
import UIKit

/// Serves the library to paired TVs (Android `LanLibraryService`). iOS cannot keep a server running in the background, so this
/// serves while the app is on screen, shows "Serving to <TV>", keeps the screen awake while a TV plays, and asks the system for
/// a few extra seconds when the app is left, so a playing film is not cut mid-scene by a quick app switch.
///
/// Two ways in: the LAN listener (TLS on port 8765, announced over Bonjour and to the control plane) and the relay tunnel.
@MainActor @Observable
final class LanServingController {
    private(set) var running = false
    /// Names of the TVs being served, for "Serving to …".
    private(set) var tvNames: [String] = []
    private(set) var activeStreams = 0
    /// "Streaming via relay · 12 MB", while the relay carries a stream.
    private(set) var relayText: String?
    private(set) var failure: String?
    private(set) var lanAddress: String?

    /// iOS gives a backgrounded app only a few seconds, so Android's 30-minute idle limit (`LAN_IDLE_TIMEOUT_MS`) is capped here.
    static let backgroundGrace: TimeInterval = 25

    @ObservationIgnored private let pairing: PairingStore
    @ObservationIgnored private let store: LibraryStore
    @ObservationIgnored private let root: URL
    @ObservationIgnored private let media: AppMediaProvider
    @ObservationIgnored private let catalog: @Sendable () -> CatalogClient
    @ObservationIgnored private let network = NetworkInfo()
    @ObservationIgnored private let lifetime: LanLibraryLifetime
    @ObservationIgnored private let lanHostOverride: String?
    @ObservationIgnored private var server: LanHTTPServer?
    @ObservationIgnored private var credentials: LanCredentials?
    @ObservationIgnored private var tunnel: RelayTunnel?
    @ObservationIgnored private var watchers: [Task<Void, Never>] = []
    @ObservationIgnored private var foreground = true
    @ObservationIgnored private var userStopped = false
    @ObservationIgnored private var starting = false
    /// Bumped by every `stop()`, so a `launch` that was still starting when serving was stopped can tell and undo itself.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var relayStartBytes: Int64 = 0
    @ObservationIgnored private var lastRelayNotice = Date.distantPast
    @ObservationIgnored private var backgroundTask = UIBackgroundTaskIdentifier.invalid
    @ObservationIgnored private let liveActivity = ServingLiveActivityController()

    init(pairing: PairingStore, store: LibraryStore, root: URL, sources: AppSources, configuration: AppConfiguration, catalog: @escaping @Sendable () -> CatalogClient) {
        self.pairing = pairing; self.store = store; self.root = root; self.catalog = catalog
        lifetime = LanLibraryLifetime(idleTimeout: min(Double(configuration.lanIdleTimeoutMs) / 1000, Self.backgroundGrace))
        lanHostOverride = configuration.lanHostOverride.isEmpty ? nil : configuration.lanHostOverride
        media = AppMediaProvider(sources: sources)
        network.observe { [weak self] type in Task { @MainActor in self?.networkChanged(type) } }
        liveActivity.endStale()
    }

    // MARK: Start and stop

    /// Starts serving when TVs are paired and an account is signed in; when already serving, brings the accepted TVs in step with
    /// the store (a TV removed here or on another phone is refused at once, CAB-37).
    func start() {
        guard !userStopped else { return }
        let tvs = pairing.tvs
        guard !tvs.isEmpty, pairing.accountToken?.isEmpty == false else { stop(); return }
        tvNames = tvs.map(\.name)
        if running { credentials?.sync(tvs); return }
        guard !starting else { return }
        starting = true
        Task { await launch(tvs) }
    }

    /// The owner's Stop: serving ends until the app is opened again.
    func userStop() { userStopped = true; stop() }

    func stop() {
        generation += 1
        watchers.forEach { $0.cancel() }
        watchers = []
        tunnel?.stop(); tunnel = nil
        server?.stop(); server = nil
        credentials = nil
        running = false
        activeStreams = 0
        relayText = nil
        lanAddress = nil
        tvNames = []
        UIApplication.shared.isIdleTimerDisabled = false
        endBackgroundTask()
        liveActivity.end()
    }

    private func launch(_ tvs: [PairedTv]) async {
        defer { starting = false }
        let attempt = generation
        let token: @Sendable () -> String? = { [pairing] in pairing.accountToken }
        let client = catalog()
        let passes = PrivatePassVerifier(verify: { pass in
            guard let token = token() else { return nil }
            return await client.verifyLanPass(token: token, pass: pass)
        })
        let credentials = LanCredentials(verifier: LanCapabilityVerifier(verify: { capability in
            guard let token = token() else { return nil }
            return await client.verifyLanCapability(token: token, capability: capability)
        }))
        credentials.sync(tvs)
        let lifetime = self.lifetime
        let router = LanLibraryRouter(store: store, commands: CommandQueue(root: root), credentials: credentials, privatePasses: passes, media: media,
                                      onTvRequest: { lifetime.tvRequest() }, onStreamStarted: { [weak self] in lifetime.streamStarted(); Task { @MainActor in self?.streamsChanged() } },
                                      onStreamFinished: { [weak self] in lifetime.streamFinished(); Task { @MainActor in self?.streamsChanged() } })
        do {
            let identity = try LanTlsIdentity.load()
            let server = LanHTTPServer(router: router, port: LanLibraryRouter.port, tls: identity.serverOptions(),
                                       advertisement: .init(name: LanLibraryRouter.bonjourName, type: LanLibraryRouter.bonjourType,
                                                            txt: NetworkInfo.localIPv4().map { ["ip": $0] } ?? [:]))
            server.onFailure = { [weak self] in Task { @MainActor in self?.serverFailed() } }
            _ = try await server.start()
            // Stopped (sign-out, last TV removed, Stop) while the listener was coming up: undo.
            guard attempt == generation else { server.stop(); return }
            self.server = server
            self.credentials = credentials
            running = true
            failure = nil
            startRelay(router: router)
            startWatchers(credentials)
            await announce(identity)
            // TVs paired or removed while starting were skipped by `start()`; bring the accepted set up to date now.
            credentials.sync(pairing.tvs)
            if pairing.tvs.isEmpty { stop(); return }
            tvNames = pairing.tvs.map(\.name)
            updateActivity()
        } catch {
            // Another app holds the port, or the identity could not be made: serving is off, and the screen says so.
            failure = error.localizedDescription
            running = false
        }
    }

    /// The listener died (iOS does this across a suspension): serving restarts at once if the app is on screen, otherwise on return.
    private func serverFailed() {
        guard running else { return }
        stop()
        if foreground { start() }
    }

    // MARK: TV address


    /// Tells the control plane where this phone's LAN server is, with the certificate TVs pin (Android `publishLanAddress`).
    private func announce(_ identity: LanTlsIdentity? = nil) async {
        guard let host = lanHostOverride ?? NetworkInfo.localIPv4() else { lanAddress = nil; return }
        lanAddress = "\(host):\(LanLibraryRouter.port)"
        let fingerprint = (try? identity ?? LanTlsIdentity.load())?.fingerprint
        _ = await catalog().announce(host: host, port: Int(LanLibraryRouter.port), deviceId: pairing.phoneDeviceId, token: pairing.accountToken, certSha256: fingerprint)
    }

    private func networkChanged(_ type: String) {
        guard running else { return }
        if RelayConsent.shouldResetAllowance(network: type, allowedUntil: pairing.relayMobileAllowedUntil) { pairing.relayMobileAllowedUntil = 0 }
        tunnel?.networkChanged()
        Task { await announce() }
    }

    // MARK: Relay

    private func startRelay(router: LanLibraryRouter) {
        let pairing = self.pairing
        let network = self.network
        let tunnel = RelayTunnel(
            relayURL: { RelayTunnel.phoneURL(apiBaseURL: pairing.apiBaseUrl) }, accountToken: { pairing.accountToken }, phoneDeviceId: { pairing.phoneDeviceId },
            networkType: { network.networkType },
            admit: { type in
                if RelayConsent.shouldResetAllowance(network: type, allowedUntil: pairing.relayMobileAllowedUntil) { pairing.relayMobileAllowedUntil = 0 }
                return RelayConsent.admit(network: type, policy: pairing.relayMobileData, allowedUntil: pairing.relayMobileAllowedUntil, ask: { RelayConsentNotice.ask() })
            },
            router: router,
            onActivity: { [weak self] activity in Task { @MainActor in self?.relayActivity(activity) } })
        self.tunnel = tunnel
        tunnel.start()
    }

    /// While relaying, the banner says so and how much was sent (mobile data called out).
    private func relayActivity(_ activity: RelayTunnel.Activity) {
        guard running else { return }
        let now = Date()
        if activity.activeStreams > 0, now.timeIntervalSince(lastRelayNotice) < 2 { return }
        lastRelayNotice = now
        if activity.activeStreams == 0 { relayStartBytes = activity.bytesSent; relayText = nil } else {
            let megabytes = (activity.bytesSent - relayStartBytes) / (1024 * 1024)
            relayText = L(activity.network == "cellular" ? "ios_serving_relay_mobile" : "ios_serving_relay_mb", "\(megabytes)")
        }
        updateActivity()
    }

    // MARK: Revocation and idle

    /// R-2: asks the control plane every minute which household devices are revoked and drops them live. A failure keeps the last
    /// known state; the server-side check stays the authoritative gate.
    private func startWatchers(_ credentials: LanCredentials) {
        let client = catalog()
        let pairing = self.pairing
        watchers.append(Task {
            while !Task.isCancelled {
                if let token = pairing.accountToken, !token.isEmpty { for id in await client.fetchRevokedDeviceIds(token: token) { credentials.revokeDevice(id) } }
                try? await Task.sleep(nanoseconds: 60_000_000_000)
            }
        })
        watchers.append(Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard let self else { return }
                if lifetime.shouldStop() { stop(); return }
            }
        })
    }

    private func streamsChanged() {
        activeStreams = lifetime.activeStreams
        // A locked screen suspends the app and would cut the film: stay awake while a TV plays.
        UIApplication.shared.isIdleTimerDisabled = running && activeStreams > 0
        updateActivity()
    }

    // MARK: App state

    func setForeground(_ value: Bool) {
        // The owner's Stop lasts until the app is opened again.
        if value && !foreground { userStopped = false }
        foreground = value
        lifetime.appForeground(value)
        if value {
            endBackgroundTask()
            start()   // serving stopped while the app was away (idle, or the system ended it) starts again
            streamsChanged()
        } else if running {
            // A few seconds of grace so a playing film survives a quick switch to another app.
            // The system calls this on the main thread and wants the task ended before it returns.
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "cablegram-serving") { [weak self] in
                MainActor.assumeIsolated { self?.stop() }
            }
            updateActivity()
        }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    // MARK: Live Activity

    private func updateActivity() {
        guard running else { liveActivity.end(); return }
        liveActivity.update(title: L("ios_serving_to", tvNames.last ?? L("ui_your_tv_f04452")), detail: relayText ?? L("ios_serving_keep_open"), streams: activeStreams)
    }
}
