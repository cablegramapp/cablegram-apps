import Foundation

/// One HTTP exchange: `status` is nil when the server could not be reached.
public struct APIReply: Sendable {
    public var status: Int?
    public var data: Data

    public init(status: Int?, data: Data = Data()) {
        self.status = status
        self.data = data
    }

    public var isSuccess: Bool { status.map { (200...299).contains($0) } ?? false }

    /// The server's `{"error": "..."}` code, if the body has one.
    public var errorCode: String? { object?["error"] as? String }

    var object: [String: Any]? { (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] }

    func decode<T: Decodable>(_ type: T.Type = T.self) -> T? { try? JSONDecoder().decode(T.self, from: data) }
}
