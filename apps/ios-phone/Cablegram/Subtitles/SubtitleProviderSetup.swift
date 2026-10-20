import CablegramCore
import SwiftUI
import UIKit

private let subdlKeyPage = URL(string: "https://subdl.com/panel/api")!
private let openSubtitlesKeyPage = URL(string: "https://www.opensubtitles.com/consumers")!

/// One free account is enough: SubDL (a key only) leads; OpenSubtitles is an optional extra because it also needs the person's login.
/// Inputs are never prefilled or copied into diagnostics. `requireAccount` is false where the screen is opened from Settings, so Done always leaves it.
struct SubtitleProviderSetup: View {
    let viewModel: PhoneViewModel
    let onDone: () -> Void
    var doneLabel: String?
    var requireAccount = true
    @State private var showOpenSubtitles = false
    @Environment(\.openURL) private var openURL

    var body: some View {
        let revision = viewModel.subtitles.credentialRevision
        let subdl = viewModel.subtitles.providerConfigured("subdl")
        let openSubtitles = viewModel.subtitles.providerConfigured("opensubtitles")
        VStack(alignment: .leading, spacing: 16) {
            Text(key: "ui_subtitle_accounts_9602a0").font(PhoneFont.titleLarge).foregroundStyle(.white)
            Text(key: "ui_cablegram_finds_subtitles_through_your_own_free_accoun_811b7f").foregroundStyle(Color.phoneMuted)

            SectionCard(title: L("ui_subdl_recommended_7b58c1")) {
                if subdl {
                    connectedRow(L("ui_subdl_is_connected_be1fd7"))
                    ProviderForm(viewModel: viewModel, provider: "subdl", connected: true)
                } else {
                    Text(key: "ui_quickest_to_set_up_it_only_needs_a_key_1a6632").foregroundStyle(Color.phoneMuted)
                    step(1, L("ui_sign_in_or_create_a_free_account_at_subdl_112b2c"))
                    step(2, L("ui_open_the_api_page_and_copy_your_api_key_3d48c9"))
                    link(L("ui_open_subdl_api_page_61aa3f"), subdlKeyPage)
                    step(3, L("ui_paste_the_key_here_don_t_use_your_subdl_password_26ff9e"))
                    ProviderForm(viewModel: viewModel, provider: "subdl", connected: false)
                }
            }
            SectionCard(title: L("ui_opensubtitles_optional_4070ca")) {
                if openSubtitles {
                    connectedRow(L("ui_opensubtitles_is_connected_8086b6"))
                    ProviderForm(viewModel: viewModel, provider: "opensubtitles", connected: true)
                } else {
                    Text(key: "ui_the_biggest_catalogue_often_better_for_older_titles_it_9d2b9e").foregroundStyle(Color.phoneMuted)
                    Button { showOpenSubtitles.toggle() } label: {
                        HStack(spacing: 4) { Text(key: showOpenSubtitles ? "ui_hide_ac20a5" : "ui_add_opensubtitles_too_a20dce"); Image(systemName: showOpenSubtitles ? "chevron.up" : "chevron.down") }
                    }.buttonStyle(AccentTextButtonStyle())
                    if showOpenSubtitles {
                        Text(L("ui_opensubtitles_needs_two_things_an_api_key_for_searchin_6cf012") + L("ui_because_each_download_counts_against_your_own_account__fb8976") + L("ui_your_login_stays_on_this_phone_572eb1")).foregroundStyle(Color.phoneMuted)
                        step(1, L("ui_create_a_free_account_at_opensubtitles_com_c1e6ef"))
                        step(2, L("ui_open_the_api_consumers_page_create_a_consumer_and_copy_864978"))
                        link(L("ui_open_opensubtitles_api_page_9f649e"), openSubtitlesKeyPage)
                        step(3, L("ui_enter_the_key_with_your_opensubtitles_username_and_pas_5e0f85"))
                        ProviderForm(viewModel: viewModel, provider: "opensubtitles", connected: false)
                    }
                }
            }
            PrivacyAndTerms()
            let any = subdl || openSubtitles
            Button(action: onDone) { Text(doneLabel ?? L("ui_find_subtitles_e39ed2")) }.buttonStyle(PrimaryButtonStyle(minHeight: 48)).disabled(!(any || !requireAccount))
            if !any && requireAccount { Text(key: "ui_connect_at_least_one_account_to_search_9f3093").font(.system(size: 12)).foregroundStyle(Color.phoneMuted) }
        }
        .id(revision)
    }

    private func connectedRow(_ text: String) -> some View {
        HStack(spacing: 8) { Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.phoneAccent); Text(text).foregroundStyle(.white) }
    }

    private func step(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) { Text("\(number).").foregroundStyle(Color.phoneAccent); Text(text).foregroundStyle(.white) }
    }

    private func link(_ label: String, _ url: URL) -> some View {
        Button { openURL(url) } label: { HStack(spacing: 8) { Text(label); Image(systemName: "arrow.up.right.square").font(.system(size: 14)) } }.buttonStyle(OutlinedButtonStyle())
    }
}

