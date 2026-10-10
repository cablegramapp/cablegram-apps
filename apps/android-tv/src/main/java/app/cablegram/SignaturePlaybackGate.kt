package app.cablegram

/**
 * When a ready title replaces the waiting clip. A title that is ready quickly starts at once; otherwise the clip
 * gets a short minimum once it is on screen, never a whole cycle. Callbacks from older attempts never release a title.
 */
internal class SignaturePlaybackGate(
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val quickMs: Long = QUICK_MS,
    private val minimumMs: Long = MINIMUM_MS,
) {
    private var attempt: String? = null
    private var begunAt = 0L
    private var clipStartedAt: Long? = null
    private var holdForClip = true

    /** [holdForClip] is false for casts, remote commands and retries: those start the moment they are ready. */
    fun begin(id: String, holdForClip: Boolean = true) {
        attempt = id
        begunAt = now()
        clipStartedAt = null
        this.holdForClip = holdForClip
    }

    fun clipStarted(id: String) {
        if (id == attempt && clipStartedAt == null) clipStartedAt = now()
    }

    fun isCurrent(id: String): Boolean = id == attempt

    /** Milliseconds the ready title still waits (0 = start now), or null for an attempt that is no longer current. */
    fun delayFor(id: String): Long? {
        if (id != attempt) return null
        val time = now()
        if (!holdForClip || time - begunAt <= quickMs) return 0
        val started = clipStartedAt ?: return 0
        return (started + minimumMs - time).coerceAtLeast(0)
    }

    fun cancel() {
        attempt = null
        clipStartedAt = null
    }

    companion object {
        const val QUICK_MS = 1_500L
        const val MINIMUM_MS = 2_000L
    }
}
