# Server Capabilities Contract (`GET /api/server`)

Unauthenticated endpoint that phones, TVs, and the web remote call before sign-in (and after switching servers) to discover the server edition, sign-in mode, active features, and legal documents.

## Route

- `GET /api/server`

## Response (`200 OK`)

### Community Edition

```json
{
  "api_version": 1,
  "edition": "community",
  "server_version": "0.1.0",
  "auth": { "mode": "device" },
  "features": {
    "artwork": false,
    "ai_title_resolution": false,
    "subtitles": false,
    "relay": false,
    "web_import": false,
    "cloud_storage": false,
    "telegram": true,
    "lan_playback": true,
    "profile_lock": true
  },
  "legal": null
}
```

### Hosted Edition

```json
{
  "api_version": 1,
  "edition": "hosted",
  "server_version": "0.1.0",
  "auth": {
    "mode": "account",
    "google": { "client_id": "..." },
    "apple": { "client_id": "..." },
    "email_code": true
  },
  "features": {
    "artwork": true,
    "ai_title_resolution": true,
    "subtitles": true,
    "relay": true,
    "web_import": false,
    "cloud_storage": true,
    "telegram": true,
    "lan_playback": true,
    "profile_lock": true
  },
  "legal": {
    "terms": {
      "version": "2026-10-15",
      "url": "https://cablegram.app/terms.html",
      "acceptance_required_from": "2026-10-15"
    },
    "privacy": {
      "version": "2026-10-15",
      "url": "https://cablegram.app/privacy.html"
    }
  }
}
```

## Client Rules

1. **Missing feature keys** default to `false`, except `telegram` and `lan_playback`, which default to `true` when talking to older servers that do not expose `GET /api/server`.
2. **Cache scope:** Cached capabilities MUST be keyed by the normalized server base URL. Switching servers clears cached tokens, pairing state, and capability cache.
3. **Excluded feature routes:** When a feature is `false`, clients MUST hide its UI entry points and avoid calling its routes. If an older client calls an unmounted feature route on a Community server, the server responds with `410 Gone` and `{"error": "feature_unavailable"}`.
4. **TV server handoff during LAN pairing:** When a phone configured with a non-default server URL pairs a TV over the local network, the phone sends the server URL to the TV's local pairing/config endpoint (`POST /cablegram/server` or query/header on LAN handshake) so the TV switches to that server and starts a pairing session there.
