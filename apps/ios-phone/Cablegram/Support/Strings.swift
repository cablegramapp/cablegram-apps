import Foundation
import SwiftUI

/// Strings from the generated catalog (Android key names). `L(key, args...)` resolves a key and fills `%1$@` / `%1$d`
/// placeholders. Android's `%s` takes any value, so a number given for a `%@` slot is turned into text here instead of
/// crashing `String(format:)`.
func L(_ key: String, _ args: CVarArg...) -> String {
    var format = NSLocalizedString(key, bundle: .main, value: key, comment: "")
    // Strings that exist only on iPhone (Photos, Files) live in their own table; the rest come from the Android catalog.
    if format == key { format = NSLocalizedString(key, tableName: "Strings-ios", bundle: .main, value: key, comment: "") }
    return fillPlaceholders(format, args)
}

func fillPlaceholders(_ format: String, _ args: [CVarArg]) -> String {
    guard !args.isEmpty else { return format }
    let slots = placeholderKinds(format)
    let converted: [CVarArg] = args.enumerated().map { index, arg in
        let kind = index < slots.count ? slots[index] : nil
        if kind == "@", !(arg is String || arg is NSString) { return String(describing: arg) }
        return arg
    }
    return String(format: format, locale: Locale.current, arguments: converted)
}

/// The conversion character (`@`, `d`…) of each positional placeholder, in argument order.
private func placeholderKinds(_ format: String) -> [Character?] {
    guard let regex = try? NSRegularExpression(pattern: #"%(\d+)\$([@dflu])"#) else { return [] }
    let range = NSRange(format.startIndex..., in: format)
    var kinds: [Int: Character] = [:]
    for match in regex.matches(in: format, range: range) {
        if let n = Range(match.range(at: 1), in: format).flatMap({ Int(format[$0]) }), let k = Range(match.range(at: 2), in: format) {
            kinds[n] = format[k].first
        }
    }
    let count = kinds.keys.max() ?? 0
    return (1...max(count, 1)).map { kinds[$0] }
}

extension Text {
    init(key: String) { self.init(verbatim: L(key)) }
}
