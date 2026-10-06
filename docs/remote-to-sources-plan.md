# Plan: retire the phone remote, launch the TV app with Cast Connect, add a Sources tab

Status: proposal, agreed in discussion on 2026-10-05. Nothing below is built yet. Claims about Google
policy and Cast behaviour come from Google's public documentation and have not been tested against this app;
the spike at the end confirms them.

## Why

- The phone remote (`PhoneTab.Remote`, `RemoteCommands.kt`, `CommandQueue.kt`) sends commands through the
  relay or LAN and waits for the TV to confirm them. That delay is visible: commands show as sent, then
  unconfirmed or rejected. The TV's own remote does the same job with no delay, so the phone remote adds
  little.
- "Play on TV" only works when the TV app is already in the foreground. The TV manifest has no service or
  receiver, and Android 10+ blocks a background app from starting an activity, so a relay command cannot
  wake a closed app.
- Content sources are scattered: phone files and "Play from Web" are in the Import tab (`PhoneTab.Browse`),
  Telegram sign-in is in Settings. Drive, S3-compatible storage and later sources need one home.

## 1. Retire the remote controls, keep "Play on TV"

Remove the transport controls (play, pause, seek, volume, navigate). Keep the action that starts a title on
the TV, and move it to Cast Connect (section 2).

Hide behind a flag first instead of deleting, so it can come back cheaply. Places that depend on the remote:

- `PhoneTab.Remote` and `remote/RemoteScreen.kt`;
- `RemoteBar` in `PhoneScreens.kt` (mini player that opens the Remote tab), and the "Remote" button on the
  Library header;
- the ongoing notification controls via `CastSession`;
- `apps/web-remote`;
- the TV's command handling and `contracts/remote-and-lan.md` (keep the contract while the relay remains the
  fallback for TVs without Cast).

Maestro flows and `MaestroIds.NAV_REMOTE` need updating with the tab change.

## 2. Launch the TV app with Cast Connect

Cast Connect lets a Cast sender (the phone) start an Android TV app directly. On a Google TV device, a cast
request for the linked app ID:

- launches the TV app even when it is closed, and can wake the TV over HDMI-CEC;
- hands the request to the app as an intent carrying our data (title ID, start position);
- gives the phone the system cast controls (notification, lock screen, volume keys) over the local network
  through the TV's `MediaSession`, not through our relay. These can replace the custom remote.

### Work

- **Cast developer console:** register a receiver app ID (one-time fee, USD 5 at the time of writing) and
  link it to the Android TV package name.
- **TV app:** add `com.google.android.gms:play-services-cast-tv`; handle the Cast load intent (look up the
  title by ID, play with the existing player); expose a `MediaSession` so play, pause and seek from the phone
  work.
- **Phone app:** add the Cast SDK (`play-services-cast-framework`, `mediarouter`); use the cast button or
  device picker for "Play on TV".
- **Media path is unchanged:** the video still streams as today (LAN, relay, or later a signed URL from a
  source). Cast carries only the launch request and the controls.

### Security

The cast request carries a title ID only, never a credential or a playable URL. The TV resolves the ID
against its own signed-in household, so pairing still decides what may play. Anyone on the same Wi-Fi can
see the TV as a cast target; that alone must not let them play household content.

### Limits

- Works only on Android TV / Google TV devices with Cast built in. That includes our test device
  (Chromecast with Google TV), but not Fire TV, Samsung (Tizen) or LG (webOS).
- Discovery needs the phone on the same network as the TV.
- Fallback for other TVs: keep the relay command. If the TV app is open it plays; if not, tell the user to
  open Cablegram on the TV.

## 3. Sources tab (connectors)

Replace the Remote tab with **Sources**, and fold the Import tab and the Telegram setup from Settings into
it, so there is one tab instead of two more. Each source is a card showing connection state, with a browse
view and a way to add items to the Library:

- Telegram (existing);
- This phone (existing local files);
- Web link (existing "Play from Web");
- S3-compatible storage (first new source);
- Google Drive (after the scope decision below);
- later sources.

### Connector interface

The interface matters more than the tab layout; it is what makes later sources cheap (the part of Stremio
worth copying is its add-on model). Each connector provides:

- **auth:** connect, disconnect, current state, refresh credentials;
- **browse:** list folders/items, search where supported;
- **resolve:** turn an item into a playable stream for the TV (URL plus headers or expiry);
- **refresh:** detect removed or changed items.

Where possible the TV streams straight from the source (signed or short-lived URLs for Drive and S3)
instead of through the phone over LAN. That removes another source of delay and works when the phone is
asleep. Telegram stays on its current path.

### Risks

- **Google Drive scopes.** Reading a user's existing videos needs `drive.readonly`, a restricted scope.
  Restricted scopes require Google's OAuth verification plus an annual third-party security assessment
  (CASA), which costs money and time. `drive.file` with the Google Picker avoids that, but the user must pick
  files one by one. Decide before building; check Google's current policy at that time.
- **R2 is a developer product.** Most users have no R2 keys. Build a general S3-compatible connector
  (Cloudflare R2, Backblaze B2, Wasabi, MinIO) and treat it as a power-user feature.
- **Content policy.** Keep connectors to storage the user owns. A Stremio-style open add-on catalog invites
  piracy sources and app-store policy trouble.

## Order

1. Spike: on the Chromecast with Google TV, a Cast-launched intent that starts a known title by ID with the
   TV app closed. Confirms section 2 before anything else changes.
2. Put the remote controls behind a flag; ship "Play on TV" on Cast Connect with the relay fallback.
3. Sources tab on the connector interface, migrating Telegram, phone files and web links into it.
4. S3-compatible connector.
5. Google Drive, once the scope is decided.
