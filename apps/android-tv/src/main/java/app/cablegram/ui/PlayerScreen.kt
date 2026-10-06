package app.cablegram.ui

import app.cablegram.data.DEFAULT_LOADING_VIDEOS
import androidx.compose.foundation.layout.aspectRatio
import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import app.cablegram.MainActivity
import app.cablegram.R
import app.cablegram.data.PlaybackResponse
import app.cablegram.data.playbackNeedsReload
import app.cablegram.data.TvCommand
import app.cablegram.data.validationError
import app.cablegram.player.CableGramPlayer
import app.cablegram.player.ControlsFocus
import app.cablegram.player.DpadKey
import app.cablegram.player.HudEffect
import app.cablegram.player.HudTrack
import app.cablegram.player.MenuSection
import app.cablegram.player.PlaybackHud
import app.cablegram.player.PlaybackHudContext
import app.cablegram.player.PlaybackHudState
import app.cablegram.player.PlaybackPreferencesStore
import app.cablegram.player.OverlayMenu
import app.cablegram.player.PlayerSettings
import app.cablegram.player.PlayerSettingsPreference
import app.cablegram.player.SettingsRow
import app.cablegram.player.SubtitlePreference
import app.cablegram.player.matchSubtitleTrack
import app.cablegram.player.PlayerEvent
import app.cablegram.player.PlayerSource
import app.cablegram.data.smartSubtitles
import app.cablegram.player.VlcCableGramPlayer
import app.cablegram.player.menuOptions
import app.cablegram.player.reducePlaybackHud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Composable
fun PlayerScreen(
    videoId: String,
    title: String,
    playback: PlaybackResponse,
    isLive: Boolean = false,
    durationSeconds: Int? = null,
    onState: (Int, Int?, Boolean, Int, Boolean, String) -> Unit,
    remoteCommand: TvCommand?,
    remoteTitleCommandId: String?,
    onRemoteCommandConsumed: (String, String?) -> Unit,
    onRemotePlaybackResult: (String?) -> Unit,
    onRenewPlayback: suspend () -> PlaybackResponse?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val player = remember(playback.url) { VlcCableGramPlayer(context) }
    var error by remember(playback.url) { mutableStateOf<String?>(null) }
    var lastVolume by remember { mutableFloatStateOf(1f) }
    var volumeFeedback by remember { mutableStateOf<VolumeFeedback?>(null) }
    var volumeFeedbackVersion by remember { mutableIntStateOf(0) }
    var isPlaying by remember(playback.url) { mutableStateOf(false) }
    var hasStarted by remember(playback.url) { mutableStateOf(false) }
    // After a seek the player holds playback until pictures are clean; the screen shows a spinner meanwhile.
    var settling by remember(playback.url) { mutableStateOf(false) }
    // Spec 003: a short notice when the stream moves between LAN and relay (or waits on the phone).
    var transportNotice by remember(playback.url) { mutableStateOf<String?>(null) }
    var switchingSource by remember(playback.url) { mutableStateOf(false) }
    // Shown with the Cablegram loop from the moment a path is lost until the new one plays.
    var transition by remember(playback.url) { mutableStateOf<SourceTransition?>(null) }
    var lastSourceError by remember(playback.url) { mutableStateOf<String?>(null) }
    // CAB-15: the source is opened (it passed its probe) and nothing has played yet; bounds the silent start.
    var loaded by remember(playback.url) { mutableStateOf(false) }
    var startupSwitched by remember(playback.url) { mutableStateOf(false) }
    // Retry reopens the title; it resumes where playback last moved, not at a seek target that never played.
    var retryCount by remember(playback.url) { mutableIntStateOf(0) }
    var reachedMs by remember(playback.url) { mutableLongStateOf(0L) }
    var recoveryChoice by remember(playback.url) { mutableStateOf(RecoveryChoice.RETRY) }
    LaunchedEffect(remoteTitleCommandId, hasStarted, isPlaying, error) {
        if (error != null) onRemotePlaybackResult("playback_failed")
        else if (hasStarted && isPlaying) onRemotePlaybackResult(null)
    }
    var streamHealth by remember(playback.url) { mutableStateOf(StreamHealth.GOOD) }
    val fallbackDurationMs = (durationSeconds ?: 0) * 1000L
    var currentPositionMs by remember(playback.url) { mutableLongStateOf(0L) }
    var currentDurationMs by remember(playback.url) { mutableLongStateOf(fallbackDurationMs) }
    var trackGeneration by remember(playback.url) { mutableIntStateOf(0) }
    var hud by remember(playback.url) { mutableStateOf(PlaybackHudState()) }
    var userInteractionCount by remember { mutableIntStateOf(0) }
    var selectedAudioId by remember(playback.url) { mutableStateOf<String?>(null) }
    var selectedSubtitleId by remember(playback.url) { mutableStateOf<String?>(null) }
    var subtitleChoiceApplied by remember(videoId) { mutableStateOf(false) }
    val playbackPrefs = remember { PlaybackPreferencesStore(context) }
    var playerSettings by remember { mutableStateOf(playbackPrefs.playerSettings()) }
    var activePlayback by remember(playback.url) { mutableStateOf(playback) }
    val playbackHttp = remember { OkHttpClient() }
    // A position in the last 5% (credits / an unrecorded finish) starts over instead of ending instantly.
    val resume = (playback.resumePositionSeconds ?: 0)
        .takeUnless { pos -> durationSeconds != null && durationSeconds > 0 && pos >= durationSeconds * 95 / 100 }
        ?.times(1000L) ?: 0L
    // A title started from the phone resumes directly: nobody may be holding
    // the TV remote to answer the Resume / Start over prompt.
    var start by remember(playback.url) {
        mutableStateOf<Long?>(if (resume <= 0) 0L else if (remoteTitleCommandId != null) resume else null)
    }
    val focus = remember { FocusRequester() }
    /** The live shift the phone sends while lining subtitles up (`subtitle_delay`), added to the viewer's own delay. */
    var phoneSubtitleShiftMs by remember { mutableStateOf(0L) }

    /** Re-open the title on the other path (LAN ↔ relay) at the current position. */
    suspend fun switchSource(resumeAtMs: Long? = null): Boolean {
        if (switchingSource) return false
        val next = activePlayback.fallbackUrl
            ?: activePlayback.fallbackResolver?.invoke()?.also { activePlayback = activePlayback.copy(fallbackUrl = it, fallbackResolver = null) }
            ?: return false
        switchingSource = true
        transition = transitionTo(next)
        try {
            val current = activePlayback.url
            val failure = reachableSource(playbackHttp, next, activePlayback.fallbackHeaders, quick = !isRelayUrl(next)) { transportNotice = it }
            if (failure != null) {
                lastSourceError = failure
                transition = null
                return false
            }
            val resumeAt = ((resumeAtMs ?: player.positionMs) - 2_000).coerceAtLeast(0)
            // The two paths swap places, each with its own headers: a cloud token never goes to the other path.
            activePlayback = activePlayback.copy(
                url = next, headers = activePlayback.fallbackHeaders, fallbackUrl = current, fallbackHeaders = activePlayback.headers,
            )
            player.load(PlayerSource(next, resumeAt, activePlayback.mimeType, activePlayback.headers, activePlayback.smartSubtitles()))
            transportNotice = transportNoticeFor(next)
            Log.i(PLAYBACK_LOG_TAG, "Switched source to ${if (isRelayUrl(next)) "relay" else "LAN"} at ${resumeAt / 1000}s")
            return true
        } finally {
            switchingSource = false
        }
    }

    fun exit() {
        // After a failure the player may sit on a seek target that never played; report where it really got to.
        val exitMs = if (error != null) reachedMs else player.positionMs
        if (exitMs > 0) {
            val p = (exitMs / 1000).toInt()
            val totalD = currentDurationMs.takeIf { it > 0 }?.div(1000)?.toInt()
            // R-3: final state on exit so stop/completed is persisted, not just positions.
            onState(p, totalD, false, (player.volume * 100).toInt(), player.volume == 0f, "libvlc")
        }
        onBack()
    }

    fun hudContext(): PlaybackHudContext {
        val audio = player.audioTracks.map { HudTrack(it.id.toString(), it.label) }
        val subs = player.subtitleTracks.map { HudTrack(it.id.toString(), it.label) }
        return PlaybackHudContext(
            isPlaying = player.isPlaying,
            positionMs = player.positionMs,
            durationMs = (player.durationMs ?: currentDurationMs).coerceAtLeast(0L),
            audioTracks = audio,
            subtitleTracks = subs,
            selectedAudioId = selectedAudioId,
            selectedSubtitleId = selectedSubtitleId,
            playbackSpeed = playerSettings.playbackSpeed,
            aspectRatio = playerSettings.aspectRatio,
            audioDelayMs = playerSettings.audioDelayMs,
            subtitleDelayMs = playerSettings.subtitleDelayMs,
        )
    }

    fun applySavedSubtitleIfNeeded() {
        if (subtitleChoiceApplied) return
        val saved = playbackPrefs.subtitle(videoId)
        // A Cablegram subtitle the person just added or changed is the one to show, even over an older "Off" or
        // another track remembered for this video. Once they choose again for that subtitle, that choice is kept.
        val smart = activePlayback.smartSubtitles().firstOrNull()
        if (smart != null && saved?.smartId != smart.id) {
            val track = player.subtitleTracks.firstOrNull { it.label == smart.label } ?: return  // not listed yet: try on the next update
            selectedSubtitleId = track.id.toString()
            player.selectSubtitle(selectedSubtitleId)
            playbackPrefs.saveSubtitle(videoId, SubtitlePreference(off = false, trackId = selectedSubtitleId, label = smart.label, smartId = smart.id))
            subtitleChoiceApplied = true
            return
        }
        if (saved == null) {
            // VLC may automatically enable a default/forced embedded track. Reflect the
            // actual player selection so the subtitle menu does not incorrectly show Off.
            selectedSubtitleId = player.activeSubtitleTrackId
            return
        }
        if (saved.off) {
            selectedSubtitleId = null
            player.selectSubtitle(null)
            subtitleChoiceApplied = true
            return
        }
        if (player.subtitleTracks.isEmpty()) return
        val matched = matchSubtitleTrack(saved, player.subtitleTracks)
        if (matched != null) {
            selectedSubtitleId = matched
            player.selectSubtitle(matched)
        }
        subtitleChoiceApplied = true
    }

    fun applyEffects(effects: List<HudEffect>) {
        effects.forEach { effect ->
            when (effect) {
                HudEffect.ExitPlayer -> exit()
                HudEffect.Play -> player.play()
                HudEffect.Pause -> player.pause()
                is HudEffect.SeekBy -> {
                    val next = (player.positionMs + effect.deltaMs).coerceAtLeast(0L)
                    player.seekTo(next)
                    currentPositionMs = next
                }
                is HudEffect.SeekTo -> {
                    player.seekTo(effect.positionMs)
                    currentPositionMs = effect.positionMs
                }
                is HudEffect.SelectAudio -> {
                    selectedAudioId = effect.trackId
                    player.selectAudio(effect.trackId)
                }
                is HudEffect.SelectSubtitle -> {
                    selectedSubtitleId = effect.trackId
                    player.selectSubtitle(effect.trackId)
                    subtitleChoiceApplied = true
                    val label = player.subtitleTracks.firstOrNull { it.id.toString() == effect.trackId }?.label
                    playbackPrefs.saveSubtitle(
                        videoId,
                        SubtitlePreference(off = effect.trackId == null, trackId = effect.trackId, label = label, smartId = activePlayback.smartSubtitles().firstOrNull()?.id),
                    )
                }
                is HudEffect.SetPlaybackSpeed -> {
                    playerSettings = playerSettings.copy(playbackSpeed = effect.rate).also {
                        player.setPlaybackSpeed(it.playbackSpeed)
                        playbackPrefs.savePlayerSettings(it)
                    }
                }
                is HudEffect.SetAspectRatio -> {
                    playerSettings = playerSettings.copy(aspectRatio = effect.mode).also {
                        player.setAspectRatio(it.aspectRatio)
                        playbackPrefs.savePlayerSettings(it)
                    }
                }
                is HudEffect.SetAudioDelay -> {
                    playerSettings = playerSettings.copy(audioDelayMs = effect.delayMs).also {
                        player.setAudioDelayMs(it.audioDelayMs)
                        playbackPrefs.savePlayerSettings(it)
                    }
                }
                is HudEffect.SetSubtitleDelay -> {
                    playerSettings = playerSettings.copy(subtitleDelayMs = effect.delayMs).also {
                        player.setSubtitleDelayMs(it.subtitleDelayMs + phoneSubtitleShiftMs)
                        playbackPrefs.savePlayerSettings(it)
                    }
                }
            }
        }
    }

    fun mapDpad(keyCode: Int): DpadKey? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> DpadKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DpadKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> DpadKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> DpadKey.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> DpadKey.OK
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> DpadKey.BACK
        else -> null
    }

    fun key(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    player.play()
                    hud = hud.copy(controlsVisible = true, overlay = OverlayMenu.CLOSED)
                    userInteractionCount++
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    player.pause()
                    hud = hud.copy(controlsVisible = true, overlay = OverlayMenu.CLOSED)
                    userInteractionCount++
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (player.isPlaying) player.pause() else player.play()
                    hud = hud.copy(controlsVisible = true, overlay = OverlayMenu.CLOSED)
                    userInteractionCount++
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    player.seekTo(player.positionMs + PlaybackHud.SEEK_STEP_MS)
                    currentPositionMs = player.positionMs + PlaybackHud.SEEK_STEP_MS
                    hud = hud.copy(seekFlashDeltaMs = PlaybackHud.SEEK_STEP_MS, controlsVisible = false)
                    userInteractionCount++
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val target = PlaybackHud.seekTargetMs(player.positionMs, -PlaybackHud.SEEK_STEP_MS)
                    player.seekTo(target)
                    currentPositionMs = target
                    hud = hud.copy(seekFlashDeltaMs = -PlaybackHud.SEEK_STEP_MS, controlsVisible = false)
                    userInteractionCount++
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (event.action == KeyEvent.ACTION_DOWN) exit()
                return true
            }
        }
        val dpad = mapDpad(event.keyCode) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (error != null) {
            // The failure panel owns the remote: Retry or Back, nothing else.
            when (dpad) {
                DpadKey.LEFT, DpadKey.RIGHT, DpadKey.UP, DpadKey.DOWN -> recoveryChoice = recoveryChoice.toggled()
                DpadKey.OK -> if (recoveryChoice == RecoveryChoice.RETRY) {
                    val from = reachedMs
                    error = null; lastSourceError = null
                    hasStarted = false; isPlaying = false; settling = false; loaded = false; startupSwitched = false
                    currentPositionMs = from
                    retryCount++
                } else exit()
                DpadKey.BACK -> exit()
            }
            return true
        }
        userInteractionCount++
        val (next, effects) = reducePlaybackHud(hud, dpad, event.repeatCount, hudContext())
        hud = next
        applyEffects(effects)
        return true
    }

    // Targeting Android 16, Back is a predictive-back gesture and the remote's Back key no longer reaches
    // dispatchKeyEvent, so run it through the same layered handling (close panel, hide controls, then exit).
    BackHandler { key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)) }
    DisposableEffect(activity, start) {
        if (start != null) activity?.setPlayerKeyHandler(::key)
        onDispose { activity?.setPlayerKeyHandler(null) }
    }
    DisposableEffect(player) {
        onDispose {
            val leftAtMs = if (error != null) reachedMs else player.positionMs
            if (leftAtMs > 0) {
                val p = (leftAtMs / 1000).toInt()
                val totalD = currentDurationMs.takeIf { it > 0 }?.div(1000)?.toInt()
                onState(p, totalD, false, 0, true, "libvlc")
            }
            player.release()
        }
    }
    HoldPlaybackSession(player = player, sessionActive = start != null) {
        if (player.positionMs > 0) {
            val totalD = currentDurationMs.takeIf { it > 0 }?.div(1000)?.toInt()
            onState((player.positionMs / 1000).toInt(), totalD, false, (player.volume * 100).toInt(), player.volume == 0f, "libvlc")
        }
        isPlaying = false
        hud = hud.copy(controlsVisible = true, focus = ControlsFocus.PLAY_PAUSE, overlay = OverlayMenu.CLOSED)
    }
    LaunchedEffect(hud.seekFlashDeltaMs, userInteractionCount) {
        if (hud.seekFlashDeltaMs != null && !hud.controlsVisible) {
            delay(PlaybackHud.SEEK_FLASH_MS)
            hud = hud.copy(seekFlashDeltaMs = null)
        }
    }
    LaunchedEffect(hud.controlsVisible, hud.menuOpen, hud.focus, isPlaying, userInteractionCount) {
        if (hud.controlsVisible && isPlaying && !hud.menuOpen && hud.focus != ControlsFocus.TIMELINE) {
            delay(PlaybackHud.AUTO_HIDE_MS)
            hud = hud.copy(controlsVisible = false, previewPositionMs = null, holdMultiplier = 1)
        }
    }
    LaunchedEffect(player, start, retryCount) {
        val position = (if (retryCount > 0) reachedMs else start) ?: return@LaunchedEffect
        if (retryCount == 0) reachedMs = position // a failure before anything played still resumes from here
        if (!isLive) {
            // LAN first with a short probe when a relay path exists; otherwise the full preflight.
            val primary = checkNotNull(activePlayback.url)
            val hasFallback = activePlayback.fallbackUrl != null || activePlayback.fallbackResolver != null
            val primaryError = reachableSource(playbackHttp, primary, activePlayback.headers, quick = hasFallback) { transportNotice = it }
            if (primaryError != null) {
                val fallback = activePlayback.fallbackUrl ?: activePlayback.fallbackResolver?.invoke()
                if (fallback != null) transition = transitionTo(fallback, atStart = true)
                val fallbackError = fallback?.let {
                    reachableSource(playbackHttp, it, activePlayback.fallbackHeaders, quick = false) { notice -> transportNotice = notice }
                }
                if (fallback == null || fallbackError != null) {
                    transition = null
                    error = fallbackError ?: primaryError
                    return@LaunchedEffect
                }
                // Swap headers with the URLs, as switchSource does: a cloud token never goes to the other path.
                activePlayback = activePlayback.copy(
                    url = fallback, headers = activePlayback.fallbackHeaders, fallbackUrl = primary, fallbackHeaders = activePlayback.headers,
                    fallbackResolver = null,
                )
                transportNotice = transportNoticeFor(fallback)
            }
        }
        val url = checkNotNull(activePlayback.url)
        player.load(PlayerSource(url, position, activePlayback.mimeType, activePlayback.headers, activePlayback.smartSubtitles()))
        loaded = true
        player.setPlaybackSpeed(playerSettings.playbackSpeed)
        player.setAspectRatio(playerSettings.aspectRatio)
        player.setAudioDelayMs(playerSettings.audioDelayMs)
        player.setSubtitleDelayMs(playerSettings.subtitleDelayMs + phoneSubtitleShiftMs)
        player.events().collect { event ->
            when (event) {
                is PlayerEvent.IsPlayingChanged -> {
                    isPlaying = event.isPlaying
                    if (event.isPlaying) {
                        hasStarted = true
                        transition = null
                    }
                    applySavedSubtitleIfNeeded()
                }
                is PlayerEvent.Settling -> settling = event.active
                is PlayerEvent.Buffering -> {
                    streamHealth = when {
                        event.percent >= 100f -> StreamHealth.GOOD
                        event.percent >= 50f -> StreamHealth.OK
                        else -> StreamHealth.BAD
                    }
                }
                PlayerEvent.TracksChanged -> {
                    applySavedSubtitleIfNeeded()
                    selectedSubtitleId = player.activeSubtitleTrackId
                    trackGeneration++
                }
                is PlayerEvent.Fatal -> if (!switchSource()) error = lastSourceError ?: event.message
                PlayerEvent.Ended -> {
                    // A dropped connection also ends in EOF once VLC's read-ahead buffer drains (it
                    // does not stall). An "end" well before the duration is a lost stream: continue
                    // on the other path instead of recording the title as watched.
                    val lastPositionMs = maxOf(player.positionMs, currentPositionMs)
                    val durationMs = currentDurationMs
                    if (!isLive && durationMs > 0 && lastPositionMs < durationMs - PREMATURE_END_MARGIN_MS) {
                        Log.w(PLAYBACK_LOG_TAG, "Stream ended early at ${lastPositionMs / 1000}s of ${durationMs / 1000}s")
                        if (!switchSource(resumeAtMs = lastPositionMs)) {
                            error = lastSourceError ?: lostSourceMessage(activePlayback.url)
                        }
                        return@collect
                    }
                    // Record the title as finished and leave the player; before this the TV sat on a
                    // paused last frame, progress stayed "playing", and the title offered Resume.
                    val totalD = currentDurationMs.takeIf { it > 0 }?.div(1000)?.toInt()
                    if (!isLive && totalD != null) {
                        onState(totalD, totalD, false, (player.volume * 100).toInt(), player.volume == 0f, "libvlc")
                    }
                    onBack()
                }
                else -> Unit
            }
        }
    }
    // Spec 003: a stream that stops advancing while "playing" (Wi‑Fi gone, phone asleep) is re-opened
    // on the other path at the current position. CAB-15: this also covers a seek that never recovers (the hold
    // ends on a frozen picture); no fallback or a failed one ends in the Retry / Back panel.
    LaunchedEffect(player, start) {
        if (start == null || isLive) return@LaunchedEffect
        var lastPosition = -1L
        var timerLimit = stallSwitchSeconds(activePlayback.url)
        var timer = NoProgressTimer(timerLimit)
        val seekTimer = NoProgressTimer(SEEK_STALL_SECONDS)
        var afterHold = false
        while (true) {
            delay(1_000)
            val position = player.positionMs
            if (error != null || switchingSource || !hasStarted || settling) {
                timer.reset(); seekTimer.reset()
                if (settling) afterHold = true
                lastPosition = position // a seek target is not progress
                continue
            }
            if (stallSwitchSeconds(activePlayback.url) != timerLimit) {
                timerLimit = stallSwitchSeconds(activePlayback.url)
                timer = NoProgressTimer(timerLimit)
            }
            val stalled = streamStalled(hasStarted, settling, isPlaying, error != null, switchingSource, position, lastPosition)
            if (!stalled && position != lastPosition) { reachedMs = position; afterHold = false }
            lastPosition = position
            // After a seek the hold has already waited; a stream still frozen then gets the shorter limit.
            val limitReached = if (afterHold) seekTimer.tick(stalled) else timer.tick(stalled)
            if (limitReached) {
                afterHold = false
                if (!switchSource(resumeAtMs = reachedMs)) {
                    error = lastSourceError ?: lostSourceMessage(activePlayback.url)
                }
            }
        }
    }
    // Back to the phone over Wi-Fi: on the relay, check now and then whether the phone answers on this Wi-Fi again and
    // return to it at the current position. Otherwise a title that fell back to the relay (the phone left the Wi-Fi) stays
    // there to the end, using the relay allowance and possibly the phone's mobile data although the phone is home again.
    LaunchedEffect(player, start) {
        if (start == null || isLive) return@LaunchedEffect
        while (true) {
            delay(LAN_RETURN_CHECK_MS)
            val lan = lanReturnCandidate(activePlayback.url, activePlayback.fallbackUrl) ?: continue
            if (error != null || switchingSource || !hasStarted || settling || !isPlaying) continue
            val (code, _) = probeSource(playbackHttp, lan, activePlayback.fallbackHeaders, LAN_RETURN_PROBE_MS)
            if (code == null || code !in 200..299) continue
            // switchSource resumes two seconds early (made for a stalled stream); a healthy stream continues where it is.
            if (lanReturnCandidate(activePlayback.url, activePlayback.fallbackUrl) == lan && !switchingSource) {
                switchSource(resumeAtMs = player.positionMs + 2_000)
            }
        }
    }
    // CAB-15: nothing played within the startup deadline. Try the other path once, then stop and let the viewer decide.
    LaunchedEffect(player, start) {
        if (start == null || isLive) return@LaunchedEffect
        val timer = NoProgressTimer(STARTUP_DEADLINE_SECONDS)
        while (true) {
            delay(1_000)
            if (!timer.tick(startupWaiting(loaded, hasStarted, error != null, switchingSource))) continue
            Log.w(PLAYBACK_LOG_TAG, "Nothing played within ${STARTUP_DEADLINE_SECONDS}s of opening the source")
            if (!startupSwitched) {
                startupSwitched = true
                if (switchSource()) continue
            }
            error = lastSourceError ?: "Playback didn't start. Check that your phone is online with Cablegram open, then try again."
        }
    }
    // CAB-15: a failed playback stops: no sound or picture left running, and the screen no longer reports "playing".
    LaunchedEffect(error) {
        if (error == null) return@LaunchedEffect
        player.pause()
        isPlaying = false
        transition = null
        recoveryChoice = RecoveryChoice.RETRY
    }
    LaunchedEffect(transition) {
        val shown = transition ?: return@LaunchedEffect
        delay(45_000)
        if (transition == shown) transition = null
    }
    LaunchedEffect(transportNotice) {
        val notice = transportNotice ?: return@LaunchedEffect
        if (notice == WAITING_FOR_PHONE_NOTICE) return@LaunchedEffect
        delay(6_000)
        if (transportNotice == notice) transportNotice = null
    }
    LaunchedEffect(player, start, activePlayback.expiresAt) {
        if (start == null) return@LaunchedEffect
        while (true) {
            delay(60_000)
            val expiresAt = activePlayback.expiresAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: continue
            if (expiresAt - System.currentTimeMillis() < 120_000) {
                val renewed = onRenewPlayback() ?: continue
                if (renewed.url != null && !playbackNeedsReload(activePlayback, renewed)) {
                    // The same link and header, only a later expiry: no reload, just stop asking again every minute.
                    Log.i(PLAYBACK_LOG_TAG, "Playback renewed without a reload, expires ${renewed.expiresAt}")
                    activePlayback = activePlayback.copy(expiresAt = renewed.expiresAt)
                } else if (renewed.url != null) {
                    val renewalError = preflightPlaybackUrl(playbackHttp, renewed.url, renewed.headers)
                    if (renewalError != null) {
                        error = renewalError
                        continue
                    }
                    Log.i(PLAYBACK_LOG_TAG, "Playback renewed, reloading the player at ${player.positionMs / 1000}s")
                    val playing = player.isPlaying
                    activePlayback = renewed
                    player.load(PlayerSource(renewed.url, player.positionMs, renewed.mimeType, renewed.headers, renewed.smartSubtitles()))
                    if (!playing) player.pause()
                }
            }
        }
    }
    LaunchedEffect(player, start) {
        if (start == null) {
            // Let the Resume button start observing focus first, or it is
            // focused without drawing its highlight.
            androidx.compose.runtime.withFrameNanos { }
            runCatching { focus.requestFocus() }
            return@LaunchedEffect
        }
        var tick = 0
        while (true) {
            delay(1_000)
            val newPos = player.positionMs
            if (newPos == currentPositionMs && !player.isPlaying) continue
            currentPositionMs = newPos
            val d = player.durationMs ?: 0L
            currentDurationMs = if (d > 0L) d else fallbackDurationMs
            tick++
            if (tick >= 6) {
                tick = 0
                val p = (player.positionMs / 1000).toInt()
                val totalD = currentDurationMs.takeIf { it > 0 }?.div(1000)?.toInt()
                // One write per tick: a parallel "playing"-only progress write used to race this
                // state write and could overwrite "paused"/"completed" on the server.
                onState(p, totalD, player.isPlaying, (player.volume * 100).toInt(), player.volume == 0f, "libvlc")
            }
        }
    }
    LaunchedEffect(remoteCommand) {
        remoteCommand?.let { c ->
            val invalid = c.validationError()
            if (invalid != null) {
                onRemoteCommandConsumed(c.id, invalid)
                return@LaunchedEffect
            }
            val outcome = runCatching {
            when (c.command) {
                "pause" -> {
                    player.pause()
                    hud = hud.copy(controlsVisible = true, overlay = OverlayMenu.CLOSED)
                    userInteractionCount++
                }
                "play" -> {
                    player.play()
                    hud = hud.copy(controlsVisible = true, overlay = OverlayMenu.CLOSED)
                    userInteractionCount++
                }
                "stop" -> exit()
                "seek" -> {
                    val deltaMs = (c.payload["seconds"]?.jsonPrimitive?.intOrNull ?: c.payload["value"]?.jsonPrimitive?.intOrNull ?: 0) * 1000L
                    player.seekTo(PlaybackHud.seekTargetMs(player.positionMs, deltaMs))
                    // Same feedback as a D-pad seek.
                    if (deltaMs != 0L) {
                        hud = hud.copy(seekFlashDeltaMs = deltaMs, controlsVisible = false)
                        userInteractionCount++
                    }
                }
                "volume" -> c.payload["level"]?.jsonPrimitive?.intOrNull?.let {
                    val level = it.coerceIn(0, 100)
                    lastVolume = level / 100f
                    player.volume = lastVolume
                    volumeFeedback = VolumeFeedback(level = level, muted = level == 0)
                    volumeFeedbackVersion++
                }
                "subtitle_delay" -> c.payload["delay_ms"]?.jsonPrimitive?.longOrNull?.let { ms ->
                    // Applied to what is playing now only, on top of the viewer's own subtitle delay; the saved timing
                    // is changed from the phone.
                    phoneSubtitleShiftMs = ms
                    player.setSubtitleDelayMs(playerSettings.subtitleDelayMs + ms)
                    transportNotice = "Subtitles ${if (ms < 0) "−" else "+"}${"%.2f".format(kotlin.math.abs(ms) / 1000.0)} s"
                }
                "mute" -> c.payload["muted"]?.jsonPrimitive?.booleanOrNull?.let {
                    if (it) {
                        lastVolume = player.volume.takeIf { v -> v > 0 } ?: lastVolume
                        player.volume = 0f
                    } else {
                        player.volume = lastVolume
                    }
                    volumeFeedback = VolumeFeedback(
                        level = (player.volume * 100).toInt().coerceIn(0, 100),
                        muted = it,
                    )
                    volumeFeedbackVersion++
                }
                "move", "select" -> {
                    val dpad = when {
                        c.command == "select" -> DpadKey.OK
                        else -> when (c.payload["direction"]?.jsonPrimitive?.content) {
                            "up" -> DpadKey.UP
                            "down" -> DpadKey.DOWN
                            "left" -> DpadKey.LEFT
                            "right" -> DpadKey.RIGHT
                            else -> null
                        }
                    }
                    if (dpad != null) {
                        userInteractionCount++
                        val (next, effects) = reducePlaybackHud(hud, dpad, 0, hudContext())
                        hud = next
                        applyEffects(effects)
                    }
                }
                "next" -> {
                    player.seekTo(player.positionMs + PlaybackHud.SEEK_STEP_MS)
                    hud = hud.copy(seekFlashDeltaMs = PlaybackHud.SEEK_STEP_MS, controlsVisible = false)
                    userInteractionCount++
                }
                "previous" -> {
                    player.seekTo(PlaybackHud.seekTargetMs(player.positionMs, -PlaybackHud.SEEK_STEP_MS))
                    hud = hud.copy(seekFlashDeltaMs = -PlaybackHud.SEEK_STEP_MS, controlsVisible = false)
                    userInteractionCount++
                }
                else -> error("Unsupported command")
            }
            }
            onRemoteCommandConsumed(c.id, if (outcome.isSuccess) null else "execution_failed")
        }
    }
    LaunchedEffect(volumeFeedbackVersion) {
        if (volumeFeedbackVersion == 0) return@LaunchedEffect
        delay(VOLUME_FEEDBACK_DURATION_MS)
        volumeFeedback = null
    }

    val displayPosition = hud.previewPositionMs ?: currentPositionMs
    val progressFraction = if (currentDurationMs > 0L) {
        (displayPosition.toFloat() / currentDurationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        player.VideoSurface(Modifier.fillMaxSize(), start != null)
        if (start != null) {
            // Keep the player clean during playback. The brand, route, and live quality
            // information are visible only while playback is starting or the user is interacting.
            AnimatedVisibility(
                visible = !hasStarted || hud.controlsVisible || hud.seekFlashDeltaMs != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
                Column(
                    Modifier.padding(start = 28.dp, top = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.cablegram_brand_mark),
                        contentDescription = "Cablegram",
                        modifier = Modifier.height(44.dp),
                        alpha = 0.88f,
                    )
                    // Only once something plays: before that the URL is just the path being tried.
                    // While a switch is under way the badges would describe the path being left (it said "Local LAN" while
                    // the TV waited for mobile-data consent on the relay); the switch overlay explains instead.
                    if (hasStarted && !switchingSource) TransportBadge(transport = classifyTransport(activePlayback.url, isLive))
                    if (error == null && hasStarted && !switchingSource) StreamHealthBadge(streamHealth)
                }
            }
            AnimatedVisibility(
                visible = hud.seekFlashDeltaMs != null && !hud.controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                val delta = hud.seekFlashDeltaMs ?: 0L
                val seconds = (kotlin.math.abs(delta) / 1000L).toInt()
                Text(
                    text = if (delta < 0) "↶  ${seconds} sec" else "${seconds} sec  ↷",
                    color = Paper,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(Color(0xCC10120F), RoundedCornerShape(12.dp))
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                )
            }

            AnimatedVisibility(
                visible = volumeFeedback != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                volumeFeedback?.let { VolumeFeedbackOverlay(it) }
            }

            AnimatedVisibility(
                visible = hud.controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xDD000000), Color(0xFA000000)),
                            ),
                        )
                        .padding(horizontal = 48.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, color = Paper, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (!isPlaying) {
                            Text("PAUSED", color = Acid, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 20.dp)) {
                        TransportButton(
                            label = "◀ 10s",
                            focused = hud.focus == ControlsFocus.REWIND && !hud.menuOpen,
                        )
                        TransportButton(
                            label = if (isPlaying) "❚❚" else "▶",
                            focused = hud.focus == ControlsFocus.PLAY_PAUSE && !hud.menuOpen,
                        )
                        TransportButton(
                            label = "10s ▶",
                            focused = hud.focus == ControlsFocus.FAST_FORWARD && !hud.menuOpen,
                        )
                    }

                    if (hud.timelineActive) {
                        TimelinePreview(
                            positionMs = displayPosition,
                            holdMultiplier = hud.holdMultiplier,
                            scrubbingBack = (hud.previewPositionMs ?: currentPositionMs) < currentPositionMs,
                        )
                    }

                    TimelineBar(
                        progressFraction = progressFraction,
                        focused = hud.focus == ControlsFocus.TIMELINE && !hud.menuOpen,
                    )
                    Text(
                        text = "${formatTime(displayPosition)} / ${if (currentDurationMs > 0L) formatTime(currentDurationMs) else "--:--"}",
                        color = Paper,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TransportButton(
                            label = "Audio / Subtitles",
                            focused = hud.focus == ControlsFocus.AUDIO_SUBTITLES && !hud.menuOpen,
                        )
                        TransportButton(
                            label = "Player Settings",
                            focused = hud.focus == ControlsFocus.PLAYER_SETTINGS && !hud.menuOpen,
                        )
                    }
                }
            }

            if (hud.overlay == OverlayMenu.AUDIO_SUBTITLES) {
                key(trackGeneration) {
                    AudioSubtitleMenu(
                        ctx = hudContext(),
                        section = hud.menuSection,
                        index = hud.menuIndex,
                    )
                }
            }
            if (hud.overlay == OverlayMenu.PLAYER_SETTINGS) {
                PlayerSettingsMenu(
                    settings = playerSettings,
                    focusedRow = hud.settingsRow,
                )
            }
        }
        if (start != null && !hasStarted && error == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC10120F), RoundedCornerShape(12.dp))
                    .padding(horizontal = 28.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Starting playback…", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Opening the video file",
                    color = Paper.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        // Held after a seek: no gray or frozen pictures, just the spinner until the decoder is clean.
        if (settling && error == null && hasStarted) {
            Box(Modifier.fillMaxSize().background(Color(0xF010120F)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PictureSpinner()
                    Text(
                        "Getting the picture ready…",
                        color = Paper.copy(alpha = 0.85f),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                }
            }
        }
        error?.let { message ->
            Column(
                Modifier.align(Alignment.Center).background(Color(0xE610120F), RoundedCornerShape(16.dp)).padding(28.dp).widthIn(max = 640.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, color = Coral, fontSize = 18.sp)
                Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TransportButton(label = "Retry", focused = recoveryChoice == RecoveryChoice.RETRY)
                    TransportButton(label = "Back", focused = recoveryChoice == RecoveryChoice.BACK)
                }
            }
        }
        // Spec 003: while the phone is asked to allow mobile data, show the Cablegram loop and what
        // to do on the phone instead of a frozen frame.
        if (error == null && transportNotice == WAITING_FOR_PHONE_NOTICE) {
            TransitionOverlay(PHONE_APPROVAL_TRANSITION, Modifier.align(Alignment.Center))
        } else if (error == null) {
            transition?.let { TransitionOverlay(it, Modifier.align(Alignment.Center)) }
        }
        // The short banner is for after the handover; the full screen covers the handover itself.
        if (error == null && transition == null && transportNotice != WAITING_FOR_PHONE_NOTICE) transportNotice?.let {
            Text(
                it,
                Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                    .background(Color(0xE610120F), RoundedCornerShape(14.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                color = Paper,
                fontSize = 16.sp,
            )
        }
        if (start == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(520.dp)
                    .background(PanelSoft, RoundedCornerShape(20.dp))
                    .border(1.dp, Outline, RoundedCornerShape(20.dp))
                    .padding(horizontal = 36.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Continue watching?", color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    title,
                    color = Muted,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (fallbackDurationMs > 0) {
                    Box(
                        Modifier
                            .padding(top = 20.dp)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Outline, RoundedCornerShape(2.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((resume.toFloat() / fallbackDurationMs).coerceIn(0.02f, 1f))
                                .fillMaxHeight()
                                .background(Cyan, RoundedCornerShape(2.dp)),
                        )
                    }
                }
                Text(
                    if (fallbackDurationMs > 0) "Stopped at ${formatTime(resume)} of ${formatTime(fallbackDurationMs)}"
                    else "Stopped at ${formatTime(resume)}",
                    color = Paper.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { start = resume }, modifier = Modifier.focusRequester(focus)) { Text("Resume") }
                    Button(onClick = { start = 0L }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Start over") }
                }
            }
        }
    }
}

/** T-net: how the stream travels — shown as a badge next to the brand mark. */
internal enum class Transport { LAN, RELAY, CLOUD, TELEGRAM, TELEGRAM_VIA_PHONE, LIVE }

private enum class StreamHealth { GOOD, OK, BAD }

private data class VolumeFeedback(val level: Int, val muted: Boolean)

@Composable
private fun VolumeFeedbackOverlay(feedback: VolumeFeedback) {
    val displayedLevel = feedback.level.coerceIn(0, 100)
    Column(
        modifier = Modifier
            .padding(end = 36.dp)
            .width(224.dp)
            .background(Color(0xE610120F), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (feedback.muted) "Muted" else "Volume",
                color = Paper,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (feedback.muted) "0%" else "$displayedLevel%",
                color = if (feedback.muted) Coral else Acid,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x44FFFFFF)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(displayedLevel / 100f)
                    .fillMaxHeight()
                    .background(if (feedback.muted) Coral else Acid),
            )
        }
    }
}

