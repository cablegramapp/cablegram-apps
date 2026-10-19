# Testing the TV app on someone else's LG TV

The packaged app can only be installed from a computer on the same network as the TV, so the friend (or someone at
their home) runs a few commands. You then get the results back as a photo or a QR code. Nothing here needs your
Cablegram account: the app has a **Check this TV** button on its first screen.

## What you send

`build/app.cablegram.tv_0.1.0_all.ipk` from `apps/tv-web` (about 5 MB). Make it with:

```sh
npm install
npm run build                      # uses https://api.cablegram.app; add VITE_API_BASE=… to use another server
npm run package:webos
npx @webos-tools/cli ares-package build/webos -o build      # or: ares-package build/webos -o build
```

## What the friend does (about 15 minutes)

1. **On the TV:** open the LG Content Store, install **Developer Mode**, open it and sign in with a free LG developer
   account (create one at webostv.developer.lge.com). Switch **Dev Mode Status** on and **Key Server** on; the TV
   restarts. Open the app again and note the TV's IP address and the Key Server passphrase it shows. The Dev Mode session
   lasts about 50 hours, which is plenty.
2. **On a computer on the same Wi‑Fi:** install Node.js, then the LG tools: `npm install -g @webos-tools/cli`.
3. Add the TV and fetch its key (the passphrase is the one on the TV; the username is always `prisoner`):

   ```sh
   ares-setup-device --add tv --info "{'host':'<TV IP>','port':'9922','username':'prisoner'}"
   ares-novacom --device tv --getkey
   ```
4. Install and start the app:

   ```sh
   ares-install --device tv app.cablegram.tv_0.1.0_all.ipk
   ares-launch  --device tv app.cablegram.tv
   ```
5. On the TV's first screen choose **Check this TV**. It takes about 30 seconds and shows a list of ✓ / ✗ lines and a QR
   code. **Photograph the whole screen** (or scan the QR code and send you the text it holds). Then choose **Measure
   buffer size** (about a minute) and photograph the screen again.

## What the screen tells you

| Row | Meaning |
|---|---|
| Browser | Chromium version of the TV (webOS 4 is 53, webOS 5 is 68, webOS 6 is 79, webOS 22 is 87) |
| Secure context | `crypto.subtle` is needed for direct Telegram playback |
| Video / Audio decoders | what the TV's hardware accepts through MSE; AC‑3 / E‑AC‑3 are the interesting ones |
| JavaScript speed, Telegram decryption | how slow the TV's CPU is; a 20 Mbit/s Telegram video needs 2.5 MB/s of decryption |
| Cablegram server | the server answers and the browser accepts its CORS answer from this app's origin |
| Time to first picture, 1080p decoding | the bundled test clip (1080p, about 10 Mbit/s) through the engine; dropped frames show decoder trouble |
| Subtitles from the file, Switching audio track, Seeking | the engine's features on that TV |
| MSE buffer size | how much video the TV accepts before refusing (the number the engine's buffer policy must respect) |

Put the numbers in `libs/tv-media/README.md` (the "MSE buffer limits" table).

## If something goes wrong

- *The server row fails but the internet works:* the control plane's CORS does not allow the app's origin yet
  (`WEB_APP_ORIGINS` in `apps/control-plane`). The app will not pair until it does. The diagnostics still run.
- *`ares-install` cannot reach the TV:* the TV and the computer must be on the same network, Dev Mode on, the TV's IP
  right, and the key fetched with the passphrase. Re-run `ares-novacom --device tv --getkey` after the TV restarts.
- *You want to debug live:* with the friend's computer joined to a VPN you can reach (for example Tailscale), you can run
  `ares-inspect --device tv --app app.cablegram.tv --open` yourself and see the TV's console and network tabs.

Samsung is the same idea with Tizen Studio (`tizen install`, Device Manager) and a Samsung certificate profile.
