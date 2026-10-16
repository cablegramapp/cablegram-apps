# CAB-75: phone subtitle timing preview

## Change

The dialog preview now uses TextureView and a dedicated HandlerThread. MediaPlayer creation,
preparation, listeners, controls, position reads, and release all run on that thread. Each surface
has its own identity, so an old surface callback cannot release a replacement player. The input
closes after its decoder. Controls wait for readiness, and errors stop the loading overlay.

Preparation alone did not produce a correct paused first frame on the Pixel. The player now
decodes silently until VIDEO_RENDERING_START, then pauses and enables the controls. Playback state
comes from the player. The video keeps its aspect ratio inside the dialog.

Reference: Android documents that [MediaPlayer is not thread-safe](https://developer.android.com/reference/android/media/MediaPlayer)
and that [TextureView behaves as a regular view rather than creating a separate window](https://developer.android.com/reference/android/view/TextureView).

## Validation — 2026-10-16

- Full phone `testDebugUnitTest`: 344 passed, zero failures/errors/skips.
- Phone debug build, Telegram package sync, and `git diff --check`: passed.
- Physical Pixel 8 Pro, normal app and existing local 480p episode: baseline stayed at 0:00 with
  a preview failure and MediaPlayer error `(1, -2147483648)`. The first revision restored playback
  but still showed a malformed paused frame. The final revision showed a clear paused frame,
  played with advancing time and changing frames, reopened successfully, and paused/seeks worked
  (0:25 → 0:35 after +10 seconds). Existing Dutch subtitle and saved +0.00-second timing were retained.
- `SubtitlePreviewInstrumentation` passed on the physical Pixel using a separate `.subtitleqa`
  installation and a generated red/blue H.264 fixture. It checks three dialog opens, actual pixel
  colors for the paused first frame and a frame after seek, advancing playback, pause stability,
  surface replacement, and exactly-once input cleanup.

The custom runner requires both APKs built with:

```sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest \
  -PCABLEGRAM_ABIS=arm64-v8a -PCABLEGRAM_APP_ID_SUFFIX=.subtitleqa \
  -PCABLEGRAM_TEST_RUNNER=app.cablegram.phone.SubtitlePreviewInstrumentation

adb -s DEVICE shell am instrument -w \
  app.cablegram.phone.subtitleqa.test/app.cablegram.phone.SubtitlePreviewInstrumentation
```

The 5 KB test asset was generated with FFmpeg from four seconds of red followed by four seconds
of blue, 160×90 at 15 fps, H.264/yuv420p with one-second keyframes and faststart. It contains no
user media or audio.

## Evidence and limits

Private screenshots, device observations, and the test plan are local under
`/private/tmp/cab-75-2026-10-16`. No film frames or user media are committed.

The wired device transport disappeared during setup; testing continued over its already connected
wireless transport. The local subtitle attachment check was interrupted in Android's picker before
any subtitle was saved. Online selection requires provider credentials on this phone. These paths
share AdjustContent, but their complete live journeys are not yet verified in this run. Telegram
streaming, codec compatibility beyond the observed file/fixture, and TV playback were not tested.
