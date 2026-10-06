package app.cablegram.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow

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
    /** VLC reports this while it is waiting for more media from the stream. */
    data class Buffering(val percent: Float) : PlayerEvent
    /**
     * After a seek (or a source switch that resumes mid-film) the player holds playback until the decoder delivers
     * clean pictures again; [active] tells the screen to show its spinner instead of gray or frozen frames.
     */
    data class Settling(val active: Boolean) : PlayerEvent
    data object Ended : PlayerEvent
    data class Fatal(val message: String) : PlayerEvent
}

interface CableGramPlayer {
    val positionMs: Long
    val durationMs: Long?
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
    fun release()
    @Composable fun VideoSurface(modifier: Modifier, showController: Boolean)
}
