# Contract: Telegram link and TV login

All routes are under the Cablegram server's `/api`, use bearer device tokens as
the other routes do, and are limited to the caller's household. No request or
response body carries a Telegram secret: no auth key, login code, password or
TDLib database.

To enforce that, bodies are parsed strictly. An unknown field returns `400
invalid_request` instead of being ignored. Error strings reported by devices
must be Telegram error names (`^[A-Z0-9_ ]{1,80}$`). Telegram ids are 64-bit
integers. They are accepted as JSON numbers or decimal strings, and returned as
numbers when they fit in a double exactly, otherwise as strings.

## Household link

### `GET /api/telegram/link` (phone or TV)

```json
200 { "linked": true, "telegram_user_id": 123456789, "display_name": "Alex",
      "library_chat_id": -1001234567890, "linked_at": "2026-09-29T10:00:00Z" }
200 { "linked": false }
```

### `PUT /api/telegram/link` (phone only)

Called after the phone's session reaches Ready and the library channel exists.

```json
{ "telegram_user_id": 123456789, "display_name": "Alex", "library_chat_id": -1001234567890,
  "phone_device_id": "uuid (optional; the phone device that linked, checked against the household)" }
```

- `200` with the link.
- `409 telegram_account_mismatch` if the household is already linked to a
  different Telegram account. The body carries `linked_display_name`; the
  phone must disconnect first.

### `DELETE /api/telegram/link` (phone only)

- Clears the link and cancels pending TV logins.
- Marks every `telegram` source of the household `availability = 'unavailable'`.
- Sets `telegram_logout_required = true` on each TV's next `GET /api/telegram/link`
  response, until the TV acknowledges.

### `POST /api/telegram/logout-ack` (TV only)

Body `{}`. Returns `204`. The TV calls it after logging out and deleting its
Telegram data. It clears `telegram_logout_required` for that TV. A new
`PUT /api/telegram/link` also clears it for every TV of the household.

## TV login (phone-approved)

### `POST /api/telegram/tv-logins` (TV only)

The TV has reached `AuthorizationStateWaitOtherDeviceConfirmation`.

```json
{ "login_link": "tg://login?token=…" }
```

- The link must match `^tg://login\?token=[A-Za-z0-9_=-]{16,512}$`.
- A new request replaces the TV's earlier pending request.
- Expires 60 s after creation.

Responses:
- `201 { "request_id": "uuid", "expires_in_ms": 60000 }`
- `409 telegram_not_linked` if the household has no link. The TV shows the QR
  fallback.

TDLib refreshes the token about every 30 s. The TV posts the new link each
time it changes.

### `GET /api/telegram/tv-logins/pending` (phone only)

Polled by the phone's `TelegramTvApprovalWatcher` every 5 s, and immediately
after a push when push exists.

```json
200 { "requests": [ { "request_id": "uuid", "tv_device_id": "uuid",
      "tv_name": "Living room", "login_link": "tg://login?token=…", "expires_in_ms": 41000 } ] }
```

Only the phone that is linked to Telegram should act on these requests. Other
phones ignore them.

**The phone asks first.** The login link comes from the server, and approving
it gives the holder of that link a session on the user's Telegram account. So:
- By default the phone shows a notification, "Let <TV name> use your Telegram?",
  with **Allow** and **Don't allow**, and approves nothing until the user taps
  Allow.
- The user can turn on "Approve my TVs automatically". It is off by default.
- Allow approves only the request the notification was raised for (matched by
  `request_id`, using its latest link). If that request is gone, nothing is
  approved.
- This stops silent approval. It does not authenticate the server: the TV name
  in the prompt also comes from the server.

### `POST /api/telegram/tv-logins/:id/result` (phone only)

```json
{ "outcome": "approved" }
{ "outcome": "denied" }
{ "outcome": "failed", "error": "SESSION_TOO_FRESH_1234" }
```

- Result is recorded.
- The TV learns it from its own TDLib state (Ready). It may also read
  `GET /api/telegram/tv-logins/:id` (TV only), which returns `{ "state": … }`
  so it can show "Your phone didn't approve" or the error.

