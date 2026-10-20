import CablegramCore
import Foundation
import Observation

/// What the phone does about subtitles that belongs to no one screen: the person's own provider accounts, the Find Subtitles
/// flow's visibility, the saved subtitle of the open title, and the video bytes the flow reads (Android `PhoneViewModel`'s subtitle part).
@MainActor @Observable
final class SubtitleController {
    /// Find Subtitles is showing for the open title.
    var flowOpen = false
    /// Settings' "Subtitle accounts" page; hoisted so the welcome tour can open it.
    var accountsOpen = false
    private(set) var savedSubtitle: String?
    /// Changes whenever the provider accounts change, so open screens start over with the new accounts.
    private(set) var credentialRevision = 0

    @ObservationIgnored private let pairing: PairingStore
    @ObservationIgnored private let catalog: @Sendable () -> CatalogClient
    @ObservationIgnored private let store: LibraryStore
    @ObservationIgnored private let media: AppMediaProvider
    @ObservationIgnored private let selectedId: () -> String?
    @ObservationIgnored private let setStatus: (String?) -> Void
    @ObservationIgnored private let credentials: SubtitleCredentialStore
    @ObservationIgnored private var sessions: [ObjectIdentifier: LocalSubtitleDiscovery] = [:]
    @ObservationIgnored private lazy var telegramMedia = PhoneTelegramMedia(
        removed: RemovedTelegramSources(client: catalog, token: { [pairing] in pairing.accountToken }),
        chatId: { [catalog, pairing] in guard let token = pairing.accountToken else { return nil }; return await catalog().telegramLink(token: token)?.chatId },
        // Analysis reads short sections far apart: a small download window keeps what Telegram fetches close to what the analysis budget counts.
        window: 4 * TelegramFileReader.mb, refillAt: 1 * TelegramFileReader.mb)

    init(pairing: PairingStore, catalog: @escaping @Sendable () -> CatalogClient, store: LibraryStore, media: AppMediaProvider,
         selectedId: @escaping () -> String?, setStatus: @escaping (String?) -> Void, secrets: any SecretStoring = KeychainSecretStore()) {
        self.pairing = pairing; self.catalog = catalog; self.store = store; self.media = media; self.selectedId = selectedId; self.setStatus = setStatus
        credentials = SubtitleCredentialStore(secrets: secrets)
    }

    var accountToken: String? { pairing.accountToken.flatMap { $0.isEmpty ? nil : $0 } }

    /// The signed-in account (the access token's `sub`), which keys the stored provider details.
    private func user() -> String? {
        guard let token = accountToken else { return nil }
        let parts = token.split(separator: ".")
        guard parts.count > 1 else { return nil }
        var base64 = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64 += "=" }
        guard let data = Data(base64Encoded: base64), let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let sub = object["sub"] as? String, !sub.isEmpty else { return nil }
        return sub
    }

    // MARK: Provider accounts

    func providerConfigured(_ provider: String) -> Bool { user().map { credentials.read(user: $0, provider: provider) != nil } ?? false }

    private func cancelSessions() {
        let open = Array(sessions.values)
        sessions = [:]
        open.forEach { $0.close() }
        credentialRevision += 1
    }

    func saveProvider(_ provider: String, _ details: PersonalSubtitleCredentials) throws {
        guard let user = user() else { throw LocalSubtitleError("sign_in_required") }
        cancelSessions()
        try credentials.save(user: user, provider: provider, credentials: details)
        credentialRevision += 1
    }

    func removeProvider(_ provider: String) {
        guard let user = user() else { return }
        cancelSessions()
        credentials.remove(user: user, provider: provider)
        credentialRevision += 1
    }

    /// A search session holding the person's configured accounts; it forgets them when closed.
    func newSession() -> LocalSubtitleDiscovery {
        guard let user = user() else { return LocalSubtitleDiscovery(credentials: [:]) }
        var configured: [String: PersonalSubtitleCredentials] = [:]
        for provider in ["subdl", "opensubtitles"] { if let details = credentials.read(user: user, provider: provider) { configured[provider] = details } }
        var created: LocalSubtitleDiscovery!
        created = LocalSubtitleDiscovery(credentials: configured)
        sessions[ObjectIdentifier(created)] = created
        return created
    }

    func release(_ session: LocalSubtitleDiscovery) {
        sessions[ObjectIdentifier(session)] = nil
        session.close()
    }

    /// Sign-out and account deletion: nothing of the person's subtitle accounts stays on the phone.
    func signedOut() {
        flowOpen = false
        accountsOpen = false
        savedSubtitle = nil
        if let user = user() { credentials.clear(user: user) }
        cancelSessions()
    }

    /// The open title was closed.
    func titleClosed() { flowOpen = false; savedSubtitle = nil }

    // MARK: Saved subtitle of the open title

    func request(_ path: String, token: String, method: String = "GET", body: String? = nil) async throws -> String {
        try await catalog().subtitleRequest(path: path, token: token, method: method, body: body)
    }

    /// Reads what the TV will use for this title, so the detail screen can say which subtitle is saved.
    func refreshStatus(_ item: LibraryItem) {
        guard let token = accountToken else { return }
        Task {
            let label = try? await request("playback/item/\(item.id)", token: token)
                .data(using: .utf8).flatMap { try? JSONDecoder().decode(SavedSubtitleList.self, from: $0) }?.subtitles.first
                .map { languageName($0.language) }
            // A late answer for a title that was closed meanwhile must not label the next one.
            if selectedId() == item.id { savedSubtitle = label }
        }
    }

    func removeSaved(_ item: LibraryItem) {
        guard let token = accountToken else { return }
        Task {
            do {
                let list = try JSONDecoder().decode(SavedSubtitleList.self, from: Data(try await request("playback/item/\(item.id)", token: token).utf8))
                for track in list.subtitles where !track.trackId.isEmpty { _ = try await request(track.trackId, token: token, method: "DELETE") }
                refreshStatus(item)
            } catch { setStatus(L("ui_subtitle_couldn_t_be_removed_try_again_e1a4f7")) }
        }
    }

    // MARK: The title's bytes

    /// The title's bytes for analysis and preview: the local file, or the Telegram channel copy.
    func openVideo(_ item: LibraryItem) async throws -> any SubtitleMediaInput {
        if store.hasLocalBytes(item) { return FileMediaInput(url: store.videoFile(item)) }
        if let opened = await media.openVideo(item) {
            return FileMediaInput(url: opened.url, release: opened.release)
        }
        guard item.sourceKind == "telegram" else { throw VideoUnavailable(L("ui_this_video_isn_t_stored_on_this_phone_so_it_can_t_be_c_2ce4b8")) }
        guard let key = item.telegramFileKey.map({ $0.hasPrefix("tgfile:") ? String($0.dropFirst("tgfile:".count)) : $0 }) else {
            throw VideoUnavailable(L("ui_pull_to_refresh_your_library_once_so_this_telegram_vid_c06d0f"))
        }
        guard let file = await telegramMedia.resolve(uniqueId: key) else { throw VideoUnavailable(L("ui_telegram_didn_t_provide_this_video_check_that_telegram_4bfa84")) }
        return TelegramMediaInput(media: telegramMedia, file: file)
    }
}
