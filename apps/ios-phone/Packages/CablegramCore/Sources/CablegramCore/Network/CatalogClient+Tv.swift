import Foundation

/// TV pairing, trust, approvals, profile lock and remote commands of `CatalogClient.kt`.
extension CatalogClient {
    // MARK: Pairing

    /// Claims the PIN a TV shows for this account (`POST /api/auth/device/claim`). A temporary TV carries its trust fields.
    public func pairPhone(pin: String, existingToken: String?, trust: TvTrust = .home) async -> PairOutcome {
        guard let existingToken, !existingToken.isEmpty else { return PairOutcome(claim: nil, error: nil) }
        var body: [String: Any] = ["pin": pin]
        if trust.temporary {
            body["trust_level"] = "temporary"
            if let expires = trust.expiresAt { body["trust_expires_at"] = isoTimestamp(expires) }
            body["telegram_direct_allowed"] = trust.telegramDirect
        }
        let reply = await send("POST", "/api/auth/device/claim", token: existingToken, json: body)
        guard reply.isSuccess, let object = reply.object else { return PairOutcome(claim: nil, error: reply.errorCode) }
        return PairOutcome(claim: PairClaim(lanCapability: object["lan_capability"] as? String, deviceId: object["device_id"] as? String), error: nil)
    }

    /// Tells the control plane where this phone's LAN server is (Android `announce`).
    public func announce(host: String, port: Int, deviceId: String?, token: String?, hardwareId: String? = nil, certSha256: String? = nil) async -> Bool {
        guard let deviceId, !deviceId.isEmpty, let token, !token.isEmpty else { return false }
        var body: [String: Any] = ["lan_host": host, "lan_port": port]
        if let certSha256 { body["lan_cert_sha256"] = certSha256 }
        if let hardwareId { body["hardware_id"] = hardwareId }
        return await send("PATCH", "/api/devices/\(deviceId)", token: token, json: body).isSuccess
    }

    /// Active (unrevoked) TVs of the account; nil when offline.
    public func householdTvs(token: String) async -> [MeDevice]? {
        let reply = await send("GET", "/api/me", token: token)
        guard reply.isSuccess, let devices = reply.object?["devices"] as? [[String: Any]] else { return nil }
        return devices.compactMap { dict -> MeDevice? in
            guard let data = try? JSONSerialization.data(withJSONObject: dict), let device = try? JSONDecoder().decode(MeDevice.self, from: data) else { return nil }
            return device.kind == "tv" && (device.revokedAt ?? "").isEmpty ? device : nil
        }
    }

    /// Renames a TV of the account ("Living room"). Phone-scope tokens only.
    public func renameDevice(token: String, deviceId: String, name: String) async -> Bool {
        await send("PATCH", "/api/devices/\(deviceId)", token: token, json: ["display_name": name]).isSuccess
    }

    public func setTvTrust(token: String, tvDeviceId: String, trust: TvTrust) async -> Bool {
        var body: [String: Any] = ["trust_level": trust.temporary ? "temporary" : "home", "telegram_direct_allowed": trust.telegramDirect]
        if trust.temporary, let expires = trust.expiresAt { body["trust_expires_at"] = isoTimestamp(expires) }
        return await send("PATCH", "/api/devices/\(tvDeviceId)", token: token, json: body).isSuccess
    }

    // MARK: Private playback approvals (Constitution VI)

    private struct PendingApprovals: Decodable { var items: [Item]
        struct Item: Decodable {
            var attempt_id: String; var media_item_id: String?; var title: String?; var expires_at: String?; var tv_name: String?; var shared_tv: Bool?
        }
    }

    /// Asks the control plane which private playback attempts are waiting.
    public func fetchPendingPrivateApprovals(token: String) async -> [PendingApproval] {
        let reply = await send("GET", "/api/playback/private-approvals/pending", token: token)
        guard reply.isSuccess, let parsed: PendingApprovals = reply.decode() else { return [] }
        return parsed.items.map { PendingApproval(attemptId: $0.attempt_id, mediaItemId: $0.media_item_id, title: $0.title, expiresAt: $0.expires_at, tvName: $0.tv_name, sharedTv: $0.shared_tv ?? false) }
    }

