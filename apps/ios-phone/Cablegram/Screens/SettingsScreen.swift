import CablegramCore
import SwiftUI

private enum SettingsPage: String { case account, tvs, profiles, collections, transfers, connection }

/// Port of `SettingsScreen`: the overview, then one page at a time with "Back to Settings". Pages whose content
/// depends on later phases (TVs, profiles, collections, relay and cache settings) open empty for now.
struct SettingsScreen: View {
    let viewModel: PhoneViewModel
    @State private var page: SettingsPage?
    @State private var confirmSignOut = false
    @State private var showLegal = false
    @State private var accountNameDraft = ""
    @Environment(\.openURL) private var openURL

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                ScreenHeading(title: L("ui_settings_74a883"),
                              subtitle: viewModel.householdName.isEmpty ? L("ui_make_cablegram_yours_13baae") : viewModel.householdName)
                    .accessibilityIdentifier(MaestroIds.settingsTitle)
                if let current = page {
                    Button { page = nil } label: {
                        HStack(spacing: 8) { Image(systemName: "arrow.backward").font(.system(size: 16)); Text(key: "ui_back_to_settings_ea37ec") }
                    }
                    .buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier("settings_back")
                    pageContent(current)
                } else {
                    overview
                }
                if let status = viewModel.status { StatusNote(message: status) }
                if page == nil { footer }
                if page == .account {
                    Button { if let url = URL(string: DeleteAccountScreen.webURL) { openURL(url) } } label: { Text(key: "delete_account_web_link") }
                        .buttonStyle(AccentTextButtonStyle())
                }
            }
            .padding(20)
        }
        .phoneScreenBackground()
        .onAppear { accountNameDraft = viewModel.householdName }
        .onChange(of: viewModel.householdName) { _, new in accountNameDraft = new }
        .onChange(of: page) { _, _ in viewModel.status = nil }
        .alert(L("ui_sign_out_c1c084"), isPresented: $confirmSignOut) {
            Button(L("ui_cancel_19766e"), role: .cancel) {}
            Button(L("ui_sign_out_48f0d3"), role: .destructive) { viewModel.signOut() }
        } message: { Text(key: "ui_this_phone_stops_sharing_and_controlling_your_tvs_your_960038") }
        .sheet(isPresented: $showLegal) { LegalSheet() }
    }

    private var overview: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(spacing: 0) {
                row("ui_account_7e1b0d", "lock.fill", "settings_category_account") { page = .account }
                divider
                row("ui_your_tvs_427608", "tv", "settings_category_tvs") { page = .tvs }
                divider
                row("ui_profiles_535e52", "iphone", "settings_category_profiles") { page = .profiles }
                divider
                row("ui_transfers_privacy_af4a2f", "arrow.triangle.2.circlepath", "settings_category_transfers") { page = .transfers }
                divider
                row("ui_app_connection_535ee6", "gearshape.fill", "settings_category_connection") { page = .connection }
            }
            .background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 20))
            // Subtitle accounts open in phase 8.
            row("ui_subtitle_accounts_9602a0", "books.vertical.fill", "settings_subtitle_accounts", card: true) {}
            Button { viewModel.tab = .storage } label: {
                HStack(spacing: 14) {
                    Image(systemName: "externaldrive.fill").foregroundStyle(Color.phoneAccent)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(key: "ui_storage_a69c4d").font(PhoneFont.titleMedium).foregroundStyle(.white)
                        Text(key: "ui_phone_space_cloud_copies_transfers_f4925b").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(Color.phoneMuted)
                }
                .padding(18)
                .background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 20))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier(MaestroIds.navStorage)
        }
    }

    private var divider: some View { Divider().overlay(Color.phoneOutline).padding(.horizontal, 20) }

    private func row(_ key: String, _ symbol: String, _ id: String, card: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: symbol).font(.system(size: 18)).foregroundStyle(Color.phoneAccent).frame(width: 22)
                Text(key: key).font(PhoneFont.bodyLarge).foregroundStyle(.white).frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right").font(.system(size: 14)).foregroundStyle(Color.phoneMuted).flipsForRightToLeftLayoutDirection(true)
            }
            .padding(.horizontal, 20).padding(.vertical, 16).frame(minHeight: 64)
            .background(card ? Color.phonePanel : Color.clear, in: RoundedRectangle(cornerRadius: card ? 16 : 0))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }

    @ViewBuilder private func pageContent(_ page: SettingsPage) -> some View {
        switch page {
        case .account: account
        case .transfers:
            SectionCard(title: L("ui_transfers_privacy_af4a2f")) {
                if !CablegramApp.configuration.castAppId.isEmpty {
                    PrefSwitch(key: "ui_open_tv_with_cast_0dd4f2", isOn: Binding(get: { viewModel.castConnect }, set: { viewModel.updateCastConnect($0) }), id: MaestroIds.settingsCastConnect)
                }
                PrefSwitch(key: "ui_show_remote_controls_b49919", isOn: Binding(get: { viewModel.legacyRemote }, set: { viewModel.updateLegacyRemote($0) }), id: MaestroIds.settingsLegacyRemote)
            }
        case .tvs, .profiles, .collections, .connection: EmptyView()
        }
    }

    private var account: some View {
        VStack(alignment: .leading, spacing: 20) {
            SectionCard(title: L("ui_account_7e1b0d")) {
                PhoneTextField(label: L("ui_account_name_a704d8"), text: $accountNameDraft, supporting: L("ui_shown_on_your_phones_and_tvs_b7ea60"))
                    .onChange(of: accountNameDraft) { _, new in if new.count > 80 { accountNameDraft = String(new.prefix(80)) } }
                Button { viewModel.saveHouseholdName(accountNameDraft) } label: { Text(key: "ui_save_account_name_7c6744") }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 48))
                    .disabled(accountNameDraft.trimmingCharacters(in: .whitespaces).isEmpty
                              || accountNameDraft.trimmingCharacters(in: .whitespaces) == viewModel.householdName || viewModel.busy)
            }
            Button { confirmSignOut = true } label: { Text(key: "ui_sign_out_48f0d3") }
                .buttonStyle(OutlinedButtonStyle(color: .phoneError))
                .disabled(viewModel.busy)
                .accessibilityIdentifier(MaestroIds.settingsSignOut)
            VStack(alignment: .leading, spacing: 6) {
                Button { viewModel.openDeleteAccount() } label: { Text(key: "delete_account_entry") }
                    .buttonStyle(OutlinedButtonStyle(color: .phoneError, borderColor: .phoneError))
                    .disabled(viewModel.busy)
                    .accessibilityIdentifier("settings_delete_account")
                Text(key: "delete_account_entry_hint").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            }
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { viewModel.replayWelcomeTour() } label: { Text(key: "ui_show_the_welcome_tour_140eac") }
                .buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.settingsWelcomeTour)
            Button { showLegal = true } label: { Text(key: "ui_licence_and_third_party_notices_0dcfa5") }
                .buttonStyle(AccentTextButtonStyle())
            Text(L("ui_cablegram_your_videos_your_screen_your_space_b83cca",
                   Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""))
                .font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
        }
    }
}

struct PrefSwitch: View {
    let key: String
    @Binding var isOn: Bool
    var id: String?

    var body: some View {
        Toggle(isOn: $isOn) { Text(key: key).foregroundStyle(.white) }
            .tint(.phoneAccent)
            .accessibilityIdentifier(id ?? "")
    }
}

/// The licence and third-party notices (Android `LegalText`): NOTICE and LICENSE are copied from the repository root
/// at build time, so the text in the app is the text in the repository.
private struct LegalSheet: View {
    @Environment(\.dismiss) private var dismiss

    private var text: String {
        ["NOTICE", "LICENSE"].map { name in
            (Bundle.main.url(forResource: name, withExtension: nil, subdirectory: "legal"))
                .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "\(name) is missing from this build."
        }.joined(separator: "\n\n")
    }

    var body: some View {
        NavigationStack {
            ScrollView { Text(text).font(.system(size: 12)).foregroundStyle(.white).padding(20) }
                .phoneScreenBackground()
                .navigationTitle(L("ui_licence_and_notices_ded184"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("ui_close_7d9eb7")) { dismiss() } } }
        }
    }
}
