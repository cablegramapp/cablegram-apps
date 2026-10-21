import CablegramCore
import Foundation
import Security
import UIKit

/// The phone's one Telegram session (Android `PhoneTelegram`), shared by the Import screen and the work that approves TV logins. TDLib
/// runs one client per process, so it lives here, not in a view model. It starts only when the owner connects Telegram or this phone
/// linked it before, so people who never use Telegram never load it.
@MainActor
enum PhoneTelegram {
    private static var pairing: PairingStore!
    private static var catalog: (@Sendable () -> CatalogClient)!
    private static var current: TelegramSession?
    /// The signed-out client releasing TDLib's database; a new one waits for it (see `session()`).
    private static var closing: Task<Void, Never>?

    static func configure(pairing: PairingStore, catalog: @escaping @Sendable () -> CatalogClient) {
        self.pairing = pairing
        self.catalog = catalog
    }

    /// How this phone appears in Telegram → Settings → Devices.
    static var deviceModel: String {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix(while: { $0 != 0 }), as: UTF8.self) }
        return "Apple \(machine.isEmpty ? UIDevice.current.model : machine)"
    }

    /// The session, creating (and starting) it on first use or after a sign-out closed the old one. A session ended from elsewhere
    /// (Telegram → Devices) is still logging out and holds TDLib's database for a moment; the new one waits for it, or TDLib refuses
    /// with "Can't lock file td.binlog".
    static func session() -> TelegramSession {
        if let old = current {
            if old.state != .signedOut { old.retryStartup(); return old }
            let previous = closing
            closing = Task { await previous?.value; await old.api.close() }
        }
        let created = create()
        current = created
        return created
    }

    /// Identifies the running session, so things built on one client (a file reader, TDLib file ids) are dropped when it is replaced.
    static var sessionIdentity: ObjectIdentifier? { current.map(ObjectIdentifier.init) }

    /// The running session if there is one; never starts TDLib.
    static func existing() -> TelegramSession? { current }

    private static func create() -> TelegramSession {
        let pairing = self.pairing!, catalog = self.catalog!
        let session = TelegramSession(api: TdlibTelegramApi(transport: TDLibJsonTransport()), role: .phone) {
            await PhoneTelegram.waitForClose()
            guard let token = pairing.accountToken, !token.isEmpty else { throw TelegramClientUnavailable() }
            #if DEBUG
            let insecure = true
            let testDc = UserDefaults.standard.bool(forKey: "telegramTestDC")
            #else
            let insecure = false
            let testDc = false
            #endif
            let credentials = try await catalog().telegramClientCredentials(token: token, allowInsecureLocal: insecure)
            let version = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "0"
            // A Keychain that cannot be read right now must not cost the person their Telegram session: nothing is wiped, startup is retried.
            let key = try await MainActor.run { try Storage.databaseKey() }
            return await MainActor.run {
                TgParameters(databaseDirectory: Storage.databaseDirectory.path, filesDirectory: Storage.filesDirectory.path, databaseKey: key,
                             apiId: credentials.apiId, apiHash: credentials.apiHash, deviceModel: deviceModel, systemVersion: "iOS \(UIDevice.current.systemVersion)",
                             applicationVersion: "Cablegram \(version)", useTestDc: testDc)
            }
        }
        session.start()
        return session
    }

    private static func waitForClose() async { await closing?.value }

    // MARK: Preferences (per install; completion belongs to a Telegram account, never to another account on the phone)

    private static var defaults: UserDefaults { .standard }
    static var wasLinked: Bool { defaults.bool(forKey: "telegram_linked") }
    static func setLinked(_ linked: Bool) { defaults.set(linked, forKey: "telegram_linked") }

    static func communityChannelsCompleted(userId: Int64) -> Bool { defaults.bool(forKey: "telegram_community_channels_v1_\(userId)") }
    static func completeCommunityChannels(userId: Int64) { defaults.set(true, forKey: "telegram_community_channels_v1_\(userId)") }

    static func libraryBotsCompleted(userId: Int64, chatId: Int64) -> Bool { defaults.bool(forKey: "telegram_library_bots_v1_\(userId)_\(chatId)") }
    static func completeLibraryBots(userId: Int64, chatId: Int64) { defaults.set(true, forKey: "telegram_library_bots_v1_\(userId)_\(chatId)") }
    static func handledLibraryBots(userId: Int64, chatId: Int64) -> Set<Int64> {
        Set((defaults.stringArray(forKey: "telegram_library_bot_decisions_v1_\(userId)_\(chatId)") ?? []).compactMap(Int64.init))
    }
    static func recordLibraryBotDecision(userId: Int64, chatId: Int64, botId: Int64) {
        let ids = handledLibraryBots(userId: userId, chatId: chatId).union([botId])
        defaults.set(ids.map(String.init), forKey: "telegram_library_bot_decisions_v1_\(userId)_\(chatId)")
    }

    /// "Approve my TVs automatically": off by default, so a card asks first. The login link comes from the server, and approving it
    /// gives that link's holder a session on the user's Telegram account; the user should see and confirm it unless they opted in.
    static var autoApproveTvs: Bool {
        get { defaults.bool(forKey: "telegram_auto_approve_tvs") }
        set { defaults.set(newValue, forKey: "telegram_auto_approve_tvs") }
    }

    /// After log-out: drop the session and delete its database, pieces and key.
    static func forget() {
        current = nil
        setLinked(false)
        Storage.wipe()
    }

    /// The Cablegram logo as a file TDLib can upload (channel photo).
    static func logoPath() -> String? { Bundle.main.path(forResource: "cablegram_library_photo", ofType: "jpg") }

    /// Opens a private channel in the Telegram app; Telegram's private links drop the -100 prefix.
    nonisolated static func channelLink(_ chatId: Int64) -> String { "tg://privatepost?channel=\(String(chatId).replacingOccurrences(of: "-100", with: "", options: .anchored))&post=1" }

    /// A member-only channel link, not an invitation granting access.
    nonisolated static func channelWebLink(_ chatId: Int64) -> String { "https://t.me/c/\(String(chatId).replacingOccurrences(of: "-100", with: "", options: .anchored))/1" }

    /// Where TDLib keeps its database and files, and the key that encrypts the database (Android `TelegramDatabaseKey`): 32 random bytes in
    /// the Keychain. If the key is lost the database cannot be read, so it is wiped and the person links Telegram again.
    enum Storage {
        private static let keyName = "telegram_database_key"
        private static let secrets = KeychainSecretStore(service: "app.cablegram.phone.telegram")

        static var databaseDirectory: URL {
            let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)) ?? FileManager.default.temporaryDirectory
            var url = base.appendingPathComponent("telegram/db", isDirectory: true)
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
            return url
        }

        static var filesDirectory: URL {
            let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("telegram/files", isDirectory: true)
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
            return url
        }

        static func databaseKey() throws -> Data {
            switch secrets.lookup(keyName) {
            case let .found(text): if let key = Data(base64Encoded: text), key.count == 32 { return key }
            case .unavailable: throw TelegramClientUnavailable()
            case .missing: break
            }
            // No key (or one that is not a key): whatever database exists cannot be opened, so start fresh.
            wipe()
            var bytes = [UInt8](repeating: 0, count: 32)
            _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
            secrets.write(keyName, Data(bytes).base64EncodedString())
            return Data(bytes)
        }

        /// Whether a Telegram database (and so possibly a signed-in session) is stored on this device.
        static var hasLocalData: Bool { ((try? FileManager.default.contentsOfDirectory(atPath: databaseDirectory.path))?.isEmpty == false) }

        static func wipe() {
            let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: false))
            if let base { try? FileManager.default.removeItem(at: base.appendingPathComponent("telegram")) }
            try? FileManager.default.removeItem(at: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("telegram"))
            secrets.write(keyName, nil)
        }
    }
}

extension TelegramSession {
    /// Waits (polling the observable state) until `matches` holds, or `seconds` pass (nil). Kotlin `withTimeoutOrNull { state.first {…} }`.
    func first(timeout seconds: Double, where matches: (TelegramState) -> Bool) async -> TelegramState? {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if matches(state) { return state }
            if Task.isCancelled { return nil }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return matches(state) ? state : nil
    }
}
