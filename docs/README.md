# Using Cablegram

Cablegram plays videos from your Android phone or connected sources on an Android TV or Google TV.
The phone and TV apps are open source; the Cablegram servers run separately.

## Connect your TV

Open Cablegram on the TV, then use the phone app to pair it with the code shown on the TV.
Choose a profile on the TV before playback. For videos stored on your phone, keep the phone
reachable; using the same Wi-Fi lets the TV stream directly from it.

iPhone users can use the [web remote](https://app.cablegram.app/) in Safari and choose
**Share → Add to Home Screen**. It controls the TV and browses the household library, but cannot
serve videos stored on the iPhone. See the [web remote guide](../apps/web-remote/README.md).

## Choose files and folders

The Android phone app uses the system file and folder pickers. Cablegram can read the files or
folders you choose without requesting access to your entire media library. Files stay in their
original location.

You can remember more than one folder. Removing a remembered folder releases its access; it does
not delete the original files or remove imported library entries. Videos that relied on that
access may need selecting again. If you reinstall the app, revoke access, or move a file, use the
picker again. Android may prevent selecting a storage root; choose a subfolder or individual files.

## Find your Telegram library channel

At the top of **Import**, the **My library channel** card opens the channel linked to your
Cablegram household. Choose **Open in Telegram**, then send or forward videos into that channel
to add them to your Cablegram library. The card shows the linked Telegram account to help you
recognize the destination. If Telegram is not connected yet, choose **Connect Telegram**.

**Copy link** copies a link to that same private channel. It works for existing channel members;
it is not an invitation and does not grant anyone access. If the channel is no longer available,
Cablegram offers to create a replacement instead of opening or copying the old destination.

## Use a separate Telegram account (optional)

Before connecting Telegram, **Learn about separate accounts** opens an in-app help page. You can
keep using your existing account; reading the help page does not start a Telegram session or
require buying a number. Return with **Back** to continue or choose **Not now** to leave setup.
The same help is available later under **Import → Connected storage → Telegram**.

A separate account needs another phone number that you can keep and recover. The help page
explains SMS support, number retention, temporary-number risks, two-step verification, and how
to end Cablegram sessions. It includes Super as an independent provider example with links to
its current guidance and the Telegram FAQ. It makes no registration or anonymity guarantee,
includes Grizzly SMS and 5SIM links with `utm_source=cablegram.app`, an approximate starting
price of US$0.40 per verification, and a notice that these are independent third-party services.
Prices, refund availability, registration and continued number access are not guaranteed.

Provider guidance checked on 17 October 2026: [Super](https://www.super.ee/en/esim) states that
initial activation is limited to Estonia and qualifying top-ups are required to retain the
number. This does not verify Telegram registration in Germany. Review the provider’s current
conditions before buying. [Telegram](https://telegram.org/faq) advises retaining control of the
phone number associated with an account.

## Add subtitles

In video details on the phone, choose **Attach subtitle file**, then select an `.srt` or `.vtt`
file, up to 2,000,000 bytes. Check the language and text preview before confirming. You do not need
a subtitle-provider account for a local file.

Confirmation saves the parsed subtitle text to your household so paired TVs can use it. WebVTT
styling and positioning are not retained. You can adjust timing or remove the saved track later.
Restart playback on the TV to load a newly attached track. Cancelling the picker or choosing an
invalid file keeps your existing track. This feature requires a phone and server version that
support local subtitle attachment.

Provider search is optional and uses your own OpenSubtitles or SubDL credentials on the phone.
See the [subtitle contract](../contracts/subtitles.md) for storage and data-flow details.

## Privacy and checking a release

Telegram authentication runs through TDLib on each device. Same-Wi-Fi playback streams from the
phone; remote playback can pass through Cablegram's relay. The relay forwards video bytes without
storing them and is not end-to-end encrypted. See the [privacy policy](https://cablegram.app/privacy.html)
for service data handling and account deletion.

To check a downloaded APK, its signature, or its build attestation, follow [VERIFY.md](../VERIFY.md).
The [API and protocol contracts](../contracts/) describe what the public apps send and receive.

## Get help

If a phone video is unavailable, check that the phone is online and still has access to the selected
file or folder. Try selecting it again if access was revoked or the file moved. If a TV is waiting
for private-video approval, open Cablegram or its notification on the phone and choose **Allow once**.

Report bugs through this repository's Issues tab with the app version, device models, and steps to
reproduce. Keep passwords, tokens, and personal media out of reports. Report security problems
privately using [SECURITY.md](../SECURITY.md).
