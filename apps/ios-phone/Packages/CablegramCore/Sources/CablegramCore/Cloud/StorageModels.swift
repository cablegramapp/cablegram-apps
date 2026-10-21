import Foundation

public struct StorageQuota: Codable, Sendable, Equatable {
    public var usedBytes: Int64
    public var limitBytes: Int64?
    public init(usedBytes: Int64 = 0, limitBytes: Int64? = nil) {
        self.usedBytes = usedBytes
        self.limitBytes = limitBytes
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        usedBytes = try c.decodeIfPresent(Int64.self, forKey: .usedBytes) ?? 0
        limitBytes = try c.decodeIfPresent(Int64.self, forKey: .limitBytes)
    }
}

public struct StorageConnection: Codable, Sendable, Equatable {
    public var id: String?
    public var provider: String?
    public var status: String?
    public var bucketName: String?
    public var displayLabel: String?
    /// The signed-in account (Google email); nil for providers that connect by keys.
    public var accountLabel: String?
    /// Where files go: the Drive folder name.
    public var locationLabel: String?
    /// A stable code such as `reauthorization_required`, never provider text.
    public var lastError: String?
    public var quota: StorageQuota?

    public init(id: String? = nil, provider: String? = nil, status: String? = nil, bucketName: String? = nil, displayLabel: String? = nil,
                accountLabel: String? = nil, locationLabel: String? = nil, lastError: String? = nil, quota: StorageQuota? = nil) {
        self.id = id; self.provider = provider; self.status = status; self.bucketName = bucketName; self.displayLabel = displayLabel
        self.accountLabel = accountLabel; self.locationLabel = locationLabel; self.lastError = lastError; self.quota = quota
    }
}

/// A storage provider the server offers (spec 006). `configured` is false when the server lacks what it needs.
public struct StorageProviderInfo: Codable, Sendable, Equatable {
    public var id: String
    public var name: String
    public var connectMethod: String
    public var configured: Bool

    public init(id: String, name: String? = nil, connectMethod: String = "keys", configured: Bool = false) {
        self.id = id
        self.name = name ?? id
        self.connectMethod = connectMethod
        self.configured = configured
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? id
        connectMethod = try c.decodeIfPresent(String.self, forKey: .connectMethod) ?? "keys"
        configured = try c.decodeIfPresent(Bool.self, forKey: .configured) ?? false
    }
}

public struct StorageStatusResponse: Codable, Sendable, Equatable {
    public var configured: Bool
    public var providers: [StorageProviderInfo]
    public var connection: StorageConnection?
    public var connections: [StorageConnection]

    public init(configured: Bool = false, providers: [StorageProviderInfo] = [], connection: StorageConnection? = nil, connections: [StorageConnection] = []) {
        self.configured = configured; self.providers = providers; self.connection = connection; self.connections = connections
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        configured = try c.decodeIfPresent(Bool.self, forKey: .configured) ?? false
        providers = try c.decodeIfPresent([StorageProviderInfo].self, forKey: .providers) ?? []
        connection = try c.decodeIfPresent(StorageConnection.self, forKey: .connection)
        connections = try c.decodeIfPresent([StorageConnection].self, forKey: .connections) ?? []
    }
}

/// Where to fetch this phone's own cloud copy back from (spec 006). `headers` is empty for a presigned URL (R2) and carries
/// the bearer token for Google Drive. Never logged: `description` leaves both out.
public struct ReadUrl: Sendable, Equatable, CustomStringConvertible {
    public var url: String
    public var headers: [String: String]
    public init(url: String, headers: [String: String]) {
        self.url = url
        self.headers = headers
    }
    public var description: String { "ReadUrl(headers=\(headers.count))" }
}

/// Reads the control plane's `read-url` answer; nil when it has no usable HTTPS URL.
public func parseReadUrl(_ body: Data) -> ReadUrl? {
    guard let object = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
          let url = object["url"] as? String, url.hasPrefix("https://") else { return nil }
    var headers: [String: String] = [:]
    for (name, value) in (object["headers"] as? [String: Any]) ?? [:] {
        // Only plain header names and single-line values are sent.
        if let v = value as? String, name.range(of: #"^[A-Za-z0-9-]+$"#, options: .regularExpression) != nil, !v.contains("\n"), !v.contains("\r") {
            headers[name] = v
        }
    }
    return ReadUrl(url: url, headers: headers)
}

/// How `DELETE /api/storage/sources/:id` ended: `error` is nil on success, else the server's stable error string.
public struct RemoveCopyResult: Sendable, Equatable {
    public var error: String?
    public init(error: String?) { self.error = error }
}

/// How `POST /api/storage/connect/r2` ended: `error` is nil on success, else the server's stable error string.
public struct R2ConnectResult: Sendable, Equatable {
    public var error: String?
    public init(error: String?) { self.error = error }
}

/// How `POST /api/storage/connect/google` ended: `authorizeUrl` to open, or the server's stable `error`.
public struct GoogleConnectStart: Sendable, Equatable {
    public var authorizeUrl: String?
    public var error: String?
    public init(authorizeUrl: String?, error: String?) {
        self.authorizeUrl = authorizeUrl
        self.error = error
    }
}
