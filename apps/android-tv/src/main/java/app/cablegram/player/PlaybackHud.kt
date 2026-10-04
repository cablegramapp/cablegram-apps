package app.cablegram.player

enum class DpadKey {
    UP, DOWN, LEFT, RIGHT, OK, BACK
}

enum class ControlsFocus {
    REWIND, PLAY_PAUSE, FAST_FORWARD, TIMELINE, AUDIO_SUBTITLES, PLAYER_SETTINGS
}

enum class MenuSection {
    AUDIO, SUBTITLES
}

enum class OverlayMenu {
    CLOSED, AUDIO_SUBTITLES, PLAYER_SETTINGS
}

enum class PlayerAspectRatio(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    CROP("Crop"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
    ZOOM("Zoom"),
}

enum class SettingsRow {
    SPEED, ASPECT, AUDIO_DELAY, SUBTITLE_DELAY
}

object PlayerSettings {
    val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
    const val DELAY_STEP_MS = 50L
    const val DELAY_MIN_MS = -5_000L
    const val DELAY_MAX_MS = 5_000L

    fun formatSpeed(rate: Float): String {
        val value = if (rate == rate.toInt().toFloat()) rate.toInt().toString() else rate.toString()
        return "${value}×"
    }

    fun formatDelay(ms: Long): String {
        val sign = if (ms > 0) "+" else ""
        return if (ms == 0L) "0 ms" else "$sign$ms ms"
    }

    fun nextSpeed(current: Float, delta: Int): Float {
        val index = SPEEDS.indexOfFirst { it == current }.takeIf { it >= 0 }
            ?: SPEEDS.indexOfFirst { it >= current }.takeIf { it >= 0 }
            ?: SPEEDS.lastIndex
        return SPEEDS[(index + delta).coerceIn(0, SPEEDS.lastIndex)]
    }

    fun nextAspect(current: PlayerAspectRatio, delta: Int): PlayerAspectRatio {
        val values = PlayerAspectRatio.entries
        val index = values.indexOf(current)
        return values[(index + delta).coerceIn(0, values.lastIndex)]
    }

    fun nextDelay(currentMs: Long, delta: Int): Long =
        (currentMs + delta * DELAY_STEP_MS).coerceIn(DELAY_MIN_MS, DELAY_MAX_MS)
}

data class HudTrack(val id: String?, val label: String)

data class PlaybackHudContext(
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val audioTracks: List<HudTrack> = emptyList(),
    val subtitleTracks: List<HudTrack> = emptyList(),
    val selectedAudioId: String? = null,
    val selectedSubtitleId: String? = null,
    val playbackSpeed: Float = 1f,
    val aspectRatio: PlayerAspectRatio = PlayerAspectRatio.FIT,
    val audioDelayMs: Long = 0L,
    val subtitleDelayMs: Long = 0L,
)

sealed interface HudEffect {
    data object ExitPlayer : HudEffect
    data object Play : HudEffect
    data object Pause : HudEffect
    data class SeekBy(val deltaMs: Long) : HudEffect
    data class SeekTo(val positionMs: Long) : HudEffect
    data class SelectAudio(val trackId: String) : HudEffect
    data class SelectSubtitle(val trackId: String?) : HudEffect
    data class SetPlaybackSpeed(val rate: Float) : HudEffect
    data class SetAspectRatio(val mode: PlayerAspectRatio) : HudEffect
    data class SetAudioDelay(val delayMs: Long) : HudEffect
    data class SetSubtitleDelay(val delayMs: Long) : HudEffect
}

data class PlaybackHudState(
    val controlsVisible: Boolean = false,
    val overlay: OverlayMenu = OverlayMenu.CLOSED,
    val focus: ControlsFocus = ControlsFocus.PLAY_PAUSE,
    val menuSection: MenuSection = MenuSection.AUDIO,
    val menuIndex: Int = 0,
    val settingsRow: SettingsRow = SettingsRow.SPEED,
    val previewPositionMs: Long? = null,
    val seekFlashDeltaMs: Long? = null,
    val holdMultiplier: Int = 1,
) {
    val menuOpen: Boolean get() = overlay != OverlayMenu.CLOSED
    val timelineActive: Boolean get() = controlsVisible && !menuOpen && focus == ControlsFocus.TIMELINE
}

object PlaybackHud {
    const val SEEK_STEP_MS = 10_000L
    const val AUTO_HIDE_MS = 4_000L
    const val SEEK_FLASH_MS = 1_500L

    /** Where a relative seek lands: never before the start. */
    fun seekTargetMs(positionMs: Long, deltaMs: Long): Long = (positionMs + deltaMs).coerceAtLeast(0L)

    fun seekStepMs(repeatCount: Int): Long {
        val multiplier = holdMultiplier(repeatCount)
        return SEEK_STEP_MS * multiplier
    }

    fun holdMultiplier(repeatCount: Int): Int = when {
        repeatCount >= 24 -> 10
        repeatCount >= 12 -> 4
        repeatCount >= 6 -> 2
        else -> 1
    }
}

fun reducePlaybackHud(
    state: PlaybackHudState,
    key: DpadKey,
    repeatCount: Int,
    ctx: PlaybackHudContext,
): Pair<PlaybackHudState, List<HudEffect>> {
    if (state.menuOpen) return reduceMenu(state, key, ctx)
    if (!state.controlsVisible) return reduceFullscreen(state, key, ctx)
    return reduceControls(state, key, repeatCount, ctx)
}

private fun reduceFullscreen(
    state: PlaybackHudState,
    key: DpadKey,
    ctx: PlaybackHudContext,
): Pair<PlaybackHudState, List<HudEffect>> = when (key) {
    DpadKey.UP, DpadKey.DOWN -> showControls(state) to emptyList()
    DpadKey.LEFT -> {
        val delta = -PlaybackHud.SEEK_STEP_MS
        state.copy(seekFlashDeltaMs = delta, controlsVisible = false) to listOf(HudEffect.SeekBy(delta))
    }
    DpadKey.RIGHT -> {
        val delta = PlaybackHud.SEEK_STEP_MS
        state.copy(seekFlashDeltaMs = delta, controlsVisible = false) to listOf(HudEffect.SeekBy(delta))
    }
    DpadKey.OK -> {
        val effects = if (ctx.isPlaying) listOf(HudEffect.Pause) else emptyList()
        showControls(state.copy(seekFlashDeltaMs = null)) to effects
    }
    DpadKey.BACK -> state to listOf(HudEffect.ExitPlayer)
}

private fun reduceControls(
    state: PlaybackHudState,
    key: DpadKey,
    repeatCount: Int,
    ctx: PlaybackHudContext,
): Pair<PlaybackHudState, List<HudEffect>> {
    if (key == DpadKey.BACK) {
        return hideControls(state) to emptyList()
    }
    return when (state.focus) {
        ControlsFocus.PLAY_PAUSE -> when (key) {
            DpadKey.LEFT -> state.copy(focus = ControlsFocus.REWIND, seekFlashDeltaMs = null) to emptyList()
            DpadKey.RIGHT -> state.copy(focus = ControlsFocus.FAST_FORWARD, seekFlashDeltaMs = null) to emptyList()
            DpadKey.DOWN -> enterTimeline(state, ctx) to emptyList()
            DpadKey.OK -> state.copy(seekFlashDeltaMs = null) to listOf(
                if (ctx.isPlaying) HudEffect.Pause else HudEffect.Play,
            )
            DpadKey.UP -> state to emptyList()
            DpadKey.BACK -> hideControls(state) to emptyList()
        }
        ControlsFocus.REWIND -> when (key) {
            DpadKey.RIGHT -> state.copy(focus = ControlsFocus.PLAY_PAUSE) to emptyList()
            DpadKey.DOWN -> enterTimeline(state, ctx) to emptyList()
            DpadKey.OK -> state to listOf(HudEffect.SeekBy(-PlaybackHud.SEEK_STEP_MS))
            DpadKey.LEFT, DpadKey.UP, DpadKey.BACK -> state to emptyList()
        }
        ControlsFocus.FAST_FORWARD -> when (key) {
            DpadKey.LEFT -> state.copy(focus = ControlsFocus.PLAY_PAUSE) to emptyList()
            DpadKey.DOWN -> enterTimeline(state, ctx) to emptyList()
            DpadKey.OK -> state to listOf(HudEffect.SeekBy(PlaybackHud.SEEK_STEP_MS))
            DpadKey.RIGHT, DpadKey.UP, DpadKey.BACK -> state to emptyList()
        }
        ControlsFocus.TIMELINE -> reduceTimeline(state, key, repeatCount, ctx)
        ControlsFocus.AUDIO_SUBTITLES -> when (key) {
            DpadKey.UP -> enterTimeline(state, ctx) to emptyList()
            DpadKey.OK -> openAudioMenu(state, ctx) to emptyList()
            DpadKey.RIGHT -> state.copy(focus = ControlsFocus.PLAYER_SETTINGS) to emptyList()
            DpadKey.DOWN, DpadKey.LEFT, DpadKey.BACK -> state to emptyList()
        }
        ControlsFocus.PLAYER_SETTINGS -> when (key) {
            DpadKey.UP -> enterTimeline(state, ctx) to emptyList()
            DpadKey.OK -> openSettingsMenu(state) to emptyList()
            DpadKey.LEFT -> state.copy(focus = ControlsFocus.AUDIO_SUBTITLES) to emptyList()
            DpadKey.DOWN, DpadKey.RIGHT, DpadKey.BACK -> state to emptyList()
        }
    }
}

private fun reduceTimeline(
    state: PlaybackHudState,
    key: DpadKey,
    repeatCount: Int,
    ctx: PlaybackHudContext,
): Pair<PlaybackHudState, List<HudEffect>> {
    val current = state.previewPositionMs ?: ctx.positionMs
    val duration = ctx.durationMs.coerceAtLeast(0L)
    val step = PlaybackHud.seekStepMs(repeatCount)
    val multiplier = PlaybackHud.holdMultiplier(repeatCount)
    return when (key) {
        DpadKey.LEFT -> {
            val next = (current - step).coerceAtLeast(0L)
            state.copy(previewPositionMs = next, holdMultiplier = multiplier, seekFlashDeltaMs = null) to emptyList()
        }
        DpadKey.RIGHT -> {
            val next = if (duration > 0) (current + step).coerceAtMost(duration) else current + step
            state.copy(previewPositionMs = next, holdMultiplier = multiplier, seekFlashDeltaMs = null) to emptyList()
        }
        DpadKey.UP -> state.copy(
            focus = ControlsFocus.PLAY_PAUSE,
            previewPositionMs = null,
            holdMultiplier = 1,
        ) to emptyList()
        DpadKey.DOWN -> state.copy(
            focus = ControlsFocus.AUDIO_SUBTITLES,
            previewPositionMs = null,
            holdMultiplier = 1,
        ) to emptyList()
        DpadKey.OK -> {
            val target = current.coerceAtLeast(0L)
            state.copy(previewPositionMs = null, holdMultiplier = 1) to listOf(
                HudEffect.SeekTo(target),
                HudEffect.Play,
            )
        }
        DpadKey.BACK -> hideControls(state) to emptyList()
    }
}

private fun reduceMenu(
    state: PlaybackHudState,
    key: DpadKey,
    ctx: PlaybackHudContext,
): Pair<PlaybackHudState, List<HudEffect>> = when (state.overlay) {
    OverlayMenu.PLAYER_SETTINGS -> reduceSettingsMenu(state, key, ctx)
    OverlayMenu.AUDIO_SUBTITLES -> reduceAudioSubtitleMenu(state, key, ctx)
    OverlayMenu.CLOSED -> state to emptyList()
}

private fun reduceAudioSubtitleMenu(
    state: PlaybackHudState,
    key: DpadKey,
    ctx: PlaybackHudContext,
): Pair<PlaybackHudState, List<HudEffect>> {
    val options = menuOptions(state.menuSection, ctx)
    val lastIndex = (options.size - 1).coerceAtLeast(0)
    return when (key) {
        DpadKey.BACK -> state.copy(
            overlay = OverlayMenu.CLOSED,
            focus = ControlsFocus.AUDIO_SUBTITLES,
            menuIndex = 0,
        ) to emptyList()
        DpadKey.UP -> state.copy(menuIndex = (state.menuIndex - 1).coerceAtLeast(0)) to emptyList()
        DpadKey.DOWN -> state.copy(menuIndex = (state.menuIndex + 1).coerceAtMost(lastIndex)) to emptyList()
        DpadKey.LEFT -> switchSection(state, MenuSection.AUDIO, ctx) to emptyList()
        DpadKey.RIGHT -> switchSection(state, MenuSection.SUBTITLES, ctx) to emptyList()
        DpadKey.OK -> {
            val option = options.getOrNull(state.menuIndex) ?: return state to emptyList()
            val effect = if (state.menuSection == MenuSection.AUDIO) {
                option.id?.let { HudEffect.SelectAudio(it) }
            } else {
                HudEffect.SelectSubtitle(option.id)
            }
            state to listOfNotNull(effect)
        }
    }
}

private fun reduceSettingsMenu(
    state: PlaybackHudState,
    key: DpadKey,
    ctx: PlaybackHudContext,
): Pair<PlaybackHudState, List<HudEffect>> {
    val rows = SettingsRow.entries
    val rowIndex = rows.indexOf(state.settingsRow)
    return when (key) {
        DpadKey.BACK -> state.copy(
            overlay = OverlayMenu.CLOSED,
            focus = ControlsFocus.PLAYER_SETTINGS,
            settingsRow = SettingsRow.SPEED,
        ) to emptyList()
        DpadKey.UP -> state.copy(settingsRow = rows[(rowIndex - 1).coerceAtLeast(0)]) to emptyList()
        DpadKey.DOWN -> state.copy(settingsRow = rows[(rowIndex + 1).coerceAtMost(rows.lastIndex)]) to emptyList()
        DpadKey.LEFT -> adjustSetting(state, ctx, -1)
        DpadKey.RIGHT -> adjustSetting(state, ctx, 1)
        DpadKey.OK -> state to emptyList()
    }
}

private fun adjustSetting(
    state: PlaybackHudState,
    ctx: PlaybackHudContext,
    delta: Int,
): Pair<PlaybackHudState, List<HudEffect>> {
    val effect = when (state.settingsRow) {
        SettingsRow.SPEED -> HudEffect.SetPlaybackSpeed(PlayerSettings.nextSpeed(ctx.playbackSpeed, delta))
        SettingsRow.ASPECT -> HudEffect.SetAspectRatio(PlayerSettings.nextAspect(ctx.aspectRatio, delta))
        SettingsRow.AUDIO_DELAY -> HudEffect.SetAudioDelay(PlayerSettings.nextDelay(ctx.audioDelayMs, delta))
        SettingsRow.SUBTITLE_DELAY -> HudEffect.SetSubtitleDelay(PlayerSettings.nextDelay(ctx.subtitleDelayMs, delta))
    }
    return state to listOf(effect)
}

private fun showControls(state: PlaybackHudState) = state.copy(
    controlsVisible = true,
    overlay = OverlayMenu.CLOSED,
    focus = ControlsFocus.PLAY_PAUSE,
    previewPositionMs = null,
    seekFlashDeltaMs = null,
    holdMultiplier = 1,
)

private fun hideControls(state: PlaybackHudState) = state.copy(
    controlsVisible = false,
    overlay = OverlayMenu.CLOSED,
    focus = ControlsFocus.PLAY_PAUSE,
    previewPositionMs = null,
    seekFlashDeltaMs = null,
    holdMultiplier = 1,
)

private fun enterTimeline(state: PlaybackHudState, ctx: PlaybackHudContext) = state.copy(
    focus = ControlsFocus.TIMELINE,
    previewPositionMs = ctx.positionMs,
    holdMultiplier = 1,
    seekFlashDeltaMs = null,
)

private fun openAudioMenu(state: PlaybackHudState, ctx: PlaybackHudContext): PlaybackHudState {
    val section = MenuSection.AUDIO
    val options = menuOptions(section, ctx)
    val selectedIndex = options.indexOfFirst { it.id == ctx.selectedAudioId }.takeIf { it >= 0 } ?: 0
    return state.copy(overlay = OverlayMenu.AUDIO_SUBTITLES, menuSection = section, menuIndex = selectedIndex)
}

private fun openSettingsMenu(state: PlaybackHudState): PlaybackHudState =
    state.copy(overlay = OverlayMenu.PLAYER_SETTINGS, settingsRow = SettingsRow.SPEED)

private fun switchSection(state: PlaybackHudState, section: MenuSection, ctx: PlaybackHudContext): PlaybackHudState {
    val options = menuOptions(section, ctx)
    val selectedId = if (section == MenuSection.AUDIO) ctx.selectedAudioId else ctx.selectedSubtitleId
    val selectedIndex = options.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
    return state.copy(menuSection = section, menuIndex = selectedIndex)
}

fun menuOptions(section: MenuSection, ctx: PlaybackHudContext): List<HudTrack> = when (section) {
    MenuSection.AUDIO -> ctx.audioTracks
    MenuSection.SUBTITLES -> listOf(HudTrack(null, "Off")) + ctx.subtitleTracks
}
