# Google Play Data safety: answers

Answers for the Data safety form of both Play listings, in the order the Console asks. Every row comes from
[data-flow-inventory.md](data-flow-inventory.md), which lists each flow with its destination, purpose, retention and
deletion; check a row there before changing it here. The privacy policy (`https://cablegram.app/privacy.html`) says
the same things in plain words. Reviewers compare the three, and a wrong Data safety form is a policy violation.

Last checked against code on 2026-10-07 (this repo `8d33090`, server `CableGram` `42b2cae`). Recheck before each
release that changes what an app sends.

## What changed since the last version of this file

- **Web videos are no longer stored by Cablegram** (CAB-27). The Videos row is now the relay, processed
  ephemerally. Sources saved before CAB-27 were deleted in CAB-47 (Before you submit, item 2).
- **The relay ships.** Away from home, video bytes pass through Cablegram's server in memory.
- **Covers made from the user's videos stay on the phone** unless the user saves one to the household (CAB-29).
- **Gemini no longer sees Telegram text automatically** (CAB-35). It sees what the user types in the cover editor's
  search box.
- **Subtitle search** sends file details and a speech-timing fingerprint to the server, and ids or titles to
  OpenSubtitles and SubDL.
- **The household's own storage** (Google Drive, Cloudflare R2): the server keeps an encrypted credential; videos go
  straight between the devices and the user's storage.
- **The Google Cast SDK** is in both apps and collects its own anonymised usage data.
- **No preset live channels** (CAB-26): the TV no longer contacts any channel streams.
- **New answers:** In-app search history, Diagnostics, Videos (ephemeral). Dropped: Videos (stored).

## Section 1: Data collection and security (both apps)

| Question | Phone `app.cablegram.phone` | TV `app.cablegram` |
|---|---|---|
| Does your app collect or share any of the required user data types? | Yes | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes | Yes |
| Which account creation methods does your app support? | Username (email address) and password | None: the TV pairs with a household made on a phone |
| Do you provide a way for users to request that their data is deleted? | Yes | Yes |
| Where can users request deletion? | In the app (Settings, Delete account) and `https://api.cablegram.app/delete-account` | `https://api.cablegram.app/delete-account` |
| Can users request that some data is deleted without deleting their account? | Yes: profiles (with their progress), titles, saved covers, devices, Telegram link, connected storage | Same |
| Committed to the Play Families Policy? | No | No |
| Independent security review? | No | No |

"Encrypted in transit" is about data the apps send to Cablegram and to SDKs. All of it goes over HTTPS or WSS, and
the Cast SDK encrypts its own. The phone-to-TV stream on the home network is TLS too, with the phone's own pinned
certificate (CAB-48). That stream is between the user's own devices, so it is not collection either way.

## Section 2: Data types

For every row: **Shared = No.** The providers that receive data process it for Cablegram as service providers:
Contabo (hosting), Cloudflare (mail, hosting), Google Gemini, TMDB, OpenSubtitles, SubDL. They get a title, ids
or a file hash from the server, never an account identifier. Transfers to the user's own Telegram, Google Drive or R2
are user-initiated, to the user's own accounts. Both are exceptions to "sharing" in Play's definitions.

**Processed ephemerally = No** unless the row says Yes. "Required" means the user cannot turn it off and still use
the app.

### Phone

| Category | Data type | Required or optional | Ephemeral | Purposes | What it is |
|---|---|---|---|---|---|
| Personal info | Email address | Required | No | App functionality, Account management | Sign-in, verification and reset mail |
| Personal info | Name | Required | No | App functionality | Profile names, optional display name, Telegram display name |
| Personal info | User IDs | Required | No | App functionality, Account management | Account and household ids; Telegram user id; Google account email and id when Drive is connected |
| Photos and videos | Photos | Optional | No | App functionality | A cover the user saves to the household ("Save artwork to household") |
| Photos and videos | Videos | Optional | **Yes** | App functionality | Relay playback away from home: bytes pass through the server in memory, never stored |
| Files and docs | Files and docs | Required | No | App functionality | File names, sizes and source keys in the catalog; Telegram file and message ids; imported page URLs; for subtitle search, file hash, technical details and a speech-timing fingerprint |
| App activity | App interactions | Required | No | App functionality, Analytics | Watch progress, My List, remote commands (App functionality); Cast SDK usage events (Analytics) |
| App activity | In-app search history | Optional | No | App functionality | Title searches in the cover editor (sent to Gemini and TMDB; not logged since CAB-46) |
| App activity | Other user-generated content | Optional | No | App functionality | Edited titles and details, collections, subtitle choices and timing corrections |
| App info and performance | Diagnostics | Required | No | Analytics | Cast SDK session and performance data (anonymised, kept briefly by Google) |
| Device or other IDs | Device or other IDs | Required | No | App functionality, Fraud prevention, security and compliance | Registered device id; `ANDROID_ID`, stored only as a keyed hash; IP address in server logs; the phone's LAN address |

### TV

