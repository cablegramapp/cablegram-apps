# Remote and LAN contract

Remote commands share one envelope across API polling and LAN delivery:

```json
{
  "id": "command-uuid",
  "target_tv_id": "tv-uuid",
  "command": "pause|play|stop|seek|navigate|select|volume|mute|next|previous",
  "payload": {},
  "issued_at": "2026-01-01T00:00:00Z",
  "expires_at": "2026-01-01T00:00:30Z"
}
```

Commands are authenticated, idempotent by ID, acknowledged, and rejected when
expired or targeted at a revoked device. The LAN service exposes authenticated
media, poster, command, health, and library-source endpoints. Discovery only
announces reachability; it does not announce a browseable shelf.

## Cast launch (CAB-20)

Cast Connect opens the Android TV app. The authenticated control-plane command remains the authority
for playback: the phone submits `play` with `payload.videoId` through `POST /api/control/commands`
first, then starts/reuses a Cast session and sends LOAD with exactly this `customData`:

```json
{
  "commandId": "accepted-command-uuid",
  "targetDeviceId": "paired-tv-device-uuid"
}
```

`MediaInfo.contentId` identifies the video; title and poster metadata serve the system UI only.
Cast must never carry a playable media URL, account token, LAN capability or other credential.
Malformed or additional custom-data fields are rejected. A Cast message is an untrusted signal:
the TV retrieves the command using its own token through `CommandDelivery`, preserving server
household, revocation and expiry checks. Play commands with a video ID have a 300-second server TTL.

Status custom data identifies the receiver's paired device ID as `{"deviceId":"tv-device-uuid"}`.
A target mismatch does not play anything and publishes
`{"reason":"wrong_tv","deviceId":"actual-tv-device-uuid"}`. The phone records the Cast-route
mapping and may send a new authenticated command and LOAD once to the reported device only when it
is a paired TV in the signed-in household. The status message alone grants no authority to target a TV.

On "Who's watching?", only the server command matching `pendingCastLaunch.commandId` is held open.
The banner asks the viewer to choose a profile. Choosing a profile resolves the title against that
profile's library through `findRemoteTitle`; no match finishes with `title_unavailable`. Expiry clears
the hold and finishes with `expired`; leaving the picker clears it with `superseded`. Profiles are
never selected automatically. Other commands retain `profile_required` behavior.

The TV's LibVLC player publishes state through a manually managed `MediaSessionCompat`. Cast transport
controls use the same pending-player-command path as relay controls. Stopping, ending or failing
playback publishes a stopped/idle state; phone state clears on terminal IDLE or session end. The
server command outcome remains the confirmation that a title actually started.

`castConnect` defaults off; an absent receiver app ID, unavailable Google Play services or no matching
Cast route preserves the relay path and existing CAB-18 messages. `legacyRemote` defaults on; turning
it off hides the custom remote UI while retaining its implementation and relay fallback.

The authenticated relay payload for a Cast title start also carries `castLaunch: true`,
`title`, and `senderName` for association and the picker banner. These fields never enter
Cast `customData`. If the command reaches an already open picker before LOAD, the TV
allows at most 30 seconds (and never beyond command expiry) for the exact matching
Cast signal. Without that signal it rejects `profile_required`; the marker alone cannot
hold a title for profile selection or authorize playback. Ordinary relay commands do
not include the marker and keep their current behavior.
