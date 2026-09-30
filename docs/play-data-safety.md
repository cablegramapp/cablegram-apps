# Google Play Data safety: answers

Answers for the Data safety form of both Play listings, in the order the Console asks. Derived from the
manifests, the dependency lists, `contracts/`, and the control plane (`CableGram/apps/control-plane`). Check the
"Before you submit" list at the end: a wrong Data safety form is a policy violation.

Deletion rows below are true only once the server change (`POST /api/account/delete`, `POST
/api/account/delete-by-email`, `GET /delete-account`) is deployed to `api.cablegram.app` and the phone release
with the "Delete account" screen is out.

## What the code does with data

- **Videos on the phone or in the user's Telegram channel** are never stored by Cablegram. The phone serves them
  over the local network, or through a relay that forwards bytes without keeping them.
- **Videos imported from a web address** are stored on Cablegram's server when the user chooses "store with
  Cablegram" (`cablegram_managed`). One file is capped at 5 GB. They are deleted when the account is deleted. No
  per-title deletion or cleanup of orphaned files exists yet, so do not promise either.
- **Telegram phone number, login code, password and session** go from the device to Telegram (TDLib) and never
  to Cablegram (`contracts/telegram-link.md`).
- **Third-party processing** (service providers acting for Cablegram, not "sharing" for Play): Google Gemini gets a
  cleaned file name or caption (links, emails, handles and hashtags removed, 800 characters max) to find the
  title; TMDB gets the title and year; Cloudflare's mailer sends verification and reset emails.
- **No analytics, crash-reporting or ad SDK** in either app, and no advertising ID. The server's request logger is
  off. nginx writes its default access log for `/` (IP addresses included); `/relay/` has access logging off.

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
| Files and docs | Files and docs (video file names and metadata in the catalog) | Required | App functionality |
| App activity | App interactions (playback progress, My List) | Required | App functionality |
| App activity | Other user-generated content (corrected titles, collections, posters) | Optional | App functionality |
| Device or other IDs | Device or other IDs (registered device id, hashed hardware id, IP address in logs) | Required | App functionality, Fraud prevention, security and compliance |

### TV

Same as the phone table, except: no Email address; no Name of Telegram display; profile names only; no Videos row
(the TV never uploads); Files and docs, App interactions and Device or other IDs are the same.

### Not collected: answer No for all of these

Location, Financial info, Health and fitness, Messages, Audio, Contacts, Calendar, Web browsing history, Photos,
Search history, Installed apps. The camera (QR scan for pairing) and the video permission (`READ_MEDIA_VIDEO`)
are used on the device only. The Telegram phone number and code go to Telegram, not to Cablegram.

## Section 3: Permissions to be ready to justify

- Phone: `CAMERA` (scan the TV's QR code), `READ_MEDIA_VIDEO` (choose and serve videos),
  `FOREGROUND_SERVICE_DATA_SYNC` (uploads and serving), `POST_NOTIFICATIONS`, `CHANGE_WIFI_MULTICAST_STATE` (find
  the TV on the local network), `WAKE_LOCK`.
- `READ_MEDIA_VIDEO` is sensitive. Play may ask for a broad-access declaration. If the app can use the system
  photo/video picker, remove the permission instead.

## Before you submit

1. Deploy the server branch `account-deletion` and confirm `https://api.cablegram.app/delete-account` loads.
   Release the phone app with the Delete account screen. Only then answer "Yes" to the deletion questions.
2. The privacy policy (`https://cablegram.app/privacy.html`) must list every data type above, Gemini, TMDB, the
   mail provider, IP addresses in server logs, web-imported video storage with its retention, and how to delete
   an account. Reviewers compare it with this form.
3. Wording elsewhere must match: the README, the app strings and the Play descriptions must not say Cablegram
   "never stores your videos".
4. Confirm the retention of each data type with whoever runs the servers, and state it in the policy.
5. Decide whether to add per-title deletion and cleanup of orphaned stored videos. Until then, the promise is "until
   you delete your account".