The login link is deleted from storage as soon as a result is recorded, or when
the request expires.

### The Telegram two-step password, typed on the phone

A TV that Telegram asks for the account's two-step password can ask the household phone to type it. The password is
sealed on the phone to a one-time key the TV made, so the control plane relays bytes it cannot read.

`POST /api/telegram/tv-password-requests` (TV only) `{ "public_key": "<base64 SPKI of a P-256 key>", "hint": "…" }`
returns `201 { "request_id", "expires_in_ms" }`. It replaces any earlier open request of the same TV, expires after
5 minutes, and answers `409 telegram_not_linked` or `403 telegram_not_allowed_on_temporary_tv` like a TV login.
`hint` is Telegram's own password hint.

`GET /api/telegram/tv-password-requests/pending` (phone only) returns
`{ "requests": [ { "request_id", "tv_device_id", "tv_name", "tv_public_key", "hint", "expires_in_ms" } ] }`.

`POST /api/telegram/tv-password-requests/:id/seal` (phone only) `{ "sealed": "<base64>" }` stores the sealed password
for the TV (`409 already_resolved` with its `state` if it is no longer pending, including once it has expired).
`POST /api/telegram/tv-password-requests/:id/cancel` (phone only) drops the request.

`DELETE /api/telegram/tv-password-requests/:id` (TV only, its own request) withdraws it once the TV no longer waits
(signed in another way, gave up, or Telegram moved on), so the phone stops asking; `404` when it is no longer open.

`GET /api/telegram/tv-password-requests/:id` (TV only) returns `{ "state" }`, and once the phone has sealed it,
`{ "state": "delivered", "sealed": "…" }` exactly one time: the sealed bytes are deleted as they are handed over (and
when the request is cancelled, or within a minute of it expiring). States: `pending`, `sealed`, `delivered`, `cancelled`, `expired`.

Sealing (identical on both apps): the phone makes an ephemeral P-256 key pair, derives the shared secret with the TV's
public key (ECDH), and uses `SHA-256(secret || "cablegram-tg-password-v1:" || request_id)` as an AES-256-GCM key, with
`request_id` as associated data and a random 12-byte IV. `sealed = base64(phone SPKI (91 bytes) || IV || ciphertext || tag)`.
The password is UTF-8 text. The TV checks it with Telegram itself; Cablegram never learns whether it was right.

## Catalog sources

`POST /api/catalog/items` with `source_kind: "telegram"` (phone or TV):

```json
{ "source_kind": "telegram",
  "origin_filename": "The.Bear.S00.Gary.1080p.mkv",
  "origin_identity": "tg:-1004481882279:1234",
  "stable_source_key": "tgfile:AgADxxxx",
  "bytes_total": 1122649944,
  "playback_mime": "video/x-matroska",
  "title": "optional, and the metadata fields as for phone imports" }
```

- `origin_identity` must match `tg:<chat_id>:<message_id>`, and `stable_source_key` must match
  `tgfile:<Telegram remote unique id>`. `serving_device_id` must be absent. Otherwise `400
  invalid_telegram_source`.
- The chat must be the household's `library_chat_id` (enforced by the server):
  - another chat gets `403 not_library_channel`;
  - no link gets `409 telegram_not_linked`.
- `201` creates the title and the source (`kind = telegram`, `availability = available`).
- `200` means the file is already in the library. Only the message location, file name, size
  and availability are refreshed. The title and metadata are kept, so a TV's automatic guess never
  overwrites a title matched on the phone.
- `source_kind` defaults to `phone_local`; phone imports are unchanged.

`POST /api/catalog/sources/telegram/reconcile` (phone or TV), after a full channel scan:

```json
{ "present": ["tgfile:AgAD…", "tgfile:AgAD…"] }
→ 200 { "unavailable": 1, "available": 12 }
```

## Phone login through a Home TV

