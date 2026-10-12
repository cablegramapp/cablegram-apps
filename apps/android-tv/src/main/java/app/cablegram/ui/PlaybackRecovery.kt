package app.cablegram.ui

/** CAB-15: nothing plays within this long after the source was opened and answered its probe. */
internal const val STARTUP_DEADLINE_SECONDS = 30

/** CAB-15: after a seek's hold ends, a picture that does not advance for this long is a stall. */
internal const val SEEK_STALL_SECONDS = 10

/**
 * Counts consecutive one-second ticks in which playback was expected to move and did not. [tick] returns true
 * once, when the limit is reached, and starts counting again; any tick that is not waiting starts it over.
 */
internal class NoProgressTimer(private val limitSeconds: Int) {
    private var waited = 0

    fun tick(waiting: Boolean): Boolean {
        if (!waiting) { waited = 0; return false }
        waited++
        if (waited < limitSeconds) return false
        waited = 0
        return true
    }

    fun reset() { waited = 0 }
}

/** Startup is waiting once the source is loaded and nothing has played, and nothing else is in flight. */
internal fun startupWaiting(loaded: Boolean, hasStarted: Boolean, hasError: Boolean, switching: Boolean): Boolean =
    loaded && !hasStarted && !hasError && !switching

/**
 * A started stream is stalled when the viewer wants it playing, no seek hold is active, and the position does
 * not move. The screen's own playing flag is used, not the decoder's: a decoder that is waiting for data
 * reports "not playing" although nothing was paused, and a viewer's pause clears the screen's flag.
 */
internal fun streamStalled(
    hasStarted: Boolean, settling: Boolean, wantsPlaying: Boolean, hasError: Boolean, switching: Boolean,
    positionMs: Long, lastPositionMs: Long,
): Boolean = hasStarted && !settling && wantsPlaying && !hasError && !switching && positionMs == lastPositionMs

/** Which of the two recovery buttons has focus. */
internal enum class RecoveryChoice { RETRY, BACK }

/** Left/right/up/down move between Retry and Back; OK/Back are handled by the caller. */
internal fun RecoveryChoice.toggled(): RecoveryChoice = if (this == RecoveryChoice.RETRY) RecoveryChoice.BACK else RecoveryChoice.RETRY
