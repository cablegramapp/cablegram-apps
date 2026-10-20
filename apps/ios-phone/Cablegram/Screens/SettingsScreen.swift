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
    @State private var tvToRemove: PairedTv?
    @State private var tvToRename: PairedTv?
    @State private var renameDraft = ""
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
        // Leaving the tab closes the open page, as on Android (the screen leaves the composition there).
        .onChange(of: viewModel.tab) { _, tab in if tab != .settings && tab != .storage { page = nil } }
        .onChange(of: viewModel.householdName) { _, new in accountNameDraft = new }
        .onChange(of: page) { _, _ in viewModel.status = nil }
        .alert(L("ui_sign_out_c1c084"), isPresented: $confirmSignOut) {
            Button(L("ui_cancel_19766e"), role: .cancel) {}
            Button(L("ui_sign_out_48f0d3"), role: .destructive) { viewModel.signOut() }
        } message: { Text(key: "ui_this_phone_stops_sharing_and_controlling_your_tvs_your_960038") }
        .sheet(isPresented: $showLegal) { LegalSheet() }
        .onAppear { viewModel.tv.loadProfileLock() }
        .alert(L("tv_rename_title"), isPresented: Binding(get: { tvToRename != nil }, set: { if !$0 { tvToRename = nil } })) {
            TextField(L("tv_name_label"), text: $renameDraft)
            Button(L("ui_cancel_19766e"), role: .cancel) {}
            Button(L("ui_save_changes_dd0ae7")) { if let tvToRename { viewModel.tv.renameTv(tvToRename, to: renameDraft) } }
        }
        .alert(tvToRemove.map { L("ui_remove_e4dd7c", $0.name) } ?? "", isPresented: Binding(get: { tvToRemove != nil }, set: { if !$0 { tvToRemove = nil } })) {
            Button(L("ui_cancel_19766e"), role: .cancel) {}
            Button(L("ui_remove_tv_7c437e"), role: .destructive) { if let tvToRemove { viewModel.tv.unpair(tvToRemove) } }
        } message: { Text(key: "ui_the_tv_will_be_signed_out_and_lose_access_to_your_libr_699735") }
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
                PrefSwitch(key: "ui_transfer_on_wi_fi_only_992dd6", isOn: Binding(get: { viewModel.cloud.wifiOnlyTransfers }, set: { viewModel.cloud.wifiOnlyTransfers = $0 }))
                PrefSwitch(key: "ui_transfer_only_while_charging_645032", isOn: Binding(get: { viewModel.transferWhileCharging }, set: { viewModel.transferWhileCharging = $0 }))
                Divider().overlay(Color.phoneOutline)
                PrefSwitch(key: "ui_sync_library_titles_b3b6cc", isOn: Binding(get: { viewModel.syncLibraryToApi }, set: { viewModel.syncLibraryToApi = $0 }))
                Divider().overlay(Color.phoneOutline)
                relayPolicy
            }
        case .tvs: tvsPage
        case .profiles, .collections, .connection: EmptyView()
        }
    }

    /// "Relay over mobile data": who pays for a stream the TV could not take over Wi-Fi.
    private var relayPolicy: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(key: "ui_relay_over_mobile_data_a8efaa").foregroundStyle(.white)
            Text(key: "ui_when_your_tv_can_t_reach_this_phone_on_wi_fi_cablegram_b99318").font(.footnote).foregroundStyle(Color.phoneMuted)
            Picker("", selection: Binding(get: { viewModel.relayMobileData }, set: { viewModel.updateRelayMobileData($0) })) {
                Text(key: "ui_ask_b8c209").tag(RelayMobileDataPolicy.ask)
                Text(key: "ui_always_de9f05").tag(RelayMobileDataPolicy.always)
                Text(key: "ui_never_6300ef").tag(RelayMobileDataPolicy.never)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("relay_mobile_data")
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
            lockSection
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

    /// "Lock account on TVs": a TV opens a profile only once a phone allows it (CAB-67 idle time for shared TVs).
    private var lockSection: some View {
        let tv = viewModel.tv!
        return VStack(alignment: .leading, spacing: 12) {
            Divider().overlay(Color.phoneOutline)
            if let locked = tv.profileLock {
                PrefSwitch(key: "ui_lock_account_on_tvs_011bbe", isOn: Binding(get: { locked }, set: { tv.updateProfileLock($0) }), id: MaestroIds.settingsProfileLock)
            }
            Text(key: tv.profileLock == true ? "ui_a_tv_asks_this_phone_before_it_opens_any_profile_a60f8b"
                 : tv.profileLock == false ? "ui_a_tv_opens_profiles_without_asking_unless_other_accoun_d6d7d4" : "ui_couldn_t_load_the_account_lock_check_your_connection_85acd6")
                .font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            if let minutes = tv.sharedTvIdleMinutes {
                Text(key: "ui_on_a_tv_shared_with_other_accounts_ask_again_after_f81d7a").foregroundStyle(.white)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach([(30, "ui_30_min_f69fa2"), (60, "ui_1_hour_f8b888"), (240, "ui_4_hours_e5bc99"), (720, "ui_12_hours_ff6272")], id: \.0) { value, label in
                            FilterChip(label: label, selected: minutes == value) { tv.updateSharedTvIdleMinutes(value) }.accessibilityIdentifier("settings_shared_tv_idle_\(value)")
                        }
                    }
                }
                Text(key: "ui_without_use_the_tv_then_goes_back_to_who_s_watching_an_cef3ab").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            }
        }
    }

    private var tvsPage: some View {
        let tv = viewModel.tv!
        return SectionCard(title: L("ui_your_tvs_427608")) {
            if !tv.paired { Text(key: "ui_connect_a_tv_to_enjoy_your_library_on_a_bigger_screen_a1212f").foregroundStyle(Color.phoneMuted) }
            ForEach(tv.tvs, id: \.pin) { item in
                HStack(spacing: 12) {
                    Image(systemName: "tv").foregroundStyle(Color.phoneAccent)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: item.name).foregroundStyle(.white)
                        Text(key: item == tv.tvs.last ? "ui_current_tv_55310c" : "ui_paired_tv_68fdc7").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                        TvTrustActions(viewModel: viewModel, tv: item)
                    }
                    Spacer()
                    if item.deviceId != nil {
                        Button { renameDraft = item.name; tvToRename = item } label: { Text(key: "tv_rename").foregroundStyle(Color.phoneAccent) }.disabled(tv.working)
                    }
                    Button { tvToRemove = item } label: { Text(key: "ui_remove_c3812f").foregroundStyle(Color.phoneError) }.disabled(tv.working)
                }
            }
            Button { tv.startAddTv() } label: { Text(key: "ui_connect_a_tv_0ff3f5") }
                .buttonStyle(PrimaryButtonStyle(minHeight: 48)).accessibilityIdentifier(MaestroIds.settingsAddTv)
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

/// Trust line and Telegram actions under a TV in Settings → Your TVs.
struct TvTrustActions: View {
    let viewModel: PhoneViewModel
    let tv: PairedTv

    var body: some View {
        if tv.trust.temporary {
            Text(L("ui_temporary_unpaired_d52b66", tv.trust.expiresAt.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? "soon") + (tv.trust.telegramDirect ? L("ui_uses_telegram_directly_f552f4") : ""))
                .font(.system(size: 12)).foregroundStyle(Color.phoneAccent)
        }
        if tv.deviceId != nil, tv.trust.temporary, let expires = tv.trust.expiresAt {
            Button { var trust = tv.trust; trust.expiresAt = expires.addingTimeInterval(TvTrustRules.extendBy); viewModel.tv.updateTvTrust(tv, trust) } label: { Text(key: "ui_extend_a_day_c30370") }
                .buttonStyle(AccentTextButtonStyle())
        }
    }
}