The reverse of the TV login. The phone reaches `WaitOtherDevice` (it calls
`requestQrLogin` instead of asking for a number) and asks a Home TV to do the
approving.

### `POST /api/telegram/phone-logins` (phone only)

```json
{ "login_link": "tg://login?token=…", "tv_device_id": "uuid", "phone_device_id": "uuid" }
```

- `tv_device_id` must be a Home TV of the household (`403 tv_not_home`
  otherwise).
- Expires 60 s after creation; the phone posts again when TDLib refreshes the
  link.
- Returns `201 { "request_id", "expires_in_ms" }`.
- There is no TV command: a Home TV that holds a Telegram session polls
  `GET /api/telegram/phone-logins/pending` (every 4 s). It asks the owner on screen and approves only if the
  owner allows it with the TV's remote (see the review fixes below). If the TV has no session, or nobody
  answers, the phone offers the phone-number sign-in after 2 minutes.

**Review fixes (2026-09-30):**
- **The phone that asks.** `phone_device_id` must be a phone of the household that isn't revoked
  (`400 unknown_phone_device`). The TV shows its name.
- **The owner confirms on the TV.** The TV shows "Sign in <phone> to your Telegram?" full screen, with
  focus on **Don't allow**. While it is open, the TV refuses remote-control key commands, so a phone can't
  press OK on its own request. The TV checks it is still a Home TV right before approving.
- **Verified approval, both directions.** The approving device lists Telegram's sessions first; if that
  fails, it approves nothing. After approving, the one new session must use Cablegram's API id. If the only
  new session belongs to another app (e.g. a link made in Telegram Desktop), it is ended at once, and the
  result is `failed` with `UNEXPECTED_CLIENT`. The phone records a TV's session id only when that session
  was identified this way, never "the newest session".

### `GET /api/telegram/phone-logins/pending` (TV only)

Polled while the TV shows the link screen, and after it reaches Ready. Returns
only the requests addressed to this TV, with `login_link` and `expires_in_ms`.

### `POST /api/telegram/phone-logins/:id/result` (TV only) and `GET /api/telegram/phone-logins/:id` (phone only)

The same outcomes and error-name rules as the TV login. The link is erased when
the request resolves or expires.

## Web (iPhone PWA) households

An iPhone user has no Cablegram phone app that runs TDLib. The PWA is a remote and library browser
only: it never holds a Telegram session and never sees a login code or password. The TV is the only
Telegram client in such a household.

**Web client.** The PWA signs in with `"client": "web"` in the body of `POST /api/auth/register` or
`/api/auth/login`. The session remembers it, so a refreshed token keeps it, and the phone access token
carries `client: "web"`. It is a normal phone-scope token otherwise, so it may use the routes marked "phone
or TV" and remote commands. On the Telegram routes a web token is refused (`403 forbidden_for_web_client`)
except for `GET /api/telegram/link` and the two TV-login routes below. It must not serve media and must not
call `PUT`/`DELETE /api/telegram/link` or `POST /api/telegram/phone-logins`. This limits what the PWA is
asked to do; it is not a barrier against the account owner, who can always sign in without the field.

**TV creates the link.** `PUT /api/telegram/link` also accepts a TV token, but only while the household
has no link and only from a TV whose TDLib session has reached Ready. The body is the same as for a phone,
without `phone_device_id` (`400 invalid_request` if present). If the household is already linked, a TV gets
`403 forbidden`. `DELETE` stays native-phone-only, because it logs out every TV.

**Connecting from the TV.** A Home TV whose household has no link (`GET /api/telegram/link` says
`linked: false`) offers "Connect Telegram on this TV" in Settings. The owner starts it; it never starts by
itself. The TV signs in with its own QR code (`tg://login?token=…`), shown at once and not sent to the
server. The owner scans it in the Telegram app on their phone and confirms there. When the TV reaches
Ready it finds the library channel by title, or creates it, and calls `PUT /api/telegram/link` with its own
token. If that fails (for example `403` because another device linked first), the TV signs out and wipes
its Telegram data. This is the only time a TV creates a channel.

