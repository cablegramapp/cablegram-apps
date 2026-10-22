import Foundation

// ---- Choosing and connecting a storage provider (spec 006 US1, US2) ----
// Pure functions, so the provider list and every message can be tested without a screen. Messages that Android builds from
// string resources are returned as a `LocalizedText` (catalog key plus arguments); the English-only ones stay plain text.

public struct LocalizedText: Sendable, Equatable {
    public var key: String
    public var args: [String]
    public init(_ key: String, _ args: String...) {
        self.key = key
        self.args = args
    }
}

public enum ProviderState: Sendable, Equatable {
    /// Can be connected now.
    case available
    /// Another provider is connected; this one waits until it is disconnected.
    case locked
    /// This is the connected provider.
    case connected
    /// Connected before, but the provider no longer accepts the sign-in.
    case needsSignIn
}

public struct ProviderRow: Sendable, Equatable {
    public var id: String
    public var name: String
    /// What connecting takes, in one line (catalog key).
    public var summaryKey: String
    public var durationKey: String
    public var state: ProviderState
    /// Shown under a row that cannot be used right now.
    public var note: LocalizedText?
}

public let providerGoogleDrive = "google_drive"
public let providerCloudflareR2 = "cloudflare_r2"

private let knownProviders: [String: (name: String, summaryKey: String, durationKey: String)] = [
    providerGoogleDrive: ("Google Drive", "ui_sign_in_with_google_1dbcee", "ui_about_1_minute"),
    providerCloudflareR2: ("Cloudflare R2", "ui_use_your_own_bucket_and_keys_1a0fdb", "ui_about_5_minutes_for_advanced_users_1d6ff3"),
]

/// A provider's display name for messages: the server's, else this build's, else a neutral phrase (`yourOwnStorage`).
public func providerName(_ id: String?, status: StorageStatusResponse? = nil, yourOwnStorage: String) -> String {
    if let name = status?.providers.first(where: { $0.id == id })?.name, !name.trimmingCharacters(in: .whitespaces).isEmpty { return name }
    if let id, let known = knownProviders[id] { return known.name }
    return yourOwnStorage
}

/// The "Your own storage" list: the providers the server is set up for, in the server's order. While one is connected the
/// rest are turned off; a connection that needs signing in again is marked on its own row.
public func providerRows(_ status: StorageStatusResponse?, yourOwnStorage: String) -> [ProviderRow] {
    let offered = (status?.providers ?? []).filter { $0.configured && knownProviders[$0.id] != nil }
    let active = status?.connection.flatMap { $0.status == "active" ? $0 : nil }
    let broken = active == nil ? status?.connections.first { $0.status == "error" } : nil
    return offered.map { provider in
        let known = knownProviders[provider.id]!
        let label = provider.name.trimmingCharacters(in: .whitespaces).isEmpty ? known.name : provider.name
        let state: ProviderState
        var note: LocalizedText?
        if active?.provider == provider.id { state = .connected }
        else if let active {
            state = .locked
            note = LocalizedText("ui_disconnect_to_switch_5d456e", providerName(active.provider, status: status, yourOwnStorage: yourOwnStorage))
        } else if broken?.provider == provider.id {
            state = .needsSignIn
            note = LocalizedText("ui_sign_in_again_to_keep_using_it_3abfd0")
        } else { state = .available }
        return ProviderRow(id: provider.id, name: label, summaryKey: known.summaryKey, durationKey: known.durationKey, state: state, note: note)
    }
}

/// True when the server offers at least one provider this build can connect.
public func hasOwnStorageProvider(_ status: StorageStatusResponse?) -> Bool {
    (status?.providers ?? []).contains { $0.configured && knownProviders[$0.id] != nil }
}

/// Free bytes when the provider reports a limit; nil when unknown (R2 has no fixed quota).
public func freeSpace(_ quota: StorageQuota?) -> Int64? {
    guard let quota, let limit = quota.limitBytes else { return nil }
    return max(limit - quota.usedBytes, 0)
}

/// "Google Drive · name@gmail.com · 12.4 GB free": the connected card's line (`freeFormat` is the catalog's "%1$@ free").
public func connectedSummary(_ connection: StorageConnection, status: StorageStatusResponse? = nil, yourOwnStorage: String, freeFormat: (String) -> String) -> String {
    let label = connection.displayLabel?.trimmingCharacters(in: .whitespaces) ?? ""
    let base = label.isEmpty ? providerName(connection.provider, status: status, yourOwnStorage: yourOwnStorage) : label
    let free = freeSpace(connection.quota).map { freeFormat(formatBytes($0)) }
    return [base, free].compactMap { $0 }.joined(separator: " · ")
}

