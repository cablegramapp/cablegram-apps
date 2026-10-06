# CAB-20 throwaway Cast Connect spike

Branch: `spike/cab-20-cast-connect`. Receiver app: `938FB492` (Custom Receiver, Cablegram).
This branch is a launch/logging experiment, not the production integration. LOAD never triggers
playback. No URL, token, credential, or production command is sent by the synthetic sender.

## Build

Set `sdk.dir` and `CABLEGRAM_CAST_APP_ID=938FB492` in both ignored app `local.properties` files.
From each app directory:

```sh
# Phone
./gradlew assembleDebug -PALLOW_NO_TELEGRAM=true -PCABLEGRAM_ABIS=arm64-v8a
# Chromecast TV
./gradlew assembleDebug assembleRelease -PALLOW_NO_TELEGRAM=true -PCABLEGRAM_ABIS=armeabi-v7a
```

The release APK is unsigned until signed using the existing release key. A release build and a
release signature are separate checks. Never report release-key coverage from an unsigned APK or
an APK signed only with the debug key. The spike includes no Telegram credentials.

## Preconditions

- Link `app.cablegram` to the receiver in the Cast console.
- Register the Chromecast's **software Cast serial** and wait for **Ready for Testing**; Google
  specifies fifteen minutes and a subsequent device restart.
- Publish `CableGram-homePage/cast/index.html`; the configured URL currently returns HTTP 404.
- Check both device ABIs and installed signer identities before installation. These APKs use the
  real package names: a signer mismatch requires uninstalling and loses application data. Preserve
  existing state and obtain specific reset authorization before any uninstall.
- Sign TV APKs separately with the actual release key and debug key; retain hashes and public cert
  fingerprints for the reviewed QA report.

## Sender controls

Launch phone activity `app.cablegram.phone/.cast.SpikeSenderActivity` after installing the approved
build. Route buttons perform **session selection only**. “Inspect session” displays connection state.
“Send synthetic LOAD” sends a generated UUID as commandId and `cab20-spike-tv` as targetDeviceId;
contentId is `cab20-spike-title`, with title metadata and no content URL. “End Cast session” ends it.

Filter phone and TV logcat on tag `CAB20_SPIKE`. Correlate `CONNECT`, `ROUTE_SELECTED`, `SESSION`,
`SEND_LOAD`, `LOAD_RESULT` with TV `ON_CREATE`, `ON_NEW_INTENT`, and `LOAD` records. Each logs wall time
in milliseconds. Do not dump arbitrary intent extras or credentials. Calibrate device clock skew
before calculating cross-device timing; use physical screen observations for CEC wake evidence.

TV Application registers the LOAD callback before MainActivity passes intents to MediaManager.
It creates an idle MediaSessionCompat and starts/stops the Cast receiver with process lifecycle.
Both LAUNCH and LOAD filters are present, with singleTask delivery. LOAD validates exactly two
nonblank string fields and logs only that synthetic data; malformed data returns a failed task.

## Gate and evidence

Debug sender and TV APK builds have passed. No Cast cases have run. Main implementation and PR A
remain gated on reviewed real-device evidence for cold launch, physical standby wake, intact
customData, both sideloaded signing variants, launch timing, and session-only versus LOAD behavior.
Post spike results to CAB-20 only after reviewing evidence. QA artifacts are under
`/private/tmp/cab-20-qa/`; execute through the Sol → Luna workflow.

SDK/API reference: https://developers.google.com/cast/docs/android_tv_receiver/core_features
