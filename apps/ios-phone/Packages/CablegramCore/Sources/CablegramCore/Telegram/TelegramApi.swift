import Foundation

// Port of `app/cablegram/telegram/TelegramApi.kt` (shared by the Android phone and TV apps). The method set must stay equal to the
// Android allowlist: `TelegramApiSurfaceTests` and `scripts/quality/check-telegram-sync.mjs` fail when it drifts.

/// The part of Telegram (TDLib) Cablegram uses, behind a protocol so the session logic and the stream reader can be tested without
/// the native library. `TdlibTelegramApi` is the real one.
public protocol TelegramApi: Sendable {
    var authStates: AsyncStream<TgAuthState> { get }
    var fileUpdates: AsyncStream<TgFile> { get }
    var newMessages: AsyncStream<TgMessage> { get }
    var deletedMessages: AsyncStream<TgDeletedMessages> { get }
    /// A message's text or caption was edited (a bot that posts a placeholder first, say); `date` is 0.
    var editedMessages: AsyncStream<TgMessage> { get }

    func start(_ parameters: TgParameters) async throws
    func requestQrLogin() async throws
    func setPhoneNumber(_ phone: String) async throws
    func checkCode(_ code: String) async throws
    func checkPassword(_ password: String) async throws
    func confirmQrLogin(_ link: String) async throws
    func logOut() async throws
    /// Closes the client and waits until its database is released, without signing out: another client may then open it.
    func close() async

    /// Join one of the fixed Cablegram community channels, only after the owner saves their selection.
    func joinCommunityChannel(_ channel: CablegramChannel) async throws

    /// Read recommendations from the fixed public @CablegramBotHubs channel; never joins it.
    func recommendedLibraryBots() async throws -> [RecommendedLibraryBot]
    /// Only discovered, Telegram-verified bots, after separate permission/disclaimer confirmation.
    func addRecommendedBotToLibrary(libraryChatId: Int64, botUserId: Int64) async throws

    /// Organize only the library, joined Cablegram channels, support bot, and confirmed library bots.
    func organizeCablegramFolder(libraryChatId: Int64, reviewedBotUserIds: [Int64]) async throws -> TgFolderResult

    func me() async throws -> TgUser
    func chat(_ chatId: Int64) async -> TgChat?
    func findChatByTitle(_ title: String) async throws -> Int64?
    func createChannel(title: String, description: String) async throws -> Int64
    /// Sets the library channel's photo from a local image file (the Cablegram logo).
    func setChannelPhoto(chatId: Int64, imagePath: String) async throws
    func videos(chatId: Int64, fromMessageId: Int64, limit: Int) async throws -> TgVideoPage
    func message(chatId: Int64, messageId: Int64) async -> TgMessage?
    func deleteMessages(chatId: Int64, messageIds: [Int64]) async throws
    /// "Save to Telegram": sends one video the owner chose into the library channel, marked streamable, and returns it once Telegram
    /// has stored it. `onProgress` gets (uploaded, total) bytes. This is the only call that sends anything, and only into the
    /// household's library channel.
    func uploadVideo(chatId: Int64, path: String, caption: String?, onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> TgVideo
    /// The largest file the account may upload: 2 GB, or 4 GB with Telegram Premium.
    func uploadLimitBytes() async throws -> Int64

    func file(_ fileId: Int) async throws -> TgFile
    func download(fileId: Int, offset: Int64, limit: Int64) async throws
    func readPart(fileId: Int, offset: Int64, count: Int64) async throws -> Data
    func cancelDownload(_ fileId: Int) async throws
    func deleteLocalFile(_ fileId: Int) async throws

    /// Whether the account has two-step verification, and its hint; never the password itself.
    func passwordState() async throws -> TgPasswordState

    func activeSessions() async throws -> [TgSessionInfo]
    func terminateSession(_ sessionId: Int64) async throws
}

/// A failed Telegram request: `code` is TDLib's error code, `name` its message such as `PASSWORD_HASH_INVALID`.
public struct TgError: Error, Equatable, Sendable, CustomStringConvertible {
    public var code: Int
    public var name: String
    public init(code: Int, name: String) { self.code = code; self.name = name }
    public var description: String { "\(code) \(name)" }

