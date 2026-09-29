# Pairing, membership, TV unlock, and private playback

Auth for the full product: Cablegram account, household membership, device pairing, profile unlock, remote-command authorization, and private-media approval. Pairing PIN is never a long-lived media password.

## Household membership

1. Register creates a Cablegram user, a household (if they are founding one), and at least one profile.
2. Additional people join only through an explicit invite/accept (or equivalent household-admin grant). Signed-in non-members MUST be denied household resources (catalog, devices, progress, pairing claim, remote, private approval).
3. A household member MAY leave or be removed. Remaining devices and sessions for that person MUST stop using household resources.
4. Each phone is a registered household device distinct from the user login. Revoking a phone MUST NOT automatically unpair TVs; revoking a TV MUST NOT sign out phones.

## Pairing (TV ↔ household)

1. TV `POST /api/auth/device` → PIN + pairing token (short TTL).
2. Show PIN/QR on TV (no long password on remote).
3. Phone of a household member `POST /api/auth/device/claim` with PIN. Response includes the TV's `lan_capability` (the TV's LAN media credential, not the pairing PIN).
4. TV poll `GET /api/auth/device/{id}` with pairing token until `paired` + TV JWT. The FIRST post-pairing poll response also carries `lan_capability`; the server clears the plaintext immediately after delivery.
5. TV stores JWT in platform secure storage (legacy Keystore pattern KEEP).
6. App restart: restore JWT; do not require the phone online; do not require PIN pairing again unless revoked.

PIN is only for **claiming** the pending session. It is hashed at rest. It is **not** the LAN media password, **not** the profile PIN, and **not** the private-media approval credential.

A member MAY revoke a TV (`DELETE /api/devices/{id}`). The TV MUST lose household access; in-flight ordinary playback MUST stop at the next authorization check or app use as specified in the spec.

## Profile unlock (ordinary play authorization)

1. Opening the TV app or switching profile requires PIN (if the profile has one) or an explicit user-id allowance/confirmation.
2. Unlock is local (cached `pin_hash`) so an already-paired TV can unlock when the control service is down.
3. After unlock, **ordinary** household catalog titles with a reachable source may play with no further PIN, phone tap, or control-plane per-title grant.
4. Control service down: if the profile is already unlocked (or hash is cached and the user completes PIN locally), the TV MAY **start** LAN play of ordinary titles; queue progress.
5. A title or source marked **private** is not ordinary (next section).

## Private media approval (Constitution VI)

Private items/sources MUST NOT play on the TV until the authorized phone approves that specific playback attempt.

1. TV (unlocked profile, paired) requests play of a private item/source.
2. Control plane (when reachable) creates a short-lived approval bound to household, profile, TV, item/source, and this attempt.
3. Authorized phone confirms once. The grant is consumed on first successful use (or expiry) and MUST NOT be reused for another title, TV, profile, or later attempt.
4. Pairing PIN and profile PIN MUST NOT be accepted as the private-approval credential.
5. If the control service or phone cannot complete approval, the TV MUST NOT start private playback (ordinary LAN play-without-control-plane does not apply).

## Remote commands

Only authenticated household members on registered, non-revoked phones MAY send remote commands to a paired TV. Unauthorized devices MUST be ignored. Remote authorization is product-required, not deferred.