    /// The one-time token the TV redeems, or nil when the request was not accepted.
    public func approvePrivatePlayback(token: String, attemptId: String) async -> Bool {
        let reply = await send("POST", "/api/playback/private-approvals/\(attemptId)/approve", token: token, json: [:])
        return reply.isSuccess && ((reply.object?["token"] as? String)?.isEmpty == false)
    }

    public func denyPrivatePlayback(token: String, attemptId: String) async -> Bool {
        await send("POST", "/api/playback/private-approvals/\(attemptId)/deny", token: token, json: [:]).isSuccess
    }

    // MARK: Profile lock: a TV opens a profile once a phone allows it

    private struct PendingProfiles: Decodable { var items: [Item]
        struct Item: Decodable { var request_id: String; var profile_name: String?; var tv_name: String?; var expires_at: String?; var shared_tv: Bool? }
    }

    public func fetchPendingProfileRequests(token: String) async -> [PendingApproval] {
        let reply = await send("GET", "/api/profiles/switch-requests/pending", token: token)
        guard reply.isSuccess, let parsed: PendingProfiles = reply.decode() else { return [] }
        return parsed.items.map { PendingApproval(attemptId: $0.request_id, title: $0.profile_name, expiresAt: $0.expires_at, tvName: $0.tv_name, sharedTv: $0.shared_tv ?? false, kind: .profile) }
    }

    /// False when the request was already answered, expired, or the server could not be reached.
    public func answerProfileRequest(token: String, requestId: String, allow: Bool) async -> Bool {
        await send("POST", "/api/profiles/switch-requests/\(requestId)/\(allow ? "approve" : "deny")", token: token, json: [:]).isSuccess
    }

    /// Everything TVs and joining phones wait for: private titles, profiles to open, and join requests.
    public func fetchAllPendingApprovals(token: String) async -> [PendingApproval] {
        async let profiles = fetchPendingProfileRequests(token: token)
        async let playback = fetchPendingPrivateApprovals(token: token)
        async let joins = fetchPendingJoinRequests(token: token)
        let (prof, play, jn) = await (profiles, playback, joins)
        return prof + play + jn.map { $0.asApproval() }
    }

    /// Sends the phone's answer; false when the TV or joining phone no longer waits or the server could not be reached.
    public func answerApproval(token: String, approval: PendingApproval, allow: Bool) async -> Bool {
        switch approval.kind {
        case .profile: await answerProfileRequest(token: token, requestId: approval.attemptId, allow: allow)
        case .playback: allow ? await approvePrivatePlayback(token: token, attemptId: approval.attemptId) : await denyPrivatePlayback(token: token, attemptId: approval.attemptId)
        case .phoneJoin: await answerJoinRequest(id: approval.attemptId, token: token, approved: allow)
        }
    }

    private func household(_ token: String) async -> [String: Any]? {
        let reply = await send("GET", "/api/me", token: token)
        return reply.isSuccess ? reply.object?["household"] as? [String: Any] : nil
    }

    /// Whether TVs must ask a phone before opening a profile; nil when offline.
    public func profileLock(token: String) async -> Bool? { await household(token)?["profile_lock"] as? Bool }

    /// CAB-67: the account's idle time for shared TVs, in minutes; nil when offline.
    public func sharedTvIdleMinutes(token: String) async -> Int? { await household(token)?["shared_tv_idle_minutes"] as? Int }

    private func patchHousehold(_ token: String, _ body: [String: Any]) async -> [String: Any]? {
        let reply = await send("PATCH", "/api/household", token: token, json: body)
        return reply.isSuccess ? reply.object?["household"] as? [String: Any] : nil
    }

    /// The idle time as the server saved it; nil when it could not be saved.
    public func setSharedTvIdleMinutes(token: String, minutes: Int) async -> Int? {
        await patchHousehold(token, ["shared_tv_idle_minutes": minutes])?["shared_tv_idle_minutes"] as? Int
    }

    /// The lock as the server saved it; nil when it could not be saved.
    public func setProfileLock(token: String, on: Bool) async -> Bool? {
        await patchHousehold(token, ["profile_lock": on])?["profile_lock"] as? Bool
    }

    // MARK: Remote commands

