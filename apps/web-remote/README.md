# Cablegram web remote

A remote control and library browser for people who can't install the Android phone app, such as iPhone
users. It is a plain web app (no build step, no dependencies) that you add to the Home Screen from Safari:
**Share → Add to Home Screen**. No App Store account or review is involved.

## What it does

- Signs in to a Cablegram account (as `client: "web"`) and pairs a TV with the 6-digit code the TV shows.
- Plays titles from the household library on the TV, and sends remote commands: play, pause, stop, seek,
  next, previous, volume, mute, and the arrow keys with OK.
- Answers "Let *TV* use your Telegram?" when a TV signs in. **Allow** opens Telegram, which asks you to
  confirm. Nothing is approved without your tap.

## What it never does

- **It never holds a Telegram session.** No login code, password or session passes through it. A household
  with no Cablegram phone app connects Telegram from the TV instead (Settings → Connect Telegram on this
  TV). See [contracts/telegram-link.md](../../contracts/telegram-link.md).
- It doesn't serve videos from the phone (a browser can't), so it can't be a media source.
- It can't link, unlink or change the household's Telegram account; the server refuses those from a web
  client.

## Run it

```sh
cd apps/web-remote
npm test          # the API client's tests (Node 20+)
npm run serve     # http://localhost:8080
```

`config.js` holds the API address (`https://api.cablegram.app`). To use another server, change it, and have
that server list this app's origin in `WEB_APP_ORIGINS` so the browser may call it.

`npm run build` assembles `dist/`, which is what gets served. To host it yourself, serve `dist/` on any static
host over HTTPS (a service worker and Home Screen install need it). Cablegram's own copy is published to
Cloudflare by `.github/workflows/web-remote.yml` using `wrangler.jsonc`.

## Files

| Path | What |
|---|---|
| `src/api.js` | The control-plane client: sign-in, token refresh, TV commands, TV login answers. |
| `src/app.js` | The screens. Server text is always set as text, never as HTML. |
| `scripts/build.mjs` | Assembles `dist/` and writes `version.txt` and `SHA256SUMS`. |
| `sw.js` | Caches the app shell only. It never caches or intercepts API calls. |
| `test/` | Node tests for the client and the command shapes the TV accepts. |