/// Where a save goes, for the words on every save sheet: the connected provider. Saves need one (CAB-38).
public func saveDestination(_ status: StorageStatusResponse?, yourOwnStorage: String) -> String {
    guard let active = status?.connection, active.status == "active" else { return yourOwnStorage }
    return providerName(active.provider, status: status, yourOwnStorage: yourOwnStorage)
}

/// The line under the destination on a save sheet. Own storage has no fixed size unless the provider reports one.
public func destinationSpaceLine(_ status: StorageStatusResponse?, cablegramAvailable: Int64) -> LocalizedText {
    guard let active = status?.connection, active.status == "active" else { return LocalizedText("ui_available_9ffcbc", formatBytes(cablegramAvailable)) }
    if let free = freeSpace(active.quota) { return LocalizedText("ui_free_97e1da", formatBytes(free)) }
    return LocalizedText("ui_in_your_own_storage_e2575f")
}

/// What `cablegram://storage?provider=…&result=…` carried.
public struct StorageReturn: Sendable, Equatable {
    public var provider: String
    public var result: String
    public init(provider: String, result: String) {
        self.provider = provider
        self.result = result
    }
}

/// Reads the app link Google's flow ends on. Anything else, or any value that is not a plain code, is refused.
public func parseStorageReturn(_ uri: String) -> StorageReturn? {
    guard let components = URLComponents(string: uri.trimmingCharacters(in: .whitespacesAndNewlines)), components.scheme == "cablegram",
          components.host == "storage", components.path.isEmpty, components.fragment == nil else { return nil }
    var params: [String: String] = [:]
    for item in components.queryItems ?? [] { if let value = item.value { params[item.name] = value } }
    func code(_ v: String?) -> String? { v.flatMap { $0.range(of: #"^[a-z_]{1,40}$"#, options: .regularExpression) != nil ? $0 : nil } }
    guard let provider = code(params["provider"]), let result = code(params["result"]) else { return nil }
    return StorageReturn(provider: provider, result: result)
}

/// The wording Google puts beside the one box that matters on its consent screen. Google lets a person leave any permission
/// unticked, and the box starts unticked for most people, so the app says which one to tick before opening Google and again
/// if it was missed.
public let drivePermissionText = "See, edit, create, and delete only the specific Google Drive files you use with this app"

/// What the app says just before it opens Google's sign-in (two catalog strings, joined).
public func googleSignInPreparation() -> [LocalizedText] {
    [LocalizedText("ui_google_will_show_a_box_beside_tick_it_then_tap_continu_c21e7b", drivePermissionText),
     LocalizedText("ui_it_only_reaches_the_files_cablegram_creates_never_the__2a9187")]
}

/// True when the sign-in ended because the permission box was left unticked, so the screen offers to try again.
public func signInNeedsAnotherTry(_ ret: StorageReturn) -> Bool { ret.result == "scope_missing" }

/// What to tell the owner when the sign-in ends. Never repeats text that came from the link or the provider.
public func storageReturnMessage(_ ret: StorageReturn, status: StorageStatusResponse? = nil, yourOwnStorage: String) -> LocalizedText {
    let name = providerName(ret.provider, status: status, yourOwnStorage: yourOwnStorage)
    switch ret.result {
    case "connected": return LocalizedText("ui_connected_to_6c372e", name)
    case "denied": return LocalizedText("ui_not_connected_you_didn_t_allow_cablegram_to_use_fb658f", name)
    case "scope_missing": return LocalizedText("ui_access_wasn_t_granted_on_google_s_screen_tick_the_box__5ed88c", name, drivePermissionText)
    case "expired": return LocalizedText("ui_the_sign_in_expired_start_again_683df5")
    case "already_connected": return LocalizedText("ui_your_storage_is_already_connected_17b55e")
    default: return LocalizedText("ui_couldn_t_connect_try_again_in_a_moment_ed1a38", name)
    }
}

/// What to tell the owner when the app could not even start the sign-in.
public func googleStartMessage(_ error: String) -> LocalizedText {
    switch error {
    case "provider_not_configured": LocalizedText("ui_google_drive_isn_t_set_up_on_this_server_7692c7")
    case "storage_already_connected": LocalizedText("ui_your_storage_is_already_connected_17b55e")
    case "offline": LocalizedText("ui_couldn_t_reach_cablegram_check_your_connection_3c4a29")
    default: LocalizedText("ui_couldn_t_start_the_google_sign_in_1730f7")
    }
}

/// Words for a disconnect, naming where the files stay.
public func disconnectMessage(_ provider: String?, status: StorageStatusResponse? = nil, yourOwnStorage: String) -> LocalizedText {
    switch provider {
    case providerGoogleDrive: LocalizedText("ui_google_drive_disconnected_your_files_stay_in_your_driv_d0a3b7")
    case providerCloudflareR2: LocalizedText("ui_storage_disconnected_your_files_stay_in_the_bucket_del_cc7dcf")
    default: LocalizedText("ui_disconnected_your_files_stay_where_they_are_fb9447", providerName(provider, status: status, yourOwnStorage: yourOwnStorage).capitalizedFirst)
    }
}

extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}

/// What to tell the owner when downloading a cloud copy back fails. Never repeats provider text (English only, like Android).
public func cloudDownloadMessage(_ code: Int) -> String {
    switch code {
    case 401: "Your cloud storage needs you to sign in again. Open Storage and sign in."
    case 403, 429: "Your cloud storage is limiting downloads of this video. Try again in a little while."
    case 404: "This video is no longer in your cloud storage."
    default: "Your cloud storage didn't send the video (\(code))."
    }
}

/// What the confirmation says before a cloud copy is deleted.
public func removeCopyQuestion(title: String, destination: String) -> String {
    "This deletes the copy of \"\(title)\" from \(destination). The video stays on this phone, and you can save it again later."
}

/// What to tell the owner when removing a cloud copy ends. Never repeats provider text.
public func removeCopyMessage(_ result: RemoveCopyResult, destination: String) -> String {
    switch result.error {
    case nil, "not_found": "Removed the copy from \(destination). The video stays on this phone."
    case "reauthorization_required": "\(destination) needs you to sign in again before it can remove files. Open Storage and sign in."
    case "storage_unavailable": "\(destination) isn't answering. Try again later."
    case "provider_rate_limited": "\(destination) is limiting requests right now. Try again in a little while."
    case "offline": "Couldn't reach Cablegram. Check your connection."
    default: "Couldn't remove the copy from \(destination)."
    }
}

/// What to tell the owner about a failed R2 connect. Never repeats what the server or bucket said.
public func r2ConnectMessage(_ error: String) -> String {
    switch error {
    case "credentials_rejected": "Cloudflare refused these keys. Check the Access Key ID and the Secret, and that the token can edit this bucket."
    case "bucket_not_found": "That bucket wasn't found in this account. Check the account ID and bucket name, and that R2 is turned on."
    case "invalid_request": "One of the fields doesn't look right. The account ID is 32 characters; the bucket is lowercase letters, digits and dashes."
    case "storage_already_connected": "Your storage is already connected."
    case "storage_not_configured": "This server can't keep storage keys yet."
    case "storage_unavailable": "Cloudflare R2 isn't answering. Try again in a moment."
    case "offline": "Couldn't reach Cablegram. Check your connection."
    default: "Couldn't connect (\(error))."
    }
}

// Live checks for the connect form: nil means fine (or nothing typed yet). Mirror the server's own rules.

/// The 32-character account ID, from what the owner typed: the ID itself, or the Endpoint address Cloudflare shows with a new
/// token (`https://<account id>.r2.cloudflarestorage.com`, with or without a bucket after it). Nil if neither.
public func r2AccountIdFrom(_ input: String) -> String? {
    let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
    if text.range(of: #"^[0-9a-fA-F]{32}$"#, options: .regularExpression) != nil { return text.lowercased() }
    guard let regex = try? NSRegularExpression(pattern: #"^(?:https?://)?([0-9a-fA-F]{32})\.r2\.cloudflarestorage\.com(?:[/?#].*)?$"#, options: .caseInsensitive),
          let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)), let range = Range(match.range(at: 1), in: text) else { return nil }
    return text[range].lowercased()
}

private func fails(_ pattern: String, _ value: String) -> Bool { value.trimmingCharacters(in: .whitespaces).range(of: pattern, options: .regularExpression) == nil }

public func r2AccountIdProblem(_ value: String) -> String? {
    value.trimmingCharacters(in: .whitespaces).isEmpty || r2AccountIdFrom(value) != nil ? nil
        : "That isn't an account ID. Paste the Endpoint address Cloudflare showed with your keys."
}

public func r2BucketProblem(_ value: String) -> String? {
    value.trimmingCharacters(in: .whitespaces).isEmpty || !fails(#"^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$"#, value) ? nil
        : "Use 3 to 63 lowercase letters, digits or dashes, as when you made the bucket."
}

public func r2KeyIdProblem(_ value: String) -> String? {
    value.trimmingCharacters(in: .whitespaces).isEmpty || !fails(#"^[A-Za-z0-9]{16,128}$"#, value) ? nil
        : "That doesn't look like an Access Key ID. Copy it again from the token page."
}

public func r2SecretProblem(_ value: String) -> String? {
    value.trimmingCharacters(in: .whitespaces).isEmpty || !fails(#"^[A-Za-z0-9/+=_-]{32,128}$"#, value) ? nil
        : "That doesn't look like a Secret Access Key. It's the long value under the Access Key ID."
}
