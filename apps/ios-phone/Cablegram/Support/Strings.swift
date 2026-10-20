import Foundation
import SwiftUI

/// Strings from the generated catalog (Android key names). Use `Text(LocalizedStringKey(key))` for plain text and
/// `L(key, args...)` when the string has `%1$@` / `%1$d` placeholders or is needed as a `String`.
func L(_ key: String, _ args: CVarArg...) -> String {
    let format = NSLocalizedString(key, bundle: .main, value: key, comment: "")
    return args.isEmpty ? format : String(format: format, locale: Locale.current, arguments: args)
}

extension Text {
    init(key: String) { self.init(LocalizedStringKey(key)) }
}
