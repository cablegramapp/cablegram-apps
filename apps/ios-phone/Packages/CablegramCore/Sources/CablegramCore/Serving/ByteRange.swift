import Foundation

/// A request's `Range` header against a body of `length` bytes. Port of `LanLibraryServer.parseRange`: single ranges only
/// (`a-b`, `a-`, suffix `-n`); anything else that is not satisfiable is answered 416, never the whole file, because players
/// probe the end of a file with `bytes=<size>-` and give up seeking when they get a 200 back.
public enum ByteRange: Sendable, Equatable {
    case full
    case partial(start: Int64, end: Int64)
    case unsatisfiable

    public var length: Int64 {
        if case let .partial(start, end) = self { return end - start + 1 }
        return 0
    }

    public static func parse(_ header: String?, length: Int64) -> ByteRange {
        guard let header, !header.trimmingCharacters(in: .whitespaces).isEmpty else { return .full }
        guard length > 0 else { return .full }
        guard header.hasPrefix("bytes=") else { return .unsatisfiable }
        let spec = String(header.dropFirst(6)).split(separator: ",", omittingEmptySubsequences: false).first.map(String.init)?.trimmingCharacters(in: .whitespaces) ?? ""
        guard let dash = spec.firstIndex(of: "-") else { return .unsatisfiable }
        let first = spec[..<dash].trimmingCharacters(in: .whitespaces)
        let second = spec[spec.index(after: dash)...].trimmingCharacters(in: .whitespaces)
        if first.isEmpty && second.isEmpty { return .unsatisfiable }
        if first.isEmpty {
            guard let n = Int64(second), n > 0 else { return .unsatisfiable }
            return .partial(start: max(length - n, 0), end: length - 1)
        }
        guard let start = Int64(first), start >= 0, start < length else { return .unsatisfiable }
        let end: Int64
        if second.isEmpty { end = length - 1 } else {
            guard let parsed = Int64(second), parsed >= start else { return .unsatisfiable }
            end = min(parsed, length - 1)
        }
        return .partial(start: start, end: end)
    }

    public func contentRange(of length: Int64) -> String? {
        if case let .partial(start, end) = self { return "bytes \(start)-\(end)/\(length)" }
        return nil
    }
}