**Approving a TV from the PWA.** When the household is linked, `GET /api/telegram/tv-logins/pending` is
also open to web clients. The PWA:
- shows the same "Let <TV name> use your Telegram?" prompt, with **Allow** and **Don't allow**. There is no
  auto-approve for web devices;
- on Allow, opens the `login_link` (`tg://login?token=…`), so the Telegram app asks the user to confirm;
- posts `{ "outcome": "approved" | "denied" }`.

**What is weaker.** The PWA can't list Telegram sessions, so it can't run the "verified approval" check
that native phones do (`UNEXPECTED_CLIENT`). The server supplies the `login_link` and the TV name, so a
compromised server could hand the PWA a link from another client. The TV can't detect that: it never signs
in, and it can't list sessions before it has one. The prompt only stops silent approval, as it does on
native phones. A web client may only post `approved` or `denied`; `failed` gets `403 forbidden_for_web_client`.

## TV trust and Telegram sessions

### Pairing and TV settings (phone only)

The pairing confirmation, and `PATCH /api/devices/:tvDeviceId`, accept:

```json
{ "trust_level": "temporary", "trust_expires_at": "2026-10-02T10:00:00Z", "telegram_direct_allowed": false }
```

- `trust_level` defaults to `home`.
- A temporary TV needs `trust_expires_at` in the future and within 30 days (`400 trust_expires_at_required`, `400 invalid_trust_expires_at`).
- `telegram_direct_allowed: true` is refused with `409 telegram_link_too_new` while the household's Telegram link is under 24 h old.
- The pairing claim (`POST /api/auth/device/claim`) takes the same fields; if they are invalid the new TV is revoked again.
- `GET /api/me` lists `trust_level`, `trust_expires_at` and `telegram_direct_allowed` for each device.
- Only the phone can change these fields.
- After `trust_expires_at`, the server revokes the TV exactly as `DELETE /api/devices/:id`
  does. The TV's tokens fail with `401 device_expired` and the TV wipes its local data.

### `GET /api/telegram/link` (TV) — added fields

`"telegram_direct": false` when the TV is temporary without opt-in, and
`"telegram_logout_required": true` when its session must be dropped.

### `POST /api/telegram/tv-logins` (TV) — added refusal

`403 telegram_not_allowed_on_temporary_tv`. The TV does not start a TDLib
session. It plays Telegram titles via the phone.

### `PUT /api/telegram/tv-sessions/:tvDeviceId` (phone only)

Called after approval, once the phone has matched the session in
`getActiveSessions`:

```json
{ "telegram_session_id": "5842069301234567890", "approved_at": "…", "expires_at": "…|null" }
```

### `GET /api/telegram/tv-sessions?due=true` (phone only)

Returns `{ "sessions": [ … ] }`. Reasons: `sign_out`, `disconnected`, `expired`, `removed`.

Sessions the phone must end:
- the TV was removed;
- Sign out of Telegram was tapped;
- the end time passed;
- Telegram was disconnected.

```json
[ { "tv_device_id": "…", "telegram_session_id": "…", "reason": "expired" } ]
```

### `POST /api/telegram/tv-sessions/:tvDeviceId/terminated` (phone only)

Records the outcome:
- `{ "outcome": "terminated" }`;
- `{ "outcome": "already_gone" }`;
- `{ "outcome": "failed", "error": "FRESH_RESET_AUTHORISATION_FORBIDDEN" }`.

Failed sessions stay due and are retried.

### `POST /api/telegram/tv-sessions/:tvDeviceId/sign-out` (phone only)

Marks the session due now (Sign out of Telegram), and sets
`telegram_logout_required` for the TV.

### Telegram through the phone

There is no server playback response for this (the TV builds playback URLs itself from the catalog). A TV
with `telegram_direct: false` builds the phone path itself, over the LAN or the relay:

```json
{ "kind": "phone_telegram", "url": "http://<phone>:8765/telegram/<unique_file_id>?token=<capability>",
  "fallback_url": "<relay URL for the same path>" }
```