    /// Telegram asks to wait before retrying (`FLOOD_WAIT_x` or "Too Many Requests: retry after x").
    public var retryAfterSeconds: Int? {
        guard let regex = try? NSRegularExpression(pattern: #"(?:FLOOD_WAIT_|retry after )(\d+)"#),
              let match = regex.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)),
              let range = Range(match.range(at: 1), in: name) else { return nil }
        return Int(name[range])
    }
}

public struct TgParameters: Sendable {
    public var databaseDirectory: String
    public var filesDirectory: String
    public var databaseKey: Data
    public var apiId: Int
    public var apiHash: String
    public var deviceModel: String
    public var systemVersion: String
    public var applicationVersion: String
    /// Telegram's test servers (debug builds only, for end-to-end checks without a real account).
    public var useTestDc: Bool
    public init(databaseDirectory: String, filesDirectory: String, databaseKey: Data, apiId: Int, apiHash: String, deviceModel: String, systemVersion: String, applicationVersion: String, useTestDc: Bool = false) {
        self.useTestDc = useTestDc
        self.databaseDirectory = databaseDirectory; self.filesDirectory = filesDirectory; self.databaseKey = databaseKey; self.apiId = apiId
        self.apiHash = apiHash; self.deviceModel = deviceModel; self.systemVersion = systemVersion; self.applicationVersion = applicationVersion
    }
}

public enum TgAuthState: Sendable, Equatable {
    case waitParameters
    case waitPhoneNumber
    case waitCode
    case waitPassword(hint: String)
    case waitOtherDevice(link: String)
    case ready
    case loggingOut
    case closed
    /// A state Cablegram does not support, e.g. registration of a new Telegram account.
    case unsupported(name: String)
}

public struct TgUser: Sendable, Equatable {
    public var id: Int64
    public var displayName: String
    public init(id: Int64, displayName: String) { self.id = id; self.displayName = displayName }
}

public struct TgPasswordState: Sendable, Equatable {
    public var hasPassword: Bool
    public var hint: String
    public init(hasPassword: Bool, hint: String) { self.hasPassword = hasPassword; self.hint = hint }
}

/// `isMember` is false after the owner left the channel (Telegram keeps a creator who left as a non-member).
public struct TgChat: Sendable, Equatable {
    public var id: Int64
    public var title: String
    public var hasPhoto: Bool
    public var isMember: Bool
    public init(id: Int64, title: String, hasPhoto: Bool, isMember: Bool = true) { self.id = id; self.title = title; self.hasPhoto = hasPhoto; self.isMember = isMember }
}

/// The household's library channel, and whether Cablegram just created it.
public struct TgLibrary: Sendable, Equatable {
    public var chatId: Int64
    public var created: Bool
    public init(chatId: Int64, created: Bool) { self.chatId = chatId; self.created = created }
}

public struct TgFile: Sendable, Equatable {
    public var id: Int
    public var size: Int64
    /// Telegram's id for the file's content, stable across forwards and accounts.
    public var uniqueId: String
    public var downloadOffset: Int64
    public var downloadedPrefixSize: Int64
    public var downloadedSize: Int64
    public var completed: Bool
    public init(id: Int, size: Int64, uniqueId: String, downloadOffset: Int64 = 0, downloadedPrefixSize: Int64 = 0, downloadedSize: Int64 = 0, completed: Bool = false) {
        self.id = id; self.size = size; self.uniqueId = uniqueId; self.downloadOffset = downloadOffset
        self.downloadedPrefixSize = downloadedPrefixSize; self.downloadedSize = downloadedSize; self.completed = completed
    }
}

