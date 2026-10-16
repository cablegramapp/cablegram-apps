package app.cablegram.phone

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SubtitlePreviewState(
    val ready: Boolean = false,
    val durationMs: Int = 0,
    val positionMs: Int = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val seeking: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val error: String? = null,
)

/** One dialog's input and player. Every MediaPlayer call, including callbacks, stays on its Looper. */
internal class SubtitlePreviewPlayer(private val input: SubtitleMediaInput) : AutoCloseable {
    private val thread = HandlerThread("subtitle-preview").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean()
    private val mutableState = MutableStateFlow(SubtitlePreviewState())
    val state = mutableState.asStateFlow()
    private var player: MediaPlayer? = null
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var priming = false

    private val poll = object : Runnable {
        override fun run() {
            val mp = player ?: return
            if (!mutableState.value.ready) return
            runCatching {
                mutableState.value = mutableState.value.copy(positionMs = mp.currentPosition, playing = mp.isPlaying)
            }.onFailure { fail(mp) }
            if (player === mp) handler.postDelayed(this, 150)
        }
    }

    fun attach(texture: SurfaceTexture) {
        if (closed.get()) return
        handler.post {
            if (closed.get()) return@post
            releasePlayer()
            this.texture = texture
            mutableState.value = SubtitlePreviewState()
            val mp = MediaPlayer()
            player = mp
            runCatching {
                surface = Surface(texture).also(mp::setSurface)
                mp.setOnPreparedListener {
                    if (player !== it || closed.get()) return@setOnPreparedListener
                    runCatching {
                        LocalSubtitleAnalysis.selectPlayableAudio(it, input)
                        mutableState.value = mutableState.value.copy(durationMs = it.duration, videoWidth = it.videoWidth, videoHeight = it.videoHeight)
                        // A prepared player can expose an incomplete first buffer. Decode silently until
                        // the renderer confirms a real frame, then leave the preview paused for the user.
                        priming = true
                        it.setVolume(0f, 0f)
                        it.start()
                        handler.postDelayed({ if (player === mp && priming) fail(mp) }, 10_000)
                    }.onFailure { fail(mp) }
                }
                mp.setOnSeekCompleteListener {
                    if (player === it) mutableState.value = mutableState.value.copy(seeking = false, positionMs = it.currentPosition)
                }
                mp.setOnInfoListener { current, what, _ ->
                    if (player === current) {
                        when (what) {
                            MediaPlayer.MEDIA_INFO_BUFFERING_START -> mutableState.value = mutableState.value.copy(buffering = true)
                            MediaPlayer.MEDIA_INFO_BUFFERING_END ->
                                mutableState.value = mutableState.value.copy(buffering = false)
                            MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                                if (priming) {
                                    priming = false
                                    current.pause()
                                    current.setVolume(1f, 1f)
                                    mutableState.value = mutableState.value.copy(ready = true, positionMs = current.currentPosition, buffering = false)
                                    handler.post(poll)
                                }
                            }
                        }
                    }
                    false
                }
                mp.setOnVideoSizeChangedListener { current, width, height ->
                    if (player === current) mutableState.value = mutableState.value.copy(videoWidth = width, videoHeight = height)
                }
                mp.setOnCompletionListener {
                    if (player === it) mutableState.value = mutableState.value.copy(playing = false, positionMs = it.currentPosition)
                }
                mp.setOnErrorListener { current, _, _ -> fail(current); true }
                input.attach(mp)
                mp.prepareAsync()
            }.onFailure { fail(mp) }
        }
    }

    /** TextureView returns false from onSurfaceTextureDestroyed; release its texture after the decoder. */
    fun detach(texture: SurfaceTexture) {
        if (!handler.post {
            if (this.texture === texture) {
                releasePlayer()
                this.texture = null
                mutableState.value = SubtitlePreviewState()
            }
            texture.release()
        }) texture.release()
    }

    fun togglePlayback() = command { mp ->
        if (mp.isPlaying) mp.pause() else mp.start()
        mutableState.value = mutableState.value.copy(playing = mp.isPlaying)
    }

    fun seek(ms: Int) = command { mp ->
        val target = ms.coerceIn(0, maxOf(mutableState.value.durationMs, 1))
        mutableState.value = mutableState.value.copy(seeking = true, positionMs = target)
        mp.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    private fun command(action: (MediaPlayer) -> Unit) {
        if (closed.get()) return
        handler.post {
            val mp = player ?: return@post
            if (!mutableState.value.ready || closed.get()) return@post
            runCatching { action(mp) }.onFailure { fail(mp) }
        }
    }

    private fun fail(mp: MediaPlayer) {
        // A late callback from a destroyed surface must never release its replacement.
        if (player !== mp) return
        releasePlayer()
        mutableState.value = SubtitlePreviewState(error = "This video can't be previewed right now, but you can still adjust the timing.")
    }

    private fun releasePlayer() {
        handler.removeCallbacks(poll)
        priming = false
        val old = player
        player = null
        runCatching { old?.release() }
        surface?.release()
        surface = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            releasePlayer()
            runCatching { input.close() }
            thread.quitSafely()
        }
    }
}
