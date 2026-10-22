import Foundation

/// A video the router can read right now. `release` closes whatever made it readable (a security scope, a temporary copy).
public struct LanMediaFile: Sendable {
    public var url: URL
    public var length: Int64
    public var release: @Sendable () -> Void
    public init(url: URL, length: Int64, release: @escaping @Sendable () -> Void = {}) { self.url = url; self.length = length; self.release = release }
}

/// A Telegram video the phone can stream to a TV that holds no Telegram session itself.
public struct TelegramFileRef: Sendable, Equatable {
    public var fileId: Int
    public var size: Int64
    public var mime: String
    public init(fileId: Int, size: Int64, mime: String) { self.fileId = fileId; self.size = size; self.mime = mime }
}

/// What the media server needs to serve `/telegram/<file unique id>`.
public protocol TelegramMedia: Sendable {
    /// Resolves Telegram's unique file id (the part after `tgfile:`) to a readable file, or nil.
    func resolve(uniqueId: String) async -> TelegramFileRef?
    /// Up to `want` bytes at `position`, or nil if Telegram delivered nothing in time.
    func read(fileId: Int, position: Int64, want: Int) async -> Data?
    /// Nothing more will be read from `fileId` for now: stop its download and drop what is stored.
    func release(fileId: Int)
}

/// The bytes `[start, end]` of a Telegram file, read in `chunk`-sized pieces.
public final class TelegramRangeStream: ByteStream, @unchecked Sendable {
    public static let chunk = 1024 * 1024
    private let read: @Sendable (Int64, Int) async -> Data?
    private let lock = NSLock()
    private var position: Int64
    private let end: Int64
    private var onClose: (@Sendable () -> Void)?

    public init(position: Int64, end: Int64, onClose: (@Sendable () -> Void)? = nil, read: @escaping @Sendable (Int64, Int) async -> Data?) {
        self.position = position; self.end = end; self.read = read; self.onClose = onClose
    }

    public func next(limit: Int) async throws -> Data? {
        let (start, want): (Int64, Int) = lock.withLock { (position, Int(min(Int64(min(limit, Self.chunk)), end - position + 1))) }
        guard start <= end, want > 0, let data = await read(start, want), !data.isEmpty else { return nil }
        lock.withLock { position += Int64(data.count) }
        return data
    }

    public func close() {
        let callback = lock.withLock { () -> (@Sendable () -> Void)? in defer { onClose = nil }; return onClose }
        callback?()
    }
}

/// Finds the bytes of titles that are not copied into the app (a Files bookmark, a Photos video).
public protocol LanMediaProvider: Sendable {
    func openVideo(_ item: LibraryItem) async -> LanMediaFile?
}

/// The routes of the phone's media server, independent of the transport (`contracts/lan-media.md`): the LAN listener (TLS) and
/// the relay tunnel (in-process, `acceptQueryToken`) both call `handle`. Port of `LanLibraryServer.serve`.
///
/// Authenticates callers only with a paired TV's device capability (R-1); the pairing PIN is never a LAN credential. A TV on the
/// LAN sends its capability in the `Authorization` header; a player, which cannot, plays a short-lived link from `/links/...`.
/// `?token=` is accepted only for the relay tunnel, where the relay's ticket authorizes the TV.
public final class LanLibraryRouter: Sendable {
    public static let port: UInt16 = 8765
    public static let linkParam = "link"
    public static let bonjourType = "_cablegram._tcp"
    public static let bonjourName = "CablegramPhone"

    private static let supported: Set<String> = [
        "pause", "play", "stop", "seek", "seek_back", "seek_forward", "next", "previous",
        "move", "select", "volume", "mute", "text_input", "play_video",
    ]
    private static let requiringVideo: Set<String> = ["seek", "seek_back", "seek_forward", "next", "previous", "play_video"]

    private let store: LibraryStore
    private let commands: CommandQueue
    private let credentials: LanCredentials
    private let privatePasses: PrivatePassVerifier?
    private let links: LanStreamLinks
    private let media: (any LanMediaProvider)?
    private let telegram: (any TelegramMedia)?
    private let onTvRequest: @Sendable () -> Void
    private let onStreamStarted: @Sendable () -> Void
    private let onStreamFinished: @Sendable () -> Void

    public init(store: LibraryStore, commands: CommandQueue, credentials: LanCredentials, privatePasses: PrivatePassVerifier? = nil,
                links: LanStreamLinks = LanStreamLinks(), media: (any LanMediaProvider)? = nil, telegram: (any TelegramMedia)? = nil,
                onTvRequest: @escaping @Sendable () -> Void = {}, onStreamStarted: @escaping @Sendable () -> Void = {},
                onStreamFinished: @escaping @Sendable () -> Void = {}) {
        self.store = store; self.commands = commands; self.credentials = credentials; self.privatePasses = privatePasses
        self.links = links; self.media = media; self.telegram = telegram
        self.onTvRequest = onTvRequest; self.onStreamStarted = onStreamStarted; self.onStreamFinished = onStreamFinished
    }

