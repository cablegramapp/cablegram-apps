# Device test matrix

What was run, on what, and what is left. Each row has a LAN column (phone and TV on one network) and a
relay column (TV reaches the phone through `api.cablegram.app`'s relay).

Results use: **pass**, **fail**, **not run** (nothing was tried), **needs physical device**, **blocked**
(with the reason). Fill the blank cells as runs happen; one line per run in the run log below.

## How the emulator runs were set up

- Emulators sit behind separate NATs, so LAN playback needs the phone built with
  `-PCABLEGRAM_LAN_HOST_OVERRIDE=10.0.2.2` and `adb -s <phone> forward tcp:8765 tcp:8765`. The TV then
  reaches the phone's server at `10.0.2.2:8765`.
- Relay playback needs an account with a verified email (free relay) and a device that has not already
  served the limit of free households (`relay_free_device_limit`, default 2 per device).
- A TV that cannot open a WebSocket falls back to HTTP polling; see "Found while testing" below.

## Matrix

"Physical" is the Pixel 8 Pro and the Chromecast with Google TV on the same home Wi-Fi (the Chromecast at
192.168.3.140), LAN path, 2026-10-03. "Emulator" is the earlier relay run against the test VPS.

| Row | LAN | Relay | Notes |
|---|---|---|---|
| Pair (PIN from the TV, confirmed on the phone) | pass (physical) | pass (emulator) | The PIN rotates quickly; type it soon after reading it. |
| Import (video from the phone's storage) | pass (physical, one clip and a multi-select of three) | pass (emulator) | The picker is multi-select; the app asks the approval question and the title lookup once per file. |
| Select TV | pass (physical) | pass (emulator) | Pairing a second TV selects it; the chooser lists every household TV, stale ones included. |
| Prepare (title is made ready before playing) | not run | not run | |
| Play | pass (physical; H.264 clip, TV timecode advancing) | pass (emulator, 2026-10-02) | The relay run used a verified email and a device under the free limit. |
| Pause / seek / resume | pause: pass (physical: mini-player and notification; TV timecode froze); seek and resume: not run on physical | pause and resume: pass (emulator); seek: not run | Seek is unit-tested only. |
| TV offline (TV app stopped) | pass (physical) | pass (emulator) | Phone said "Sent to TV…", then "TV didn't confirm. Check the TV." about 8 s later, then cleared to the TV name about 6 s after that; its pause/play state never changed. |
| TV restart | app restart: pass (physical: force-stop, relaunch, pick the profile, play again worked); device reboot: not run | not run | A reboot was not tried: wireless adb may not come back on a Chromecast. |
| Phone in background | pass (physical: app sent Home, the notification's Pause froze the TV and the notification read "Paused on TV") | pass (emulator, notification remote) | Not a kill under memory pressure. |
| Phone in doze (`adb shell dumpsys deviceidle force-idle`) | pass (physical: deep state `IDLE`, the TV played the 20 s, 70 MB clip to the end with no error) | not run | Short clip. A longer stream under Doze, and the notification remote in Doze, were not tried. |
| Network change (Wi-Fi to mobile, or a drop and return) | not run | not run | adb was over Wi-Fi, so toggling Wi-Fi would have cut the connection. Needs USB. |
| Source unavailable (phone app killed during playback) | pass (physical) | not run | After the phone app was force-stopped, the TV kept playing the 12 MB clip (fully cached); for the 70 MB clip it showed "Your phone isn't reachable. Open Cablegram on the phone and check that it has internet." about 5 s later, without crashing. |
| Codec: H.264 (8-bit, 1080p) | pass (physical, hardware `c2.amlogic.avc.decoder`) | pass (emulator) | |
| Codec: HEVC (8-bit, 1080p) | pass (physical, hardware `c2.amlogic.hevc.decoder`) | not run | |
| Codec: 10-bit (H.264 High 10, 1080p) | pass (physical, software `avcodec`: the hardware decoder reported "not supported") | not run | No late-frame warnings in the log; smoothness was judged from the log and screenshots only. |
| Codec: high bitrate (H.264, 30 Mbps, 1080p) | pass (physical, hardware AVC, no late-frame warnings) | not run | The relay's free plan caps at 4 Mbps, so a relay run needs a paid household. |

## Still open

- Relay on the physical devices (needs a verified email and a device under the free limit).
- Network change and device reboot (need USB, or a way to restore wireless adb).
- Seek and resume on the physical devices, and a longer stream under Doze.
- Prepare.

## Found while testing

- **TV completes a command over HTTP with an empty JSON body, and the control plane answers 400.**
  The TV posts `POST /api/control/commands/:id/complete` with `Content-Type: application/json` and no
  body; Fastify refuses it (`Body cannot be empty when content-type is set to 'application/json'`), and
  the TV retries once per second. It only shows when the WebSocket is unavailable (here, a counting
  proxy that did not pass upgrades), so the HTTP fallback never confirms a command and keeps hitting
  the server (about 180 failed calls in a few minutes). Not fixed; either the server accepts an empty
  body on `complete`, or the TV sends none.
- **The TV chooser lists every household TV, including ones whose app data was cleared.** After
  re-pairing an emulator the phone still lists the old TV rows, with the same device name, and a command
  sent to a stale one just expires. Revoking unused TVs hides them.
- **The phone's LAN server uses fixed port 8765.** A second Cablegram install on the same phone (here, a test copy
  next to a personal debug build) cannot start its server, so the TV says "Your phone isn't reachable" with no
  hint of the cause. Stop the other install before testing.
- **After account deletion the TV stays signed in.** The control plane treats a TV device whose row no longer
  exists as "not revoked" (`isTvRevoked` in `src/http/authorize.ts`), so the TV's token keeps working and it just
  shows an empty library. This fails step 3 of the release gate in `docs/play-data-safety.md`; not fixed.
- **Emulators are heavy.** Two emulators plus Gradle builds on one laptop made the runs slow, and both
  emulators eventually died. Run the matrix with one emulator at a time where possible.

## Run log

| Date | Row | Path | Phone (model, OS) | TV (model, OS) | Network | Result | Notes |
|---|---|---|---|---|---|---|---|
| 2026-10-02 | Pair, import, select TV, play, pause/resume, TV offline, notification remote | Relay | Pixel_10_Pro AVD, API 37, arm64 | CG_TV_1080p AVD, API 36 Android TV, arm64 | Emulator NAT, internet to `api.cablegram.app` | pass | Debug builds from `fix/confirmed-remote-commands`. |
| 2026-10-03 | Pair, import, play | LAN (forwarded) | Pixel_10_Pro AVD, API 37, arm64 | CG_TV_1080p AVD, API 36 Android TV, arm64 | Emulator NAT, host-forwarded port 8765 | pass | R8 release builds from `build/apk-size`, against a local control plane. |
| 2026-10-03 | Pair, import, select TV, play, pause, TV offline, background, doze, source unavailable, codecs | LAN | Pixel 8 Pro, Android 17 (API 37), arm64 | Chromecast with Google TV, Android 14 (API 34), armeabi-v7a | Home Wi-Fi, same subnet | pass | Debug builds from `origin/main` installed as `.verify` copies next to the owner's own builds, against `api.cablegram.app`; removed afterwards. |
| 2026-10-03 | Account deletion (release gate step 3) | n/a | Pixel 8 Pro | Chromecast | Home Wi-Fi | sign-in fails: pass; rows gone: pass; TV signed out: **fail** | See "Found while testing". |
