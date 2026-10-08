package app.cablegram.phone

/** Monotonic activity shared by the service and both HTTP listeners. */
internal class LanLibraryLifetime(
    private val idleTimeoutMs: Long,
    private val clock: () -> Long,
) {
    private var foreground = false
    private var backgroundSince = clock()
    private var lastTvActivity = backgroundSince
    private var streams = 0

    @Synchronized
    fun appForeground(value: Boolean) {
        if (foreground && !value) backgroundSince = clock()
        foreground = value
    }

    @Synchronized
    fun tvRequest() { lastTvActivity = clock() }

    @Synchronized
    fun streamStarted() {
        streams++
        lastTvActivity = clock()
    }

    @Synchronized
    fun streamFinished() {
        streams--
        lastTvActivity = clock()
    }

    @Synchronized
    fun shouldStop(castActive: Boolean): Boolean =
        !foreground && !castActive && streams == 0 &&
            clock() - maxOf(backgroundSince, lastTvActivity) >= idleTimeoutMs
}
