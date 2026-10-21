import Foundation

/// A command the TV's remote app asked this phone to run: play a title, pause, seek, and so on.
public struct RemoteCommand: Codable, Sendable, Equatable {
    public var id: String
    public var command: String
    public var videoId: String?
    public init(id: String, command: String, videoId: String? = nil) { self.id = id; self.command = command; self.videoId = videoId }
}

/// Commands posted to `/commands` wait here until the phone's own player takes them (Android `CommandQueue`).
public final class CommandQueue: @unchecked Sendable {
    private let file: URL
    private let lock = NSLock()

    public init(root: URL) { file = root.appendingPathComponent("commands.json") }

    public func add(_ command: String, videoId: String? = nil) {
        lock.withLock {
            let next = pending() + [RemoteCommand(id: UUID().uuidString.lowercased(), command: command, videoId: videoId)]
            try? JSONEncoder().encode(next).write(to: file, options: .atomic)
        }
    }

    public func drain() -> [RemoteCommand] {
        lock.withLock {
            let items = pending()
            try? FileManager.default.removeItem(at: file)
            return items
        }
    }

    private func pending() -> [RemoteCommand] {
        guard let data = try? Data(contentsOf: file) else { return [] }
        return (try? JSONDecoder().decode([RemoteCommand].self, from: data)) ?? []
    }
}
