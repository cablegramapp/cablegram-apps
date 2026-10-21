import Foundation

/// One TDLib JSON object (a request, a response or an update), with typed readers. TDLib writes 64-bit integers as strings in JSON and
/// accepts both forms, so `int64` reads either.
public struct TdObject: @unchecked Sendable {
    public var fields: [String: Any]
    public init(_ fields: [String: Any]) { self.fields = fields }

    public var type: String { fields["@type"] as? String ?? "" }
    public func string(_ key: String) -> String { fields[key] as? String ?? "" }
    public func bool(_ key: String) -> Bool { (fields[key] as? Bool) ?? ((fields[key] as? NSNumber)?.boolValue ?? false) }
    public func int(_ key: String) -> Int { Int(int64(key)) }
    public func int64(_ key: String) -> Int64 { Self.int64(fields[key]) }
    public func object(_ key: String) -> TdObject? { (fields[key] as? [String: Any]).map(TdObject.init) }
    public func objects(_ key: String) -> [TdObject] { (fields[key] as? [Any] ?? []).compactMap { ($0 as? [String: Any]).map(TdObject.init) } }
    public func int64s(_ key: String) -> [Int64] { (fields[key] as? [Any] ?? []).map { Self.int64($0) } }

    static func int64(_ any: Any?) -> Int64 {
        if let number = any as? NSNumber { return number.int64Value }
        if let text = any as? String { return Int64(text) ?? 0 }
        return 0
    }

    public func data(_ key: String) -> Data { Data(base64Encoded: string(key)) ?? Data() }
}

/// What `TdlibTelegramApi` needs from TDLib: ask something and get the answer, and hear the updates. The real transport (TDLibFramework's
/// JSON client) lives in the app target; tests use a fake.
public protocol TdTransport: Sendable {
    /// Sends `request` and returns TDLib's answer. An `error` answer is thrown as `TgError`.
    func send(_ request: TdObject) async throws -> TdObject
    /// Updates (`update*` objects). Each call is a new subscription: it sees what arrives after it was made.
    var updates: AsyncStream<TdObject> { get }
}
