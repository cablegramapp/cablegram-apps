# Google Play Data safety: draft answers

Draft for the Data safety form of both Play listings. Derived from the manifests, the dependency lists and
`contracts/`. Check every line against the closed servers and the privacy policy before submitting. A wrong
Data safety form is a policy violation.

## Overview questions

| Question | Phone (`app.cablegram.phone`) | TV (`app.cablegram`) |
|---|---|---|
| Does the app collect or share required user data types? | Yes | Yes |
| Is all collected data encrypted in transit? | Yes (HTTPS to `api.cablegram.app`) | Yes |
| Can users request that data is deleted? | Yes, but the mechanism must exist: see open items | Yes |
| Account creation offered in app | Yes (email and password) | No (pairs with a phone-created household) |
| Independent security review / Play Families / UPI | No | No |

## Data types collected by Cablegram (sent to its servers)

| Category | Type | Phone | TV | Purpose | Optional? | Shared? |
|---|---|---|---|---|---|---|
| Personal info | Email address | Yes (sign-up, login, invites) | No | Account management | Required | No |
| Personal info | Name | Yes (profile names, Telegram display name) | Yes (profile names) | App functionality | Required | No |
| Personal info | User IDs | Yes (account, household, Telegram user id) | Yes (household, profile, device) | App functionality, account management | Required | No |
| App activity | Other user-generated content | Yes (library titles, corrected matches, posters) | Yes | App functionality | Required | No |
| App activity | App interactions | Yes (per-profile playback progress) | Yes | App functionality | Required | No |
| Files and docs | Files and docs | Yes (video file names and metadata in the catalog) | Yes | App functionality | Required | No |
| Device or other IDs | Device or other IDs | Yes (registered device id, TV display name) | Yes | App functionality, security | Required | No |

Choose "Collected" only for what the servers store. Not declared as collected:

- **Video files.** The servers never store them. The relay forwards bytes without keeping them, which counts
  as ephemeral processing. The LAN path stays inside the household.
- **Telegram phone number, login code, password and session.** They go from the device to Telegram (TDLib) and
  never to Cablegram (`contracts/telegram-link.md`).
- **Camera (QR scan for pairing).** Processed on the device only.
- **Videos read from the phone (`READ_MEDIA_VIDEO`).** Read for upload or serving, not stored by Cablegram.
- **Location, contacts, financial info, health, messages, audio, photos.** Not accessed.
- **Advertising ID.** Not used. No ads, analytics or crash-reporting SDK is in the dependency lists.

## Sharing

Answer "No data shared". Telegram receives data only when the user signs in to their own account and uses
their own library channel, which is user-directed transfer and does not need to be declared as sharing. Note
this in the privacy policy so the user sees it.

## Permissions to be ready to justify

- Phone: `CAMERA` (scan the TV's QR), `READ_MEDIA_VIDEO` (choose and serve videos), `FOREGROUND_SERVICE_DATA_SYNC`
  (uploads and serving), `POST_NOTIFICATIONS`, `CHANGE_WIFI_MULTICAST_STATE` (find the TV on the LAN).
- `READ_MEDIA_VIDEO` is a sensitive permission. Play may ask for a declaration that the app needs broad video
  access. If the app uses the system photo/video picker for selection, remove the permission instead.

## Verified against the control plane (`CableGram/apps/control-plane`)

- **No analytics, crash reporting or ad SDK.** App logs are structured JSON to stdout. Fastify's request logger is off.
- **IP addresses.** nginx (`deploy/nginx-cablegram.conf`) uses its default access log for `/`, so IP addresses are
  logged. `/relay/` has `access_log off`. This is covered by declaring Device or other IDs as collected. Say so
  in the privacy policy.
- **Email** goes to your own Cloudflare mailer Worker (`notify.cablegram.app`) to send codes and invites. That is
  a service provider acting for you, not sharing.
- **Third-party lookups.** A cleaned video file name or caption (links, emails, @handles and hashtags removed,
  800 characters max) goes to **Google Gemini** to extract the title, and the resulting title and year go to
  **TMDB** for posters and metadata. Both act as service providers processing data for you. This is not
  "sharing" for Play, but the privacy policy should name them, because file names can contain personal text.
- **Deletion endpoints that exist:** profile (`DELETE /api/profiles/:id`, which also removes its progress),
  device revoke, Telegram link removal, catalog collection and tombstone removal.

## Open items to confirm before submitting

1. **Account deletion does not exist.** The control plane has no route that deletes a user account or household.
   Play requires an in-app path and a public web URL for account and data deletion, because the phone app
   creates accounts. Build the endpoint, an in-app "Delete account" action and a deletion page on
   `cablegram.app` before you submit. Answering "users can request deletion" without them would be a false
   declaration.
2. **Retention.** State in the privacy policy how long each data type is kept, and what account deletion removes.
3. **Privacy policy.** `https://cablegram.app/privacy.html` must list each data type above, plus Gemini, TMDB
   and the mail provider.
4. **`READ_MEDIA_VIDEO`.** Play may require a broad-access declaration. Prefer the system video picker.
