# Relay protocol (v1)

The relay is a reverse HTTP tunnel to the phone's existing media server. A TV
sends an ordinary HTTP request to the relay; the relay forwards it over the
phone's WebSocket; the phone executes it against its local media server
(`http://127.0.0.1:8765`) and streams the response back. Authorization of the
media request (capability, private pass, Range) stays on the phone.

## Endpoints

| Who | Request | Auth |
|---|---|---|
| Phone | `GET /relay/v1/phone` (WebSocket upgrade) | `Authorization: Bearer <phone access token>` |
| TV | `GET\|HEAD /relay/v1/p/<phoneDeviceId>/<path>?<query>` | `Authorization: Bearer <tv token>` |
| Anyone | `GET /relay/v1/health` | none |

`<path>` is limited to `media/<id>` and `poster/<id>`; the query is passed
through unchanged (it carries `token` and `pass` for the phone).

The relay authorizes each request with the Cablegram servers; that server-to-server
exchange is not part of this contract.

## WebSocket frames (phone ↔ relay)

Text frames are JSON control messages; binary frames carry body bytes.

Relay → phone:

```json
{"t":"req","sid":7,"method":"GET","path":"/media/<id>?token=…","headers":{"range":"bytes=0-"}}
{"t":"credit","sid":7,"bytes":1048576}
{"t":"cancel","sid":7}
{"t":"ping"}
```

Phone → relay:

```json
{"t":"hello","v":1,"network":"wifi|cellular|other"}
{"t":"head","sid":7,"status":206,"headers":{"content-range":"…","content-length":"…","content-type":"video/mp4","accept-ranges":"bytes"}}
{"t":"end","sid":7}
{"t":"error","sid":7,"status":403,"reason":"mobile_data_not_allowed"}
{"t":"pong"}
```

Binary body frame: 4-byte big-endian `sid` followed by payload (≤ 64 KiB).

## Flow control

Each stream starts with 1 MiB of credit. The phone sends body bytes only while
it has credit; the relay grants more as the TV socket drains (writes complete),
so a slow TV cannot make the phone or relay buffer unbounded data. A `cancel`
(TV disconnected) stops the phone's read immediately.

## Errors seen by the TV

| Status | `error` | Meaning |
|---|---|---|
| 503 | `phone_offline` | No relay connection from that phone |
| 402 | `relay_quota_exceeded` | Household quota used up |
| 429 | `relay_streams_exceeded` | Concurrent stream cap |
| 403 | `wrong_household` / `mobile_data_not_allowed` / `mobile_data_pending` | Relay refused the request |
| 504 | `phone_timeout` | Phone did not answer the request head in 15 s |
