import Foundation
import Observation

// Port of `CablegramChannels.kt`.

/// A fixed allowlist: this feature cannot join arbitrary chats or read their messages.
public enum CablegramChannel: CaseIterable, Sendable, Hashable {
    case news, community, botHubs

    public var title: String {
        switch self { case .news: "Cablegram News"; case .community: "Cablegram Community"; case .botHubs: "Cablegram Bot Hubs" }
    }
    public var username: String {
        switch self { case .news: "CablegramNews"; case .community: "CablegramCommunity"; case .botHubs: "CablegramBotHubs" }
    }
    public var url: String { "https://t.me/\(username)" }
}

public struct CommunityChannelsState: Sendable, Equatable {
    public var selected: Set<CablegramChannel> = Set(CablegramChannel.allCases)
    public var joined: Set<CablegramChannel> = []
    public var failed: Set<CablegramChannel> = []
    public var saving = false
    public var completed = false
    public init() {}
}

/// No Telegram calls until Save. Retries keep successful joins and try every remaining selection.
@MainActor @Observable
public final class CommunityChannels {
    public private(set) var state = CommunityChannelsState()
    @ObservationIgnored private let api: any TelegramApi
    @ObservationIgnored private let onCompleted: @MainActor () -> Void
    @ObservationIgnored private var saveRunning = false
    @ObservationIgnored private let joinTimeout: Double

    public init(api: any TelegramApi, joinTimeout: Double = 20, onCompleted: @escaping @MainActor () -> Void) {
        self.api = api; self.onCompleted = onCompleted; self.joinTimeout = joinTimeout
    }

    public func select(_ channel: CablegramChannel, selected: Bool) {
        guard !state.saving, !state.completed, !state.joined.contains(channel) else { return }
        if selected { state.selected.insert(channel) } else { state.selected.remove(channel) }
        state.failed.remove(channel)
    }

    public func save() async {
        guard !saveRunning else { return }
        saveRunning = true
        defer { state.saving = false; saveRunning = false }
        if state.completed { return }
        state.saving = true
        state.failed = []
        let api = self.api
        for channel in CablegramChannel.allCases where state.selected.contains(channel) && !state.joined.contains(channel) {
            do {
                try await withTimeout(seconds: joinTimeout) { try await api.joinCommunityChannel(channel) }
                state.joined.insert(channel)
            } catch is CancellationError {
                return
            } catch {
                state.failed.insert(channel)
            }
        }
        if state.failed.isEmpty {
            onCompleted()
            state.completed = true
        }
    }
}
