# Unhappy signatures acceptance plan

Environment: isolated app.cablegram.unhappyqa, local unavailable API, TV emulator emulator-5554 (API 36, 1080p, arm64). Existing installations/accounts are preserved. No physical-device or production-account acceptance is claimed.

1. Native ErrorScreen: decode each downloaded R2 error clip, observe one continuous loop of the ten-second clip, press physical Select to activate focused Retry; switch to a missing clip and press Retry again. PASS requires decoder advancement for all three, loop boundary, both callbacks, and released media. The fixture puts the activity view model in demo mode to disable reviewer gesture interception while a separate ErrorScreen is hosted. Deadline 15 seconds for the first loop, six seconds per decoder start; no automatic retries.
2. Native PlayerScreen: controlled HTTP 503 LAN source and delayed relay 502 phone_offline. Capture warning video, terminal error video; change fixture relay to MP4 success and press physical Select/Retry. PASS requires actual LibVLC playing callback after a new relay request and release of terminal error clip. Twenty-second recovery deadline is a test bound, not a product requirement. This simulates initial source fallback, not loss of physical Wi-Fi during a movie.
3. Maestro actual app: seed an invalid dummy credential in the isolated app with the API bound to an unavailable local port; cold launch, dismiss signature, observe Profiles unavailable, capture loaded signature, send physical Select to focused Retry and observe the error screen afterward. PASS requires visible error/retry controls and repeated recovery navigation. Thirty-second UI observation bounds; no automatic retries. Missing media or mismatched state is FAIL; unavailable emulator/tool is INCONCLUSIVE.

Retain native/Gradle/Maestro output, screenshots, failing attempts, final limitations. Remove isolated app and emulator fixture media after testing.

Follow-up authorized after first fixture failure: move media from shell temporary storage into app-private files to satisfy media-server access; precompute MockWebServer URLs off the UI thread to avoid fixture NetworkOnMainThreadException. Retain initial result.

Second native follow-up: first run with app-private media decoded all clips and passed player recovery, but the activity reviewer gesture consumed the second rapid Select on the independent ErrorScreen fixture. Put that activity model in demo mode to isolate the fixture from the pairing-only reviewer gesture; retain that failure. Final run passed both scenarios.
