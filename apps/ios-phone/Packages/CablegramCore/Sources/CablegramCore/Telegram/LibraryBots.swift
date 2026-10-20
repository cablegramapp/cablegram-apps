import Foundation
import Observation

// Port of `LibraryBots.kt`.

public let botHubDescription = "Discover useful Telegram bots that make managing, organizing, and enjoying your video library easier."
public let botIndependence = "These are independent third-party bots and are not developed, owned, or operated by Cablegram."
public let botDisclaimer = "This recommendation is shared for informational purposes only. Using the bot is entirely optional and at the user's own discretion and responsibility.\n\nCablegram is not responsible for the bot's operation, content, privacy practices, or any consequences arising from its use. Please review its permissions before adding it to your channel."
public let botPermissions = "The bot will be added to your Cablegram library channel as an administrator with permission to post messages. Telegram also gives administrators access to channel activity, statistics, and member information. The bot can receive new library posts, including videos and captions. Other optional permissions are off. If it is already an administrator, its existing permissions are kept. Some bots need additional setup in Telegram."

public struct RecommendedLibraryBot: Sendable, Equatable {
    public var id: Int64
    public var username: String
    public var displayName: String
    public init(id: Int64, username: String, displayName: String) { self.id = id; self.username = username; self.displayName = displayName }
    public var url: String { "https://t.me/\(username)" }
}

/// Extract candidates only; Telegram must verify every candidate is a bot before showing it.
public enum BotHubLinks {
    private static let username = try! NSRegularExpression(pattern: "^[A-Za-z][A-Za-z0-9_]{4,31}$")
    private static let tokens = try! NSRegularExpression(
        pattern: #"https?://[^\s<>]+|(?<![A-Za-z0-9_./])(?:t\.me|telegram\.me)/[^\s<>]+|tg://[^\s<>]+|(?<![A-Za-z0-9_./])@[A-Za-z][A-Za-z0-9_]{4,31}"#,
        options: .caseInsensitive)

    public static func usernames(_ text: String, embeddedUrls: [String] = []) -> Set<String> {
        var result = Set<String>()
        let found = tokens.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap { Range($0.range, in: text).map { String(text[$0]) } }
        for raw in found + embeddedUrls {
            let token = raw.trimmingCharacters(in: CharacterSet(charactersIn: ".,;!)]}\"'"))
            var candidate: String
            if token.hasPrefix("@") { candidate = String(token.dropFirst()) } else {
                let lower = token.lowercased()
                let text = lower.hasPrefix("t.me/") || lower.hasPrefix("telegram.me/") ? "https://\(token)" : token
                guard let components = URLComponents(string: text), let scheme = components.scheme?.lowercased() else { continue }
                if ["http", "https"].contains(scheme), ["t.me", "telegram.me", "www.t.me", "www.telegram.me"].contains(components.host?.lowercased() ?? ""),
                   components.user == nil, components.port == nil {
                    let parts = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).split(separator: "/", omittingEmptySubsequences: false)
                    guard parts.count == 1 else { continue }
                    candidate = String(parts[0])
                } else if scheme == "tg", components.host?.lowercased() == "resolve" {
                    guard let domain = components.percentEncodedQuery?.split(separator: "&").first(where: { $0.hasPrefix("domain=") }) else { continue }
                    candidate = String(domain.dropFirst("domain=".count))
                } else { continue }
            }
            if username.firstMatch(in: candidate, range: NSRange(candidate.startIndex..., in: candidate)) != nil { result.insert(candidate.lowercased()) }
        }
        return result
    }
}

public struct LibraryBotsState: Sendable, Equatable {
    public var bots: [RecommendedLibraryBot] = []
    public var index = 0
    public var loaded = false
    public var busy = false
    public var disclaimerAccepted = false
    public var error: String?
    public var completed = false
    public var added = 0
    public init() {}
    public var current: RecommendedLibraryBot? { bots.indices.contains(index) ? bots[index] : nil }
}

/// A separate opt-in per bot. Neither discovery nor skipping grants access to the library.
@MainActor @Observable
public final class LibraryBotRecommendations {
    public private(set) var state = LibraryBotsState()
    @ObservationIgnored private let api: any TelegramApi
    @ObservationIgnored private let libraryChatId: Int64
    @ObservationIgnored private let handledBotIds: Set<Int64>
    @ObservationIgnored private let onDecision: @MainActor (Int64) -> Void
    @ObservationIgnored private let onCompleted: @MainActor () -> Void
    @ObservationIgnored private var operationRunning = false
    @ObservationIgnored private let loadTimeout: Double
    @ObservationIgnored private let addTimeout: Double

    public init(api: any TelegramApi, libraryChatId: Int64, handledBotIds: Set<Int64> = [], loadTimeout: Double = 60, addTimeout: Double = 30,
                onDecision: @escaping @MainActor (Int64) -> Void, onCompleted: @escaping @MainActor () -> Void) {
        self.api = api; self.libraryChatId = libraryChatId; self.handledBotIds = handledBotIds; self.loadTimeout = loadTimeout; self.addTimeout = addTimeout
        self.onDecision = onDecision; self.onCompleted = onCompleted
    }

    public func load() async {
        guard !operationRunning else { return }
        operationRunning = true
        defer { state.busy = false; operationRunning = false }
        if state.loaded || state.completed { return }
        state.busy = true
        state.error = nil
        let api = self.api
        do {
            let bots = try await withTimeout(seconds: loadTimeout) { try await api.recommendedLibraryBots() }
            var seen = Set<Int64>()
            state.bots = bots.filter { seen.insert($0.id).inserted && !handledBotIds.contains($0.id) }
            state.loaded = true
        } catch is TimeoutError {
            state.error = "Loading bot recommendations took too long. Try again or skip bots."
        } catch is CancellationError {
            return
        } catch {
            state.error = "Couldn’t load recommendations from @CablegramBotHubs. Try again or skip bots."
        }
    }

    public func acceptDisclaimer(_ accepted: Bool) {
        guard !state.busy, !state.completed, state.current != nil else { return }
        state.disclaimerAccepted = accepted
    }

    public func addCurrent() async {
        guard !operationRunning else { return }
        operationRunning = true
        defer { state.busy = false; operationRunning = false }
        guard let bot = state.current, state.disclaimerAccepted, !state.completed else { return }
        state.busy = true
        state.error = nil
        let api = self.api, chat = libraryChatId
        do {
            try await withTimeout(seconds: addTimeout) { try await api.addRecommendedBotToLibrary(libraryChatId: chat, botUserId: bot.id) }
            state.added += 1
            advance(bot.id)
        } catch is TimeoutError {
            state.error = "Adding the bot took too long. Retry or skip this bot. A retry checks whether it is already an administrator."
        } catch is CancellationError {
            return
        } catch {
            state.error = "Couldn’t add this bot. Check your connection and that you can add administrators to the library channel, then retry or skip."
        }
    }

    public func skipCurrent() {
        guard !state.busy, !state.completed, let bot = state.current else { return }
        advance(bot.id)
    }

    public func finish() {
        guard !state.busy, !state.completed else { return }
        onCompleted()
        state.completed = true
        state.disclaimerAccepted = false
    }

    private func advance(_ botId: Int64) {
        onDecision(botId)
        let next = state.index + 1
        if next >= state.bots.count { onCompleted() }
        state.index = next
        state.disclaimerAccepted = false
        state.error = nil
        state.completed = next >= state.bots.count
    }
}
