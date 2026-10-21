import CablegramCore
import Foundation
import GoogleCast
import Observation

/// Cast sender (Android `CastConnectSender`): finds Cast devices while the app or the picker is visible, joins one, and launches the
/// Cablegram receiver with the command the control plane accepted. With no receiver id (`CablegramCastAppID` empty) Cast is hidden and
/// every title goes through the relay.
@MainActor @Observable
final class CastSender: NSObject {
    private(set) var routes: [CastRoute] = []
    let available: Bool
    @ObservationIgnored var onDevice: ((CastDeviceStatus, String) -> Void)?
    @ObservationIgnored var onPlayback: ((Bool?, Bool) -> Void)?

    @ObservationIgnored private var client: GCKRemoteMediaClient?
    @ObservationIgnored private var deviceStatus: CastDeviceStatus?
    @ObservationIgnored private var pendingConnection: CheckedContinuation<Void, Error>?
    @ObservationIgnored private var visible = false
    @ObservationIgnored private var connectionToken = 0
    @ObservationIgnored private var statusWaiters: [CheckedContinuation<CastDeviceStatus?, Never>] = []

    /// Must run once, early (the SDK wants its options before anything else touches it). Does nothing without a receiver id.
    static func configure(appId: String) {
        guard !appId.isEmpty, !GCKCastContext.isSharedInstanceInitialized() else { return }
        let options = GCKCastOptions(discoveryCriteria: GCKDiscoveryCriteria(applicationID: appId))
        // Discovery starts only while the app or the picker is visible.
        options.disableDiscoveryAutostart = true
        options.launchOptions = GCKLaunchOptions(relaunchIfRunning: false, androidReceiverCompatible: true)
        GCKCastContext.setSharedInstanceWith(options)
    }

    /// Cast is built in (a receiver id was configured and the SDK started).
    static var configured: Bool { GCKCastContext.isSharedInstanceInitialized() }

    override init() {
        available = GCKCastContext.isSharedInstanceInitialized()
        super.init()
        guard available else { return }
        let context = GCKCastContext.sharedInstance()
        context.sessionManager.add(self)
        context.discoveryManager.add(self)
        if let session = context.sessionManager.currentCastSession, session.connectionState == .connected { attach(session) }
    }

    var playbackPaused: Bool { client?.mediaStatus?.playerState == .paused }

    /// No session, or the receiver's media ended, failed or was cancelled.
    var playbackTerminated: Bool {
        guard available, GCKCastContext.sharedInstance().sessionManager.currentCastSession?.connectionState == .connected else { return true }
        guard let status = client?.mediaStatus else { return false }
        return terminalCastIdle(playerState: status.playerState.rawValue, idleReason: status.idleReason.rawValue)
    }

    private func refreshRoutes() {
        guard available else { return }
        let manager = GCKCastContext.sharedInstance().discoveryManager
        routes = (0..<manager.deviceCount).map { manager.device(at: $0) }.map { CastRoute(deviceId: $0.deviceID, name: $0.friendlyName ?? $0.deviceID) }
    }

    func setVisible(_ value: Bool) {
        guard available else { return }
        visible = value
        let manager = GCKCastContext.sharedInstance().discoveryManager
        if value { manager.startDiscovery(); refreshRoutes() } else { manager.stopDiscovery() }
    }

    /// A scan for the picker: an active one, not the passive default.
    func scanForPicker() {
        guard available, visible else { return }
        let manager = GCKCastContext.sharedInstance().discoveryManager
        manager.passiveScan = false
        manager.startDiscovery()
        refreshRoutes()
    }

    private func attach(_ session: GCKCastSession) {
        client?.remove(self)
        client = session.remoteMediaClient
        client?.add(self)
        pendingConnection?.resume()
        pendingConnection = nil
    }

    private func ended() {
        client?.remove(self)
        client = nil
        pendingConnection?.resume(throwing: CastFailure.connection)
        pendingConnection = nil
        onPlayback?(nil, true)
    }

    enum CastFailure: Error, LocalizedError {
        case unavailable, deviceUnavailable, connection, deviceChanged, notAccepted, notIdentified
        var errorDescription: String? {
            switch self {
            case .unavailable: "Cast unavailable"
            case .deviceUnavailable: "Cast device unavailable"
            case .connection: "Cast connection failed"
            case .deviceChanged: "Cast device changed"
            case .notAccepted: "TV did not accept Cast launch"
            case .notIdentified: "TV did not identify itself. Try again."
            }
        }
    }

