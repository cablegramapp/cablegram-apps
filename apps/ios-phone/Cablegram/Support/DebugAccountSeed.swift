#if DEBUG
import CablegramCore
import Foundation

/// Debug builds only: UI tests and Maestro flows start signed in by passing a throwaway test account's tokens as
/// launch arguments (`-seedAccessToken … -seedRefreshToken … -seedName …`), so no email inbox is needed. The
/// arguments are ignored when absent, and none of this is compiled into release builds.
enum DebugAccountSeed {
    static func apply(to pairing: PairingStore, store: LibraryStore) {
        let defaults = UserDefaults.standard
        if defaults.bool(forKey: "seedResetAccount") { pairing.clearAccount() }
        if defaults.bool(forKey: "seedTourSeen") { pairing.welcomeTourSeen = true }
        if defaults.object(forKey: "seedTourSeen") != nil, !defaults.bool(forKey: "seedTourSeen") { pairing.welcomeTourSeen = false }
        if defaults.bool(forKey: "seedLibrary") { seedLibrary(store) }
        guard let access = defaults.string(forKey: "seedAccessToken"), !access.isEmpty else { return }
        pairing.accountToken = access
        pairing.refreshToken = defaults.string(forKey: "seedRefreshToken")
        pairing.displayName = defaults.string(forKey: "seedName") ?? "Test"
    }

    /// A small sample library (two films, one in progress, and a three-episode series) for screenshots and flows.
    private static func seedLibrary(_ store: LibraryStore) {
        guard store.list().isEmpty else { return }
        func item(_ id: String, _ title: String, type: String = "movie", tmdb: Int? = nil, season: Int? = nil, episode: Int? = nil,
                  position: Int = 0, minutes: Int = 100, added: String) -> LibraryItem {
            LibraryItem(id: id, title: title, filename: "\(title).mkv", durationSeconds: minutes * 60, fileSizeBytes: Int64(minutes) * 8_000_000,
                        genres: ["Drama"], year: 2021, mediaType: type, importedAt: added, positionSeconds: position,
                        lastWatchedAt: position > 0 ? added : nil, tmdbId: tmdb, seasonNumber: season, episodeNumber: episode)
        }
        [item("f1", "Dune", position: 2400, added: "2026-10-01T10:00:00Z"), item("f2", "Arrival", added: "2026-10-02T10:00:00Z"),
         item("e1", "The Bear", type: "tv", tmdb: 1, season: 1, episode: 1, minutes: 30, added: "2026-10-03T10:00:00Z"),
         item("e2", "The Bear", type: "tv", tmdb: 1, season: 1, episode: 2, minutes: 30, added: "2026-10-03T10:01:00Z"),
         item("e3", "The Bear", type: "tv", tmdb: 1, season: 2, episode: 1, minutes: 30, added: "2026-10-03T10:02:00Z")].forEach(store.add)
    }
}
#endif
