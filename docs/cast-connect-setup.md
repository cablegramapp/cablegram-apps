# CAB-20 Cast Connect setup and spike gate

The mandatory real-device spike passed for release-key and debug-key sideloaded launch and synthetic
LOAD delivery, with release physical standby wake observed by the owner. Debug standby remains optional and not run.
The relay remains the authority for playback. The spike does not resolve or play actual media.

Local preparation verification (2026-10-06): both apps passed
`./gradlew testDebugUnitTest assembleDebug --offline -PALLOW_NO_TELEGRAM=true`. Both generated an empty
`CABLEGRAM_CAST_APP_ID` without configuration. The web receiver passed a local CAF stub check for
initialization, LOAD rejection, and absent-SDK rendering. This does not establish real Cast coverage.

## Owner setup

Receiver registered by the owner: **938FB492**, Custom Receiver, Cablegram, with URL
`https://cablegram.app/cast/`. On 2026-10-06, the owner confirmed receiver **938FB492** linked to `app.cablegram`, the
Chromecast registered and **Ready for Testing**, and the homepage deployed. A subsequent live HTTP
check returned **200** and verified the expected CAF receiver page.
The receiver ID is now configured in both apps' ignored `local.properties` and verified in generated
BuildConfig. It is not a committed default: missing configuration still yields an empty string.

Throwaway worktree: `/private/tmp/cab-20-cast-spike`, branch `spike/cab-20-cast-connect`, commit
`869c319`. The synthetic sender and log-only TV receiver compile; phone/TV debug builds and the TV
release variant passed. The local release build is unsigned. GitHub Actions subsequently signed the TV spike APK using
the existing release secrets; signing alone does not establish device coverage.
Build logs and APK hashes are under `/private/tmp/cab-20-qa/`. The owner subsequently authorized uninstall, data clear, and downgrade for the spike. Both
release-key APKs were installed after exact-package reinstalls; subsequent reviewed launch missions passed.
A copy of the TV release variant is signed with the existing debug key at
`/private/tmp/cab-20-qa/cablegram-tv-release-debug-key.apk`; `apksigner verify` passed. The separate release-key APK is now available from the GitHub Actions signing run below. Debug-key signing is preparation, not a
sideloading test result by itself. DBG-I subsequently installed that artifact; DBG-A2 and DBG-LOAD established its actual cold launch and payload delivery.

1. Register a **Custom receiver** in the Cast developer console and link Android TV package
   `app.cablegram`. Supply the resulting receiver app ID; do not use the default media receiver ID.
2. Register the test Chromecast's serial number in the console.
3. Publish `CableGram-homePage/cast/index.html`. Its intended console receiver URL is
   `https://cablegram.app/cast/`; confirm that the deployed URL serves this page before configuring it.
   The page displays “Cablegram needs a Google TV” and rejects LOAD requests on web receivers.
4. Set `CABLEGRAM_CAST_APP_ID` in each Android app's ignored `local.properties`, or pass
   `-PCABLEGRAM_CAST_APP_ID=<receiver-app-id>` to Gradle. A Gradle property takes precedence.
   Both apps expose `BuildConfig.CABLEGRAM_CAST_APP_ID`; absent configuration produces an empty string.
   The SDK integration must treat that as disabled and retain the relay path.

In the console, edit application **938FB492** and associate Android TV package **app.cablegram**.
On Devices, choose **Add New Device** and use the Chromecast's **software (Cast) serial number**,
not its hardware or ADB serial. Obtain it by casting the developer console page to the Chromecast,
or from the TV's Settings > System > Cast / Google Cast after enabling developer mode. Google says
to wait fifteen minutes, verify **Ready for Testing**, and restart the Cast device afterward.
Without developer-device registration, Google's documented sideloading restriction prevents this spike:
Cast Connect otherwise works only for installations from Google Play.

