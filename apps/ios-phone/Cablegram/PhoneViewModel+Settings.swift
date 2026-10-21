import CablegramCore
import Foundation
import UIKit

/// Settings pages of Android's `PhoneViewModel`: profiles and their language, the relay allowance, the phone's language, the connection.
extension PhoneViewModel {
    func loadProfiles() {
        guard let token = pairing.accountToken, !token.isEmpty else { return }
        Task { if let list = await catalog().profiles(token: token) { profiles = list } }
    }

    func loadRelayUsage() {
        guard let token = pairing.accountToken, !token.isEmpty else { return }
        Task { if let usage = await catalog().relayUsage(token: token, phoneDeviceId: pairing.phoneDeviceId) { relayUsage = usage } }
    }

    /// The tag reported to the server, from the system's language list (Settings → Cablegram → Language picks the app's own).
    var deviceLanguageTag: String { phoneLanguageTag(preferred: Locale.preferredLanguages) }

    /// Tells the control plane which language this phone uses; retried on the next foreground if it failed.
    func syncDeviceLanguage() {
        guard let token = pairing.accountToken, !token.isEmpty else { return }
        let tag = deviceLanguageTag
        let target = "\(token)|\(tag)"
        guard target != UserDefaults.standard.string(forKey: "synced_device_language") else { return }
        Task {
            if await catalog().syncDeviceLanguage(token: token, languageTag: tag) { UserDefaults.standard.set(target, forKey: "synced_device_language") }
        }
    }

    func updateProfileLanguage(_ profile: HouseholdProfile, _ language: String?) {
        guard !busy, language == nil || supportedUiLanguages.contains(language!), let token = pairing.accountToken else { return }
        Task {
            busy = true
            defer { busy = false }
            if await catalog().setProfileLanguage(token: token, profileId: profile.id, languageTag: language) {
                if let list = await catalog().profiles(token: token) { profiles = list }
                status = L("ui_language_updated_ff0392")
            } else {
                status = L("ui_could_not_update_the_language_check_your_connection_an_ce45e9")
            }
        }
    }

    func deleteProfile(_ profile: HouseholdProfile) {
        guard let token = pairing.accountToken else { return }
        Task {
            busy = true
            let code = await catalog().deleteProfile(token: token, profileId: profile.id)
            busy = false
            switch code {
            case 204: status = L("ui_deleted_afb6f5", profile.name)
            case 409: status = L("ui_the_account_owner_s_profile_can_t_be_deleted_2f8068")
            case nil: status = L("ui_couldn_t_reach_cablegram_check_your_connection_and_try_de82d8")
            default: status = L("ui_couldn_t_delete_right_now_e5be9f", profile.name)
            }
            loadProfiles()
        }
    }

    func updateApiBaseUrl(_ value: String) {
        guard isValidServerUrl(value) else { status = L("ui_enter_a_valid_http_or_https_server_address_cbfe30"); return }
        pairing.apiBaseUrl = value.trimmingCharacters(in: .whitespaces)
        apiBaseUrl = pairing.apiBaseUrl
    }

    func updateTunnelUrl(_ value: String) {
        guard value.trimmingCharacters(in: .whitespaces).isEmpty || isValidServerUrl(value) else { status = L("ui_enter_a_valid_tunnel_address_or_leave_it_empty_773859"); return }
        pairing.tunnelUrl = value.trimmingCharacters(in: .whitespaces)
        tunnelUrl = pairing.tunnelUrl
    }

    func clearLocalMetadata() {
        store.clearPosters()
        refresh()
        status = L("ui_cleared_local_posters_and_artwork_cache_1ef435")
    }

    /// The phone's addresses on the local network, for the advanced connection page.
    var phoneIps: [String] { NetworkInfo.localIPv4().map { [$0] } ?? [] }
}
