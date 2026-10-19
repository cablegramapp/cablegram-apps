import CablegramCore
import SwiftUI

/// Tab shell, port of `LibraryShell`. The tab contents arrive in the following phases.
struct PhoneRootView: View {
    @State private var selection: PhoneTab = .library
    // Becomes the legacy-remote preference in phase 2.
    private let legacyRemote = false

    var body: some View {
        TabView(selection: $selection) {
            ForEach(PhoneTab.barItems(legacyRemote: legacyRemote), id: \.self) { tab in
                PlaceholderTabView(tab: tab)
                    .tabItem { Label(tab.titleKey, systemImage: tab.symbol) }
                    .tag(tab)
                    .accessibilityIdentifier(tab.maestroId)
            }
        }
    }
}

private struct PlaceholderTabView: View {
    let tab: PhoneTab

    var body: some View {
        Text(tab.titleKey)
            .font(PhoneFont.headlineMedium)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .phoneScreenBackground()
    }
}

extension PhoneTab {
    var titleKey: LocalizedStringKey {
        switch self {
        case .library: "ui_library_dc20b3"
        case .browse: "ui_import_2cff9b"
        case .remote: "ui_remote_ffa98e"
        case .storage: "ui_storage_a69c4d"
        case .settings: "ui_settings_74a883"
        }
    }

    var symbol: String {
        switch self {
        case .library: "books.vertical"
        case .browse: "plus"
        case .remote: "tv"
        case .storage: "externaldrive"
        case .settings: "gearshape"
        }
    }

    var maestroId: String {
        switch self {
        case .library: MaestroIds.navLibrary
        case .browse: MaestroIds.navBrowse
        case .remote: MaestroIds.navRemote
        case .storage: MaestroIds.navStorage
        case .settings: MaestroIds.navSettings
        }
    }
}