    /// Joins the device if needed, loads the title on the receiver with the accepted command, and waits for the TV to say who it is.
    func load(routeId: String, commandId: String, targetDeviceId: String, videoId: String, title: String, poster: String?) async throws -> CastDeviceStatus? {
        guard available else { throw CastFailure.unavailable }
        let manager = GCKCastContext.sharedInstance().sessionManager
        if manager.currentCastSession?.connectionState != .connected || manager.currentCastSession?.device.deviceID != routeId {
            guard let device = discovered(routeId) else { throw CastFailure.deviceUnavailable }
            connectionToken += 1
            let token = connectionToken
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                pendingConnection = continuation
                if !manager.startSession(with: device) { pendingConnection = nil; continuation.resume(throwing: CastFailure.connection); return }
                Task { @MainActor in
                    try? await Task.sleep(for: .seconds(30))
                    if connectionToken == token, let waiting = pendingConnection { pendingConnection = nil; waiting.resume(throwing: TimeoutError()) }
                }
            }
        }
        guard let connected = manager.currentCastSession, connected.connectionState == .connected, connected.device.deviceID == routeId,
              let remote = connected.remoteMediaClient else { throw CastFailure.deviceChanged }
        let metadata = GCKMediaMetadata(metadataType: .movie)
        metadata.setString(title, forKey: kGCKMetadataKeyTitle)
        if let poster = publicPosterUrl(poster), let url = URL(string: poster) { metadata.addImage(GCKImage(url: url, width: 0, height: 0)) }
        deviceStatus = nil
        let info = GCKMediaInformationBuilder(contentID: videoId)
        info.contentType = "video/mp4"
        info.streamType = .buffered
        info.metadata = metadata
        let builder = GCKMediaLoadRequestDataBuilder()
        builder.mediaInformation = info.build()
        builder.customData = ["commandId": commandId, "targetDeviceId": targetDeviceId]
        nonisolated(unsafe) let request = remote.loadMedia(with: builder.build())
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let outcome = RequestOutcome(continuation)
                request.delegate = outcome
                objc_setAssociatedObject(request, &CastSender.outcomeKey, outcome, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
                Task { try? await Task.sleep(for: .seconds(30)); outcome.finish(.failure(TimeoutError())) }
            }
        } onCancel: { request.cancel() }
        // The TV reads its own stored credentials asynchronously after LOAD. Wait for its identity instead of deciding retry
        // from a stale or absent immediate load response.
        let value = await waitForDeviceStatus(seconds: 5)
        guard var value else { throw CastFailure.notIdentified }
        value.wrongTv = value.wrongTv || value.deviceId != targetDeviceId
        return value
    }

    private static var outcomeKey = 0

    private func discovered(_ id: String) -> GCKDevice? {
        let manager = GCKCastContext.sharedInstance().discoveryManager
        return (0..<manager.deviceCount).map { manager.device(at: $0) }.first { $0.deviceID == id }
    }

    private func waitForDeviceStatus(seconds: Double) async -> CastDeviceStatus? {
        if let deviceStatus { return deviceStatus }
        return await withCheckedContinuation { continuation in
            statusWaiters.append(continuation)
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(seconds))
                self.resolveWaiters(self.deviceStatus)
            }
        }
    }

    private func resolveWaiters(_ status: CastDeviceStatus?) {
        let waiting = statusWaiters
        statusWaiters = []
        waiting.forEach { $0.resume(returning: status) }
    }

    func release() {
        setVisible(false)
        guard available else { return }
        client?.remove(self)
        GCKCastContext.sharedInstance().sessionManager.remove(self)
        GCKCastContext.sharedInstance().discoveryManager.remove(self)
        pendingConnection?.resume(throwing: CancellationError())
        pendingConnection = nil
        resolveWaiters(nil)
        onDevice = nil; onPlayback = nil
    }
}

/// Turns a Cast request's delegate callbacks into one result.
private final class RequestOutcome: NSObject, GCKRequestDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    init(_ continuation: CheckedContinuation<Void, Error>) { self.continuation = continuation }
    func finish(_ result: Result<Void, Error>) {
        let taken = lock.withLock { () -> CheckedContinuation<Void, Error>? in defer { continuation = nil }; return continuation }
        taken?.resume(with: result)
    }
    func requestDidComplete(_ request: GCKRequest) { finish(.success(())) }
    func request(_ request: GCKRequest, didFailWithError error: GCKError) { finish(.failure(CastSender.CastFailure.notAccepted)) }
    func request(_ request: GCKRequest, didAbortWith abortReason: GCKRequestAbortReason) { finish(.failure(CancellationError())) }
}

extension CastSender: GCKSessionManagerListener, GCKDiscoveryManagerListener, GCKRemoteMediaClientListener {
    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKCastSession) {
        nonisolated(unsafe) let session = session
        MainActor.assumeIsolated { attach(session) }
    }
    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didResumeCastSession session: GCKCastSession) {
        nonisolated(unsafe) let session = session
        MainActor.assumeIsolated { attach(session) }
    }
    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKCastSession, withError error: any Error) {
        MainActor.assumeIsolated { pendingConnection?.resume(throwing: CastFailure.connection); pendingConnection = nil }
    }
    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKCastSession, withError error: (any Error)?) { MainActor.assumeIsolated { ended() } }
    nonisolated func sessionManager(_ sessionManager: GCKSessionManager, didFailToResumeSession session: GCKSession, withError error: any Error) { MainActor.assumeIsolated { ended() } }

    nonisolated func didUpdateDeviceList() { MainActor.assumeIsolated { refreshRoutes() } }

    nonisolated func remoteMediaClient(_ client: GCKRemoteMediaClient, didUpdate mediaStatus: GCKMediaStatus?) {
        nonisolated(unsafe) let mediaStatus = mediaStatus
        MainActor.assumeIsolated {
            guard let status = mediaStatus, let session = GCKCastContext.sharedInstance().sessionManager.currentCastSession else { return }
            let routeId = session.device.deviceID
            if let data = status.customData as? [String: Any], let own = data["deviceId"] as? String, !own.isEmpty {
                let value = CastDeviceStatus(deviceId: own, wrongTv: (data["reason"] as? String) == "wrong_tv")
                deviceStatus = value
                resolveWaiters(value)
                onDevice?(value, routeId)
            }
            if terminalCastIdle(playerState: status.playerState.rawValue, idleReason: status.idleReason.rawValue) { onPlayback?(nil, true) }
            else if status.playerState == .paused { onPlayback?(true, false) }
            else if status.playerState == .playing { onPlayback?(false, false) }
        }
    }
}
