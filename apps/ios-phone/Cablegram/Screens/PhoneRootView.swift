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
            } else if viewModel.tv.addingTv {
                PairingForm(viewModel: viewModel)
            } else if viewModel.needsName {
                NameForm(viewModel: viewModel)
            } else if viewModel.welcomeTourOpen {
                WelcomeTour(viewModel: viewModel)
            } else {
                LibraryShell(viewModel: viewModel)
            }
        }
        .modifier(PrivacyPrompt(viewModel: viewModel))
        .fullScreenCover(isPresented: Binding(get: { viewModel.telegram.sheetOpen && !viewModel.needsAccount }, set: { if !$0 { viewModel.telegram.closeSheet() } })) {
            TelegramConnectSheet(viewModel: viewModel)
        }
        // A TV needs the two-step password: typed here, sealed to the TV's key (a sheet that opens by itself while Cablegram is open).
        .sheet(item: Binding(get: { viewModel.needsAccount ? nil : viewModel.telegram.work.passwordRequest }, set: { if $0 == nil, let open = viewModel.telegram.work.passwordRequest { viewModel.telegram.work.dismissPasswordRequest(open.requestId) } })) { request in
            TelegramPasswordSheet(request: request, work: viewModel.telegram.work, catalog: viewModel.catalog, token: { viewModel.pairing.accountToken })
        }
        // A TV scanned before signing in waits here until there is an account to pair it to (CAB-62).
        .sheet(isPresented: Binding(get: { viewModel.tv.pendingPair != nil && !viewModel.needsAccount && !viewModel.termsRequired }, set: { if !$0 { viewModel.tv.cancelPendingPair() } })) {
            PairTrustDialog(viewModel: viewModel)
        }
        .fullScreenCover(isPresented: Binding(get: { viewModel.artworkEditor.draft != nil }, set: { if !$0 { viewModel.artworkEditor.close() } })) {
            ArtworkEditorScreen(controller: viewModel.artworkEditor, viewModel: viewModel)
        }
        // More than one Cast device and none is this TV's: ask which one (Android's "Play on TV" dialog).
        .confirmationDialog(L("ui_play_on_tv_f47dcd"), isPresented: Binding(get: { viewModel.tv.castPickerOpen }, set: { if !$0 { viewModel.tv.dismissCastPicker() } }), titleVisibility: .visible) {
            ForEach(viewModel.tv.castRoutes, id: \.deviceId) { route in Button(route.name) { viewModel.tv.chooseCastRoute(route) } }
            Button(L("ui_cancel_19766e"), role: .cancel) { viewModel.tv.dismissCastPicker() }
        } message: {
            if viewModel.tv.castRoutes.isEmpty { Text(key: "ui_looking_for_tvs_8edf98") }
        }
        .preferredColorScheme(.dark)
        // Dynamic Type up to "XXX Large"; beyond that the fixed-height cards (artwork, tab bar) would no longer hold their layout.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }
}

/// Tab shell, port of `LibraryShell`. The tab contents arrive in the following phases.
struct LibraryShell: View {
    @Bindable var viewModel: PhoneViewModel

    var body: some View {
        // Storage is a screen under Settings: the bar keeps highlighting Settings while it is open.
        let selection = Binding<PhoneTab>(get: { viewModel.tab.barItem }, set: { viewModel.selectTab($0) })
        VStack(spacing: 0) {
            AddedVideosCard(viewModel: viewModel)
            PrepareCard(viewModel: viewModel)
            if !viewModel.tv.pendingApprovals.isEmpty { ApprovalCard(viewModel: viewModel) }
            TelegramApprovalCard(work: viewModel.telegram.work)
            TransferCard(viewModel: viewModel)
            if viewModel.tab != .remote {
                RemoteBar(viewModel: viewModel)
            }
            ServingBanner(serving: viewModel.serving)
            tabs(selection)
        }
        .phoneScreenBackground()
    }

    private func tabs(_ selection: Binding<PhoneTab>) -> some View {
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
            case .browse: BrowseScreen(viewModel: viewModel)
            case .remote where viewModel.legacyRemote: RemoteScreen(viewModel: viewModel)
            case .settings:
                if viewModel.tab == .storage { StorageScreen(viewModel: viewModel) } else { SettingsScreen(viewModel: viewModel) }
            case .remote where !viewModel.legacyRemote: LibraryHome(viewModel: viewModel)
            default: PlaceholderTabView(tab: tab)
            }
            // A series page and a title page are full-screen overlays on any tab (Android `LibraryShell`).
            if let series = viewModel.shownSeries { SeriesOverlay(viewModel: viewModel, series: series).transition(.move(edge: .trailing)) }
            if let selected = viewModel.selected {
                if viewModel.subtitles.flowOpen {
                    SubtitleFlow(viewModel: viewModel, item: selected) { viewModel.subtitles.flowOpen = false }.id(selected.id)
                } else {
                    DetailOverlay(viewModel: viewModel, item: selected).id(selected.id).transition(.move(edge: .trailing))
                }
            }
        }
        .overlay { CloudFlow(viewModel: viewModel) }
        .modifier(DeleteConfirmation(viewModel: viewModel))
        .modifier(RemoveCloudCopyDialog(viewModel: viewModel))
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
