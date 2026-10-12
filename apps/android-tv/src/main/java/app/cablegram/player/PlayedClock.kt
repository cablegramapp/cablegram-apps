package app.cablegram.player

/**
 * CAB-56: libVLC's clock stops advancing for the last few seconds of a file while the buffered picture and sound
 * still play out, so `time` at EndReached can sit 5 s or more short of the length. A natural end then looked like a
 * lost stream and the TV switched to the relay. This remembers when the clock last moved during playback and adds
 * the wall-clock time played since, capped, so the end check sees how far playback really got.
 *
 * Time on hold (paused, stopped, seeking, buffering) is not counted. A stream that really drops still ends short:
 * the estimate only covers what was played.
 */
internal class PlayedClock(private val maxLagMs: Long = MAX_CLOCK_LAG_MS) {
    private var anchorMs = 0L
    private var anchorAt = 0L
    private var running = false
    private var endedAtMs: Long? = null

    /** The player reports [timeMs]; a value that has not moved keeps the old anchor. */
    fun onTime(timeMs: Long, nowMs: Long) {
        if (timeMs == anchorMs) return
        anchorMs = timeMs
        anchorAt = nowMs
    }

    fun onPlaying(timeMs: Long, nowMs: Long) {
        running = true
        endedAtMs = null
        anchorMs = timeMs
        anchorAt = nowMs
    }

    /** Paused, stopped or seeking: nothing plays until [onPlaying]. */
    fun onHold() {
        running = false
    }

    /** Buffering below 100 %: playback is stalled, so the time so far does not count. */
    fun onStall(nowMs: Long) {
        anchorAt = nowMs
    }

    fun onSeek(targetMs: Long, nowMs: Long) {
        endedAtMs = null
        anchorMs = targetMs
        anchorAt = nowMs
    }

    /** The end was reached: keep how far playback got, for whoever asks after the player has stopped. */
    fun onEnd(timeMs: Long, nowMs: Long, rate: Float) {
        endedAtMs = playedThroughMs(timeMs, nowMs, rate)
        running = false
    }

    /** A new source: nothing is known about it yet. */
    fun reset() {
        running = false
        endedAtMs = null
        anchorMs = 0L
        anchorAt = 0L
    }

    fun playedThroughMs(timeMs: Long, nowMs: Long, rate: Float): Long {
        endedAtMs?.let { return maxOf(timeMs, it) }
        if (!running) return timeMs
        val extra = ((nowMs - anchorAt) * rate).toLong().coerceIn(0L, maxLagMs)
        return maxOf(timeMs, anchorMs + extra)
    }

    companion object {
        /** Longer than the lag seen on a Chromecast with Google TV (about 5 s), short of a real loss. */
        const val MAX_CLOCK_LAG_MS = 10_000L
    }
}
