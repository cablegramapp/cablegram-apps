package app.cablegram.player

/** Fewer pictures than this per second, with more dropped than shown, means the screen is effectively black. */
private const val STRUGGLING_PICTURES_PER_SECOND = 2.0
/** Back to this many pictures per second, dropping no more than it shows, and the picture is back. */
private const val RECOVERED_PICTURES_PER_SECOND = 8.0

/**
 * Notices a decoder that can't keep up: the sound plays on, but nearly every picture arrives too late and is dropped,
 * so the screen stays black. Seen with AV1, which a Chromecast with Google TV decodes in software, while its memory was
 * short. Fed VLC's picture counters about once a second while playback runs normally (not paused, held or seeking).
 */
class DecodeHealth(private val windowMs: Long = 5_000) {
    var struggling = false
        private set
    private var windowStartMs = -1L
    private var shownAtStart = 0
    private var droppedAtStart = 0

    /** Playback stopped or was held: the next sample starts a new window. [struggling] stays until a window clears it. */
    fun pause() { windowStartMs = -1L }

    /** A new video: nothing is known about it yet. */
    fun reset() {
        pause()
        struggling = false
    }

    /** One reading of VLC's displayed and lost picture counters. Returns true when [struggling] changed. */
    fun sample(nowMs: Long, displayed: Int, lost: Int): Boolean {
        // VLC's counters start over with each media; a smaller value is a new count, not a negative one.
        if (windowStartMs < 0 || displayed < shownAtStart || lost < droppedAtStart) {
            startWindow(nowMs, displayed, lost)
            return false
        }
        val elapsed = nowMs - windowStartMs
        if (elapsed < windowMs) return false
        val shown = displayed - shownAtStart
        val dropped = lost - droppedAtStart
        val perSecond = shown * 1_000.0 / elapsed
        val next = if (struggling) {
            !(perSecond >= RECOVERED_PICTURES_PER_SECOND && dropped <= shown)
        } else {
            perSecond < STRUGGLING_PICTURES_PER_SECOND && dropped > shown
        }
        startWindow(nowMs, displayed, lost)
        if (next == struggling) return false
        struggling = next
        return true
    }

    private fun startWindow(nowMs: Long, displayed: Int, lost: Int) {
        windowStartMs = nowMs
        shownAtStart = displayed
        droppedAtStart = lost
    }
}