/// A video in the library channel, sent as a video or as a video document.
public struct TgVideo: Sendable, Equatable {
    public var chatId: Int64
    public var messageId: Int64
    public var file: TgFile
    public var fileName: String
    public var caption: String
    public var mimeType: String
    public var durationSeconds: Int
    public var supportsStreaming: Bool
    public var date: Int64
    public init(chatId: Int64, messageId: Int64, file: TgFile, fileName: String = "", caption: String = "", mimeType: String = "", durationSeconds: Int = 0, supportsStreaming: Bool = false, date: Int64 = 0) {
        self.chatId = chatId; self.messageId = messageId; self.file = file; self.fileName = fileName; self.caption = caption
        self.mimeType = mimeType; self.durationSeconds = durationSeconds; self.supportsStreaming = supportsStreaming; self.date = date
    }
}

/// `text` is a text message's text or a media message's caption (photo, video, document, animation).
public struct TgMessage: Sendable, Equatable {
    public var chatId: Int64
    public var messageId: Int64
    public var video: TgVideo?
    public var text: String
    public init(chatId: Int64, messageId: Int64, video: TgVideo? = nil, text: String = "") { self.chatId = chatId; self.messageId = messageId; self.video = video; self.text = text }
}

public struct TgDeletedMessages: Sendable, Equatable {
    public var chatId: Int64
    public var messageIds: [Int64]
    public init(chatId: Int64, messageIds: [Int64]) { self.chatId = chatId; self.messageIds = messageIds }
}

/// `notes` are the page's other messages that have text: a TMDB reference to one of the videos may be among them.
public struct TgVideoPage: Sendable, Equatable {
    public var videos: [TgVideo]
    public var nextFromMessageId: Int64
    public var notes: [TgMessage]
    public init(videos: [TgVideo], nextFromMessageId: Int64, notes: [TgMessage] = []) { self.videos = videos; self.nextFromMessageId = nextFromMessageId; self.notes = notes }
}

public struct TgSessionInfo: Sendable, Equatable {
    public var id: Int64
    public var isCurrent: Bool
    public var apiId: Int
    public var applicationName: String
    public var deviceModel: String
    public var logInDate: Int64
    public init(id: Int64, isCurrent: Bool, apiId: Int, applicationName: String = "", deviceModel: String = "", logInDate: Int64 = 0) {
        self.id = id; self.isCurrent = isCurrent; self.apiId = apiId; self.applicationName = applicationName; self.deviceModel = deviceModel; self.logInDate = logInDate
    }
}

/// Unstarted bot dialogs cannot be included in a Telegram folder without user interaction.
public struct TgFolderResult: Sendable, Equatable {
    public var unlistedBots: [String]
    public init(unlistedBots: [String] = []) { self.unlistedBots = unlistedBots }
}

/// In-memory application identity. Deliberately no description, no `Codable`, no persistent cache.
public struct TelegramClientCredentials: Sendable {
    public let apiId: Int
    public let apiHash: String
    public init(apiId: Int, apiHash: String) { self.apiId = apiId; self.apiHash = apiHash }
}

/// Fixed message with no server body, credential value or underlying error attached.
public struct TelegramClientUnavailable: Error, Sendable {
    public static let message = "Couldn't start Telegram. Check your connection and try again."
    public init() {}
    public var message: String { Self.message }
}

/// Fans one source out to any number of listeners (Kotlin `SharedFlow`): each `stream()` gets what is sent after it subscribed.
public final class Broadcaster<Element: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<Element>.Continuation] = [:]

    public init() {}

    public func stream() -> AsyncStream<Element> {
        AsyncStream { continuation in
            let id = UUID()
            lock.withLock { continuations[id] = continuation }
            continuation.onTermination = { [weak self] _ in self?.lock.withLock { _ = self?.continuations.removeValue(forKey: id) } }
        }
    }

    public func send(_ element: Element) {
        let all = lock.withLock { Array(continuations.values) }
        for continuation in all { continuation.yield(element) }
    }

    public func finish() {
        let all = lock.withLock { () -> [AsyncStream<Element>.Continuation] in defer { continuations = [:] }; return Array(continuations.values) }
        for continuation in all { continuation.finish() }
    }
}
