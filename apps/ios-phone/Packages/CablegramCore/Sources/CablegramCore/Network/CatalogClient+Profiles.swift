import Foundation

/// Profiles, the phone's language and the relay allowance (the matching part of `CatalogClient.kt`).
extension CatalogClient {
    /// Household profiles (owner first); nil when offline.
    public func profiles(token: String) async -> [HouseholdProfile]? {
        let reply = await send("GET", "/api/profiles", token: token)
        guard reply.isSuccess, let list = reply.object?["profiles"] as? [Any], let data = try? JSONSerialization.data(withJSONObject: list) else { return nil }
        return try? JSONDecoder().decode([HouseholdProfile].self, from: data)
    }

    /// Sets the language a profile uses on TVs; nil follows the phone.
    public func setProfileLanguage(token: String, profileId: String, languageTag: String?) async -> Bool {
        await send("PATCH", "/api/profiles/\(profileId)/language", token: token, json: ["ui_language": languageTag ?? NSNull()]).isSuccess
    }

    /// Deletes a household profile with its progress and My List. The HTTP status (nil offline).
    public func deleteProfile(token: String, profileId: String) async -> Int? {
        await send("DELETE", "/api/profiles/\(profileId)", token: token).status
    }

    /// Reports this phone's language without changing other accounts on a shared TV.
    public func syncDeviceLanguage(token: String, languageTag: String) async -> Bool {
        await send("PUT", "/api/me/language", token: token, json: ["ui_language": languageTag]).isSuccess
    }

    /// This month's relay allowance; nil when offline.
    public func relayUsage(token: String, phoneDeviceId: String?) async -> RelayUsage? {
        let query = phoneDeviceId.flatMap { $0.isEmpty ? nil : "?device_id=\($0.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? $0)" } ?? ""
        let reply = await send("GET", "/api/relay/usage" + query, token: token)
        return reply.isSuccess ? reply.decode() : nil
    }
}
