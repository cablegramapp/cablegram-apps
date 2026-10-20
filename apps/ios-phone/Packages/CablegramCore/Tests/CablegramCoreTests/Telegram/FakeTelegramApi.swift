import Foundation
@testable import CablegramCore

// Port of `FakeTelegramApi.kt` plus the small helpers the Telegram tests share (the Kotlin tests used virtual time instead).

/// A value two tasks can touch.
final class TgLocked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Value
    init(_ value: Value) { stored = value }
    var value: Value {
        get { lock.withLock { stored } }
        set { lock.withLock { stored = newValue } }
    }
    func update<T>(_ body: (inout Value) -> T) -> T { lock.withLock { body(&stored) } }
}

/// Holds callers at `wait()` until `open()` (a Kotlin `CompletableDeferred<Unit>`).
actor TgGate {
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func open() {
        isOpen = true
        let all = waiters
        waiters = []
        for waiter in all { waiter.resume() }
    }
}

/// Polls `condition` (every 10 ms, up to `timeout` seconds) on the caller's actor; true once it holds.
func eventually(timeout: Double = 2, isolation: isolated (any Actor)? = #isolation, _ condition: () -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return condition()
}

/// A short pause for "nothing else happens" assertions.
func settle(_ milliseconds: Int = 150) async { try? await Task.sleep(for: .milliseconds(milliseconds)) }

/// In-memory Telegram: files "download" only when the test calls `deliver`.
final class FakeTelegramApi: TelegramApi, @unchecked Sendable {
    private struct State {
        var calls: [String] = []
        var files: [Int: TgFile] = [:]
        var content: [Int: Data] = [:]
        var chats: [Int64: String] = [:]
        var photos: [Int64: String] = [:]
        var left: Set<Int64> = []
        var user = TgUser(id: 42, displayName: "Test User")
        var failNext: TgError?
        var failReadsRemaining = 0
        var createdChannelId: Int64 = -1001
        var onConfirm: (@Sendable () -> Void)?
        var recommendedBots: [RecommendedLibraryBot] = []
        var videoPages: [Int64: TgVideoPage] = [:]
        var videoPageRequests = 0
        var uploadLimit: Int64 = 2000 * 1024 * 1024
        var uploadResult: TgVideo?
        var sessions = [TgSessionInfo(id: 1, isCurrent: true, apiId: 100, applicationName: "Cablegram", deviceModel: "Pixel", logInDate: 1)]
        var failSessions = false
        var lastAuth: TgAuthState?
        var joinHook: (@Sendable (CablegramChannel) async throws -> Void)?
        var addBotHook: (@Sendable (Int64, Int64) async throws -> Void)?
    }

    private let lock = NSLock()
    private var state = State()
    private let authBroadcaster = Broadcaster<TgAuthState>()
    private let fileBroadcaster = Broadcaster<TgFile>()
    private let newBroadcaster = Broadcaster<TgMessage>()
    private let deletedBroadcaster = Broadcaster<TgDeletedMessages>()
    private let editedBroadcaster = Broadcaster<TgMessage>()

    private func read<T>(_ keyPath: KeyPath<State, T>) -> T { lock.withLock { state[keyPath: keyPath] } }
    private func write<T>(_ keyPath: WritableKeyPath<State, T>, _ value: T) { lock.withLock { state[keyPath: keyPath] = value } }
    private func mutate<T>(_ body: (inout State) throws -> T) rethrows -> T { try lock.withLock { try body(&state) } }

