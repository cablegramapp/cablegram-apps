package app.cablegram.data

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

data class PlaybackReport(
    val state: String,
    val videoId: String?,
    val originId: String?,
    val positionMs: Long?,
    val seq: Long,
)

/**
 * CAB-57: tells the server what the player is doing, so the phone's remote follows a title that ends, stops or is
 * paused on the TV instead of showing the last command it sent. A change is sent at once; while a state lasts, the
 * position is refreshed every [refreshMs]. Reports go out one at a time and only the newest waiting one is sent.
 */
class PlaybackStateReporter(
    scope: CoroutineScope,
    private val send: suspend (PlaybackReport) -> Unit,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val refreshMs: Long = REFRESH_MS,
    // Starts at the wall clock, so a TV that restarted still reports above what the server kept from before.
    private var seq: Long = System.currentTimeMillis(),
) {
    private var last: PlaybackReport? = null
    private var lastQueuedAt = 0L
    private val pending = Channel<PlaybackReport>(Channel.CONFLATED)

    init {
        scope.launch { for (report in pending) runCatching { send(report) } }
    }

    fun playing(videoId: String, originId: String?, positionMs: Long) = report("playing", videoId, originId, positionMs)
    fun paused(videoId: String, originId: String?, positionMs: Long) = report("paused", videoId, originId, positionMs)
    fun stopped() = report("stopped", null, null, null)

    private fun report(state: String, videoId: String?, originId: String?, positionMs: Long?) {
        val previous = last
        val changed = previous == null || previous.state != state || previous.videoId != videoId
        if (!changed && (state == "stopped" || nowMs() - lastQueuedAt < refreshMs)) return
        val next = PlaybackReport(state, videoId, originId, positionMs, ++seq)
        last = next
        lastQueuedAt = nowMs()
        pending.trySend(next)
    }

    companion object {
        const val REFRESH_MS = 15_000L
    }
}
