package app.cablegram.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class PlayerSource(
    val url: String,
    val startPositionMs: Long,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Subtitles to attach as external tracks; the first is selected by default. */
    val subtitles: List<app.cablegram.data.SmartSubtitle> = emptyList(),
)

data class PlayerTrack(val id: Int, val label: String)

sealed interface PlayerEvent {
    data object Ready : PlayerEvent
    data object TracksChanged : PlayerEvent
    data class IsPlayingChanged(val isPlaying: Boolean) : PlayerEvent
    /** VLC reports this while it is waiting for more media from the stream; sent only when the level changes. */
    data class Buffering(val percent: Float) : PlayerEvent
    data object Ended : PlayerEvent
    data class Fatal(val message: String) : PlayerEvent
}

interface CableGramPlayer {
    val positionMs: Long
    val durationMs: Long?
    /** How far playback has really got; at the end this can be ahead of [positionMs] (CAB-56). */
    val playedThroughMs: Long get() = positionMs
    val isPlaying: Boolean
    var volume: Float
    val audioTracks: List<PlayerTrack>
    val subtitleTracks: List<PlayerTrack>
    val activeSubtitleTrackId: String?
    fun load(source: PlayerSource)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun selectSubtitle(trackId: String?)
    fun selectAudio(trackId: String?)
    fun setPlaybackSpeed(rate: Float)
    fun setAspectRatio(mode: PlayerAspectRatio)
    fun setAudioDelayMs(delayMs: Long)
    fun setSubtitleDelayMs(delayMs: Long)
    fun events(): Flow<PlayerEvent>
    /**
     * After a seek (or a source switch that resumes mid-film) the player holds playback until the decoder delivers
     * clean pictures again; while true the screen shows its spinner instead of gray or frozen frames. A state, not an
     * event: a burst of events must never lose the end of the hold and leave the spinner over a playing film.
     */
    val isSettling: StateFlow<Boolean>
    fun release()
    @Composable fun VideoSurface(modifier: Modifier, showController: Boolean)
}
