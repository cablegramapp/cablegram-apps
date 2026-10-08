# TV unhappy signature videos — 2026-10-08

## Implemented behavior

- Randomly selects from the three existing public R2 `error/` clips for each visible warning/error. Recomposition retains the choice; random selections can repeat.
- General errors (profiles, library, missing/deleted media, conversion/removal failures) and terminal player errors show a prominent looping clip beside the explanation and existing recovery actions. Retry, Back, removal, conversion, and unpair confirmation keep their existing behavior.
- LAN-to-relay transitions, relay mobile-data approval waits, and Telegram-to-phone fallback use error clips. Successful return to LAN or direct Telegram keeps the normal signature collection.
- Phone-offline preparation uses error clips; ordinary preparation and private-title approval keep the normal clips. Profile/PIN/session and Telegram sign-in warnings display inline error clips beside the message.
- Relay notices after handover and poor/OK stream-quality warnings display compact clips. Player warning clips are muted and do not request audio focus; overlapping player warnings show at most one signature video. Warning duration and recovery timing remain unchanged; a recovered movie does not wait for the entire error clip.
- Recovery controls are available immediately. Signature media errors/stalls use the existing static Cablegram fallback. Native videos cannot take remote focus and release playback when removed.

## Passing evidence

- All three R2 objects returned HTTP 200 and downloaded successfully. Native decoding was checked with those exact downloaded bytes. Durations: approximately 10, 20, and 20 seconds.
- Final isolated debug and instrumentation builds passed: `.unhappyqa`, arm64-v8a, `ALLOW_NO_TELEGRAM=true`, unavailable local API `http://127.0.0.1:1/`. Production defaults were not changed. [Build](unhappy-signatures-evidence/build.txt), [fixture build](unhappy-signatures-evidence/instrumentation-build.txt).
- **45 unit tests passed**, zero failures/errors/skips: existing UI recovery/transport tests and signature gate tests. [Counts](unhappy-signatures-evidence/unit-results.txt), [Gradle output](unhappy-signatures-evidence/regressions-build.txt).
- TV emulator CG_TV_1080p, API 36, arm64, 1920×1080: native test decoded all three clips, observed a complete loop of the ten-second clip, activated focused Retry with physical Select, checked missing-file fallback and another physical Retry, and verified release. [Native result](unhappy-signatures-evidence/instrumentation.txt), [error clip](unhappy-signatures-evidence/error-playing.png), [missing-clip fallback](unhappy-signatures-evidence/error-missing-clip.png).
- Real PlayerScreen with controlled HTTP faults: failed primary LAN path, delayed relay response, warning clip, relay phone_offline error, then physical Select/Retry after the fixture relay became healthy. PASS required a new relay request, actual LibVLC playing state, and release of the terminal error clip. [Warning](unhappy-signatures-evidence/lan-relay-warning.png), [error](unhappy-signatures-evidence/relay-offline-error.png), [recovered movie](unhappy-signatures-evidence/recovered-playback.png).
- Maestro actual-app flow passed opening → Skip → Profiles unavailable, visible Retry/Unpair, physical Select, and error controls afterward. An invalid dummy credential was created only in the isolated QA installation; its API pointed at the unavailable local port. Screenshots showed the public R2 signature decoded beside readable messages and controls. Native player assertions above establish actual Retry recovery separately. [Flow](unhappy-signatures-evidence/error-and-retry.yaml), [output](unhappy-signatures-evidence/maestro.txt), [screenshot](unhappy-signatures-evidence/maestro-ui/.maestro/tests/2026-10-08_200744/error-and-retry/takeScreenshot/profiles-error.png).
- Native opening regression passed loaded normal signature, missing opening clip dismissal, and ready playback despite missing preparation clip. [Result](unhappy-signatures-evidence/opening-regression.txt).
- Screenshots were visually reviewed. Whitespace check passed. Isolated QA app/test packages and temporary emulator media were removed; existing installations/accounts were preserved.

Before opening the PR, the full TV unit suite passed (212 tests, zero failures/errors/skips); Telegram package synchronization and staged gitleaks also passed. [Pre-PR checks](unhappy-signatures-evidence/pre-pr-checks.txt).

## Retained test failures

The initial native fixture could not decode media from shell temporary storage, then crashed because MockWebServer URL creation ran on the UI thread. Moving media into app-private files and constructing URLs off the UI thread corrected both setup problems. [Initial result](unhappy-signatures-evidence/instrumentation-fixture-failed.txt).

The next run decoded all clips and passed player recovery, but the pairing activity's rapid reviewer Select gesture intercepted the independent ErrorScreen fixture's second Select. The fixture now puts that underlying activity model in demo mode before hosting ErrorScreen, avoiding pairing gesture interception; the final two-test run passed. [Intermediate result](unhappy-signatures-evidence/instrumentation-reviewer-gesture-failed.txt), [test plan](unhappy-signatures-evidence/plan.md).

## Limits

No physical TV, actual mid-movie Wi-Fi loss, authenticated relay, phone mobile-data consent, or physical decoder/audio concurrency was accepted. The native HTTP scenario exercises initial LAN fallback through the production player UI, rather than physical network handover. Profile/PIN/Telegram inline warnings and poor-quality badges compile but were not individually driven through authenticated end-to-end flows. Only the ten-second error clip was observed across a loop boundary; the two twenty-second clips were checked for real decoder advancement. Offline R2 access falls back to static Cablegram branding and recovery controls.
