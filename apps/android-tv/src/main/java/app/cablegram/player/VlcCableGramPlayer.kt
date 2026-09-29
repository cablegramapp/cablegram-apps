package app.cablegram.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import app.cablegram.BuildConfig

/**
 * libvlc-android only supports a single [LibVLC] instance per process — creating and releasing
 * it repeatedly leaves native singletons (audio output, vout, media discovery) in a bad state,
 * which then breaks every subsequent playback. Create it once and never release it here.
 */
object VlcEngine {
    private var instance: LibVLC? = null

    fun get(context: Context): LibVLC = instance ?: LibVLC(
        context.applicationContext,
        arrayListOf<String>().apply {
            addAll(listOf(
                "--ipv4",
                "--no-sub-autodetect-file",
                "--avcodec-hw=mediacodec",
                "--rate=1",
                "--network-caching=1500",
            ))
            if (BuildConfig.DEBUG) add("--verbose=2")
        },
    ).also { instance = it }
}

private const val SETTLE_TICK_MS = 250L
/** Playback stays held at least this long after a seek, so the first pictures are not gray. */
private const val SETTLE_MIN_HOLD_MS = 700L
/** Waiting for the stream to buffer again is never worth more than this. */
private const val SETTLE_MAX_HOLD_MS = 15_000L
/** The spinner never stays longer than this, even when the decoder never becomes clean. */
private const val SETTLE_MAX_TOTAL_MS = 22_000L
private const val SETTLE_SAMPLE_MS = 1_000L
/** Pictures that must be displayed in a clean sample (about a third of a second of film). */
private const val SETTLE_MIN_PICTURES = 8

class VlcCableGramPlayer(context: Context) : CableGramPlayer {
    private val libVlc = VlcEngine.get(context)
    private val mediaPlayer = MediaPlayer(libVlc)
    private val videoLayout = VLCVideoLayout(context)
    private val eventFlow = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    private var availableAudioTracks: List<PlayerTrack> = emptyList()
    private var availableSubtitleTracks: List<PlayerTrack> = emptyList()
    private var selectedAudioTrackId: Int? = null
    private var requestedSubtitleTrackId: Int? = null
    private var currentUrl: String? = null
    private var currentHeaders: Map<String, String> = emptyMap()
    private var pendingSeekMs: Long? = null
    private var startedOnce = false
    private var viewsAttached = false
    private var playWhenAttached = false
    private var resumeAfterSeek = true
    private var playbackRate = 1.0f
    private var aspectRatioMode = PlayerAspectRatio.FIT
    private var audioDelayMs = 0L
    private var subtitleDelayMs = 0L

    // ---- Settling after a seek: hold playback until the picture is clean, so no gray or frozen frames show ----
    private val handler = Handler(Looper.getMainLooper())
    private var settling = false
    private var settleStartedAt = 0L
    private var settleResume = false
    private var settleResumed = false
    private var settleBuffered = false
    private var settleSampleAt = 0L
    private var settleLost = 0
    private var settleCorrupt = 0
    private var settleShown = 0
    private var settleOnFirstPlaying = false
    private val settleTick = object : Runnable {
        override fun run() { settleStep() }
    }

