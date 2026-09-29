# LAN media protocol (phone → TV)

Control plane never carries video. After a profile is unlocked on a **paired** TV, the TV may request any catalog title’s `phone_local` bytes from the serving phone.

## Discovery

1. Phone advertises DNS-SD `_cablegram._tcp` with household/device identifiers (not the 6-digit pairing PIN as the media secret).
2. Phone PATCHes `/api/devices/{id}` with last `lan_host` / `lan_port` (presence only).
3. TV resolves: NSD match on household device, else last hint from catalog/device payload.

## HTTP (phone)

Base: `http://{lan_host}:{lan_port}` (TLS on LAN is a later hardening; MVP may use HTTP on the private network with a device capability token).

Authorization: bearer **device capability** minted at pairing claim (`lan_capability`), delivered to the TV via the pairing poll. During the transition rollout the server ALSO accepts the legacy pairing PIN (double-accept) so unmatched app versions keep working; the PIN MUST be dropped once both clients send the capability (`tasks.md` T071).

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/media/{origin_identity}` | `Range` requests; video bytes |
| HEAD | `/media/{origin_identity}` | Size / accept ranges |
| GET | `/poster/{origin_identity}` | Optional local poster if control poster missing |

Authorization: bearer **device/session capability** established at pairing (TV device secret), valid while the TV presents as that paired device. **Not** the pairing PIN. **Not** per-title. Phone MUST refuse revoked device ids when it can reach the control plane; when the control plane is down, the phone MAY honor a previously paired TV capability already known locally (pairing leftover), matching “unlocked TV may start play.”

Profile PIN is enforced on the **TV UI**, not on each media GET.

## Serving Lifecycle & Background Execution (Phone)

To prevent Android OS from killing the process or putting the Wi-Fi radio to sleep while streaming to the TV:
1. The phone HTTP media server MUST be managed by an Android Foreground Service with an ongoing playback notification while an active client connection is streaming.
2. Acquire a `WifiManager.WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`) and `PowerManager.WakeLock` for the duration of active media serving.

## Failures (household language)

- Phone unreachable / asleep
- Not on this network
- No `phone_local` source for this item
- Device revoked

MIME: send the real type when known; do not assume `video/mp4` only.
