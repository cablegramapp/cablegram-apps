# Contract: R2 storage connection, upload and playback

Routes are under `/api`, use bearer device tokens, and are limited to the caller's household. No
response carries an OAuth token, an S3 key or a secret. Field names are camelCase for the existing
phone models (`/api/storage/status`, `/connect/cloudflare`) and snake_case for new routes, as in the
rest of the API.

## Connection

### `GET /api/storage/status` (phone or TV)

```json
200 { "configured": true, "defaultBackend": "none",
      "connection": { "id": "uuid", "provider": "cloudflare_r2", "status": "active",
                      "bucketName": "cablegram-user-storage",
                      "displayLabel": "Masoud / cablegram-user-storage",
                      "connectedAt": "2026-10-01T10:00:00Z", "lastError": null },
      "connections": [ …same shape… ] }
```

`connection` is the active one or `null`. `configured` is false when the server has no Cloudflare
OAuth client.

### `GET /api/storage/connect/cloudflare?returnTo=phone` (phone only)

`200 { "provider": "cloudflare_r2", "authUrl": "https://dash.cloudflare.com/oauth2/auth?…" }`.
`503 cloudflare_oauth_not_configured`. `409 storage_already_connected` while one is active.
The `state` inside `authUrl` is a 15-minute signed token with the household, device and a nonce. The
nonce is single use.

### `GET /api/storage/oauth/cloudflare/callback` (browser, no bearer)

Validates `state`, exchanges the code, lists accounts, and either provisions (one account) or
returns an account picker page (several). Always returns HTML. On success it links to
`cablegram://storage`. Failures say what to do: enable R2, add the API-token scope, or retry.

### `POST /api/storage/oauth/cloudflare/select` (browser, form, no bearer)

`ticket` (10-minute signed token) and `accountId`. Same result page as the callback.

### `POST /api/storage/disconnect` (phone only)

`{}` → `200 { "status": "disconnected" }`, `404 storage_not_connected`. Deletes the stored credential,
aborts open uploads, marks the household's `cloud_r2` sources `unavailable`. Objects stay in the bucket.

## Upload (phone only)

### `POST /api/storage/uploads`

```json
{ "attach_to_origin_identity": "phone local id", "size_bytes": 1073741824,
  "content_type": "video/x-matroska", "file_name": "film.mkv" }
```
(or `media_item_id` instead of the identity.) Strict body.

- `201 { "upload_id": "uuid", "part_size": 16777216, "part_count": 64, "resumed": false }`.
- A second call for the same title while one is open returns that upload with `resumed: true`.
- `409 storage_not_connected`, `404 not_found`, `413 file_too_large` (> 10 000 parts).

### `GET /api/storage/uploads/:id/parts?from=1&count=8`

`200 { "uploaded": [ {"part": 1, "etag": "…", "size": 16777216} ],
      "urls": [ { "part": 1, "url": "https://…presigned PUT…" } ], "expires_in_s": 3600 }`.
`uploaded` is R2's own `ListParts`. URLs are minted per request for the requested parts only.

### `POST /api/storage/uploads/:id/complete`

`{ "parts": [ {"part": 1, "etag": "…"} ] }`. Completes the multipart upload, `HEAD`s the object and
checks `size_bytes`. On success it adds the `cloud_r2` source (idempotent by object key) and returns
`200 { "source_id": "uuid", "media_item_id": "uuid", "bytes": 1073741824 }`. On a size mismatch it deletes
the object and returns `422 size_mismatch`.

### `DELETE /api/storage/uploads/:id`

Aborts the multipart upload. `204`.

## Sources

### `DELETE /api/storage/sources/:sourceId` (phone only)

Deletes the object from the bucket and the source row. `204`. `404` for any other household's source.

## Playback

`POST /api/playback/resolve` (TV), for a title whose available source is `cloud_r2`:

```json
200 { "status": "ready", "url": "https://<acct>.r2.cloudflarestorage.com/…?X-Amz-…",
      "expiresAt": "…", "title": "…", "poster_url": "…" }
```
Private titles keep the approval check. `404 source_unavailable` while disconnected.