    init {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Buffering -> {
                    val percent = event.buffering.coerceIn(0f, 100f)
                    if (settling && percent >= 100f) settleBuffered = true
                    eventFlow.tryEmit(PlayerEvent.Buffering(percent))
                }
                MediaPlayer.Event.Playing -> {
                    startedOnce = true
                    restorePlaybackSettings()
                    restoreTrackSelections()
                    refreshTracks()
                    flushPendingSeek()
                    eventFlow.tryEmit(PlayerEvent.IsPlayingChanged(true))
                    // Resuming mid-film after a source switch starts on a non-keyframe just like a seek does.
                    if (settleOnFirstPlaying) {
                        settleOnFirstPlaying = false
                        beginSettle(resumePlaying = false)
                    }
                }
                MediaPlayer.Event.SeekableChanged -> flushPendingSeek()
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted -> refreshTracks()
                // While the player holds playback on purpose, the screen must not show a paused state.
                MediaPlayer.Event.Paused -> if (!settling) eventFlow.tryEmit(PlayerEvent.IsPlayingChanged(false))
                MediaPlayer.Event.Stopped -> {
                    endSettle()
                    eventFlow.tryEmit(PlayerEvent.IsPlayingChanged(false))
                }
                MediaPlayer.Event.EndReached -> eventFlow.tryEmit(PlayerEvent.Ended)
                MediaPlayer.Event.EncounteredError -> eventFlow.tryEmit(
                    PlayerEvent.Fatal("VLC could not open the stream. See logcat (tag: VLC) for details."),
                )
            }
        }
        videoLayout.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.post { ensureViewsAttached() }
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (!viewsAttached) return
                viewsAttached = false
                mediaPlayer.detachViews()
            }
        })
    }

    override val positionMs get() = pendingSeekMs ?: mediaPlayer.time
    override val durationMs get() = mediaPlayer.length.takeIf { it > 0 }
    override val isPlaying get() = mediaPlayer.isPlaying
    override var volume: Float get() = mediaPlayer.volume / 100f; set(value) { mediaPlayer.setVolume((value.coerceIn(0f, 1f) * 100).toInt()) }
    override val audioTracks get() = availableAudioTracks
    override val subtitleTracks get() = availableSubtitleTracks
    override val activeSubtitleTrackId: String?
        get() = mediaPlayer.spuTrack.takeIf { it >= 0 }?.toString()
    override fun load(source: PlayerSource) {
        currentUrl = source.url
        currentHeaders = source.headers
        pendingSeekMs = null
        startedOnce = false
        resumeAfterSeek = true
        handler.removeCallbacks(settleTick)
        settling = false
        settleOnFirstPlaying = source.startPositionMs > 0
        attachMedia(source.url, source.startPositionMs, source.headers)
        if (viewsAttached) mediaPlayer.play() else playWhenAttached = true
    }
    override fun play() {
        // The viewer asked to play during the post-seek hold: stop holding and play now.
        if (settling) { settleResume = true; settleResumed = true; endSettle() }
        mediaPlayer.play()
    }

    override fun pause() {
        val wasSettling = settling
        if (wasSettling) {
            // Review fix 8: the viewer paused during the hold. It must not be resumed on their behalf, and the
            // screen must show paused. VLC sends no Paused event for a player the hold had already paused.
            settleResume = false
            settleResumed = true
            endSettle()
        }
        mediaPlayer.pause()
        if (wasSettling) eventFlow.tryEmit(PlayerEvent.IsPlayingChanged(false))
    }
    override fun seekTo(positionMs: Long) {
        // A user seek keeps the current play/pause state; only the start-up resume seek autoplays.
        if (startedOnce) resumeAfterSeek = mediaPlayer.isPlaying
        pendingSeekMs = positionMs.coerceAtLeast(0)
        if (!flushPendingSeek()) {
            val url = currentUrl ?: return
            if (startedOnce && !mediaPlayer.isSeekable) {
                val restartAt = pendingSeekMs ?: 0L
                pendingSeekMs = null
                attachMedia(url, restartAt, currentHeaders)
                if (viewsAttached) mediaPlayer.play() else playWhenAttached = true
            }
        }
    }
    override fun selectSubtitle(trackId: String?) {
        requestedSubtitleTrackId = trackId?.toIntOrNull()
        mediaPlayer.setSpuTrack(requestedSubtitleTrackId ?: -1)
    }
    override fun selectAudio(trackId: String?) {
        selectedAudioTrackId = trackId?.toIntOrNull()
        selectedAudioTrackId?.let(mediaPlayer::setAudioTrack)
    }

    override fun setPlaybackSpeed(rate: Float) {
        playbackRate = rate.coerceIn(0.25f, 4f)
        mediaPlayer.rate = playbackRate
    }

    override fun setAspectRatio(mode: PlayerAspectRatio) {
        aspectRatioMode = mode
        applyAspectRatio()
    }

    override fun setAudioDelayMs(delayMs: Long) {
        audioDelayMs = delayMs
        mediaPlayer.audioDelay = delayMs * 1_000L
    }

    override fun setSubtitleDelayMs(delayMs: Long) {
        subtitleDelayMs = delayMs
        mediaPlayer.spuDelay = delayMs * 1_000L
    }
    override fun events(): Flow<PlayerEvent> = eventFlow
    override fun release() {
        handler.removeCallbacks(settleTick)
        settling = false
        playWhenAttached = false
        viewsAttached = false
        mediaPlayer.stop()
        mediaPlayer.detachViews()
        mediaPlayer.release()
    }

    private fun ensureViewsAttached() {
        if (viewsAttached || !videoLayout.isAttachedToWindow) return
        if (videoLayout.width <= 0 || videoLayout.height <= 0) {
            videoLayout.post { ensureViewsAttached() }
            return
        }
        // TextureView (last arg true) is required inside Compose: SurfaceView is a separate
        // window and often leaves the first decoded frame stuck while audio keeps running.
        mediaPlayer.attachViews(videoLayout, null, true, true)
        viewsAttached = true
        applyAspectRatio()
        if (playWhenAttached) {
            playWhenAttached = false
            mediaPlayer.play()
        }
    }

    private fun attachMedia(url: String, startPositionMs: Long, headers: Map<String, String>) {
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=1500")
            addOption(":http-user-agent=Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Cablegram/1.0")
            headers.entries
                .filter { (name, value) -> name.matches(Regex("[A-Za-z0-9-]+")) && !value.contains('\n') && !value.contains('\r') }
                .forEach { (name, value) ->
                    when (name.lowercase()) {
                        "referer" -> addOption(":http-referrer=$value")
                        "user-agent" -> addOption(":http-user-agent=$value")
                        else -> addOption(":http-header=$name: $value")
                    }
                }
            addOption(":http-reconnect")
            if (startPositionMs > 0) addOption(":start-time=${startPositionMs / 1000.0}")
        }
        mediaPlayer.media = media
        media.release()
        restorePlaybackSettings()
    }

    private fun flushPendingSeek(): Boolean {
        val target = pendingSeekMs ?: return true
        if (!mediaPlayer.isSeekable) return false
        val length = mediaPlayer.length
        pendingSeekMs = null
        if (length > 0L) {
            mediaPlayer.position = (target.toFloat() / length.toFloat()).coerceIn(0f, 1f)
        } else {
            mediaPlayer.time = target
        }
        if (resumeAfterSeek && !mediaPlayer.isPlaying) mediaPlayer.play()
        // Only a seek during playback: the first, start-up seek is not followed by a hold.
        if (startedOnce) beginSettle(resumePlaying = resumeAfterSeek)
        resumeAfterSeek = true
        return true
    }

    /**
     * After a seek the decoder often starts between keyframes: pictures are frozen or gray until the next
     * keyframe, and a decoder that cannot keep up drops frames and breaks the ones after them. So playback is
     * held, and the screen shows a spinner, until the stream has buffered again and, once playing, a full second
     * passes with pictures displayed and none lost or corrupt (or a time limit is reached).
     */
    private fun beginSettle(resumePlaying: Boolean) {
        handler.removeCallbacks(settleTick)
        settling = true
        settleStartedAt = SystemClock.elapsedRealtime()
        settleResume = resumePlaying
        settleResumed = !resumePlaying // a paused player is not resumed, only waited for
        settleBuffered = false
        settleSampleAt = 0L
        if (resumePlaying && mediaPlayer.isPlaying) mediaPlayer.pause()
        eventFlow.tryEmit(PlayerEvent.Settling(true))
        handler.postDelayed(settleTick, SETTLE_TICK_MS)
    }

    private fun settleStep() {
        if (!settling) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - settleStartedAt
        if (!settleResume) {
            // The viewer had paused: nothing plays, so wait only for the seek's buffering to finish.
            if ((settleBuffered && elapsed >= SETTLE_MIN_HOLD_MS) || elapsed >= SETTLE_MAX_HOLD_MS) { endSettle(); return }
        } else if (!settleResumed) {
            // Holding: play again once buffered (and briefly settled), or when waiting is no use.
            if ((settleBuffered && elapsed >= SETTLE_MIN_HOLD_MS) || elapsed >= SETTLE_MAX_HOLD_MS) {
                settleResumed = true
                mediaPlayer.play()
            }
        } else if (mediaPlayer.isPlaying) {
            val stats = runCatching { mediaPlayer.media?.stats }.getOrNull()
            if (stats != null) {
                if (settleSampleAt == 0L) {
                    settleSampleAt = now; settleLost = stats.lostPictures; settleCorrupt = stats.demuxCorrupted; settleShown = stats.displayedPictures
                } else if (now - settleSampleAt >= SETTLE_SAMPLE_MS) {
                    val clean = stats.lostPictures == settleLost && stats.demuxCorrupted == settleCorrupt &&
                        stats.displayedPictures - settleShown >= SETTLE_MIN_PICTURES
                    if (clean) { endSettle(); return }
                    settleSampleAt = now; settleLost = stats.lostPictures; settleCorrupt = stats.demuxCorrupted; settleShown = stats.displayedPictures
                }
            } else if (elapsed >= SETTLE_MIN_HOLD_MS + SETTLE_SAMPLE_MS) {
                endSettle(); return
            }
        }
        if (elapsed >= SETTLE_MAX_TOTAL_MS) { endSettle(); return }
        handler.postDelayed(settleTick, SETTLE_TICK_MS)
    }

    private fun endSettle() {
        handler.removeCallbacks(settleTick)
        if (!settling) return
        settling = false
        // Never leave the film paused by the hold itself.
        if (settleResume && !settleResumed) mediaPlayer.play()
        eventFlow.tryEmit(PlayerEvent.Settling(false))
    }

    private fun refreshTracks() {
        availableAudioTracks = mediaPlayer.audioTracks?.map { PlayerTrack(it.id, it.name ?: "Audio ${it.id}") } ?: emptyList()
        availableSubtitleTracks = mediaPlayer.spuTracks?.map { PlayerTrack(it.id, it.name ?: "Subtitle ${it.id}") } ?: emptyList()
        eventFlow.tryEmit(PlayerEvent.TracksChanged)
    }

    private fun restorePlaybackSettings() {
        mediaPlayer.rate = playbackRate
        applyAspectRatio()
        mediaPlayer.audioDelay = audioDelayMs * 1_000L
        mediaPlayer.spuDelay = subtitleDelayMs * 1_000L
    }

    private fun applyAspectRatio() {
        when (aspectRatioMode) {
            PlayerAspectRatio.FIT -> {
                mediaPlayer.aspectRatio = null
                mediaPlayer.scale = 0f
            }
            PlayerAspectRatio.FILL -> {
                mediaPlayer.aspectRatio = "${videoLayout.width.coerceAtLeast(1)}:${videoLayout.height.coerceAtLeast(1)}"
                mediaPlayer.scale = 0f
            }
            PlayerAspectRatio.CROP -> {
                mediaPlayer.aspectRatio = null
                mediaPlayer.scale = cropScale()
            }
            PlayerAspectRatio.RATIO_16_9 -> {
                mediaPlayer.aspectRatio = "16:9"
                mediaPlayer.scale = 0f
            }
            PlayerAspectRatio.RATIO_4_3 -> {
                mediaPlayer.aspectRatio = "4:3"
                mediaPlayer.scale = 0f
            }
            PlayerAspectRatio.ZOOM -> {
                mediaPlayer.aspectRatio = null
                mediaPlayer.scale = (cropScale() * 1.25f).coerceAtLeast(1.25f)
            }
        }
    }

    private fun cropScale(): Float {
        val vw = mediaPlayer.currentVideoTrack?.width?.takeIf { it > 0 } ?: videoLayout.width
        val vh = mediaPlayer.currentVideoTrack?.height?.takeIf { it > 0 } ?: videoLayout.height
        val sw = videoLayout.width.takeIf { it > 0 } ?: vw
        val sh = videoLayout.height.takeIf { it > 0 } ?: vh
        if (vw <= 0 || vh <= 0 || sw <= 0 || sh <= 0) return 1f
        val video = vw.toFloat() / vh.toFloat()
        val screen = sw.toFloat() / sh.toFloat()
        return if (screen > video) screen / video else video / screen
    }

    private fun restoreTrackSelections() {
        selectedAudioTrackId?.takeIf { selected -> mediaPlayer.audioTracks?.any { it.id == selected } == true }?.let(mediaPlayer::setAudioTrack)
        requestedSubtitleTrackId?.takeIf { selected -> mediaPlayer.spuTracks?.any { it.id == selected } == true }?.let(mediaPlayer::setSpuTrack)
    }

    @Composable override fun VideoSurface(modifier: Modifier, showController: Boolean) {
        key(mediaPlayer) {
            DisposableEffect(Unit) {
                onDispose {
                    playWhenAttached = false
                    if (viewsAttached) {
                        viewsAttached = false
                        mediaPlayer.detachViews()
                    }
                }
            }
            AndroidView(
                factory = {
                    videoLayout.layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    videoLayout
                },
                update = { ensureViewsAttached() },
                modifier = modifier,
            )
        }
    }
}