See [Cast Connect console setup](https://developers.google.com/cast/docs/android_tv_receiver/core_features#cast_developer_console_setup)
and [device registration and software serial](https://developers.google.com/cast/docs/registration#register_your_devices).

## Spike evidence required before PR A

Use a throwaway spike branch and the real Chromecast with Google TV and phone on the same network.
Send only `{ "commandId": "...", "targetDeviceId": "..." }` as LOAD customData; use a synthetic video
ID for contentId. The spike handler logs receipt without playing or resolving media. Do not transmit
credentials, media URLs, or tokens.

Record each attempt separately with phone send time, command ID, TV `onCreate` time, TV LOAD receipt
and parsed customData, installed package/version, APK hash, and signing certificate fingerprint.

| Scenario | Required evidence | Status |
| --- | --- | --- |
| Closed TV app, session only | Whether session establishment opens the app before LOAD | Release PASS: session LAUNCH opens app before LOAD, about 0.8 seconds after Connect |
| Closed TV app, LOAD | App opens; intact customData; LOAD-to-onCreate duration well below 300 seconds | Release PASS: session already opened app; subsequent LOAD callback about 111 ms after send |
| TV standby, LOAD | Physical TV wake through CEC and app launch | Release PASS: CEC4, owner observed real standby wake on Connect before LOAD; LOAD about 128 ms |
| Sideloaded release-key APK | Cold launch and intact customData with verified release signing | PASS: A-REL and CEC4 |
| Sideloaded debug-key APK | Cold launch and intact customData with verified debug signing | PASS: DBG-A2 reviewed cold launch; DBG-LOAD exact two-field LOAD/status 0, cold-session and running |

Stop if launch/wake, intact customData, or either sideloaded signing variant fails. Missing evidence
is inconclusive. The reviewed spike results were posted to CAB-20 on 2026-10-06, before PR A; do not infer device passes
from builds or phone UI. Main TV, phone, and contract changes follow only after the gate passes.

Replacing an installed APK with another signing key can require uninstalling and losing its local
pairing/profile state. Resolve test-state preservation before the signing variants are installed.

References: [Google CAF receiver setup](https://developers.google.com/cast/docs/web_receiver/basic)
and [LOAD interception](https://developers.google.com/cast/docs/web_receiver/core_features).

## GitHub Actions signing

The owner authorized signing inside Actions rather than retrieving secret values.
[Signing run 37495965006](https://github.com/cablegramapp/cablegram-apps/actions/runs/37495965006)
builds commit `0b6a0b5d0414b92a87cde1a0a6b41e5cfc963918` on the throwaway spike branch.
The TV artifact is downloaded to `/private/tmp/cab-20-qa/ci-release/tv/cablegram-tv-cab20-release-key.apk`.
Local `apksigner verify` and SHA-256 checksum verification passed. Its public signing certificate
SHA-256 is `02d4f66d77fd08ad94adafa7b2b2f5c2e23a485b5d666323f354cf8d6ce2f913`.
No keystore or signing password is included in the artifacts. This resolves release signing
preparation; subsequent A-REL and CEC4 established real-device launch and sideloading behavior.

The phone artifact is downloaded to `/private/tmp/cab-20-qa/ci-release/phone/cablegram-phone-cab20-release-key.apk`; its local signature and checksum checks also passed with the same certificate and source commit. The workflow completed successfully for both apps.

The sender's discovery setup was subsequently corrected to use the Cast framework's merged selector
and active scanning while its chooser is visible. [Signing run 37500354630](https://github.com/cablegramapp/cablegram-apps/actions/runs/37500354630)
built commit `22cf848cdecac7ba245e5122d7b1409c86fe7fed`. The corrected phone APK passed local
checksum and signature verification with the same release certificate and installed successfully.
Before the Chromecast restart, its chooser still showed no Cast routes after a 25-second observation.
That observation was a discovery blocker, not evidence that Cast Connect launch or LOAD handling fails.

Network evidence confirmed the phone uses `wlan1` at `192.168.3.179/24` and the TV uses `wlan0`
at `192.168.3.140/24`. Same-subnet addresses do not establish multicast reachability.
After a restart attempt the TV disappeared from ADB; the owner reported that wireless debugging
had been disabled and then re-enabled it. Mission D8 confirmed the TV connected again with boot
complete and both exact packages installed. A fresh 25-second sender scan still showed no Cast
routes. Google Home could discover the Chromecast. Subsequent availability probes established that
receiver `938FB492` was reported unavailable because the hardware serial had been registered instead
of the Cast software serial. Correct registration, backend propagation and a later power-cycle made
the receiver available; D9 then found the unique Chromecast route without further sender changes.
Release-key A-REL and CEC4, and debug-key DBG-A2/DBG-LOAD, subsequently passed as listed above. CEC1's earlier screensaver observation
was reclassified as inconclusive; only CEC4 establishes physically observed real standby wake.
Reviewed missions and evidence are tracked in `/private/tmp/cab-20-qa/session-summary.md`.

## Authorized signing transitions

The owner explicitly authorized uninstall, data clear, and downgrade for CAB-20 testing.
I1 TV replacement was rejected with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; existing data was not
cleared by that attempt. I2 then uninstalled only `app.cablegram` and installed the release-key TV
APK. The phone replacement was likewise rejected; I2 uninstalled only `app.cablegram.phone` and
installed the release-key phone APK. All four planned uninstall/fresh-install operations reported
Success. Separate `.drivetest` and `.metatest` packages were excluded. I2 passed installation only;
no Cast behavior follows from that result. Evidence: `/private/tmp/cab-20-qa/evidence/I2/attempt-1/`.

Debug cold-launch estimate was 1.36 s ±0.26 s after Connect. DBG-LOAD callback estimates
83/133 ms have conservative ±167 ms combined clock uncertainty. Both LOADs had exact two-field
customData and sender status 0. The original DBG-A2 shortened-action harness failure remains
recorded; review established the full LAUNCH action before LOAD. Process death during an existing
Cast session and debug physical standby were not tested. These are spike results, not production
profile selection, playback or control acceptance.

## Production preferences and QA

The phone's Settings preferences expose **Open TV with Cast** (default off) and **Show Remote
controls** (default on). Cast requires a configured receiver ID and Google Play services; absent
routes retain the relay path. Discovery is active only during the visible picker search and returns
to foreground discovery afterward. The selected paired TV stays the target even when asleep.

`quality/maestro/phone/cast-remote-flags.yaml` expects authenticated main UI with Cast off and Remote
on. It hides and restores the Remote tab through tagged switches, leaving both original values.
`nav_remote` exists only with Remote enabled; `detail_play_on_tv` retains its original identity.
On the Pixel 8 Pro, Maestro runs of this flow timed out before reaching the switches; those attempts
remain recorded as inconclusive (FLAGS1). A direct, guarded ADB run of the same steps passed: the
Remote tab hid with Remote off and returned when restored, and Cast stayed at its original value (off).
Production playback cases and their actual device results belong in `docs/device-test-matrix.md`.
