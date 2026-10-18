# Cablegram TV (Samsung Tizen and LG webOS)

One plain TypeScript + Vite web app, packaged twice: a Tizen `.wgt` for Samsung TVs and a webOS `.ipk` for LG TVs.
It follows the same contracts as the Android TV app (`../../contracts`): pairing PIN, profile picker and PIN,
library, resume position and progress.

## Status

Works: pairing (verified against the real control plane), profiles and PIN unlock, library rows (Continue watching,
My list, All titles), D-pad navigation, playback with progress saving, resume.

Plays: copies on the phone (through the relay) and copies in the household's own cloud (`/api/playback/resolve`).

Does not play yet:
- **Telegram-hosted titles.** The Android TV app reads them with TDLib, which does not run in a TV web view. The plan
  is an MTProto client in JS (for example mtcute) published as its own open-source library; until then the TV says so.
- **Private titles** (need phone approval, `/api/playback/private-approvals`).
- **Phone over LAN.** A browser cannot pin the phone's self-signed certificate, so the relay carries phone copies.
- Pairing QR code (the PIN and the address are shown as text), subtitles, remote commands from the phone,
  UI languages other than English.

## Server requirement: CORS

The control plane only answers browser origins listed in `WEB_APP_ORIGINS` (`apps/control-plane/src/http/cors.ts`).
A packaged TV app loads from `file://` (Tizen) or a local origin (webOS), so one of these is needed before a real TV works:
allow those origins on the control plane, or host the app on an origin we control and point the TV at it.
For simulators and local testing use the dev proxy instead:

```sh
node scripts/dev-proxy.mjs 8787 &
VITE_API_BASE=http://localhost:8787 npm run build
```

## Build, test, package

```sh
npm install
npm test                 # api, relay URL and D-pad navigation tests
npm run build            # type-check + legacy-compatible bundle (Chromium 53+) in dist/
npm run package:webos    # stages build/webos, then: ares-package build/webos -o build   (npm i -g @webos-tools/cli)
npm run package:tizen    # stages build/tizen, then: tizen package -t wgt -s <cert profile> -- build/tizen
```

The webOS package builds and validates with `ares-package`. The Tizen package needs a Samsung certificate profile
(Tizen Studio Certificate Manager), which is yours to create.
