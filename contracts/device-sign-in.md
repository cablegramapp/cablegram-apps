# Device Sign-In Contract (`auth.mode = "device"`)

Used by Cablegram Community servers where one household owner operates the server without email or OAuth accounts.

## Routes

- `GET /api/auth/methods`
- `POST /api/auth/setup/claim`
- `POST /api/auth/join-requests`
- `GET /api/auth/join-requests/:id`
- `GET /api/auth/join-requests/pending`
- `POST /api/auth/join-requests/:id/approve`
- `POST /api/auth/join-requests/:id/deny`

## 1. First Phone (or Recovery): Setup Code Claim

On first start with no household (or when the operator runs `cablegram-server setup-code`), the server logs a single-use 6-digit setup code valid for 1 hour.

- `POST /api/auth/setup/claim`
  - Request body:
    ```json
    {
      "code": "123456",
      "device_name": "Pixel 9",
      "household_name": "Home",
      "hardware_id": "optional-device-id",
      "client": "app"
    }
    ```
  - Response (`201 Created`):
    ```json
    {
      "access_token": "...",
      "refresh_token": "...",
      "user_id": "...",
      "household_id": "...",
      "profile_id": "...",
      "device_id": "...",
      "device_secret": "...",
      "display_name": "Home"
    }
    ```
  - Errors: `400 invalid_setup_code`, `400 setup_code_expired`, `429 too_many_setup_attempts`.

## 2. Additional Phones: Join Request & Existing-Phone Approval

1. **New phone starts a join request** (unauthenticated):
   - `POST /api/auth/join-requests`
   - Request body: `{"device_name": "iPad", "client": "app"}`
   - Response (`201 Created`):
     ```json
     {
       "id": "uuid",
       "short_code": "654321",
       "poll_secret": "base64url-secret",
       "expires_at": "2026-10-21T20:00:00.000Z"
     }
     ```
2. **New phone polls its join request** using `Authorization: Join <poll_secret>`:
   - `GET /api/auth/join-requests/:id`
   - Response (`200 OK`):
     - While waiting: `{"id": "uuid", "status": "pending"}`
     - Once approved (one-time token handoff):
       ```json
       {
         "id": "uuid",
         "status": "approved",
         "access_token": "...",
         "refresh_token": "...",
         "household_id": "...",
         "device_id": "...",
         "display_name": "Home"
       }
       ```
     - If denied or expired: `{"id": "uuid", "status": "denied"}` or `{"id": "uuid", "status": "expired"}`
3. **Signed-in phone lists and resolves pending requests** (`Bearer <phone_jwt>`):
   - `GET /api/auth/join-requests/pending` -> `{"requests": [{"id": "uuid", "device_name": "iPad", "expires_at": "...", "created_at": "..."}]}`
   - `POST /api/auth/join-requests/:id/approve` with optional `{"short_code": "654321"}` -> `{"ok": true, "status": "approved", "device_id": "..."}`
   - `POST /api/auth/join-requests/:id/deny` -> `{"ok": true, "status": "denied"}`