    public func handle(_ request: HTTPRequest, acceptQueryToken: Bool = false) async -> HTTPResponse {
        var path = request.path
        while path.count > 1 && path.hasSuffix("/") { path.removeLast() }
        let isHead = request.method == "HEAD"
        let isRead = request.method == "GET" || isHead

        // A stream link stands in for the capability, only for the one path it was issued for.
        var grant: LanStreamLinks.Grant?
        if let linkId = request.query[Self.linkParam] {
            guard let redeemed = await links.redeem(linkId, path: path, stillAccepted: { [credentials] in await credentials.authorized([$0]) }) else {
                return .error(401, "invalid_stream_link")
            }
            grant = redeemed
        }
        let supplied = Self.suppliedCredentials(request, acceptQueryToken: acceptQueryToken)
        if grant == nil, !(await credentials.authorized(supplied)) { return .error(401, "invalid_lan_token") }
        onTvRequest()

        if grant == nil, request.method == "GET", path.hasPrefix("/links/") { return await issueLink(request, String(path.dropFirst("/links".count))) }
        if request.method == "POST", path == "/commands" { return postCommand(request.body) }

        if isRead, path == "/library" {
            let items = store.list().map { item -> LibraryItem in var copy = item; copy.posterPath = nil; return copy }
            let body = (try? JSONEncoder().encode(LibraryFile(items: items))) ?? Data("{}".utf8)
            return HTTPResponse(status: 200, headers: [("Content-Type", "application/json")], body: isHead ? .empty : .data(body))
        }
        if isRead, path.hasPrefix("/media/") { return await serveMedia(request, id: String(path.dropFirst(7)), grant: grant, supplied: supplied, isHead: isHead) }
        if isRead, path.hasPrefix("/telegram/") { return await serveTelegram(request, uniqueId: String(path.dropFirst(10)), isHead: isHead) }
        if isRead, path.hasPrefix("/poster/") {
            guard let item = store.get(String(path.dropFirst(8))), let poster = store.posterFile(item), let data = try? Data(contentsOf: poster) else { return .error(404, "not_found") }
            return HTTPResponse(status: 200, headers: [("Content-Type", "image/jpeg")], body: isHead ? .empty : .data(data))
        }
        if request.method == "GET", path == "/commands" {
            return .json(String(decoding: (try? JSONEncoder().encode(commands.drain())) ?? Data("[]".utf8), as: UTF8.self))
        }
        return .error(404, "not_found")
    }

    // MARK: Commands

    private func postCommand(_ body: Data) -> HTTPResponse {
        guard let command = try? JSONDecoder().decode(RemoteCommand.self, from: body) else { return .error(400, "invalid_command") }
        guard Self.supported.contains(command.command) else { return .error(400, "unsupported_command") }
        let blank = command.videoId?.trimmingCharacters(in: .whitespaces).isEmpty ?? true
        if (Self.requiringVideo.contains(command.command) && blank) || (command.videoId?.count ?? 0) > 200 || command.command.count > 32 {
            return .error(400, "invalid_command_payload")
        }
        commands.add(command.command, videoId: command.videoId)
        return .json("{\"ok\":true}")
    }

    // MARK: Stream links

    /// A link to `path` for the player, issued only to a TV that sent its capability in the header: one taken from a URL must not
    /// be able to mint more links (CAB-44).
    private func issueLink(_ request: HTTPRequest, _ path: String) async -> HTTPResponse {
        guard let capability = Self.headerCapability(request), await credentials.authorized([capability]) else { return .error(401, "header_required") }
        let streamable: Bool
        if path.hasPrefix("/media/") { streamable = store.get(String(path.dropFirst(7))) != nil }
        else if path.hasPrefix("/telegram/") { streamable = telegram != nil && Self.isTelegramId(String(path.dropFirst(10))) }
        else { streamable = false }
        guard streamable else { return .error(404, "not_found") }
        let id = links.issue(path: path, capability: capability, tvDeviceId: await credentials.tvDeviceId(capability))
        return .json("{\"link\":\"\(id)\",\"expires_in_seconds\":\(links.idleSeconds)}")
    }

    // MARK: Telegram

    private static func isTelegramId(_ id: String) -> Bool { id.range(of: "^[A-Za-z0-9_-]{8,200}$", options: .regularExpression) != nil }

