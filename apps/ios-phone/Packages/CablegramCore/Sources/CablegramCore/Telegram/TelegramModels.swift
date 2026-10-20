import Foundation

// Server-side Telegram models (`MeModels.kt`).

/// The household's Telegram link (`GET /api/telegram/link`). Ids come as strings or numbers.
public struct TelegramLinkInfo: Sendable, Equatable {
    public var linked: Bool
    public var userId: Int64?
    public var displayName: String?
    public var chatId: Int64?
    public var linkedAt: String?
    public init(linked: Bool = false, userId: Int64? = nil, displayName: String? = nil, chatId: Int64? = nil, linkedAt: String? = nil) {
        self.linked = linked; self.userId = userId; self.displayName = displayName; self.chatId = chatId; self.linkedAt = linkedAt
    }

    init(_ object: [String: Any]) {
        func int64(_ any: Any?) -> Int64? { (any as? NSNumber)?.int64Value ?? (any as? String).flatMap(Int64.init) }
        self.init(linked: object["linked"] as? Bool ?? false, userId: int64(object["telegram_user_id"]), displayName: object["display_name"] as? String,
                  chatId: int64(object["library_chat_id"]), linkedAt: object["linked_at"] as? String)
    }
}

/// Outcome of linking: `conflictName` is set when the household already uses another Telegram account.
public struct TelegramLinkResult: Sendable, Equatable {
    public var link: TelegramLinkInfo?
    public var conflictName: String?
    public init(link: TelegramLinkInfo?, conflictName: String? = nil) { self.link = link; self.conflictName = conflictName }
}

/// A TV Telegram session the phone must end (`GET /api/telegram/tv-sessions?due=true`).
public struct DueTvSession: Sendable, Equatable {
    public var tvDeviceId: String
    public var telegramSessionId: String
    public var reason: String
}

/// A title removed from the library but remembered by its Telegram file. `hidden` can be restored.
public struct TelegramTombstone: Sendable, Equatable {
    public var stableSourceKey: String
    public var itemId: String?
    public var title: String
    public var state: String
    public var lastError: String?
}

public struct TelegramDeletionOrder: Sendable, Equatable {
    public var stableSourceKey: String
    public var chatId: Int64
    public var messageIds: [Int64]
    public init(stableSourceKey: String, chatId: Int64, messageIds: [Int64]) { self.stableSourceKey = stableSourceKey; self.chatId = chatId; self.messageIds = messageIds }
}

/// `GET /api/telegram/tv-password-requests/pending`: a TV waiting for the Telegram two-step password.
public struct PendingTvPasswordRequest: Sendable, Equatable {
    public var requestId: String
    /// The TV that asked; its sign-in is approved without asking again once the password went to it.
    public var tvDeviceId: String?
    public var tvName: String
    /// The TV's one-time public key; the password is sealed to it.
    public var tvPublicKey: String
    /// Telegram's own password hint.
    public var hint: String
}

/// `GET /api/telegram/tv-logins/pending`.
public struct PendingTvLogin: Sendable, Equatable {
    public var requestId: String
    public var tvDeviceId: String
    public var tvName: String
    public var loginLink: String
    public var expiresInMs: Int64
}
