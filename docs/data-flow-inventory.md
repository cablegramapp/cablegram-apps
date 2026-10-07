# Data flow inventory

Every flow of user data that leaves a phone or TV: what it is, where it goes, why, how long it is kept, how it is
deleted, and which outside provider is involved. This is the source for [play-data-safety.md](play-data-safety.md)
and the privacy policy (`CableGram-homePage/privacy.html`). When a flow changes, change this file first.

Checked against code on 2026-10-07: this repo at `8d33090` (main, after CAB-25, CAB-26, CAB-27, CAB-29, CAB-37), the
closed-source server repo `CableGram` at `42b2cae` (main, after CAB-27, CAB-29, CAB-35), and the production env keys
copied by `deploy/deploy-vps.sh`. Live checks the same day: `https://api.cablegram.app/delete-account` answers 200.
Log retention on the VPS was last checked on 2026-09-30 and not rechecked.

## Who receives data

| Party | Role | What it gets | Where it is configured |
|---|---|---|---|
| Cablegram service (`api.cablegram.app`) | Cablegram itself | Everything in the server rows below | Contabo VPS (Germany), PGlite database, nginx, systemd journal |
| Contabo GmbH | Hosting | Everything stored on or passing through the VPS | VPS provider |
| Cloudflare | Mail, web remote hosting, public video bucket | Email address and mail text (mailer Worker); IP address and requests of web-remote users (`app.cablegram.app`); IP address of TVs that load the loading-screen videos (`pub-…r2.dev`) | `CableGram/apps/mailer`, `apps/web-remote/wrangler.jsonc`, `DEFAULT_LOADING_VIDEOS` in the TV app (the server has no `/api/videos/loading-videos` route, so the TV always uses this list) |
| Google (Gemini API) | Title lookup | Text typed in the cover editor's search box, cleaned (links, emails, @handles, #hashtags and Telegram lines removed, at most 800 characters) | `GOOGLE_AI_STUDIO_API_KEY` is set in production |
| TMDB | Title lookup and artwork | From the server: a title, year, season and episode. From phones and TVs: image requests, so TMDB sees their IP address | `TMDB_API_KEY` is set in production |
| OpenSubtitles | Subtitle search | From the server: subtitle languages, the OpenSubtitles file hash, IMDb or TMDB id (or the title when there is none), season, episode, year | `OPENSUBTITLES_API_KEY` is set in production |
| SubDL | Subtitle search | From the server: subtitle languages, IMDb or TMDB id or title, season, episode | `SUBDL_API_KEY` is set in production |
| Google Drive | The household's own storage (optional) | OAuth sign-in from the server; video uploads straight from the phone; playback straight to the TV | `GOOGLE_OAUTH_CLIENT_ID` is set in production; scope `drive.file` |
| Cloudflare R2 (the user's own bucket) | The household's own storage (optional) | The user's bucket keys (given to the server); video uploads straight from the phone; playback straight to the TV | The user's own Cloudflare account |
| Telegram | The user's own Telegram account (optional) | Everything a Telegram client sends: phone number, login code, two-step password, session, channel and file requests, uploads | TDLib in both apps; the server never contacts Telegram |
| Google (Cast SDK) | Casting from phone to TV | Anonymised Cast usage and device data, collected by the SDK itself | `play-services-cast-framework` (phone), `play-services-cast-tv` (TV) |
| Web sites the user imports from | Source of a web video | The page request comes from the server (VPS IP address); playback comes from the TV (TV IP address) | `web-import/extractor.ts` |

No analytics, crash-reporting or advertising SDK is in either app, and neither reads the advertising ID. The Cast SDK
is the only third-party SDK that sends data on its own.

## Account and household

| Data | From → to | Why | Kept | Deleted by |
|---|---|---|---|---|
| Email address | Phone → service; service → Cloudflare mailer → recipient | Sign-in, verification and reset codes | Until account deletion | Account deletion |
| Password | Phone → service over HTTPS, stored as a bcrypt hash | Sign-in | Hash until account deletion | Account deletion |
| Display name, household name | Phone → service | Shown in the apps | Until account deletion | Edit in app; account deletion |
| Email codes (verify, reset) | Service → mailer → user's inbox; stored hashed | Confirm the address, reset the password | Row stays after it expires or is used | Account deletion |
| Profile names, avatar choice, profile PIN (hashed) | Phone → service | Profiles | Until the profile or account is deleted | Delete profile; account deletion |
| Subtitle preferences (languages, SDH, forced, auto-select) | Phone → service | Subtitle search defaults | Until account deletion | Account deletion (cascade on user) |

If other people share the household, deleting one account removes that user, their sessions and email codes, and
revokes their phones (name, LAN address and owner cleared). The household, its library, profiles and progress stay
for the others.

## Devices, pairing and sessions

| Data | From → to | Why | Kept | Deleted by |
|---|---|---|---|---|
| Device id, kind, name (phone and TV) | Device → service | Household device list | Until the device row is deleted | Account deletion. Removing a device revokes it; the row stays |
| `ANDROID_ID` | Phone and TV → service, raw over HTTPS; the server stores only an HMAC-SHA256 with a server key | Limit free relay per physical device | `devices.hardware_hash` until account deletion; `relay_device_usage` and `device_households` are **kept after account deletion** | Not deleted (see below) |
| Device secret, refresh tokens | Service → device; stored as hashes | Authentication | Until revoked or account deletion | Sign out, remove device, account deletion |
| Pairing PIN, pairing token, LAN capability | TV ↔ service ↔ phone; PIN and token stored as keyed hashes | Pair a TV, give it a LAN credential | Pairing row stays (marked expired) until account deletion. `lan_capability_plain` holds the capability for the phone to pick up | Account deletion |
| Phone's LAN address and port | Phone → service | TVs find the phone at home | Until replaced or account deletion | Account deletion; cleared when a co-member's account is deleted |
| Trust level and expiry for a TV (home or temporary) | Phone → service | Temporary TVs (hotels) expire | Until account deletion | Account deletion |
| IP address of every request | Device → nginx | Operating the service | nginx access log (all paths except `/relay/` and the Google OAuth callback), 15 days | Log rotation |

Kept after account deletion, on purpose and unlinked from any account: `relay_device_usage` (relay bytes per hashed
hardware id and month) and `device_households` (hashed hardware id and a household id that no longer exists). Without
them a new account on the same phone would get a fresh free allowance. The policy says so.

## Library and media metadata

| Data | From → to | Why | Kept | Deleted by |
|---|---|---|---|---|
| Title, year, description, genres, cast, director, season/episode, media type | Phone → service; TMDB → service | Library on every TV | Until the title is removed or account deletion | Remove title; account deletion |
| Original file name, size, stable source key, source fingerprint, phone location id, availability, private flag | Phone → service | Match files across scans, show availability | Same | Same |
| Telegram source: channel id, message id, Telegram file id, file name, size, caption-derived title | Phone → service | Telegram titles in the library | Same. Removed titles leave a tombstone (title, message ids) | Account deletion. Disconnect Telegram hides them |
| Web source: page URL, canonical URL, provider, provider video id, playback URL and headers | Phone → service; service fetches the page | Play a web video on the TV | Until removed or account deletion | Same |
| Collections, My List | Phone → service | Library organisation | Same | Edit; account deletion |
| Removed-title tombstones | Service | Stop a removed Telegram title coming back on the next scan | Until account deletion | Account deletion |
| Library jobs (web analysis) | Service | Progress of an import | Row stays after it finishes | Account deletion |

## Artwork

| Data | From → to | Why | Kept | Deleted by |
|---|---|---|---|---|
| Catalog poster and backdrop links | TMDB → service; images load from `image.tmdb.org` on phone and TV | Artwork | Link until changed or account deletion | Same |
| A cover made on the phone (a still of the user's video, or the user's own image) | Stays on the phone; TVs load it from the phone over the LAN (CAB-29) | Artwork | On the phone | Phone app |
| A cover the user saves to the household ("Save artwork to household", confirmed) | Phone → service, JPEG/PNG as base64, at most 1.5 MB, in `media_items.poster_data` | Same cover on every TV | Until removed, replaced, the video is marked private, or account deletion | `DELETE /api/catalog/items/:id/poster`; account deletion |

A saved cover is served without credentials to anyone who has the item id (`Cache-Control: private`), and is refused
while the title is private. The policy says "anyone you give its link to".

## Title lookup (Gemini, TMDB)

- **Cover editor search (phone).** The text in the search box goes to the service (`POST /api/library/resolve-title`,
  200 characters max), which cleans it and sends it to Gemini (`gemini-2.5-flash`). If Gemini finds a title, or is
  not available, the service searches TMDB with it. The box starts with the item's current title, so a Telegram
  title (derived from the caption or file name) reaches Gemini if the user searches without editing it. See Open
  items.
- **Automatic matching of Telegram titles.** TMDB only. No AI model sees Telegram text unless `TELEGRAM_TITLE_AI`
  is set, and it is not set in production (CAB-35).
- **Logged** in the systemd journal (CAB-46): the query length, provider status and timing, the reply length, the
  confidence and the TMDB id of a match. Not the query, the prompt, Gemini's reply or the matched title. A failed
  parse logs the error class only. `CATALOG_LOG_TEXT=true` brings the text back for local debugging; the deploy
  never sets it.
- Gemini and TMDB never get the video, its contents, or the file path. The server's IP address is what they see.

## Subtitles (OpenSubtitles, SubDL)

| Data | From → to | Why | Kept | Deleted by |
|---|---|---|---|---|
| Technical details: OpenSubtitles hash, size, duration, fps, container, codec, resolution, audio and embedded subtitle languages | Phone → service; hash, ids, title, season, episode, year and languages → OpenSubtitles; ids or title, season, episode and languages → SubDL | Find matching subtitles | Search results 1 hour (`subtitle_discoveries`, deleted at the next search after expiry) | Expiry; account deletion |
| Speech-timing fingerprint: up to 6 windows of 100–600 bits, each bit "speech or not" for 0.1–0.5 s of audio. Computed on the phone; raw audio never leaves the phone | Phone → service only | Check that a subtitle's timing fits the video | With the search, 1 hour | Same |
| Chosen subtitle: provider, provider ref, all cues, offset, scale, corrections | Provider → service; service → TVs | Show the subtitle on every TV | Until replaced or account deletion (`media_subtitles`, cascade on user and source) | Account deletion; removing the source |

Logged: counts and reasons per search, no titles or cues. Providers see the server's IP address, never the user's.

## Watch activity and remote control

| Data | From → to | Why | Kept | Deleted by |
|---|---|---|---|---|
| Progress per profile and title (position, duration, state) | TV → service | Continue Watching | Until account deletion | Delete profile; account deletion |
| Remote commands (play, pause, seek, …) with target TV | Phone or web remote → service → TV | Remote control | Row stays (expired, completed or rejected) until account deletion | Account deletion |
| Profile switch requests (profile, TV name) | TV → service → phone | Approve a profile switch | Row stays after it resolves | Account deletion |
| Private playback approvals (profile, TV, title, hashed token, hashed LAN pass) | TV → service → phone | Approve a private title | Row stays (expired or consumed) | Account deletion |

## Video bytes

| Mode | Path | Through Cablegram? | Encrypted in transit | Kept |
|---|---|---|---|---|
| Phone file, same Wi‑Fi | TV → phone's LAN server (`_cablegram._tcp.`, port 8765), plain HTTP | No | **No**: plain HTTP on the local network. Every request needs the TV's LAN capability; the pairing PIN is never accepted (CAB-43) | Not kept |
| Phone file, away from home (relay) | TV → `api.cablegram.app/relay/v1/p/<phone>/…` (HTTPS) → relay → phone's WebSocket (WSS) → phone's LAN server | **Yes** | TLS on both legs, terminated at nginx on the VPS. **Not end-to-end**: the relay process handles the bytes in clear | Only in socket buffers, bounded by per-stream credit (1 MiB, then 256 KiB grants). Nothing on disk; `/relay/` has access logging off. Bytes per household and per hashed device are counted per month |
| Telegram title, TV with its own session | TV ↔ Telegram (TDLib) | No | Telegram's MTProto | TDLib cache on the TV, removed when playback stops |
| Telegram title, TV without a session | TV → phone (LAN or relay) → Telegram | Through the relay if away from home | As the rows above | As above |
| Household's own Drive or R2 | Phone uploads straight to Drive (resumable) or R2 (multipart, presigned); TV plays from a link the service mints (expires in 6 hours, never stored) | No: the service only starts and completes the upload | HTTPS | In the user's storage, under the user's control. Account deletion revokes the Drive token and deletes the connection, and leaves the files |
| Web video | TV plays from the source site | No | As the source serves it | Not kept by Cablegram |
| Web video saved to Cablegram storage | Retired by CAB-27: `POST /api/library/items/:id/storage-transfers` answers 410. Files saved before that are still on disk under `WEB_STORAGE_DIR` and still served | Yes, for those older files | HTTPS | Until account deletion. Wipe before launch (see Open items) |
| Cast | Phone tells a Cast receiver which URL to load; the receiver loads it as the TV would | As the mode it uses | As the mode it uses | — |

The relay is not an open proxy (checked in `CableGram/apps/relay/src/relay.ts` and `control-plane/src/http/relay-internal.ts`):

- A phone's WebSocket needs a phone access token, and that phone must be an active (not revoked) device of the
  token's household.
- A TV request needs a TV access token or a short-lived relay ticket bound to that one phone. The TV must be active,
  and the TV and phone must be in the same household.
- Only `GET` and `HEAD`, and only paths `media/`, `poster/` or `telegram/` followed by one id, which the phone's own
  LAN server answers. Only `Range` and `If-Range` are passed through. The relay never opens a connection to any other
  host; its only outbound calls go to the control plane on loopback.
- `/internal/relay/*` needs a shared secret, is refused for anything that came through nginx, and nginx answers 404
  for `/internal/`.
- Per household: monthly byte quota, stream limit and a bitrate cap on the free plan. Free relay needs a confirmed
  email.

## Telegram

| Data | From → to | Why | Kept | Deleted by |
|---|---|---|---|---|
| Phone number, login code, two-step password (phone sign-in) | Phone → Telegram (TDLib) | Sign in | Never sent to Cablegram | — |
| Two-step password for signing in a TV | Typed on the phone, sealed to the TV's one-time key (ECDH P-256, AES-256-GCM, bound to the request id), relayed by the service | Sign the TV in | Sealed blob until delivered, cancelled, or 5 minutes (then cleared). The password hint is stored in plain text with the request | Expiry clears the sealed blob; rows go at account deletion |
| TV sign-in link (Telegram QR login token) | TV → service → phone | Approve a TV sign-in from the phone | Expires after 60 seconds; cleared when used or when an expired request is next looked at | Same |
| Telegram user id, display name, library channel id | Phone → service | Show the link; find the channel | Until Disconnect Telegram or account deletion | Disconnect Telegram; account deletion |
| TV Telegram session state (session id, expiry, sign-out) | Service | Sign TVs out remotely | Until account deletion | Account deletion |
| TDLib database (session, cache) | On each device, encrypted with a key in the Android Keystore | Telegram client | On the device | Disconnect Telegram; uninstall |

The sealed password is not end-to-end in the strict sense: the TV's public key reaches the phone through the service
without being authenticated, so an operator who replaced it could read the password. Policy wording says "sealed on
your phone for that TV" and does not say end-to-end.

## Household cloud storage (optional)

| Data | From → to | Why | Kept | Deleted by |
|---|---|---|---|---|
| Google sign-in: refresh token (AES-256-GCM with `STORAGE_CREDENTIALS_KEY`), Google account email, subject id, Cablegram folder id | Google → service | Upload and play from the user's Drive | Until disconnect or account deletion. Access tokens only in memory | Disconnect (token revoked at Google); account deletion |
| OAuth state and PKCE verifier | Service | Sign-in round trip | 10 minutes, deleted at the next sign-in | Expiry |
| R2: account id, bucket, endpoint, label, access keys (encrypted as above) | Phone → service | Upload and play from the user's bucket | Until disconnect or account deletion | Disconnect; account deletion |
| Upload records (object key, size, content type, file name) | Phone → service | Resume and complete uploads | Until account deletion | Account deletion |

## Web remote (`app.cablegram.app`)

Static files on Cloudflare Workers. Signs in with the Cablegram account (`sessions.client = 'web'`) and keeps its
tokens and the chosen TV in the browser's `localStorage`. It sends the same remote commands as the phone.

## Diagnostics and logs

- **Apps:** no crash reporting and no log upload. LibVLC no longer writes stream URLs to logcat (CAB-37).
- **Server, systemd journal** (14 days; daily files, so at most 15): title-lookup lengths, timings and TMDB ids (above),
  TMDB queries, poster stored/removed events (item id and size), subtitle search counts, Google OAuth callback
  results, mail send failures. If `MAILER_URL` were unset, the recipient and subject of each mail would be logged
  instead of sent. It is set in production.
- **nginx access log** (15 days): IP address, path and user agent for all paths except `/relay/` and the Google
  OAuth callback. Paths include item and source ids. Query strings are not logged (CAB-47).
- **syslog** (and `auth.log`, `kern.log`, `mail.log`): daily, 14 kept, so at most 15 days (CAB-41).

## Open items

These stop the policy from being simpler, or are gaps found while writing this. Not fixed in CAB-30.

1. **Done in CAB-47: Cablegram-stored web videos are gone.** The route that served them is removed, and migration
   035 deletes the `cloud_object` sources. The VPS had no files under `WEB_STORAGE_DIR` (checked 2026-10-07).
2. **The cover editor sends Telegram-derived titles to Gemini** when the user searches without editing the
   pre-filled title. Related to CAB-35 and CAB-39: skip the AI step for Telegram items, or start the box empty.
3. **Done in CAB-43: the pairing PIN no longer opens the phone's LAN server.** `LanCredentials.kt` accepts only
   device capabilities, and the TV no longer falls back to sending the PIN. The policy may say again that the PIN
   is never a LAN credential.
4. **LAN streams are plain HTTP.** Anyone on the same network can read the bytes and the LAN credential. The
   policy now says so.
5. **Expired short-lived rows are never purged:** pairing sessions, remote commands, profile switch requests,
   private approvals, email codes, Telegram login and password requests, library jobs. Their secrets are hashed or
   cleared, but the rows stay until account deletion. A daily purge (for example 30 days after expiry) would let
   the policy say "deleted" rather than "expire".
6. **Done in CAB-41: journal and syslog are limited to 15 days.** `deploy/deploy-vps.sh` installs a journald
   drop-in (`MaxRetentionSec=14day`, `MaxFileSec=1day`) and rotates syslog daily with 14 kept. The policy now says
   all logs are kept for up to 15 days.
7. **Done in CAB-46: title lookups log no text.** Only lengths, statuses, timings and ids. The policy line now
   says so.
8. **Done in CAB-47: no tokens in the nginx log.** The `/api/storage/objects/:id?token=` route is removed, and
   nginx logs `$uri` instead of `$request`, so no query string is logged on any route.
9. **The relay is not end-to-end encrypted,** and the in-app relay text says "through the Cablegram relay" without
   saying that. The policy says it plainly. Consider one line in the mobile-data prompt.
10. **The Cast SDK's data** cannot be opted out of or deleted (Google's
    [Cast SDK data disclosure](https://developers.google.com/cast/docs/android_sender/data_disclosure)). It is
    declared in the Data safety form and named in the policy.