    /// Sends a remote-control command to one TV. `.accepted` means the server stored it, not that the TV ran it; see
    /// `commandStatus`. A network error is retried once with the same body, so the same client-generated id makes the retry
    /// harmless. If both attempts fail on the network, the status is read once: the first attempt may have reached the server.
    public func postCommand(token: String, command: String, videoId: String? = nil, targetDeviceId: String?, arguments: [String: any Sendable] = [:]) async -> CommandSend {
        guard let targetDeviceId, !targetDeviceId.trimmingCharacters(in: .whitespaces).isEmpty else { return .failed }
        let id = UUID().uuidString.lowercased()
        guard let body = try? remoteCommandBody(command: command, targetDeviceId: targetDeviceId, videoId: videoId, arguments: arguments, id: id) else { return .failed }
        func attempt() async -> CommandSend? {
            let reply = await send("POST", "/api/control/commands", token: token, json: body)
            guard let status = reply.status else { return nil }
            if reply.isSuccess { return .accepted(id: id) }
            // The server names the revoked or removed TV; anything else stays a plain failure.
            if status == 400, String(decoding: reply.data, as: UTF8.self).contains("unknown_target_device") { return .targetGone }
            return .failed
        }
        if let first = await attempt() { return first }
        if let second = await attempt() { return second }
        // Stored by a request whose response was lost: the TV will run it, so report it as sent.
        if case .known? = await commandStatus(token: token, id: id) { return .accepted(id: id) }
        return .failed
    }

    /// CAB-57: what the TV's player last reported; nil when it could not be read (or the server is older).
    public func tvPlayback(token: String, deviceId: String) async -> TvPlayback? {
        let reply = await send("GET", "/api/control/playback/\(deviceId)", token: token)
        guard reply.isSuccess, let body = reply.object else { return nil }
        func int64(_ key: String) -> Int64? { (body[key] as? NSNumber)?.int64Value }
        return TvPlayback(state: body["state"] as? String ?? "unknown", videoId: body["video_id"] as? String, originId: body["origin_id"] as? String,
                          positionMs: int64("position_ms"), ageMs: int64("age_ms"), online: body["online"] as? Bool ?? true,
                          volume: (body["volume"] as? NSNumber)?.intValue)
    }

    /// What the TV did with a command; nil when the status could not be read right now.
    public func commandStatus(token: String, id: String) async -> CommandStatus? {
        let reply = await send("GET", "/api/control/commands/\(id)", token: token)
        switch reply.status {
        case nil: return nil
        case 404:
            // This endpoint answers an unknown command with `not_found`, which is not final: keep asking. Any other 404 (Fastify's
            // route-not-found, or a proxy page) is a server without the endpoint, so the TV's result cannot be read.
            return reply.errorCode == "not_found" ? nil : .unsupported
        default:
            guard reply.isSuccess, let body = reply.object else { return nil }
            return .known(state: body["status"] as? String ?? "", reason: body["reason"] as? String, expiresInMs: (body["expires_in_ms"] as? NSNumber)?.int64Value)
        }
    }

    // MARK: Serving (LAN capability, private passes, revocation)

    /// The TV device id owning `capability` in this household; nil when unknown or offline.
    public func verifyLanCapability(token: String, capability: String) async -> String? {
        let reply = await send("POST", "/api/lan-capabilities/verify", token: token, json: ["capability": capability])
        return reply.isSuccess ? reply.object?["tv_device_id"] as? String : nil
    }

    /// Confirms a TV's LAN pass for a private title; nil when invalid or offline.
    public func verifyLanPass(token: String, pass: String) async -> VerifiedPass? {
        let reply = await send("POST", "/api/playback/lan-passes/verify", token: token, json: ["pass": pass])
        guard reply.isSuccess, let body = reply.object, let expires = body["expires_at"] as? String, let date = parseISO8601(expires) else { return nil }
        return VerifiedPass(originIdentities: Set(body["origin_identities"] as? [String] ?? []), tvDeviceId: body["tv_device_id"] as? String, expiresAt: date)
    }

    /// R-2: the household devices the control plane reports revoked (empty when offline or on error).
    public func fetchRevokedDeviceIds(token: String) async -> Set<String> {
        let reply = await send("GET", "/api/me", token: token)
        guard reply.isSuccess, let devices = reply.object?["devices"] as? [[String: Any]] else { return [] }
        return Set(devices.compactMap { dict in
            guard let id = dict["id"] as? String, let revoked = dict["revoked_at"] as? String, !revoked.isEmpty else { return nil }
            return id
        })
    }
}
