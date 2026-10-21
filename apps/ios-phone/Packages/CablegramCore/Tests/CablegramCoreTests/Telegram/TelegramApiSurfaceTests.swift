import Foundation
import Testing

// Port of `TelegramApiSurfaceTest.kt`: pins the Telegram surface to library management, device sign-in, joining the fixed Cablegram
// channels after an explicit Save, and bot recommendations confirmed individually. No reading other chats, listing contacts, or
// messaging people. Adding a call needs review against the README and privacy claims.

@Suite struct TelegramApiSurfaceTests {
    static let allowed: Set<String> = [
        // Sign-in and sign-out.
        "start", "requestQrLogin", "setPhoneNumber", "checkCode", "checkPassword", "confirmQrLogin", "logOut", "close", "me",
        // The library channel only: find or create it, set its logo, list its videos, delete its messages.
        "chat", "findChatByTitle", "createChannel", "setChannelPhoto", "videos", "message", "deleteMessages",
        // Save to Telegram (T011): the only call that sends anything.
        "uploadVideo", "uploadLimitBytes",
        // Optional fixed Cablegram channels: only joined after the owner taps Save.
        "joinCommunityChannel",
        // Read only the fixed Bot Hubs recommendation feed; add only verified discovered bots.
        "recommendedLibraryBots", "addRecommendedBotToLibrary",
        // Merge Cablegram-related chats into the named folder.
        "organizeCablegramFolder",
        // Files of those videos.
        "file", "download", "readPart", "cancelDownload", "deleteLocalFile",
        // Ending Cablegram's own TV sessions (spec 004 US8).
        "passwordState", "activeSessions", "terminateSession",
        // Updates (Kotlin getters `getAuthStates`, ... are properties here).
        "authStates", "fileUpdates", "newMessages", "deletedMessages", "editedMessages",
    ]

    /// Names of the `func`s and `var`s declared in `public protocol TelegramApi`.
    static func declaredMembers() throws -> Set<String> {
        // .../Tests/CablegramCoreTests/Telegram/<this file> -> package root.
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { root.deleteLastPathComponent() }
        let source = try String(contentsOf: root.appendingPathComponent("Sources/CablegramCore/Telegram/TelegramApi.swift"), encoding: .utf8)
        var inside = false
        var names = Set<String>()
        let member = try NSRegularExpression(pattern: #"^\s*(?:func|var)\s+(\w+)\s*[(:<]"#)
        for line in source.components(separatedBy: "\n") {
            if !inside { inside = line.contains("public protocol TelegramApi"); continue }
            if line.hasPrefix("}") { break }
            if let m = member.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)), let r = Range(m.range(at: 1), in: line) { names.insert(String(line[r])) }
        }
        return names
    }

    @Test func theTelegramLayerExposesOnlyWhatTheLibraryNeeds() throws {
        let actual = try Self.declaredMembers()
        #expect(!actual.isEmpty)
        #expect(actual == Self.allowed, "extra: \(actual.subtracting(Self.allowed).sorted()), missing: \(Self.allowed.subtracting(actual).sorted())")
    }
}
