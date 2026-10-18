# Arabic and Farsi timeline verification

The phone subtitle timeline and TV player timeline keep their physical left-to-right
ordering inside an RTL interface. The existing `LtrControls` scopes cover the slider,
transport controls, TV timeline preview, progress bar and elapsed/total text.

## Pixel subtitle controls

Build an isolated app and its instrumentation APK from `apps/android-phone`:

```sh
./gradlew -PCABLEGRAM_APP_ID_SUFFIX=.timelinecheck -PCABLEGRAM_ABIS=arm64-v8a assembleDebug assembleDebugAndroidTest
```

Install `build/outputs/apk/debug/cablegram-phone-debug.apk` and
`build/outputs/apk/androidTest/debug/cablegram-phone-debug-androidTest.apk` on the
selected Pixel. Run only the relevant class, with an explicit device serial:

```sh
adb -s "$PIXEL_SERIAL" shell am instrument -w -r \
  -e class app.cablegram.phone.SubtitlePlaybackDirectionTest \
  app.cablegram.phone.timelinecheck.test/androidx.test.runner.AndroidJUnitRunner
```

Both Arabic and Persian tests must pass. They render the production subtitle controls
inside an RTL composition, tap at the physical left quarter, assert a position near
25%, check rewind is physically left of forward, and drag right to increase time.
Screenshots are saved under the app's external-files directory. Capture uses Compose
rather than a second UiAutomation connection, so Maestro can remain connected.
Espresso 3.7.0 avoids the reflective InputManager lookup that fails on Android 17.
These tests exercise control behavior with a controlled position; they do not play
or synchronize a subtitle video.

## TV emulator playback

Build the isolated TV app and instrumentation APK from `apps/android-tv`, using the
same Gradle arguments as above. Install its two APKs on the chosen TV emulator.
Use a video substantially longer than the requested resume position:

```sh
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=10 -t 120 -an \
  -c:v libx264 -preset ultrafast -crf 34 -pix_fmt yuv420p \
  -movflags +faststart timeline-120s.mp4
```

Serve this fixture over HTTP on host loopback with support for HEAD, byte ranges,
Content-Length and Content-Range. Forward its port only to the selected emulator:

```sh
adb -s "$TV_SERIAL" reverse tcp:18769 tcp:18769
adb -s "$TV_SERIAL" shell am instrument -w -r \
  -e class app.cablegram.player.PlayerFaultHarnessTest \
  -e locale fa -e url http://127.0.0.1:18769/timeline.mp4 \
  -e resume 30 -e hold 120 \
  app.cablegram.timelinecheck.test/androidx.test.runner.AndroidJUnitRunner
```

Repeat with `-e locale ar`. The player preflight accepts HTTP(S), so a `file:` URL
is unsuitable. A 13-second video with a 15-second resume point is also unsuitable.
The harness's successful exit alone does not establish successful playback.

Use Maestro to inspect the actual player before interacting. Pause playback using
Media Play/Pause. With the transport row focused, Down enters the timeline. Send
Right immediately after Down and capture the preview. After the 1.2-second scrub
commit, check that elapsed time and the decoded frame have advanced by 10 seconds.
The commit returns focus to the transport row: send Down again before Left to
verify a 10-second rewind. Do not confuse transport-button focus changes with seeks.

Verify progress fills from the left and its thumb moves right for forward seeking,
elapsed precedes total duration, rewind is physically left of forward, and surrounding
Arabic/Farsi text stays RTL. Capture screenshots, hierarchy text and `FaultHarness`
playback logs. Stop after 120 seconds per locale, or stop sooner with Back twice.
Do not retry an unknown failure without first identifying the compromised precondition.

## Evidence from 2026-10-18

Only the Pixel 8 Pro (Android 17/API 37) and `CG_TV_1080p` emulator (Android 16/API 36)
were used for this verification. Both phone instrumentation tests passed. TV Farsi
seeking moved `0:47 → 0:57 → 0:47`; Arabic moved `0:43 → 0:53 → 0:43`, with total
`2:00`. Screenshot review confirmed left-to-right progress and RTL surrounding text.
Session logs, screenshots, hierarchy snapshots and the detailed result report are
in `/tmp/cablegram-timeline-qa/`. This is emulator TV evidence, not physical TV
coverage, and does not test phone-to-TV commands or account/language propagation.

Remove the isolated `.timelinecheck` app and test packages, the reverse port and
temporary media server after testing. Preserve the normal app installations and data.
