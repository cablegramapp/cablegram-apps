import Foundation

/// Telegram as cloud storage (`contracts/telegram-link.md`): the endpoints of `CatalogClient.kt` under "Telegram as cloud storage" and
/// "TV trust and Telegram sessions".
extension CatalogClient {
    /// Refuses every redirect: the Telegram client config carries the application's API id and hash.
    private final class NoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest) async -> URLRequest? { nil }
    }

    /// The application's Telegram identity from the control plane (`/api/telegram/client-config`), held in memory only. Fixed error:
    /// nothing the server said is attached. `allowInsecureLocal` lets a debug build use plain http to localhost.
    public func telegramClientCredentials(token: String, allowInsecureLocal: Bool = false) async throws -> TelegramClientCredentials {
        let base = baseURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !token.isEmpty, let url = URL(string: base + "/api/telegram/client-config") else { throw TelegramClientUnavailable() }
        let local = ["localhost", "127.0.0.1", "10.0.2.2"].contains(url.host ?? "")
        guard url.scheme == "https" || (allowInsecureLocal && local) else { throw TelegramClientUnavailable() }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = "GET"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.cachePolicy = .reloadIgnoringLocalCacheData
        do {
            let (data, response) = try await session.data(for: request, delegate: NoRedirect())
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let id = (body["api_id"] as? NSNumber)?.intValue, let hash = body["api_hash"] as? String,
                  id > 0, hash.range(of: "^[a-fA-F0-9]{32}$", options: .regularExpression) != nil else { throw TelegramClientUnavailable() }
            return TelegramClientCredentials(apiId: id, apiHash: hash)
        } catch is CancellationError { throw CancellationError() } catch { throw TelegramClientUnavailable() }
    }

    /// The household's Telegram link; nil when offline.
    public func telegramLink(token: String) async -> TelegramLinkInfo? {
        let reply = await send("GET", "/api/telegram/link", token: token)
        return reply.isSuccess ? reply.object.map(TelegramLinkInfo.init) : nil
    }

    /// Records the linked account and library channel (no secrets). Throws when offline or refused.
    public func putTelegramLink(token: String, telegramUserId: Int64, displayName: String, libraryChatId: Int64, phoneDeviceId: String?) async throws -> TelegramLinkResult {
        var body: [String: Any] = ["telegram_user_id": String(telegramUserId), "display_name": String(displayName.prefix(128)), "library_chat_id": String(libraryChatId)]
        if let phoneDeviceId { body["phone_device_id"] = phoneDeviceId }
        let reply = await send("PUT", "/api/telegram/link", token: token, json: body)
        if reply.isSuccess, let object = reply.object { return TelegramLinkResult(link: TelegramLinkInfo(object)) }
        if reply.status == 409 { return TelegramLinkResult(link: nil, conflictName: reply.object?["linked_display_name"] as? String ?? "another account") }
        throw TelegramRequestFailed(operation: "Could not save the Telegram link", status: reply.status)
    }

    /// TVs waiting for this household's Telegram approval; nil when offline.
    public func pendingTvLogins(token: String) async -> [PendingTvLogin]? {
        let reply = await send("GET", "/api/telegram/tv-logins/pending", token: token)
        guard reply.isSuccess else { return nil }
        return (reply.object?["requests"] as? [[String: Any]] ?? []).compactMap { r in
            guard let id = r["request_id"] as? String, let tv = r["tv_device_id"] as? String, let link = r["login_link"] as? String else { return nil }
            return PendingTvLogin(requestId: id, tvDeviceId: tv, tvName: r["tv_name"] as? String ?? "TV", loginLink: link, expiresInMs: (r["expires_in_ms"] as? NSNumber)?.int64Value ?? 0)
        }
    }

    /// TVs waiting for the Telegram two-step password; nil when offline.
    public func pendingTvPasswordRequests(token: String) async -> [PendingTvPasswordRequest]? {
        let reply = await send("GET", "/api/telegram/tv-password-requests/pending", token: token)
        guard reply.isSuccess else { return nil }
        return (reply.object?["requests"] as? [[String: Any]] ?? []).compactMap { r in
            guard let id = r["request_id"] as? String, let key = r["tv_public_key"] as? String else { return nil }
            return PendingTvPasswordRequest(requestId: id, tvDeviceId: r["tv_device_id"] as? String, tvName: r["tv_name"] as? String ?? "TV", tvPublicKey: key, hint: r["hint"] as? String ?? "")
        }
    }

    /// Tells TVs whether the linked Telegram account has two-step verification, so they ask for it before signing in.
    public func putTelegramTwoStep(token: String, twoStep: Bool) async -> Bool {
        await send("PUT", "/api/telegram/link/two-step", token: token, json: ["two_step": twoStep]).isSuccess
    }

    /// Sends the password, already sealed to the TV's key (`PasswordSeal`); true when stored.
    public func sealTvPassword(token: String, requestId: String, sealed: String) async -> Bool {
        await send("POST", "/api/telegram/tv-password-requests/\(requestId)/seal", token: token, json: ["sealed": sealed]).isSuccess
    }

    public func cancelTvPassword(token: String, requestId: String) async -> Bool {
        await send("POST", "/api/telegram/tv-password-requests/\(requestId)/cancel", token: token, json: [:]).isSuccess
    }

    /// Reports the outcome of a TV login; `error` must be a Telegram error name (never free text).
    public func postTvLoginResult(token: String, requestId: String, outcome: String, error: String? = nil) async -> Bool {
        var body: [String: Any] = ["outcome": outcome]
        if let error { body["error"] = error }
        return await send("POST", "/api/telegram/tv-logins/\(requestId)/result", token: token, json: body).isSuccess
    }

    /// Registers a video from the household's library channel; idempotent by Telegram file. Throws unless accepted (409: already there).
    public func registerTelegramVideo(token: String, video: TgVideo) async throws {
        var body: [String: Any] = [
            "source_kind": "telegram",
            "origin_filename": video.fileName.trimmingCharacters(in: .whitespaces).isEmpty ? "Telegram video \(video.messageId)" : video.fileName,
            "origin_identity": TelegramLibrarySync.originIdentity(video), "stable_source_key": TelegramLibrarySync.stableKey(video),
        ]
        if video.file.size > 0 { body["bytes_total"] = video.file.size }
        if !video.mimeType.trimmingCharacters(in: .whitespaces).isEmpty { body["playback_mime"] = String(video.mimeType.prefix(100)) }
        if video.durationSeconds > 0 { body["duration_seconds"] = video.durationSeconds }
        if let title = TelegramLibrarySync.title(video) { body["title"] = title }
        let reply = await send("POST", "/api/catalog/items", token: token, json: body)
        guard reply.isSuccess || reply.status == 409 else { throw TelegramRequestFailed(operation: "register", status: reply.status) }
    }

    /// Asks a Home TV to approve this phone's Telegram QR login; the link goes to the TV, never to be stored.
    public func postPhoneLogin(token: String, loginLink: String, tvDeviceId: String, phoneDeviceId: String) async -> Bool {
        await send("POST", "/api/telegram/phone-logins", token: token, json: ["login_link": loginLink, "tv_device_id": tvDeviceId, "phone_device_id": phoneDeviceId]).isSuccess
    }

    /// "Save to Telegram": adds the uploaded `video` as a Telegram source of the phone's title `localItemId`.
    public func attachTelegramCopy(token: String, localItemId: String, video: TgVideo) async -> Bool {
        var body: [String: Any] = [
            "source_kind": "telegram",
            // The server knows this phone title by the id the phone gave it, not by its own item id.
            "attach_to_origin_identity": localItemId,
            "origin_filename": video.fileName.trimmingCharacters(in: .whitespaces).isEmpty ? "Telegram video \(video.messageId)" : video.fileName,
            "origin_identity": TelegramLibrarySync.originIdentity(video), "stable_source_key": TelegramLibrarySync.stableKey(video),
        ]
        if video.file.size > 0 { body["bytes_total"] = video.file.size }
        if !video.mimeType.trimmingCharacters(in: .whitespaces).isEmpty { body["playback_mime"] = String(video.mimeType.prefix(100)) }
        return await send("POST", "/api/catalog/items", token: token, json: body).isSuccess
    }

    /// TMDB references found in the library channel for its videos.
    public func postTelegramHints(token: String, hints: [TgTmdbHint]) async throws {
        let list: [[String: Any]] = hints.map { hint in
            var entry: [String: Any] = ["stable_source_key": "tgfile:\(hint.uniqueId)", "tmdb_type": hint.tmdbType, "tmdb_id": hint.tmdbId]
            if let season = hint.season, let episode = hint.episode { entry["season_number"] = season; entry["episode_number"] = episode }
            return entry
        }
        let reply = await send("POST", "/api/catalog/sources/telegram/hints", token: token, json: ["hints": list])
        guard reply.isSuccess else { throw TelegramRequestFailed(operation: "hints", status: reply.status) }
    }

    /// After a full channel scan: sources not in `present` are marked unavailable.
    public func reconcileTelegramVideos(token: String, present: Set<String>) async throws {
        let reply = await send("POST", "/api/catalog/sources/telegram/reconcile", token: token, json: ["present": present.sorted()])
        guard reply.isSuccess else { throw TelegramRequestFailed(operation: "reconcile", status: reply.status) }
    }

    /// Hides or deletes a title (`hide`, `delete`, `delete_source`); nil when the request failed.
    public func removeTelegramTitle(token: String, itemId: String, mode: String, stableSourceKey: String? = nil) async -> [TelegramDeletionOrder]? {
        var body: [String: Any] = ["mode": mode]
        if let stableSourceKey { body["stable_source_key"] = stableSourceKey }
        let reply = await send("POST", "/api/catalog/items/\(itemId)/removal", token: token, json: body)
        guard reply.isSuccess else { return nil }
        return (reply.object?["telegram_deletions"] as? [[String: Any]] ?? []).compactMap { d in
            guard let key = d["stable_source_key"] as? String, let chat = (d["chat_id"] as? NSNumber)?.int64Value ?? (d["chat_id"] as? String).flatMap(Int64.init) else { return nil }
            let ids = (d["message_ids"] as? [Any] ?? []).compactMap { ($0 as? NSNumber)?.int64Value ?? ($0 as? String).flatMap(Int64.init) }
            return TelegramDeletionOrder(stableSourceKey: key, chatId: chat, messageIds: ids)
        }
    }

    /// Tells the control plane how a channel deletion ended; `error` is kept for the retry.
    public func reportTelegramDeletion(token: String, stableSourceKey: String, error: String? = nil) async -> Bool {
        let body: [String: Any] = error == nil ? ["outcome": "deleted"] : ["outcome": "failed", "error": String(error!.prefix(500))]
        return await send("POST", "/api/catalog/tombstones/\(stableSourceKey)/result", token: token, json: body).isSuccess
    }

    public func telegramTombstones(token: String) async -> [TelegramTombstone]? {
        let reply = await send("GET", "/api/catalog/tombstones", token: token)
        guard reply.isSuccess else { return nil }
        return (reply.object?["tombstones"] as? [[String: Any]] ?? []).compactMap { t in
            guard let key = t["stable_source_key"] as? String else { return nil }
            return TelegramTombstone(stableSourceKey: key, itemId: t["item_id"] as? String, title: t["title"] as? String ?? "", state: t["state"] as? String ?? "hidden", lastError: t["last_error"] as? String)
        }
    }

    public func restoreTelegramTitle(token: String, stableSourceKey: String) async -> Bool {
        await send("DELETE", "/api/catalog/tombstones/\(stableSourceKey)", token: token).isSuccess
    }

    // MARK: TV trust and Telegram sessions

    /// Records the Telegram session a TV holds, so the phone can end it later.
    public func putTvTelegramSession(token: String, tvDeviceId: String, telegramSessionId: Int64) async -> Bool {
        await send("PUT", "/api/telegram/tv-sessions/\(tvDeviceId)", token: token, json: ["telegram_session_id": String(telegramSessionId)]).isSuccess
    }

    /// TV sessions to end now; nil when offline.
    public func dueTvTelegramSessions(token: String) async -> [DueTvSession]? {
        let reply = await send("GET", "/api/telegram/tv-sessions?due=true", token: token)
        guard reply.isSuccess else { return nil }
        return (reply.object?["sessions"] as? [[String: Any]] ?? []).compactMap { s in
            guard let tv = s["tv_device_id"] as? String else { return nil }
            let session = (s["telegram_session_id"] as? String) ?? (s["telegram_session_id"] as? NSNumber).map { "\($0)" } ?? ""
            return DueTvSession(tvDeviceId: tv, telegramSessionId: session, reason: s["reason"] as? String ?? "removed")
        }
    }

    /// `outcome` is `terminated`, `already_gone`, or `failed` with a Telegram error name.
    public func reportTvSessionEnded(token: String, tvDeviceId: String, outcome: String, error: String? = nil) async -> Bool {
        var body: [String: Any] = ["outcome": outcome]
        if let error { body["error"] = error }
        return await send("POST", "/api/telegram/tv-sessions/\(tvDeviceId)/terminated", token: token, json: body).isSuccess
    }

    /// "Sign out of Telegram" for one TV: the phone ends its session and the TV wipes its Telegram data.
    public func signOutTvTelegram(token: String, tvDeviceId: String) async -> Bool {
        await send("POST", "/api/telegram/tv-sessions/\(tvDeviceId)/sign-out", token: token, json: [:]).isSuccess
    }

    /// Disconnects Telegram for the household; TVs are told to log out.
    public func deleteTelegramLink(token: String) async -> Bool {
        await send("DELETE", "/api/telegram/link", token: token).isSuccess
    }
}

/// A Telegram control-plane request the server refused or could not answer.
public struct TelegramRequestFailed: Error, Sendable {
    public var operation: String
    public var status: Int?
    public init(operation: String, status: Int?) { self.operation = operation; self.status = status }
}