| Category | Data type | Required or optional | Ephemeral | Purposes | What it is |
|---|---|---|---|---|---|
| Personal info | Name | Required | No | App functionality | Profile names, Telegram display name |
| Personal info | User IDs | Required | No | App functionality, Account management | Household id; Telegram user id |
| Files and docs | Files and docs | Required | No | App functionality | Telegram file names, sizes and ids when the TV scans the channel |
| App activity | App interactions | Required | No | App functionality, Analytics | Watch progress, My List, profile switch and private-title requests; Cast SDK usage (Analytics) |
| App info and performance | Diagnostics | Required | No | Analytics | Cast TV SDK session and playback data |
| Device or other IDs | Device or other IDs | Required | No | App functionality, Fraud prevention, security and compliance | Registered device id; `ANDROID_ID`, stored only as a keyed hash; IP address in server logs |

No Email address, Photos, Videos or search history: the TV never sends them. The TV receives relay video; the phone
sends it.

### Not collected: answer No for all of these

Location, Financial info, Health and fitness, Messages, Audio, Contacts, Calendar, Web browsing history, Installed
apps, Other app performance data, Crash logs.

- **Messages:** the server keeps Telegram message ids and a title derived from a caption, not message content, and
  never contacts Telegram. The phone number, login code and password go from the device to Telegram. The TV's
  two-step password is sealed on the phone for that TV and passes through the server only as ciphertext, for at
  most 5 minutes.
- **Audio:** the speech-timing fingerprint is up to six windows of "speech or not" bits, made on the phone; raw audio
  never leaves the phone. It is declared under Files and docs.
- **Web browsing history:** an imported page URL is something the user pasted to add a video, not a history of sites
  visited. It is declared under Files and docs.
- The camera (QR scan for pairing) and `READ_MEDIA_VIDEO` are used on the device only.

### Judgement calls to confirm

1. **Cast SDK.** Google publishes no type-by-type table. Its
   [disclosure](https://developers.google.com/cast/docs/android_sender/data_disclosure) describes anonymised
   discovery and session events, device information and app information, encrypted in transit and kept briefly. We
   declare App interactions and Diagnostics for Analytics, not shared because it is anonymised. Recheck the page
   before submitting.
2. **Videos as ephemeral.** The relay holds bytes only in socket buffers, never on disk, and logs no relay paths.
   That fits "processed ephemerally". The old stored web videos did not; they were removed in CAB-47 (Before you
   submit, item 2).
3. **Search history.** Cover-editor searches are in-app searches, and they leave the device for the server, Gemini
   and TMDB. That is why the answer changed from No to Yes. Since CAB-46 the server logs only their length, which
   does not change the answer.

## Section 3: Permissions to be ready to justify

- Phone: `CAMERA` (scan the TV's QR code), `READ_MEDIA_VIDEO` and `READ_EXTERNAL_STORAGE` up to API 32 (choose and
  serve videos), `FOREGROUND_SERVICE_CONNECTED_DEVICE` (the LAN library service, CAB-25),
  `FOREGROUND_SERVICE_DATA_SYNC` (uploads to the household's own storage), `POST_NOTIFICATIONS`,
  `CHANGE_WIFI_MULTICAST_STATE` (find the TV on the local network), `WAKE_LOCK`.
- TV: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`. Nothing sensitive.
- `READ_MEDIA_VIDEO` is sensitive. Play may ask for a broad-access declaration. If the app can use the system
  photo/video picker, remove the permission instead.

## Before you submit

1. **Account deletion end to end.** `GET https://api.cablegram.app/delete-account` answered 200 on 2026-10-07. Still
   do one end-to-end check on the release build: create a test account, connect a TV, import a title, save a cover,
   delete the account in the app, then confirm sign-in fails, the TV is signed out and the catalog rows are gone.
2. **Cablegram-stored web videos: done (CAB-47).** The route that served them is gone and migration 035 deletes
   the `cloud_object` sources. The VPS had no stored files.
3. **The privacy policy** (`https://cablegram.app/privacy.html`) must be the version from CAB-30 or later. It names
   every provider above, the relay, LAN streaming (encrypted since CAB-48), the Cast SDK, subtitle search, log contents and
   retention, and the hashed device id kept after deletion.
4. **Wording elsewhere must match.** The README, the app strings and the Play descriptions must not say end-to-end
   encrypted, or that Cablegram never handles your videos (the relay does), or that Cablegram stores web videos.
5. **Log retention: done (CAB-41).** The policy says logs are kept for up to 15 days. The deploy script sets the
   journal to 14 days and syslog to daily with 14 kept; nginx was already daily with 14 kept.

## Kept after account deletion (say so in the policy)

`relay_device_usage` (relay bytes per hashed hardware id and month) and `device_households` (hashed hardware id and a
household id that no longer exists anywhere). Unlinked from any account. They stop a new account on the same phone
getting a fresh free relay allowance. Logs and backups age out on their normal schedule.

## Open items

See [data-flow-inventory.md, Open items](data-flow-inventory.md#open-items). The ones that change this form if fixed:
a purge of expired rows (no form change, but the policy could say "deleted"), and the cover-editor search (if it
stops calling Gemini, In-app search history stays, but Gemini's line in the policy shrinks).
