# Private-media approval waiting screen — 2026-10-08

The approval wait now gives the Cablegram signature video most of the screen, beside a clear
phone action: open Cablegram or its notification and tap **Allow once**. The title remains visible.
Approval waits no longer show the preparation percentage, timeline, or estimated preparation time.
The existing loading-video component now uses native continuous looping and releases playback when
removed. Its other existing callers also benefit from removing the replay pause; no new scenarios
have been added. Approval API, notification, denial, timeout, and cancellation logic are unchanged.

## Checks

- Isolated TV debug APK and instrumentation APK built with `.approvalqa`, arm64-v8a, and
  `ALLOW_NO_TELEGRAM=true`. Existing installations and account data were preserved.
- `PrepareTimelineTest` (4 tests) and `LibraryFlowTest` (5 tests): all passed, no skips.
- TV emulator `CG_TV_1080p`, `emulator-5554` (API 36), 1920×1080: Maestro passed approval-heading,
  phone-instruction, automatic-start-message, and absence-of-preparation/0% assertions.
- [Screenshot](private-approval-evidence/ui/2026-10-08_185422/approval-screen/takeScreenshot/approval-wait.png)
  was visually inspected: signature clip and all instructions were readable with a two-line title.
- Native harness hosted the actual `PrepareStatusScreen` with a downloaded copy of the first
  existing public 10-second signature clip. The final run observed **four loop boundaries** and
  a maximum **624 ms** gap between decoder-position advances, with no 10-second replay pause.
- Maestro's corrected Back flow passed. Native assertions verified removal and stopped playback.
  [Instrumentation](private-approval-evidence/instrumentation.txt),
  [loop/release result](private-approval-evidence/loop-log.txt),
  [UI flow](private-approval-evidence/approval-screen.yaml), and
  [Back flow](private-approval-evidence/cancel.yaml) are retained.
- Whitespace check passed. Isolated app/test packages and the temporary emulator clip were removed.

## Corrective checks

- Existing `PlayerFaultHarnessTest` did not compile against the current required `onPlayerEvent`
  callback. Updated its log callback to the current three-argument signature; the test APK then built.
- First install command assumed the wrong APK filenames and did not install anything. Read the
  generated APK metadata, corrected the paths, and installed only the isolated packages. The initial
  failed instrumentation output is retained as `instrumentation-install-path-failure.txt`.
- The first native run passed four loops and Back cleanup, but the harness closed its activity before
  Maestro could assert the transient post-Back text. Retained that native result and the failed UI
  report under `cancel-ui`. Added an eight-second observation window in the test harness and reran;
  both the native assertions and corrected UI flow passed (`cancel-corrected-ui`).

## Limits

These are emulator checks of the real screen in an isolated harness, not a complete phone-to-TV
approval journey. No real-TV coverage, remote HTTP playback check, audio assessment, or new coverage
of approval/denial/timeout backend behavior is claimed. Poster-present layouts and increased system
font sizes were not separately exercised. The signature clip's audio settings were preserved.