    /// A title in the household's Telegram channel, streamed through this phone's own session for TVs that hold none. Single ranges only;
    /// a malformed range is served as the whole file.
    private func serveTelegram(_ request: HTTPRequest, uniqueId: String, isHead: Bool) async -> HTTPResponse {
        guard let telegram, Self.isTelegramId(uniqueId), let file = await telegram.resolve(uniqueId: uniqueId), file.size > 0 else { return .error(404, "not_found") }
        let range = TelegramByteRange.parse(request.headers["range"], size: file.size)
        let start: Int64, end: Int64, status: Int
        var headers: [(name: String, value: String)] = [("Content-Type", file.mime), ("Accept-Ranges", "bytes")]
        switch range {
        case .unsatisfiable:
            return HTTPResponse(status: 416, headers: [("Content-Type", "application/json"), ("Content-Range", range.contentRange ?? "")], body: .data(Data("{\"error\":\"range_not_satisfiable\"}".utf8)))
        case let .partial(s, e, _): start = s; end = e; status = 206; headers.append(("Content-Range", range.contentRange ?? ""))
        case .full: start = 0; end = file.size - 1; status = 200
        }
        let length = end - start + 1
        if isHead { headers.append(("Content-Length", String(length))); return HTTPResponse(status: status, headers: headers, body: .empty) }
        onStreamStarted()
        let fileId = file.fileId
        let stream = TelegramRangeStream(position: start, end: end, onClose: { telegram.release(fileId: fileId) }) { position, want in await telegram.read(fileId: fileId, position: position, want: want) }
        return HTTPResponse(status: status, headers: headers, body: .stream(length: length, source: ActivityByteStream(stream, onFinish: onStreamFinished)))
    }

    // MARK: Media

    private func serveMedia(_ request: HTTPRequest, id: String, grant: LanStreamLinks.Grant?, supplied: [String], isHead: Bool) async -> HTTPResponse {
        guard let item = store.get(id) else { return .error(404, "not_found") }
        if item.isPrivate {
            var tv = grant?.tvDeviceId
            if tv == nil { for capability in supplied { if let found = await credentials.tvDeviceId(capability) { tv = found; break } } }
            guard await privatePasses?.allows(pass: request.query["pass"], itemId: id, tvDeviceId: tv) == true else { return .error(403, "approval_required") }
        }
        var file: LanMediaFile
        let local = store.videoFile(item)
        if FileManager.default.fileExists(atPath: local.path) {
            let size = ((try? FileManager.default.attributesOfItem(atPath: local.path))?[.size] as? NSNumber)?.int64Value ?? 0
            file = LanMediaFile(url: local, length: size)
        } else if let opened = await media?.openVideo(item) {
            file = opened
        } else {
            return .error(404, "not_found")
        }
        return respond(file: file, rangeHeader: request.headers["range"], mime: Self.contentType(for: item), isHead: isHead)
    }

    private func respond(file: LanMediaFile, rangeHeader: String?, mime: String, isHead: Bool) -> HTTPResponse {
        let length = file.length
        let range = ByteRange.parse(rangeHeader, length: length)
        if range == .unsatisfiable {
            file.release()
            return HTTPResponse(status: 416, headers: [("Content-Type", "application/json"), ("Content-Range", "bytes */\(length)")], body: .data(Data("{\"error\":\"range_not_satisfiable\"}".utf8)))
        }
        let start: Int64, end: Int64
        if case let .partial(s, e) = range { start = s; end = e } else { start = 0; end = max(length - 1, 0) }
        let size = length == 0 ? 0 : end - start + 1
        var headers: [(name: String, value: String)] = [("Content-Type", mime), ("Accept-Ranges", "bytes")]
        if let contentRange = range.contentRange(of: length) { headers.append(("Content-Range", contentRange)) }
        let status = range == .full ? 200 : 206
        if isHead {
            file.release()
            headers.append(("Content-Length", String(size)))
            return HTTPResponse(status: status, headers: headers, body: .empty)
        }
        guard size > 0 else { file.release(); return HTTPResponse(status: status, headers: headers, body: .empty) }
        guard let stream = FileByteStream(url: file.url, offset: start, length: size, onClose: file.release) else { file.release(); return .error(404, "not_found") }
        onStreamStarted()
        return HTTPResponse(status: status, headers: headers, body: .stream(length: size, source: ActivityByteStream(stream, onFinish: onStreamFinished)))
    }

    /// Send the real media type when known; do not assume video/mp4 (R-6).
    static func contentType(for item: LibraryItem) -> String {
        switch fileExtension(of: item.filename).lowercased() {
        case "mkv": "video/x-matroska"
        case "mp4", "m4v": "video/mp4"
        case "mov": "video/quicktime"
        case "avi": "video/x-msvideo"
        case "webm": "video/webm"
        case "ts", "m2ts": "video/mp2t"
        case "wmv": "video/x-ms-wmv"
        case "flv": "video/x-flv"
        case "3gp": "video/3gpp"
        case "ogv": "video/ogg"
        case "mpg", "mpeg": "video/mpeg"
        default: "video/mp4"
        }
    }

    // MARK: Credentials in the request

    static func headerCapability(_ request: HTTPRequest) -> String? {
        guard var value = request.headers["authorization"] else { return nil }
        if value.hasPrefix("Bearer ") { value.removeFirst(7) }
        value = value.trimmingCharacters(in: .whitespaces)
        return value.isEmpty ? nil : value
    }

    /// The header on either path; `?token=` on the relay tunnel's path only.
    static func suppliedCredentials(_ request: HTTPRequest, acceptQueryToken: Bool) -> [String] {
        [headerCapability(request), acceptQueryToken ? request.query["token"] : nil].compactMap { $0 }
    }
}