    var calls: [String] { get { read(\.calls) } set { write(\.calls, newValue) } }
    var files: [Int: TgFile] { get { read(\.files) } set { write(\.files, newValue) } }
    var content: [Int: Data] { get { read(\.content) } set { write(\.content, newValue) } }
    var chats: [Int64: String] { get { read(\.chats) } set { write(\.chats, newValue) } }
    var photos: [Int64: String] { get { read(\.photos) } set { write(\.photos, newValue) } }
    var left: Set<Int64> { get { read(\.left) } set { write(\.left, newValue) } }
    var user: TgUser { get { read(\.user) } set { write(\.user, newValue) } }
    var failNext: TgError? { get { read(\.failNext) } set { write(\.failNext, newValue) } }
    var failReadsRemaining: Int { get { read(\.failReadsRemaining) } set { write(\.failReadsRemaining, newValue) } }
    var createdChannelId: Int64 { get { read(\.createdChannelId) } set { write(\.createdChannelId, newValue) } }
    /// What confirming a login link adds to `sessions` (the device the link came from).
    var onConfirm: (@Sendable () -> Void)? { get { read(\.onConfirm) } set { write(\.onConfirm, newValue) } }
    var recommendedBots: [RecommendedLibraryBot] { get { read(\.recommendedBots) } set { write(\.recommendedBots, newValue) } }
    /// Pages of the channel keyed by the `fromMessageId` that asks for them.
    var videoPages: [Int64: TgVideoPage] { get { read(\.videoPages) } set { write(\.videoPages, newValue) } }
    var videoPageRequests: Int { read(\.videoPageRequests) }
    var uploadLimit: Int64 { get { read(\.uploadLimit) } set { write(\.uploadLimit, newValue) } }
    /// What the next `uploadVideo` returns; nil makes it fail like a Telegram error.
    var uploadResult: TgVideo? { get { read(\.uploadResult) } set { write(\.uploadResult, newValue) } }
    var sessions: [TgSessionInfo] { get { read(\.sessions) } set { write(\.sessions, newValue) } }
    var failSessions: Bool { get { read(\.failSessions) } set { write(\.failSessions, newValue) } }
    /// Replaces `joinCommunityChannel` (nothing is recorded), for a join that is slow or never ends.
    var joinHook: (@Sendable (CablegramChannel) async throws -> Void)? { get { read(\.joinHook) } set { write(\.joinHook, newValue) } }
    /// Replaces `addRecommendedBotToLibrary` (nothing is recorded).
    var addBotHook: (@Sendable (Int64, Int64) async throws -> Void)? { get { read(\.addBotHook) } set { write(\.addBotHook, newValue) } }

    // MARK: updates

    /// Replays the last state to a new subscriber (Kotlin `replay = 1`).
    var authStates: AsyncStream<TgAuthState> {
        let last = read(\.lastAuth)
        let live = authBroadcaster.stream()
        return AsyncStream { continuation in
            if let last { continuation.yield(last) }
            let forward = Task { for await auth in live { continuation.yield(auth) }; continuation.finish() }
            continuation.onTermination = { _ in forward.cancel() }
        }
    }
    var fileUpdates: AsyncStream<TgFile> { fileBroadcaster.stream() }
    var newMessages: AsyncStream<TgMessage> { newBroadcaster.stream() }
    var deletedMessages: AsyncStream<TgDeletedMessages> { deletedBroadcaster.stream() }
    var editedMessages: AsyncStream<TgMessage> { editedBroadcaster.stream() }

    func emitAuth(_ auth: TgAuthState) { write(\.lastAuth, auth); authBroadcaster.send(auth) }
    func emitNew(_ message: TgMessage) { newBroadcaster.send(message) }
    func emitDeleted(_ gone: TgDeletedMessages) { deletedBroadcaster.send(gone) }
    func emitEdited(_ message: TgMessage) { editedBroadcaster.send(message) }

    // MARK: calls

    private func record(_ call: String) throws {
        let failure = mutate { state -> TgError? in
            state.calls.append(call)
            defer { state.failNext = nil }
            return state.failNext
        }
        if let failure { throw failure }
    }

    func start(_ parameters: TgParameters) async throws { try record("start") }
    func requestQrLogin() async throws { try record("requestQrLogin") }
    func setPhoneNumber(_ phone: String) async throws { try record("setPhoneNumber:\(phone)") }
    func checkCode(_ code: String) async throws { try record("checkCode:\(code)") }
    func checkPassword(_ password: String) async throws { try record("checkPassword") }
    func confirmQrLogin(_ link: String) async throws {
        try record("confirmQrLogin")
        onConfirm?()
    }
    func logOut() async throws { try record("logOut") }
    func close() async { try? record("close") }

    func joinCommunityChannel(_ channel: CablegramChannel) async throws {
        if let hook = joinHook { try await hook(channel); return }
        try record("joinCommunityChannel:\(channel.username)")
    }
    func recommendedLibraryBots() async throws -> [RecommendedLibraryBot] {
        try record("recommendedLibraryBots")
        return recommendedBots
    }
    func addRecommendedBotToLibrary(libraryChatId: Int64, botUserId: Int64) async throws {
        if let hook = addBotHook { try await hook(libraryChatId, botUserId); return }
        try record("addRecommendedBot:\(libraryChatId):\(botUserId)")
    }
    func organizeCablegramFolder(libraryChatId: Int64, reviewedBotUserIds: [Int64]) async throws -> TgFolderResult {
        try record("organizeCablegramFolder:\(libraryChatId)")
        return TgFolderResult()
    }
    func me() async throws -> TgUser { user }

