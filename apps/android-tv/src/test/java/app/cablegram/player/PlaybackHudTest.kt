package app.cablegram.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHudTest {
    private val ctx = PlaybackHudContext(
        isPlaying = true,
        positionMs = 1_935_000L,
        durationMs = 4_836_000L,
        audioTracks = listOf(HudTrack("en", "English"), HudTrack("de", "German"), HudTrack("fr", "French")),
        subtitleTracks = listOf(HudTrack("en", "English"), HudTrack("de", "German")),
        selectedAudioId = "en",
        selectedSubtitleId = null,
    )

    @Test
    fun `fullscreen up shows controls on play pause`() {
        val (state, effects) = reducePlaybackHud(PlaybackHudState(), DpadKey.UP, 0, ctx)
        assertTrue(state.controlsVisible)
        assertEquals(ControlsFocus.PLAY_PAUSE, state.focus)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `fullscreen left seeks minus 10 seconds with flash`() {
        val (state, effects) = reducePlaybackHud(PlaybackHudState(), DpadKey.LEFT, 0, ctx)
        assertFalse(state.controlsVisible)
        assertEquals(-10_000L, state.seekFlashDeltaMs)
        assertEquals(listOf(HudEffect.SeekBy(-10_000L)), effects)
    }

    @Test
    fun `fullscreen ok pauses and shows controls`() {
        val (state, effects) = reducePlaybackHud(PlaybackHudState(), DpadKey.OK, 0, ctx)
        assertTrue(state.controlsVisible)
        assertEquals(listOf(HudEffect.Pause), effects)
    }

    @Test
    fun `fullscreen back exits`() {
        val (_, effects) = reducePlaybackHud(PlaybackHudState(), DpadKey.BACK, 0, ctx)
        assertEquals(listOf(HudEffect.ExitPlayer), effects)
    }

    @Test
    fun `golden path seeking`() {
        var state = PlaybackHudState()
        state = reducePlaybackHud(state, DpadKey.UP, 0, ctx).first
        assertEquals(ControlsFocus.PLAY_PAUSE, state.focus)
        state = reducePlaybackHud(state, DpadKey.DOWN, 0, ctx).first
        assertEquals(ControlsFocus.TIMELINE, state.focus)
        assertEquals(ctx.positionMs, state.previewPositionMs)
        state = reducePlaybackHud(state, DpadKey.RIGHT, 0, ctx).first
        assertEquals(ctx.positionMs + 10_000L, state.previewPositionMs)
        val (confirmed, effects) = reducePlaybackHud(state, DpadKey.OK, 0, ctx)
        assertEquals(ctx.positionMs + 10_000L, (effects[0] as HudEffect.SeekTo).positionMs)
        assertEquals(HudEffect.Play, effects[1])
        assertTrue(confirmed.controlsVisible)
        assertEquals(null, confirmed.previewPositionMs)
    }

    @Test
    fun `timeline up returns to play pause without seeking`() {
        var state = reducePlaybackHud(PlaybackHudState(), DpadKey.UP, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.DOWN, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.RIGHT, 0, ctx).first
        val (after, effects) = reducePlaybackHud(state, DpadKey.UP, 0, ctx)
        assertEquals(ControlsFocus.PLAY_PAUSE, after.focus)
        assertTrue(effects.isEmpty())
        assertEquals(null, after.previewPositionMs)
    }

    @Test
    fun `controls back hides instead of exiting`() {
        var state = reducePlaybackHud(PlaybackHudState(), DpadKey.UP, 0, ctx).first
        val (hidden, effects) = reducePlaybackHud(state, DpadKey.BACK, 0, ctx)
        assertFalse(hidden.controlsVisible)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `audio subtitles golden path`() {
        var state = reducePlaybackHud(PlaybackHudState(), DpadKey.UP, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.DOWN, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.DOWN, 0, ctx).first
        assertEquals(ControlsFocus.AUDIO_SUBTITLES, state.focus)
        state = reducePlaybackHud(state, DpadKey.OK, 0, ctx).first
        assertTrue(state.menuOpen)
        state = reducePlaybackHud(state, DpadKey.DOWN, 0, ctx).first
        val (_, select) = reducePlaybackHud(state, DpadKey.OK, 0, ctx)
        assertEquals(listOf(HudEffect.SelectAudio("de")), select)
        val (closed, closeEffects) = reducePlaybackHud(state.copy(overlay = OverlayMenu.AUDIO_SUBTITLES), DpadKey.BACK, 0, ctx)
        assertFalse(closed.menuOpen)
        assertEquals(ControlsFocus.AUDIO_SUBTITLES, closed.focus)
        assertTrue(closeEffects.isEmpty())
    }

    @Test
    fun `menu left right switch sections`() {
        var state = reducePlaybackHud(PlaybackHudState(), DpadKey.UP, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.DOWN, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.DOWN, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.OK, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.RIGHT, 0, ctx).first
        assertEquals(MenuSection.SUBTITLES, state.menuSection)
        val (_, effects) = reducePlaybackHud(state, DpadKey.OK, 0, ctx)
        assertEquals(listOf(HudEffect.SelectSubtitle(null)), effects)
    }

    @Test
    fun `play pause left right navigate transport row`() {
        var state = reducePlaybackHud(PlaybackHudState(), DpadKey.UP, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.LEFT, 0, ctx).first
        assertEquals(ControlsFocus.REWIND, state.focus)
        val (_, rewind) = reducePlaybackHud(state, DpadKey.OK, 0, ctx)
        assertEquals(listOf(HudEffect.SeekBy(-10_000L)), rewind)
        state = reducePlaybackHud(state, DpadKey.RIGHT, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.RIGHT, 0, ctx).first
        assertEquals(ControlsFocus.FAST_FORWARD, state.focus)
    }

    @Test
    fun `hold right on timeline increases seek step`() {
        var state = reducePlaybackHud(PlaybackHudState(), DpadKey.UP, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.DOWN, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.RIGHT, 24, ctx).first
        assertEquals(10, state.holdMultiplier)
        assertEquals(ctx.positionMs + 100_000L, state.previewPositionMs)
    }

    @Test
    fun `paused fullscreen ok shows controls without play`() {
        val paused = ctx.copy(isPlaying = false)
        val (_, effects) = reducePlaybackHud(PlaybackHudState(), DpadKey.OK, 0, paused)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `player settings golden path`() {
        var state = reducePlaybackHud(PlaybackHudState(), DpadKey.UP, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.DOWN, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.DOWN, 0, ctx).first
        state = reducePlaybackHud(state, DpadKey.RIGHT, 0, ctx).first
        assertEquals(ControlsFocus.PLAYER_SETTINGS, state.focus)
        state = reducePlaybackHud(state, DpadKey.OK, 0, ctx).first
        assertEquals(OverlayMenu.PLAYER_SETTINGS, state.overlay)
        val (_, faster) = reducePlaybackHud(state, DpadKey.RIGHT, 0, ctx)
        assertEquals(listOf(HudEffect.SetPlaybackSpeed(1.25f)), faster)
        state = reducePlaybackHud(state, DpadKey.DOWN, 0, ctx).first
        val (_, aspect) = reducePlaybackHud(state, DpadKey.RIGHT, 0, ctx)
        assertEquals(listOf(HudEffect.SetAspectRatio(PlayerAspectRatio.FILL)), aspect)
        state = reducePlaybackHud(state, DpadKey.DOWN, 0, ctx).first
        val (_, audioDelay) = reducePlaybackHud(state, DpadKey.RIGHT, 0, ctx)
        assertEquals(listOf(HudEffect.SetAudioDelay(50L)), audioDelay)
        state = reducePlaybackHud(state, DpadKey.DOWN, 0, ctx).first
        val (_, subtitleDelay) = reducePlaybackHud(state, DpadKey.LEFT, 0, ctx)
        assertEquals(listOf(HudEffect.SetSubtitleDelay(-50L)), subtitleDelay)
        val (closed, closeEffects) = reducePlaybackHud(state, DpadKey.BACK, 0, ctx)
        assertEquals(OverlayMenu.CLOSED, closed.overlay)
        assertEquals(ControlsFocus.PLAYER_SETTINGS, closed.focus)
        assertTrue(closeEffects.isEmpty())
    }

    @Test
    fun `a relative seek never lands before the start`() {
        assertEquals(0L, PlaybackHud.seekTargetMs(4_000L, -10_000L))
        assertEquals(0L, PlaybackHud.seekTargetMs(0L, -PlaybackHud.SEEK_STEP_MS))
        assertEquals(25_000L, PlaybackHud.seekTargetMs(15_000L, 10_000L))
    }

    @Test
    fun `the seek step is 10 seconds and holding the key scales it`() {
        assertEquals(10_000L, PlaybackHud.SEEK_STEP_MS)
        assertEquals(10_000L, PlaybackHud.seekStepMs(0))
        assertEquals(20_000L, PlaybackHud.seekStepMs(6))
    }
}
