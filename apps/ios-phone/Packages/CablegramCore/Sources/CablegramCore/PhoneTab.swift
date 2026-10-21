/// Same order as Android `PhoneTab`. Remote appears only with the legacy-remote preference. Storage is a screen
/// under Settings, not a bar item (Android `LibraryNav` highlights Settings while Storage is open).
public enum PhoneTab: String, CaseIterable, Sendable {
    case library, browse, remote, storage, settings

    public static func barItems(legacyRemote: Bool) -> [PhoneTab] {
        [.library, .browse, .remote, .settings].filter { $0 != .remote || legacyRemote }
    }

    /// The bar item that shows as selected for this tab.
    public var barItem: PhoneTab { self == .storage ? .settings : self }
}
