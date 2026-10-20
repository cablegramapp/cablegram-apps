import Foundation

/// Storage endpoints of `CatalogClient.kt`: status, connect (R2 keys, Google), Save to Cloud calls, read-back, disconnect.
extension CatalogClient {
    public func storageStatus(token: String) async -> StorageStatusResponse? {
        let reply = await send("GET", "/api/storage/status", token: token)
        return reply.isSuccess ? reply.decode() : nil
    }

    private func stableCode(_ reply: APIReply) -> String? {
        reply.errorCode.flatMap { $0.range(of: #"^[a-z_]{1,40}$"#, options: .regularExpression) != nil ? $0 : nil }
    }

    /// Connects the household's own R2 bucket (spec 005). The control plane checks the keys with a real write before it keeps
    /// them. `secret` is sent once, over TLS, and is not kept by this client.
    public func connectR2(token: String, accountId: String, bucket: String, accessKeyId: String, secret: String) async -> R2ConnectResult {
        let reply = await send("POST", "/api/storage/connect/r2", token: token,
                               json: ["account_id": accountId, "bucket": bucket, "access_key_id": accessKeyId, "secret_access_key": secret])
        if reply.status == nil { return R2ConnectResult(error: "offline") }
        if reply.isSuccess { return R2ConnectResult(error: nil) }
        // Only a stable error string is kept; a 400 means a field the server did not accept.
        return R2ConnectResult(error: stableCode(reply) ?? (reply.status == 400 ? "invalid_request" : "http_\(reply.status!)"))
    }

    /// Starts the Google Drive sign-in (spec 006): the server makes the state and PKCE and answers with the Google URL to open
    /// in a web authentication session.
    public func connectGoogle(token: String) async -> GoogleConnectStart {
        let reply = await send("POST", "/api/storage/connect/google", token: token, json: [:])
        if reply.status == nil { return GoogleConnectStart(authorizeUrl: nil, error: "offline") }
        if reply.isSuccess {
            if let url = reply.object?["authorize_url"] as? String, url.hasPrefix("https://") { return GoogleConnectStart(authorizeUrl: url, error: nil) }
            return GoogleConnectStart(authorizeUrl: nil, error: "invalid_response")
        }
        return GoogleConnectStart(authorizeUrl: nil, error: stableCode(reply) ?? "http_\(reply.status!)")
    }

    /// Save to Cloud against the household's own storage (specs 005, 006); `token` is the phone's account token.
    public func ownCloudApi(token: String) -> any OwnCloudApi { ClientOwnCloudApi(client: self, token: token) }

    /// Where to read this phone title's copy in the household's own storage back from, with the headers that request needs.
    /// Nil if it has no copy.
    public func ownCloudReadUrl(token: String, originIdentity: String) async -> ReadUrl? {
        let reply = await send("POST", "/api/storage/read-url", token: token, json: ["origin_identity": originIdentity])
        return reply.isSuccess ? parseReadUrl(reply.data) : nil
    }

    /// Deletes one copy in the household's own storage: the control plane removes the file, and the title keeps its other sources.
    public func removeOwnCloudSource(token: String, sourceId: String) async -> RemoveCopyResult {
        let reply = await send("DELETE", "/api/storage/sources/\(sourceId)", token: token)
        if reply.status == nil { return RemoveCopyResult(error: "offline") }
        if reply.isSuccess { return RemoveCopyResult(error: nil) }
        return RemoveCopyResult(error: stableCode(reply) ?? "http_\(reply.status!)")
    }

    public func disconnectStorage(token: String) async -> Bool {
        await send("POST", "/api/storage/disconnect", token: token, json: [:]).isSuccess
    }
}

private struct ClientOwnCloudApi: OwnCloudApi {
    let client: CatalogClient
    let token: String

    private func call<T: Decodable>(_ method: String, _ path: String, json: [String: Any]? = nil, as type: T.Type = T.self) async throws -> T {
        let reply = await client.send(method, path, token: token, json: method == "GET" || method == "DELETE" ? nil : (json ?? [:]))
        guard reply.isSuccess else {
            // Only the stable error string is kept; nothing the server echoed is shown or logged.
            let code = reply.errorCode.flatMap { $0.range(of: #"^[a-z_]{1,40}$"#, options: .regularExpression) != nil ? $0 : nil }
            throw R2ApiError(status: reply.status ?? 0, code: code ?? (reply.status.map { "http_\($0)" } ?? "offline"))
        }
        guard let value: T = reply.decode() else { throw R2ApiError(status: reply.status ?? 0, code: "invalid_response") }
        return value
    }

    func start(originIdentity: String, sizeBytes: Int64, contentType: String, fileName: String) async throws -> R2UploadStart {
        try await call("POST", "/api/storage/uploads", json: ["attach_to_origin_identity": originIdentity, "size_bytes": sizeBytes, "content_type": contentType, "file_name": fileName])
    }

    func parts(uploadId: String, from: Int, count: Int) async throws -> R2Parts {
        try await call("GET", "/api/storage/uploads/\(uploadId)/parts?from=\(from)&count=\(count)")
    }

    func complete(uploadId: String, parts: [R2PartRef]) async throws -> R2Done {
        try await call("POST", "/api/storage/uploads/\(uploadId)/complete", json: ["parts": parts.map { ["part": $0.part, "etag": $0.etag] as [String: Any] }])
    }

    func session(uploadId: String) async throws -> DriveSession { try await call("GET", "/api/storage/uploads/\(uploadId)/session") }

    func complete(uploadId: String) async throws -> R2Done { try await call("POST", "/api/storage/uploads/\(uploadId)/complete", json: [:]) }

    func abort(uploadId: String) async throws {
        let reply = await client.send("DELETE", "/api/storage/uploads/\(uploadId)", token: token)
        guard reply.isSuccess else { throw R2ApiError(status: reply.status ?? 0, code: reply.errorCode ?? (reply.status.map { "http_\($0)" } ?? "offline")) }
    }
}
