#if DEBUG
import CablegramCore
import Foundation

/// Debug builds only: UI tests and Maestro flows start signed in by passing a throwaway test account's tokens as
/// launch arguments (`-seedAccessToken … -seedRefreshToken … -seedName …`), so no email inbox is needed. The
/// arguments are ignored when absent, and none of this is compiled into release builds.
enum DebugAccountSeed {
    static func apply(to pairing: PairingStore) {
        let defaults = UserDefaults.standard
        if defaults.bool(forKey: "seedResetAccount") { pairing.clearAccount() }
        if defaults.bool(forKey: "seedTourSeen") { pairing.welcomeTourSeen = true }
        if defaults.object(forKey: "seedTourSeen") != nil, !defaults.bool(forKey: "seedTourSeen") { pairing.welcomeTourSeen = false }
        guard let access = defaults.string(forKey: "seedAccessToken"), !access.isEmpty else { return }
        pairing.accountToken = access
        pairing.refreshToken = defaults.string(forKey: "seedRefreshToken")
        pairing.displayName = defaults.string(forKey: "seedName") ?? "Test"
    }
}
#endif
