# Google Play Data safety: answers

Answers for the Data safety form of both Play listings, in the order the Console asks. Derived from the
manifests, the dependency lists, `contracts/`, and the control plane (`CableGram/apps/control-plane`). Check the
"Before you submit" list at the end: a wrong Data safety form is a policy violation.

Deletion rows below are true only once both halves are deployed and released:

- server: branch `account-deletion` of the closed-source `CableGram` repo (`POST /api/account/delete`, `POST
  /api/account/delete-by-email`, public page `GET /delete-account`), deployed to `api.cablegram.app`. On
  2026-09-30 `https://api.cablegram.app/delete-account` still answered 404;
- phone app: branch `account-deletion-in-app` of this repo (Settings, Delete account), in a release on Play.

## What the code does with data

- **Videos on the phone or in the user's Telegram channel** are never stored by Cablegram. The phone serves them
  over the local network, or through a relay that forwards bytes without keeping them.
- **Videos imported from a web address** are stored on Cablegram's server when the user chooses "store with
  Cablegram" (`cablegram_managed`; a web import alone stores nothing). One file is capped at 5 GiB; there is no
  total cap per household. Files never expire. Only account deletion removes them
  (`src/account-deletion.ts`), and only when the user is the last member of their household: if other members
  remain, the household and its files stay. No per-title deletion and no cleanup of orphaned files exists
  (`src/catalog/removal.ts` handles Telegram sources only), so do not promise either.
- **Telegram phone number, login code, password and session** go from the device to Telegram (TDLib) and never
  to Cablegram (`contracts/telegram-link.md`).
- **Third-party processing** (service providers acting for Cablegram, not "sharing" for Play): Google Gemini gets a
  cleaned file name or caption (links, emails, handles and hashtags removed, 800 characters max) to find the
  title; TMDB gets the title and year; Cloudflare's mailer sends verification and reset emails.
- **Posters.** When a title has no poster link and is not private, the phone uploads a still frame of the user's
  video as its poster (JPEG/PNG as base64, at most 1.5 MB, kept in the database). That is a photo derived from the
  user's video, so it is declared as Photos.
- **Kept after account deletion** (`src/account-deletion.ts`): relay bytes per hashed hardware id and month, and
  the hashed hardware id with a household id that no longer exists anywhere, both unlinked from any account, to
  stop a new account on the same phone getting a fresh free relay allowance. Say so in the policy.
- **Stored per phone:** its last local network address and port (`last_lan_host`, `last_lan_port`), so TVs can find it
  at home. Covered by the IP address entry under Device or other IDs.
- **No analytics, crash-reporting or ad SDK** in either app, and no advertising ID. The server's request logger is
  off (`Fastify({ logger: false })`); it does log title-lookup queries (the cleaned text, at most 500 characters).
  What the web server in front of it logs cannot be seen from the code. The draft assumes nginx writes its default access log for `/` (IP addresses included) and `/relay/` has access logging off; confirm that with whoever runs the servers.

## Section 1: Data collection and security (both apps)

| Question | Phone `app.cablegram.phone` | TV `app.cablegram` |
|---|---|---|
| Does your app collect or share any of the required user data types? | Yes | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes (HTTPS to `api.cablegram.app`) | Yes |
| Which account creation methods does your app support? | Username (email address) and password | None: the TV pairs with a household made on a phone |
| Do you provide a way for users to request that their data is deleted? | Yes | Yes |
| Where can users request deletion? | In the app (Settings, Delete account) and `https://api.cablegram.app/delete-account` | `https://api.cablegram.app/delete-account` |
| Can users request that some data is deleted without deleting their account? | Yes for profiles (Delete profile removes its progress); leave "No" for the rest | Same |
| Committed to the Play Families Policy? | No | No |
| Independent security review? | No | No |

## Section 2: Data types (declare each as Collected, not Shared)

For every row: **Shared = No**. **Processed ephemerally = No** unless stated. "Required" means the app cannot work
without it.

### Phone

| Category | Data type | Required or optional | Purposes |
|---|---|---|---|
| Personal info | Email address | Required | App functionality, Account management |
| Personal info | Name (profile names, Telegram display name) | Required | App functionality |
| Personal info | User IDs (account, household, Telegram user id) | Required | App functionality, Account management |
| Photos and videos | Videos (files imported from a web address and stored) | Optional | App functionality |
| Photos and videos | Photos (still frames of the user's videos, uploaded as posters) | Optional | App functionality |
| Files and docs | Files and docs (video file names and metadata in the catalog) | Required | App functionality |
| App activity | App interactions (playback progress, My List) | Required | App functionality |
| App activity | Other user-generated content (corrected titles, collections, posters) | Optional | App functionality |
| Device or other IDs | Device or other IDs (registered device id, hashed hardware id, IP address in logs) | Required | App functionality, Fraud prevention, security and compliance |

### TV

Same as the phone table, except: no Email address; no Telegram display name, profile names only; no Videos or
Photos row (the TV never uploads them); Files and docs, App interactions and Device or other IDs are the same.

### Not collected: answer No for all of these

Location, Financial info, Health and fitness, Messages, Audio, Contacts, Calendar, Web browsing history,
Search history, Installed apps. The camera (QR scan for pairing) and the video permission (`READ_MEDIA_VIDEO`)
are used on the device only. The Telegram phone number and code go to Telegram, not to Cablegram. (Photos is collected on the phone: see the
Posters note above.)

## Section 3: Permissions to be ready to justify

- Phone: `CAMERA` (scan the TV's QR code), `READ_MEDIA_VIDEO` (choose and serve videos),
  `FOREGROUND_SERVICE_DATA_SYNC` (uploads and serving), `POST_NOTIFICATIONS`, `CHANGE_WIFI_MULTICAST_STATE` (find
  the TV on the local network), `WAKE_LOCK`.
- `READ_MEDIA_VIDEO` is sensitive. Play may ask for a broad-access declaration. If the app can use the system
  photo/video picker, remove the permission instead.

## Before you submit

1. Deploy the server branch `account-deletion` and confirm `https://api.cablegram.app/delete-account` loads.
   Release the phone app with the Delete account screen (branch `account-deletion-in-app`). Only then answer "Yes" to the deletion questions.
2. The privacy policy (`https://cablegram.app/privacy.html`) must list every data type above, Gemini, TMDB, the
   mail provider, IP addresses in server logs, web-imported video storage with its retention, and how to delete
   an account. Reviewers compare it with this form.
3. Wording elsewhere must match: the README, the app strings and the Play descriptions must not say Cablegram
   "never stores your videos".
4. Confirm the retention of each data type with whoever runs the servers, and state it in the policy.
5. Until per-title deletion and cleanup exist, the promise is "until you delete your account". Do not say users can
   delete individual stored videos.

## Open items

- Per-title deletion of stored web videos, and cleanup of orphaned stored files (a failed delete after account
  deletion leaves a file with no row). Neither exists; not built in this task.
- No expiry and no per-household total cap on stored web videos (only 5 GiB per file). Decide and state the
  retention in the policy ("until you delete your account").
- A household with several members: deleting one account keeps the household and its stored videos. Say so.
- What the web server in front of the control plane logs, and for how long, is not in the code.
- Whether the policy text in `CableGram-homePage` (branch `fix-video-storage-wording`) is published.
- The Play store listing drafts still say "never stores your videos on its servers": edit them in Play Console.
