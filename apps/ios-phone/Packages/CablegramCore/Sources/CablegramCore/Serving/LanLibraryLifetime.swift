import Foundation

/// Activity shared by the server, the relay tunnel and the app's foreground state. iOS cannot keep a server running in the
/// background, so unlike Android this decides when a backgrounded app has been idle long enough to stop serving.
public final class LanLibraryLifetime: @unchecked Sendable {
    private let idleTimeout: TimeInterval
    private let clock: @Sendable () -> TimeInterval
    private let lock = NSLock()
    private var foreground = false
    private var backgroundSince: TimeInterval
    private var lastTvActivity: TimeInterval
    private var streams = 0

    public init(idleTimeout: TimeInterval, clock: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.idleTimeout = idleTimeout
        self.clock = clock
        backgroundSince = clock()
        lastTvActivity = backgroundSince
    }

    public func appForeground(_ value: Bool) {
        lock.withLock {
            if foreground && !value { backgroundSince = clock() }
            foreground = value
        }
    }

    public func tvRequest() { lock.withLock { lastTvActivity = clock() } }
    public func streamStarted() { lock.withLock { streams += 1; lastTvActivity = clock() } }
    public func streamFinished() { lock.withLock { streams -= 1; lastTvActivity = clock() } }
    public var activeStreams: Int { lock.withLock { streams } }

    public func shouldStop(castActive: Bool = false) -> Bool {
        lock.withLock {
            !foreground && !castActive && streams == 0 && clock() - max(backgroundSince, lastTvActivity) >= idleTimeout
        }
    }
}
