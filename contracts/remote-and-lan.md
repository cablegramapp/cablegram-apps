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