The `<unique_file_id>` is the `stable_source_key` without its `tgfile:` prefix (the relay's path filter allows no colon). The phone's media server resolves it to the channel message
and streams it from its own session, with the same Range, capability and
private-pass rules as `/media/<id>`.

## Managing titles

### `POST /api/catalog/items/:itemId/removal` (phone only)

```json
{ "mode": "hide" }
{ "mode": "delete" }
{ "mode": "delete_source", "stable_source_key": "tgfile:AgAD…" }
```

- **`hide`** writes tombstones (`hidden`) for all of the item's Telegram
  sources and hides the item for the household.
- **`delete`** writes tombstones (`deleting`), hides the item, and returns the
  Telegram work for the phone:
  `{ "telegram_deletions": [ { "stable_source_key": "tgfile:…", "chat_id": …, "message_ids": [1234, 1240] } ] }`.
  Phone-local files are deleted by the phone as today.
- **`delete_source`** does the same for one source (Remove Telegram copy); the
  item stays.
- The item disappears from `GET /api/catalog/items` for all devices at once. A
  TV playing it gets `410 media_deleted` on its next progress report or playback
  request, and stops.

### `POST /api/catalog/tombstones/:stableSourceKey/result` (phone only)

`{ "outcome": "deleted" }`, or `{ "outcome": "failed", "error": "…" }` to keep
it `deleting` for retry.

### `GET /api/catalog/tombstones` (phone)

Lists hidden and deleting titles for **Hidden videos**:
`[ { "stable_source_key", "item_id", "title", "state", "last_error" } ]`.

### `DELETE /api/catalog/tombstones/:stableSourceKey` (phone)

Restores a `hidden` title. Deleting tombstones cannot be restored.

### Registration against tombstones

`POST /api/catalog/items` with a `stable_source_key` that has a tombstone
returns `409 source_removed` and creates nothing when the message was already
in the channel at removal: its id is at or below the newest channel message the
library had registered then (message ids only grow in a channel). Devices then
skip that message in later scans. A message above that mark is a new forward of
the file: the tombstone is dropped and the title (hidden or deleted) comes back
with that message as its source. While a `delete` is still `deleting`, every
registration of the file is refused, because the phone deletes every message of
the file (FR-013) and would delete the new forward too.

## Playback

There is no playback endpoint. `GET /api/catalog/items` lists each source with `kind`,
`origin_identity`, `stable_source_key`, `availability`, `bytes_total` and `playback_mime`. For a
`telegram` source, the TV takes the chat and message ids from `origin_identity` and resolves them with
its own session (`getMessage`, then the file id). It streams them through its local Range server into
LibVLC.

A TV without direct Telegram (a temporary TV) gets the phone path instead:
`http://<phone>:8765/telegram/<unique_file_id>?token=<capability>`, or the same path over the relay.


## Native client application identity

`GET /api/telegram/client-config` requires a valid Cablegram bearer token. Native phones and TVs
with permission for direct Telegram access receive `{ "api_id": <positive int>, "api_hash": <32 hex characters> }`.
Unauthenticated/revoked clients receive 401, browser clients and temporary TVs without direct permission
receive 403. Missing or invalid server credentials return 503 `telegram_client_unavailable`.
All responses use `Cache-Control: private, no-store`. Serve the route over HTTPS; no redirects are accepted
by the apps. Configure `TELEGRAM_API_ID` and `TELEGRAM_API_HASH` in the control-plane environment.

Deploy the endpoint before releasing the updated apps. Applications hold this identity in memory for TDLib
and verify approved devices against the API ID used for that session. They have no embedded fallback or
persistent credential cache, so a fresh process needs the control plane before Telegram starts. A failed
fetch can be retried without deleting the existing Telegram account database. Running sessions don't fetch
the configuration per movie. Remote delivery reduces static APK exposure; application credentials remain
recoverable from an authenticated running client. User account passwords, codes and sessions stay on devices.
