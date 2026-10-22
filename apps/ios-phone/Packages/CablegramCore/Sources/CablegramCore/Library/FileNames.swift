import Foundation

/// What follows the last "." of `name`, or `fallback` when there is none (Kotlin `substringAfterLast('.', fallback)`). Names made
/// only of dots ("." or "...") give "", never a crash.
public func fileExtension(of name: String, fallback: String = "") -> String {
    guard let dot = name.lastIndex(of: ".") else { return fallback }
    return String(name[name.index(after: dot)...])
}
