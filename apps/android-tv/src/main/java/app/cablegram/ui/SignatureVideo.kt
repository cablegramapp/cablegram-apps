package app.cablegram.ui

import android.net.Uri
import android.os.SystemClock
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import app.cablegram.data.SignatureClips
import kotlinx.coroutines.delay

@Composable
internal fun SignatureOpeningScreen(url: String, onFinished: () -> Unit) {
    BackHandler(onBack = onFinished)
    val focus = remember { FocusRequester() }
    LaunchedEffect(url) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(InkDeep)) {
        // The opening is the one clip that may play its sound.
        SignatureVideo(url, loop = false, muted = false, onFirstCycleFinished = onFinished, onUnavailable = onFinished)
        Text("CABLEGRAM", color = Cyan, modifier = Modifier.align(Alignment.TopStart).padding(28.dp))
        Button(onClick = onFinished, modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp).focusRequester(focus)) {
            Text("Skip")
        }
    }
}

/**
 * Native looping keeps the brand clip continuous; completion is signalled once per appearance. Clips are silent and
 * take no audio focus unless [muted] is false, and play from the copy [SignatureClips] keeps once one exists.
 */
@Composable
internal fun SignatureVideo(
    url: String?,
    loop: Boolean = true,
    muted: Boolean = true,
    onStarted: () -> Unit = {},
    onFirstCycleFinished: () -> Unit = {},
    onUnavailable: () -> Unit = {},
) {
    val started by rememberUpdatedState(onStarted)
    val finished by rememberUpdatedState(onFirstCycleFinished)
    val unavailable by rememberUpdatedState(onUnavailable)
    val source = remember(url) { url?.takeIf { it.isNotBlank() }?.let(SignatureClips::playable) }
    var viewRef by remember(url) { mutableStateOf<VideoView?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var reported by remember(url) { mutableStateOf(false) }

    // A failed or stalled decorative clip must never block opening the app or playing a ready title.
    LaunchedEffect(url) {
        var previousPosition = -1
        var lastProgressAt = SystemClock.elapsedRealtime()
        var playing = false
        while (!failed) {
            delay(100)
            val view = viewRef
            val position = view?.currentPosition ?: 0
            val now = SystemClock.elapsedRealtime()
            if (view?.isPlaying == true && position != previousPosition) {
                if (playing && position < previousPosition - 500 && !reported) {
                    reported = true
                    finished()
                }
                previousPosition = position
                lastProgressAt = now
                if (!playing) started()
                playing = true
            }
            if (now - lastProgressAt > if (playing) 15_000 else 8_000) {
                failed = true
                unavailable()
            }
        }
    }
    if (source != null && !failed) {
        key(url) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        isFocusable = false
                        isFocusableInTouchMode = false
                        setAudioFocusRequest(if (muted) android.media.AudioManager.AUDIOFOCUS_NONE else android.media.AudioManager.AUDIOFOCUS_GAIN)
                        viewRef = this
                        setOnPreparedListener { player ->
                            player.isLooping = loop
                            if (muted) player.setVolume(0f, 0f)
                            start()
                        }
                        setOnCompletionListener {
                            if (!reported) { reported = true; finished() }
                        }
                        setOnErrorListener { _, _, _ ->
                            failed = true
                            unavailable()
                            true
                        }
                        setVideoURI(Uri.parse(source))
                    }
                },
                onRelease = { it.stopPlayback() },
            )
        }
    } else {
        Box(Modifier.fillMaxSize().background(InkDeep), contentAlignment = Alignment.Center) {
            Text("CABLEGRAM", color = Cyan)
        }
    }
}
