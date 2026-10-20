import CablegramCore
import SwiftUI

/// Port of `PhoneApp`: sign-in, delete account, new Terms, naming, the welcome tour, then the library shell.
struct PhoneRootView: View {
    let viewModel: PhoneViewModel

    var body: some View {
        Group {
            if viewModel.needsAccount {
                SignInScreen(viewModel: viewModel)
            } else if viewModel.deletingAccount {
                DeleteAccountScreen(viewModel: viewModel)
            } else if viewModel.termsRequired {
                TermsScreen(viewModel: viewModel)
            } else if viewModel.needsName {
                NameForm(viewModel: viewModel)
            } else if viewModel.welcomeTourOpen {
                WelcomeTour(viewModel: viewModel)
            } else {
                LibraryShell(viewModel: viewModel)
            }
        }
        .fullScreenCover(isPresented: Binding(get: { viewModel.artworkEditor.draft != nil }, set: { if !$0 { viewModel.artworkEditor.close() } })) {
            ArtworkEditorScreen(controller: viewModel.artworkEditor, viewModel: viewModel)
        }
        .preferredColorScheme(.dark)
    }
}

/// Tab shell, port of `LibraryShell`. The tab contents arrive in the following phases.
struct LibraryShell: View {
    @Bindable var viewModel: PhoneViewModel

    var body: some View {
        // Storage is a screen under Settings: the bar keeps highlighting Settings while it is open.
        let selection = Binding<PhoneTab>(get: { viewModel.tab.barItem }, set: { viewModel.selectTab($0) })
        TabView(selection: selection) {
            ForEach(PhoneTab.barItems(legacyRemote: viewModel.legacyRemote), id: \.self) { tab in
                content(for: tab)
                    .tabItem {
                        Label { Text(key: tab.titleKey) } icon: { Image(systemName: tab.symbol) }
                            .accessibilityIdentifier(tab.maestroId)
                    }
                    .tag(tab)
            }
        }
    }

    @ViewBuilder private func content(for tab: PhoneTab) -> some View {
        ZStack {
            switch tab {
            case .library: LibraryHome(viewModel: viewModel)
            case .settings:
                if viewModel.tab == .storage { PlaceholderTabView(tab: .storage) } else { SettingsScreen(viewModel: viewModel) }
            case .remote where !viewModel.legacyRemote: LibraryHome(viewModel: viewModel)
            default: PlaceholderTabView(tab: tab)
            }
            // A series page and a title page are full-screen overlays on any tab (Android `LibraryShell`).
            if let series = viewModel.shownSeries { SeriesOverlay(viewModel: viewModel, series: series).transition(.move(edge: .trailing)) }
            if let selected = viewModel.selected { DetailOverlay(viewModel: viewModel, item: selected).id(selected.id).transition(.move(edge: .trailing)) }
        }
        .modifier(DeleteConfirmation(viewModel: viewModel))
        .animation(.default, value: viewModel.selectedId)
        .animation(.default, value: viewModel.openSeriesKey)
    }
}

private struct PlaceholderTabView: View {
    let tab: PhoneTab

    var body: some View {
        Text(key: tab.titleKey)
            .font(PhoneFont.headlineMedium)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .phoneScreenBackground()
    }
}

extension PhoneTab {
    var titleKey: String {
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
