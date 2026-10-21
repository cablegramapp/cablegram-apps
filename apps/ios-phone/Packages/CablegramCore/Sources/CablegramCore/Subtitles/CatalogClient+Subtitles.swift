import Foundation

extension CatalogClient {
    /// `/api/subtitles/<path>` of the control plane, answering the body text. A refused call throws the server's error code (or
    /// `network_unavailable`), which `errorMessage` turns into words. An expired access token is renewed once.
    public func subtitleRequest(path: String, token: String, method: String = "GET", body: String? = nil) async throws -> String {
        let json: [String: Any]? = body.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] }
        let reply = await send(method, "/api/subtitles/\(path)", token: token, json: json ?? (body == nil ? nil : [:]))
        guard reply.isSuccess else { throw LocalSubtitleError(reply.errorCode ?? "network_unavailable") }
        return String(decoding: reply.data, as: UTF8.self)
    }
}