    func chat(_ chatId: Int64) async -> TgChat? {
        mutate { state in state.chats[chatId].map { TgChat(id: chatId, title: $0, hasPhoto: state.photos[chatId] != nil, isMember: !state.left.contains(chatId)) } }
    }
    func findChatByTitle(_ title: String) async throws -> Int64? {
        mutate { state in state.chats.sorted { $0.key < $1.key }.first { $0.value == title && !state.left.contains($0.key) }?.key }
    }
    func createChannel(title: String, description: String) async throws -> Int64 {
        try record("createChannel:\(title)")
        return mutate { state in state.chats[state.createdChannelId] = title; return state.createdChannelId }
    }
    func setChannelPhoto(chatId: Int64, imagePath: String) async throws {
        try record("setChannelPhoto:\(chatId)")
        mutate { $0.photos[chatId] = imagePath }
    }
    func videos(chatId: Int64, fromMessageId: Int64, limit: Int) async throws -> TgVideoPage {
        mutate { state in state.videoPageRequests += 1; return state.videoPages[fromMessageId] ?? TgVideoPage(videos: [], nextFromMessageId: 0) }
    }
    func message(chatId: Int64, messageId: Int64) async -> TgMessage? { nil }
    func uploadLimitBytes() async throws -> Int64 { uploadLimit }
    func uploadVideo(chatId: Int64, path: String, caption: String?, onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> TgVideo {
        try record("uploadVideo:\(chatId):\(path)")
        guard let video = uploadResult else { throw TgError(code: 400, name: "FILE_PARTS_INVALID") }
        onProgress(video.file.size / 2, video.file.size)
        onProgress(video.file.size, video.file.size)
        return video
    }
    func deleteMessages(chatId: Int64, messageIds: [Int64]) async throws {
        try record("deleteMessages:\(chatId):\(messageIds.map(String.init).joined(separator: ", "))")
    }

    func file(_ fileId: Int) async throws -> TgFile { try mutate { state in try Self.stored(state, fileId) } }
    func download(fileId: Int, offset: Int64, limit: Int64) async throws {
        try record("download:\(fileId)@\(offset)+\(limit)")
        // Starting a download somewhere else moves TDLib's contiguous window there.
        try mutate { state in
            var f = try Self.stored(state, fileId)
            if offset != f.downloadOffset { f.downloadOffset = offset; f.downloadedPrefixSize = 0; state.files[fileId] = f }
        }
    }
    func readPart(fileId: Int, offset: Int64, count: Int64) async throws -> Data {
        let failing = mutate { state -> Bool in
            if state.failReadsRemaining > 0 { state.failReadsRemaining -= 1; return true }
            return false
        }
        if failing { throw TgError(code: 400, name: "Failed to read the file") }
        let bytes = try mutate { state -> Data in
            guard let data = state.content[fileId] else { throw TgError(code: 404, name: "NO_CONTENT") }
            return data
        }
        return bytes.subdata(in: Int(offset)..<Int(offset + count))
    }
    func cancelDownload(_ fileId: Int) async throws { try record("cancelDownload:\(fileId)") }
    func deleteLocalFile(_ fileId: Int) async throws {
        try record("deleteLocalFile:\(fileId)")
        try mutate { state in
            var f = try Self.stored(state, fileId)
            f.downloadedPrefixSize = 0; f.downloadedSize = 0
            state.files[fileId] = f
        }
    }
    func passwordState() async throws -> TgPasswordState { TgPasswordState(hasPassword: false, hint: "") }
    func activeSessions() async throws -> [TgSessionInfo] {
        if failSessions { throw TgError(code: 500, name: "INTERNAL") }
        return sessions
    }
    func terminateSession(_ sessionId: Int64) async throws { try record("terminateSession:\(sessionId)") }

    private static func stored(_ state: State, _ id: Int) throws -> TgFile {
        guard let f = state.files[id] else { throw TgError(code: 404, name: "NO_SUCH_FILE") }
        return f
    }

    // MARK: test helpers

    @discardableResult
    func addFile(_ id: Int, size: Int) -> TgFile {
        let file = TgFile(id: id, size: Int64(size), uniqueId: "unique-\(id)")
        mutate { state in
            state.content[id] = Data((0..<size).map { UInt8($0 % 251) })
            state.files[id] = file
        }
        return file
    }

    /// TDLib has now downloaded `bytes` contiguous bytes from the current window start.
    func deliver(_ id: Int, bytes: Int64, stored: Int64? = nil) {
        let updated = mutate { state -> TgFile in
            var f = state.files[id]!
            f.downloadedPrefixSize = bytes
            f.downloadedSize = stored ?? bytes
            state.files[id] = f
            return f
        }
        fileBroadcaster.send(updated)
    }

    /// Edits a stored file in place.
    func updateFile(_ id: Int, _ change: (inout TgFile) -> Void) { mutate { change(&$0.files[id]!) } }
}