@Composable
private fun StreamHealthBadge(health: StreamHealth, modifier: Modifier = Modifier) {
    val (label, tint) = when (health) {
        StreamHealth.GOOD -> "Stream quality: Good" to Color(0xFF4ADE80)
        StreamHealth.OK -> "Stream quality: OK" to Color(0xFFFBBF24)
        StreamHealth.BAD -> "Stream quality: Poor" to Color(0xFFF87171)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC10120F))
            .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("●", color = tint, fontSize = 13.sp)
        Text(label, color = Paper.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TransportBadge(transport: Transport, modifier: Modifier = Modifier) {
    val (label, icon, tint) = when (transport) {
        Transport.LAN -> Triple("Local LAN", "⌂", Color(0xFF4ADE80))
        Transport.RELAY -> Triple("Relay", "⇄", Color(0xFFFBBF24))
        Transport.CLOUD -> Triple("Cloud", "☁", Color(0xFF8B9DF8))
        Transport.TELEGRAM -> Triple("Telegram", "✈", Color(0xFF2AABEE))
        Transport.TELEGRAM_VIA_PHONE -> Triple("Telegram via phone", "✈", Color(0xFF2AABEE))
        Transport.LIVE -> Triple("Live", "◉", Color(0xFFF87171))
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC10120F))
            .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(icon, color = tint, fontSize = 13.sp)
        Text(
            label,
            color = Paper.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Where the bytes come from, read from the playback URL: Telegram first (this TV's own session on loopback, or the phone's
 * session over the LAN), then the Cablegram relay, then the home network, and any other public host is cloud storage.
 * Telegram through the phone and the relay is shown as the relay it travels over.
 */
internal fun classifyTransport(url: String?, isLive: Boolean): Transport {
    if (isLive) return Transport.LIVE
    if (url.isNullOrBlank()) return Transport.CLOUD
    return when {
        isTelegramLocalUrl(url) -> Transport.TELEGRAM
        isRelayUrl(url) -> Transport.RELAY
        isPhoneTelegramUrl(url) -> Transport.TELEGRAM_VIA_PHONE
        url.startsWith("http://") && !isPublicHost(url) -> Transport.LAN
        else -> Transport.CLOUD
    }
}

/** True if the host looks like a public internet host (has a dot-shaped TLD), not a LAN address. */
private fun isPublicHost(url: String): Boolean {
    val host = runCatching {
        java.net.URI(url).host
    }.getOrNull() ?: return false
    // Raw IPv4 of a LAN router/phone (192.168/10./172.16-31/127.) counts as local.
    if (Regex("(^|\\.)(10|127)\\.|^192\\.168\\.|^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)) return false
    if (host.endsWith(".local")) return false
    // Bare hostnames without a dot (e.g. "phone") are LAN too.
    return host.contains(".")
}

@Composable
private fun TransportButton(label: String, focused: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (focused) Acid else Color(0x33FFFFFF), shape)
            .then(if (focused) Modifier.border(2.dp, Paper, shape) else Modifier)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused) Ink else Paper,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TimelineBar(progressFraction: Float, focused: Boolean) {
    Box(modifier = Modifier.fillMaxWidth().height(if (focused) 28.dp else 18.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (focused) 8.dp else 6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0x55FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressFraction)
                    .fillMaxHeight()
                    .background(if (focused) Acid else Paper),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(progressFraction)
                .height(if (focused) 28.dp else 18.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .size(if (focused) 22.dp else 14.dp)
                    .offset(x = 8.dp)
                    .clip(CircleShape)
                    .background(Acid)
                    .then(if (focused) Modifier.border(2.dp, Paper, CircleShape) else Modifier),
            )
        }
    }
}

@Composable
private fun TimelinePreview(positionMs: Long, holdMultiplier: Int, scrubbingBack: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 12.dp)) {
        Box(
            modifier = Modifier
                .width(220.dp)
                .height(124.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2A2E28))
                .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PREVIEW", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(formatTime(positionMs), color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (holdMultiplier > 1) {
                    val arrows = if (scrubbingBack) "◀◀" else "▶▶"
                    Text("$arrows  ${holdMultiplier}x", color = Acid, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AudioSubtitleMenu(ctx: PlaybackHudContext, section: MenuSection, index: Int) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .padding(horizontal = 48.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 640.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(Panel)
                .padding(28.dp),
        ) {
            Text("Audio & Subtitles", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            MenuSectionBlock(
                title = "Audio",
                options = menuOptions(MenuSection.AUDIO, ctx),
                selectedId = ctx.selectedAudioId,
                focused = section == MenuSection.AUDIO,
                focusedIndex = if (section == MenuSection.AUDIO) index else -1,
                fillRemaining = false,
            )
            Spacer(Modifier.height(18.dp))
            MenuSectionBlock(
                title = "Subtitles",
                options = menuOptions(MenuSection.SUBTITLES, ctx),
                selectedId = ctx.selectedSubtitleId,
                focused = section == MenuSection.SUBTITLES,
                focusedIndex = if (section == MenuSection.SUBTITLES) index else -1,
                fillRemaining = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PlayerSettingsMenu(settings: PlayerSettingsPreference, focusedRow: SettingsRow) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .padding(horizontal = 48.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 640.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Panel)
                .padding(28.dp),
        ) {
            Text("Player Settings", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Left / Right to adjust",
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
            SettingRow(
                title = "Playback Speed",
                value = PlayerSettings.formatSpeed(settings.playbackSpeed),
                focused = focusedRow == SettingsRow.SPEED,
            )
            SettingRow(
                title = "Aspect Ratio",
                value = settings.aspectRatio.label,
                focused = focusedRow == SettingsRow.ASPECT,
            )
            SettingRow(
                title = "Audio delay",
                value = PlayerSettings.formatDelay(settings.audioDelayMs),
                focused = focusedRow == SettingsRow.AUDIO_DELAY,
            )
            SettingRow(
                title = "Subtitle delay",
                value = PlayerSettings.formatDelay(settings.subtitleDelayMs),
                focused = focusedRow == SettingsRow.SUBTITLE_DELAY,
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String, focused: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Acid else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = if (focused) Ink else Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = if (focused) "◀  $value  ▶" else value,
            color = if (focused) Ink else Paper,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MenuSectionBlock(
    title: String,
    options: List<HudTrack>,
    selectedId: String?,
    focused: Boolean,
    focusedIndex: Int,
    fillRemaining: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(focused, focusedIndex, options.size) {
        if (focused && focusedIndex in options.indices) {
            listState.animateScrollToItem(focusedIndex)
        }
    }
    Column(modifier) {
        Text(title, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillRemaining) Modifier.weight(1f) else Modifier.heightIn(max = 160.dp)),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            itemsIndexed(options, key = { i, option -> "${option.id ?: "off"}-$i" }) { i, option ->
                val isFocused = focused && i == focusedIndex
                val isSelected = option.id == selectedId
                Text(
                    text = "${if (isSelected) "●  " else "    "}${option.label}",
                    color = if (isFocused) Ink else Paper,
                    fontSize = 16.sp,
                    fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isFocused) Acid else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HoldPlaybackSession(
    player: CableGramPlayer,
    sessionActive: Boolean,
    onInterrupted: () -> Unit,
) {
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(view, sessionActive) {
        val window = (view.context as? Activity)?.window
        if (sessionActive) {
            view.keepScreenOn = true
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(lifecycleOwner, player, sessionActive) {
        if (!sessionActive) return@DisposableEffect onDispose {}

        fun interruptPlayback() {
            if (!player.isPlaying) return
            player.pause()
            onInterrupted()
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) interruptPlayback()
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val audioManager = view.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                interruptPlayback()
            }
        }
        val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            null
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
        }
    }
}

private const val PLAYBACK_LOG_TAG = "CableGramPlayback"
private const val VOLUME_FEEDBACK_DURATION_MS = 1_800L

internal fun playbackUrlForLog(url: String): String {
    val parsed = try {
        java.net.URI(url)
    } catch (_: Exception) {
        return "<unparseable-playback-url>"
    }
    val host = parsed.host ?: return "<unparseable-playback-url>"
    val path = parsed.rawPath ?: parsed.path.orEmpty()
    val query = parsed.rawQuery
        ?.split("&")
        ?.filter { it.isNotEmpty() }
        ?.joinToString("&") { part ->
            val name = part.substringBefore("=")
            if (name in setOf("sig", "u", "token", "pass", "rt")) "$name=redacted" else part
        }
        .orEmpty()
    return buildString {
        append(parsed.scheme)
        append("://")
        append(host)
        if (parsed.port != -1) {
            append(':')
            append(parsed.port)
        }
        append(path)
        if (query.isNotEmpty()) {
            append('?')
            append(query)
        }
    }
}

private const val STALL_SWITCH_SECONDS = 10
private const val TELEGRAM_STALL_SWITCH_SECONDS = 25
private const val PREMATURE_END_MARGIN_MS = 5_000L
/** How often a title playing through the relay checks whether the phone answers on this Wi-Fi again. */
internal const val LAN_RETURN_CHECK_MS = 60_000L
private const val LAN_RETURN_PROBE_MS = 3_000L

/**
 * The LAN path to return to: only while playing through the relay with the phone's address on this Wi-Fi as the other path
 * (its own file, or a Telegram title the phone streams). Never a cloud URL, the TV's own Telegram, or another relay URL.
 */
internal fun lanReturnCandidate(current: String?, fallback: String?): String? =
    fallback?.takeIf {
        current != null && isRelayUrl(current) && !isRelayUrl(it) &&
            classifyTransport(it, isLive = false).let { t -> t == Transport.LAN || t == Transport.TELEGRAM_VIA_PHONE }
    }

internal const val WAITING_FOR_PHONE_NOTICE = "Waiting for your phone to allow streaming over mobile data…"

/** A rotating arc: the wait after a seek, while the decoder catches up. */
@Composable
private fun PictureSpinner() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "picture-spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "picture-spinner-angle",
    )
    androidx.compose.foundation.Canvas(Modifier.size(64.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawArc(color = Color(0x33FFFFFF), startAngle = 0f, sweepAngle = 360f, useCenter = false, style = stroke)
        drawArc(color = Color.White, startAngle = angle, sweepAngle = 100f, useCenter = false, style = stroke)
    }
}

internal fun isRelayUrl(url: String): Boolean = url.contains("/relay/v1/")

/** This TV's own Telegram session, served on 127.0.0.1 (spec 004 T009). */
internal fun isTelegramLocalUrl(url: String): Boolean = url.startsWith("http://127.0.0.1:") && url.contains("/tg/")

/** The household's own cloud storage (Drive), served on 127.0.0.1 by CloudStreamServer, which adds the token (spec 006). */
internal fun isCloudLocalUrl(url: String): Boolean = url.startsWith("http://127.0.0.1:") && url.contains("/cloud/")

/** A request for the household's own cloud storage: it carries the bearer token, or the local stream adds it. */
private fun isCloudSource(url: String, headers: Map<String, String>): Boolean =
    isCloudLocalUrl(url) || headers.keys.any { it.equals("Authorization", ignoreCase = true) }

/** A Telegram title streamed by the phone's session, over the LAN or the relay (spec 004 US8). */
internal fun isPhoneTelegramUrl(url: String): Boolean {
    // The phone serves `/telegram/<unique file id>`, the relay forwards it as `/relay/v1/p/<phone>/telegram/<id>`.
    val path = runCatching { java.net.URI(url).rawPath }.getOrNull() ?: return false
    return PHONE_TELEGRAM_PATH.matches(path)
}

private val PHONE_TELEGRAM_PATH = Regex("(/relay/v1/p/[^/]+)?/telegram/[A-Za-z0-9_-]+")

/** Telegram delivers the first bytes in seconds and may pause while it fetches the next window. */
internal fun stallSwitchSeconds(url: String?): Int = if (url != null && isTelegramLocalUrl(url)) TELEGRAM_STALL_SWITCH_SECONDS else STALL_SWITCH_SECONDS

internal fun lostSourceMessage(url: String?): String =
    if (url != null && isTelegramLocalUrl(url)) "Telegram didn't deliver the video. Check the TV's internet connection, then try again."
    else "Lost the connection to your phone. Check that it's online with Cablegram open, then try again."

internal fun transportNoticeFor(url: String): String = when {
    isTelegramLocalUrl(url) -> "Playing straight from Telegram"
    isPhoneTelegramUrl(url) && isRelayUrl(url) -> "Playing your Telegram video through your phone and the Cablegram relay"
    isPhoneTelegramUrl(url) -> "Playing your Telegram video through your phone"
    isRelayUrl(url) -> "Your phone isn't on this Wi‑Fi — streaming through the Cablegram relay"
    else -> "Back on your home Wi‑Fi — streaming directly from your phone"
}

/** What the viewer can do when the relay refuses (spec 003, contracts/relay-protocol.md). */
internal fun relayMessage(code: Int?, error: String?): String = when {
    // A 5xx without the relay's own error code comes from the proxy: the relay service is down.
    error == null && code != null && code >= 500 -> "Cablegram's relay isn't reachable right now. Put the phone and TV on the same Wi‑Fi, or try again in a few minutes."
    code == null -> "Can't reach Cablegram from this TV. Check the TV's internet connection."
    error == "phone_offline" -> "Your phone isn't reachable. Open Cablegram on the phone and check that it has internet."
    error == "mobile_data_not_allowed" -> "Your phone is on mobile data, and streaming over mobile data is turned off in Cablegram's settings on the phone."
    error == "relay_email_unverified" -> "Free relay needs a confirmed email. Open Cablegram on your phone and confirm your email in Settings, or use the same Wi‑Fi."
    error == "relay_free_device_limit" -> "Free relay isn't available: this phone or TV already gives free relay to other Cablegram accounts. Use the same Wi‑Fi, or upgrade your plan."
    error == "relay_quota_exceeded" -> "This month's relay traffic is used up. Put the phone and TV on the same Wi‑Fi, or upgrade your plan."
    error == "relay_streams_exceeded" -> "Too many relay streams are running in your household. Stop one and try again."
    error == "phone_timeout" || error == "phone_disconnected" || code == 502 || code == 504 -> "Your phone didn't respond. Check that Cablegram is open on the phone."
    else -> preflightMessage(code)
}

/** Status and relay error code of a small Range request; status null when unreachable. */
private suspend fun probeSource(client: OkHttpClient, url: String, headers: Map<String, String>, timeoutMs: Long): Pair<Int?, String?> =
    withContext(Dispatchers.IO) {
        try {
            val quick = client.newBuilder()
                .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder().url(url).header("Range", "bytes=0-1")
            headers.forEach { (name, value) ->
                if (name.matches(Regex("[A-Za-z0-9-]+")) && !value.contains('\n') && !value.contains('\r')) request.header(name, value)
            }
            quick.newCall(request.build()).execute().use { response ->
                if (response.isSuccessful) return@use response.code to null
                val code = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(response.body?.string().orEmpty())
                        .let { (it as? kotlinx.serialization.json.JsonObject)?.get("error")?.jsonPrimitive?.content }
                }.getOrNull()
                Log.w(PLAYBACK_LOG_TAG, "Source probe HTTP ${response.code} ${code ?: ""} ${playbackUrlForLog(url)}")
                response.code to code
            }
        } catch (error: Exception) {
            Log.w(PLAYBACK_LOG_TAG, "Source probe failed ${playbackUrlForLog(url)}: ${error.javaClass.simpleName}")
            null to null
        }
    }

/**
 * Null when [url] can be played now, else the message to show. LAN gets a single short probe when
 * [quick] (a relay path exists); the relay waits while the phone asks about mobile data.
 */
internal suspend fun reachableSource(
    client: OkHttpClient,
    url: String,
    headers: Map<String, String>,
    quick: Boolean,
    onNotice: (String?) -> Unit,
): String? {
    // The local Telegram stream answers only once Telegram has delivered the first bytes (seconds); the
    // player itself reports a failure, which switches to the next source.
    if (isTelegramLocalUrl(url)) return null
    if (!isRelayUrl(url)) {
        // Drive's first byte can take longer than the LAN probe allows; a slow answer is not an unreachable phone.
        if (!quick || isCloudLocalUrl(url)) return preflightPlaybackUrl(client, url, headers)
        val (code, _) = probeSource(client, url, headers, 3_000)
        val cloud = isCloudSource(url, headers)
        return if (code != null && code in 200..299) null
        else if (cloud) preflightMessage(code ?: 0, cloud = true).takeIf { code != null } ?: "Can't reach your cloud storage right now."
        else "Can't reach your phone on this Wi‑Fi."
    }
    repeat(40) {
        val (code, error) = probeSource(client, url, headers, 12_000)
        if (code != null && code in 200..299) {
            onNotice(null)
            return null
        }
        if (error != "mobile_data_pending") {
            onNotice(null)
            return relayMessage(code, error)
        }
        onNotice(WAITING_FOR_PHONE_NOTICE)
        delay(3_000)
    }
    onNotice(null)
    return "Your phone didn't allow streaming over mobile data."
}

/** What the viewer can do about a failed stream, instead of a bare "HTTP 404". */
internal fun preflightMessage(code: Int, cloud: Boolean = false): String = when {
    // A request that carries a bearer token is for the household's own cloud storage (Google Drive), not the phone.
    cloud && code == 404 -> "This video is no longer in your cloud storage. Remove it from the library or save it again."
    cloud && code == 401 -> "Your cloud storage needs you to sign in again. Open Storage on the phone."
    cloud && (code == 403 || code == 429) -> "Your cloud storage is limiting downloads of this video. Try again in a little while."
    cloud -> "Your cloud storage couldn't send this video (error $code). Try again in a moment."
    code == 404 -> "This video isn't on your phone anymore. Import it again on the phone, or remove it from the library."
    code == 401 || code == 403 -> "Your phone didn't allow this playback. Open Cablegram on the phone and try again."
    else -> "Your phone couldn't send this video (error $code). Try again in a moment."
}

private suspend fun preflightPlaybackUrl(client: OkHttpClient, url: String, headers: Map<String, String> = emptyMap()): String? {
    var lastError: String? = null
    repeat(5) { attempt ->
        lastError = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).header("Range", "bytes=0-1")
                headers.forEach { (name, value) ->
                    if (name.matches(Regex("[A-Za-z0-9-]+")) && !value.contains('\n') && !value.contains('\r')) {
                        request.header(name, value)
                    }
                }
                client.newCall(request.build()).execute().use { response ->
                    val safeUrl = playbackUrlForLog(url)
                    if (response.isSuccessful) {
                        Log.i(PLAYBACK_LOG_TAG, "Playback preflight HTTP ${response.code} $safeUrl")
                        null
                    } else {
                        Log.e(PLAYBACK_LOG_TAG, "Playback preflight HTTP ${response.code} $safeUrl")
                        preflightMessage(response.code, cloud = isCloudSource(url, headers))
                    }
                }
            } catch (error: Exception) {
                Log.e(
                    PLAYBACK_LOG_TAG,
                    "Playback preflight failed ${playbackUrlForLog(url)}: ${error.message ?: error.javaClass.simpleName}",
                )
                if (isCloudSource(url, headers)) "Can't reach your cloud storage right now."
                else "Can't reach your phone. Make sure it's on the same Wi-Fi with Cablegram open, then try again."
            }
        }
        if (lastError == null) return null
        if (attempt == 4) return lastError
        delay(400L * (attempt + 1))
    }
    return lastError
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** What the TV says while it moves a title between paths (spec 003). */
internal data class SourceTransition(val eyebrow: String, val title: String, val body: String, val footnote: String? = null)

internal val PHONE_APPROVAL_TRANSITION = SourceTransition(
    eyebrow = "PHONE NOT ON THIS WI‑FI",
    title = "Allow streaming on your phone",
    body = "Your phone isn't on the same Wi‑Fi as this TV, so Cablegram can stream through its relay using the phone's mobile data.",
    footnote = "On your phone, open the Cablegram notification and tap “Allow this time”. Playback continues here right after.",
)

internal fun transitionTo(url: String, atStart: Boolean = false): SourceTransition = when {
    isTelegramLocalUrl(url) -> SourceTransition(
        eyebrow = "SWITCHING SOURCE",
        title = "Continuing straight from Telegram",
        body = "Playback continues from where you were, using this TV's own Telegram connection.",
    )
    isPhoneTelegramUrl(url) -> SourceTransition(
        eyebrow = "SWITCHING SOURCE",
        title = "Continuing through your phone",
        body = "Telegram was too slow on this TV, so your phone fetches the video and sends it here. Playback continues from where you were.",
    )
    isRelayUrl(url) && atStart -> SourceTransition(
        eyebrow = "CONNECTING THROUGH THE RELAY",
        title = "Reaching your phone over the internet",
        body = "Your phone and this TV aren't on the same Wi‑Fi, so Cablegram streams through its relay. This takes a few seconds.",
    )
    isRelayUrl(url) -> SourceTransition(
        eyebrow = "WI‑FI CONNECTION LOST",
        title = "Switching to the Cablegram relay",
        body = "Your phone left this Wi‑Fi. Playback continues through the relay from where you were — this takes a few seconds.",
    )
    else -> SourceTransition(
        eyebrow = "BACK ON YOUR WI‑FI",
        title = "Switching back to your phone",
        body = "Your phone is on this Wi‑Fi again, so playback continues directly from it.",
    )
}

/** Full-screen handover: the Cablegram loop and what is happening, instead of a frozen frame. */
@Composable
private fun TransitionOverlay(transition: SourceTransition, modifier: Modifier = Modifier) {
    val loopUrl = remember { DEFAULT_LOADING_VIDEOS.random() }
    Box(
        modifier.fillMaxSize().background(Color(0xFF0B0D10)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF15181D)),
            ) { LoopingPrepareVideo(loopUrl) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(transition.eyebrow, color = Color(0xFFF3B86A), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text(transition.title, color = Paper, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(transition.body, color = Paper.copy(alpha = 0.8f), fontSize = 17.sp, lineHeight = 24.sp)
                transition.footnote?.let { Text(it, color = Paper.copy(alpha = 0.65f), fontSize = 15.sp, lineHeight = 22.sp) }
            }
        }
    }
}