/// Entry for one provider; once connected it collapses to Replace / Remove.
private struct ProviderForm: View {
    let viewModel: PhoneViewModel
    let provider: String
    let connected: Bool
    @State private var editing: Bool
    @State private var key = ""
    @State private var username = ""
    @State private var password = ""
    @State private var note: String?
    @State private var refused = false
    @State private var checking = false

    init(viewModel: PhoneViewModel, provider: String, connected: Bool) {
        self.viewModel = viewModel; self.provider = provider; self.connected = connected
        _editing = State(initialValue: !connected)
    }

    private var needsLogin: Bool { provider == "opensubtitles" }
    private var name: String { needsLogin ? "OpenSubtitles" : "SubDL" }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if editing {
                HStack(alignment: .top, spacing: 8) {
                    PhoneTextField(label: L("ui_api_key_7757de", name), text: $key, capitalizes: false, secure: true, id: "subtitle_\(provider)_key")
                    Button { if let pasted = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines) { key = pasted } } label: {
                        Image(systemName: "doc.on.clipboard").frame(width: 44, height: 58)
                    }.accessibilityLabel(L("ui_paste_key_b95819"))
                }
                if needsLogin {
                    PhoneTextField(label: L("ui_opensubtitles_username_f5e557"), text: $username, capitalizes: false, id: "subtitle_opensubtitles_username")
                    PasswordField(label: L("ui_opensubtitles_password_1ad679"), text: $password)
                }
                HStack(spacing: 8) {
                    Button(action: submit) {
                        if checking { HStack(spacing: 8) { ProgressView().tint(.white); Text(key: "ui_checking_ec963f") } }
                        else { Text(connected ? L("ui_save_new_details_7ddcbe") : L("ui_connect_5fd5e3", name)) }
                    }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 48))
                    .disabled(checking || key.trimmingCharacters(in: .whitespaces).isEmpty || (needsLogin && (username.trimmingCharacters(in: .whitespaces).isEmpty || password.isEmpty)))
                    .accessibilityIdentifier("subtitle_\(provider)_save")
                    if connected { Button { editing = false; key = ""; username = ""; password = "" } label: { Text(key: "ui_cancel_19766e") }.buttonStyle(AccentTextButtonStyle()) }
                }
            } else {
                HStack(spacing: 8) {
                    Button { editing = true; note = nil } label: { Text(key: needsLogin ? "ui_change_details_b71167" : "ui_replace_key_548dca") }.buttonStyle(OutlinedButtonStyle())
                    Button {
                        viewModel.subtitles.removeProvider(provider)
                        note = L("ui_removed_subtitles_you_already_chose_stay_ae4157", name)
                    } label: { Text(key: "ui_remove_c3812f") }.buttonStyle(AccentTextButtonStyle())
                }
            }
            if let note {
                Text(note).font(.system(size: 12)).foregroundStyle(refused ? Color.phoneError : Color.phoneMuted).accessibilityIdentifier("subtitle_\(provider)_note")
            }
        }
    }

    private func submit() {
        let typed = PersonalSubtitleCredentials(key: key.trimmingCharacters(in: .whitespaces), username: username.trimmingCharacters(in: .whitespaces), password: password)
        checking = true; note = nil; refused = false
        Task {
            // Ask the provider first: a refused key is never saved, and what was typed stays to be corrected.
            let check = await checkSubtitleCredentials(provider: provider, credentials: typed)
            checking = false
            if case .refused(let message) = check { refused = true; note = message; return }
            do {
                try viewModel.subtitles.saveProvider(provider, typed)
                key = ""; username = ""; password = ""; editing = false
                if case .unchecked(let message) = check { note = message } else { note = L("ui_accepted_it_you_re_ready_to_search_e0f65f", name) }
            } catch { note = L("ui_couldn_t_save_it_securely_try_again_f85662") }
        }
    }
}

/// A password field with a show/hide eye.
private struct PasswordField: View {
    let label: String
    @Binding var text: String
    @State private var visible = false

    var body: some View {
        HStack(spacing: 8) {
            PhoneTextField(label: label, text: $text, capitalizes: false, secure: !visible, id: "subtitle_opensubtitles_password")
            Button { visible.toggle() } label: { Image(systemName: visible ? "eye.slash" : "eye").frame(width: 44, height: 58) }
                .accessibilityLabel(L(visible ? "ui_hide_password_a60a56" : "ui_show_password_6aeaa6"))
        }
    }
}

private struct PrivacyAndTerms: View {
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("ui_searches_go_straight_from_this_phone_to_the_site_you_c_3c8a91") + L("ui_only_the_subtitle_you_pick_is_saved_for_your_tvs_ab5d20")).font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            Button { open.toggle() } label: {
                HStack(spacing: 4) { Text(key: open ? "ui_hide_privacy_and_terms_942d73" : "ui_privacy_and_terms_9b68c4"); Image(systemName: open ? "chevron.up" : "chevron.down") }
            }.buttonStyle(AccentTextButtonStyle())
            if open {
                Text(key: "ui_the_subtitle_sites_see_your_ip_address_the_title_file__cc820e").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                Text(key: "ui_only_the_subtitle_you_choose_its_language_provider_ref_abd48f").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                Text(key: "ui_personal_keys_do_not_grant_copyright_permission_use_tr_003d6d").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            }
        }
    }
}
