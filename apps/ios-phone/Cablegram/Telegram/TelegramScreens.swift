import CablegramCore
import SwiftUI

// Port of `TelegramScreens.kt`, `TelegramAccountHelp.kt` and the password screen of `TelegramPasswordRequests.kt`.

private let privacyURL = URL(string: "https://cablegram.app/privacy.html#telegram")!
/// The code that talks to Telegram, in the public repository, at the tag this build is released from. Update the tag together with the version.
private let telegramCodeURL = URL(string: "https://github.com/seep-labs/cablegram-apps/tree/v0.3.0/apps/ios-phone/Packages/CablegramCore/Sources/CablegramCore/Telegram")!

@MainActor
private func openInTelegram(_ chatId: Int64, notInstalled: @escaping @MainActor () -> Void) {
    guard let url = URL(string: PhoneTelegram.channelLink(chatId)) else { return }
    Task { if !(await UIApplication.shared.open(url)) { notInstalled() } }
}

/// A direct route to the household's linked channel, before the other import sources.
struct TelegramLibraryCard: View {
    let viewModel: PhoneViewModel
    @State private var copied = false

    var body: some View {
        let telegram: TelegramController = viewModel.telegram
        let link = telegram.link
        let linked = link?.linked == true
        SectionCard(title: L("telegram_library_title")) {
            Image(systemName: "paperplane.fill").font(.system(size: 30)).foregroundStyle(Color.phoneAccent)
            Text(verbatim: TelegramSession.libraryTitle).font(PhoneFont.titleLarge).foregroundStyle(.white)
            Text(key: "telegram_library_help").foregroundStyle(Color.phoneMuted)
            if telegram.health == .channelLost && linked && !telegram.notHere {
                Text(verbatim: L("ui_you_re_no_longer_in_the_channel_you_left_it_or_it_was__46af5b", TelegramSession.libraryTitle)).foregroundStyle(Color.phoneAccent)
                Button { telegram.recreateChannel() } label: { Text(verbatim: L("ui_create_a_new_cb63aa", TelegramSession.libraryTitle)) }.buttonStyle(PrimaryButtonStyle(minHeight: 52))
            } else if telegram.notHere {
                Text(key: "ui_this_phone_isn_t_signed_in_to_telegram_yet_so_it_can_t_a21b4f").foregroundStyle(Color.phoneAccent)
                Button { telegram.openSheet() } label: { Text(key: "ui_sign_in_on_this_phone_1bc154") }.buttonStyle(PrimaryButtonStyle(minHeight: 52))
            } else if linked, let chatId = link?.chatId {
                Text(verbatim: L("ui_connected_as_767c2d", telegram.accountName)).font(.system(size: 13)).foregroundStyle(Color.phoneMuted)
                Button { openInTelegram(chatId) { viewModel.status = L("telegram_library_install_telegram") } } label: { Text(key: "telegram_library_open") }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 52)).accessibilityIdentifier(MaestroIds.telegramLibraryOpen)
                Button {
                    UIPasteboard.general.string = PhoneTelegram.channelWebLink(chatId)
                    viewModel.status = L("telegram_library_copied")
                } label: { Text(key: "telegram_library_copy") }
                    .buttonStyle(OutlinedButtonStyle(minHeight: 48)).accessibilityIdentifier(MaestroIds.telegramLibraryCopy)
                Text(key: "telegram_library_members_link").font(.system(size: 13)).foregroundStyle(Color.phoneMuted)
            } else {
                Button { telegram.openSheet() } label: { Text(key: "ui_connect_telegram_00c799") }.buttonStyle(PrimaryButtonStyle(minHeight: 52))
            }
        }
        .onAppear { if linked { telegram.checkHealth() } }
    }
}

/// Telegram in Import → Connected storage: connect once on the phone, then videos shared into the private "Cablegram library" channel appear.
struct TelegramStorageEntry: View {
    let viewModel: PhoneViewModel
    let onAccountHelp: () -> Void
    @State private var confirmDisconnect = false

    var body: some View {
        let telegram: TelegramController = viewModel.telegram
        let linked = telegram.link?.linked == true
        let health = telegram.health
        let warn = telegram.notHere || health == .channelLost || health == .signedOut
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "paperplane.fill").foregroundStyle(Color.phoneAccent)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: linked ? L("ui_telegram_4bdd3a", TelegramSession.libraryTitle) : "Telegram").foregroundStyle(.white)
                    Text(verbatim: statusLine(telegram, linked: linked)).font(.system(size: 12)).foregroundStyle(warn ? Color.phoneAccent : Color.phoneMuted)
                }
            }
            .accessibilityElement(children: .contain).accessibilityIdentifier(MaestroIds.telegramEntry)
            if !linked {
                Button { telegram.openSheet() } label: { Text(key: "ui_connect_telegram_00c799") }.buttonStyle(PrimaryButtonStyle(minHeight: 52)).accessibilityIdentifier(MaestroIds.telegramConnect)
            } else if telegram.notHere {
                Button { telegram.openSheet() } label: { Text(key: "ui_sign_in_on_this_phone_1bc154") }.buttonStyle(PrimaryButtonStyle(minHeight: 52))
            } else if health == .channelLost {
                Button { telegram.recreateChannel() } label: { Text(verbatim: L("ui_create_a_new_cb63aa", TelegramSession.libraryTitle)) }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 52)).accessibilityIdentifier(MaestroIds.telegramRecreate)
            } else if health == .signedOut {
                Button { telegram.signInAgain() } label: { Text(key: "ui_sign_in_to_telegram_again_ce9582") }.buttonStyle(PrimaryButtonStyle(minHeight: 52))
            } else if let chatId = telegram.link?.chatId {
                Button { openInTelegram(chatId) { viewModel.status = L("telegram_library_install_telegram") } } label: { Text(verbatim: L("ui_open_in_telegram_700ad9", TelegramSession.libraryTitle)) }
                    .buttonStyle(OutlinedButtonStyle()).accessibilityIdentifier(MaestroIds.telegramOpen)
            }
            if linked && health == .ok && PhoneTelegram.wasLinked {
                Button { telegram.checkBotHub() } label: { Text(key: "ui_check_bot_hub_d194d2") }
                    .buttonStyle(OutlinedButtonStyle(minHeight: 52)).accessibilityIdentifier(MaestroIds.telegramCheckBotHub)
                Button { telegram.organizeFolder() } label: { Text(key: telegram.folderBusy ? "ui_organizing_564f6b" : "ui_organize_telegram_folder_84e271") }
                    .buttonStyle(OutlinedButtonStyle(minHeight: 52)).disabled(telegram.folderBusy).accessibilityIdentifier(MaestroIds.telegramOrganizeFolder)
                if let message = telegram.folderMessage { Text(verbatim: message).foregroundStyle(Color.phoneMuted) }
                Toggle(isOn: Binding(get: { PhoneTelegram.autoApproveTvs }, set: { PhoneTelegram.autoApproveTvs = $0 })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(key: "ui_approve_my_tvs_automatically_23e7c5").foregroundStyle(.white)
                        Text(key: "ui_off_a_notification_asks_before_each_tv_signs_in_to_tel_6f98b3").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                    }
                }.tint(.phoneAccent)
            }
            if linked && !telegram.notHere && health == .ok {
                Button { telegram.openHiddenVideos() } label: { Text(key: "ui_hidden_videos_e92f75") }.buttonStyle(OutlinedButtonStyle())
            }
            Button(action: onAccountHelp) { Text(key: "telegram_separate_learn") }.buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.telegramAccountHelp)
            if linked {
                Button { confirmDisconnect = true } label: { Text(key: "ui_disconnect_telegram_3896d7").foregroundStyle(Color.phoneError) }
                    .buttonStyle(OutlinedButtonStyle()).accessibilityIdentifier(MaestroIds.telegramDisconnect)
            }
        }
        .alert(L("ui_disconnect_telegram_eb9ab8"), isPresented: $confirmDisconnect) {
            Button(L("ui_disconnect_acfc5b"), role: .destructive) { telegram.disconnect() }
            Button(L("ui_cancel_19766e"), role: .cancel) {}
        } message: {
            Text(verbatim: L("ui_cablegram_signs_out_of_telegram_on_this_phone_and_on_y_73f0dc") + L("ui_channel_they_disappear_from_cablegram_until_you_connec_2ef282", TelegramSession.libraryTitle))
        }
        .sheet(isPresented: Binding(get: { telegram.showHidden }, set: { telegram.showHidden = $0 })) { HiddenVideosSheet(telegram: telegram) }
    }

    private func statusLine(_ telegram: TelegramController, linked: Bool) -> String {
        if !linked { return L("ui_use_your_own_telegram_account_as_cloud_storage_share_v_0d5978") }
        if telegram.notHere { return L("ui_this_phone_isn_t_signed_in_to_telegram_yet_so_it_can_t_a21b4f") }
        switch telegram.health {
        case .channelLost: return L("ui_you_re_no_longer_in_the_channel_you_left_it_or_it_was__46af5b", TelegramSession.libraryTitle)
        case .signedOut: return L("ui_telegram_signed_cablegram_out_on_this_phone_for_exampl_c6a476")
        default: return L("ui_connected_as_videos_you_share_into_this_private_channe_54b272", telegram.accountName)
        }
    }
}

/// Phone number → code → (password) → library set up. One request at a time (the session enforces it).
struct TelegramConnectSheet: View {
    let viewModel: PhoneViewModel
    @State private var accountHelp = false

    var body: some View {
        let telegram: TelegramController = viewModel.telegram
        let started = telegram.started || PhoneTelegram.existing() != nil
        ZStack {
            Color.phoneBackground.ignoresSafeArea()
            if accountHelp {
                TelegramAccountHelp { accountHelp = false }
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        Button { telegram.closeSheet() } label: { Text(key: "ui_close_7d9eb7") }.buttonStyle(AccentTextButtonStyle())
                        // A household that is already linked still needs the intro on a phone that has no session of its own.
                        if !started && !PhoneTelegram.wasLinked {
                            BeforeYouConnect(viewModel: viewModel) { accountHelp = true }
                        } else {
                            SignInSteps(viewModel: viewModel)
                        }
                    }
                    .padding(20)
                }
            }
        }
        .accessibilityElement(children: .contain).accessibilityIdentifier(MaestroIds.telegramSheet)
        .preferredColorScheme(.dark)
    }
}

/// What Cablegram gets, what it uses, where credentials go, how to revoke: before any input.
private struct BeforeYouConnect: View {
    let viewModel: PhoneViewModel
    let onAccountHelp: () -> Void

    var body: some View {
        let telegram: TelegramController = viewModel.telegram
        Text(key: "ui_before_you_connect_telegram_435c06").font(PhoneFont.headlineSmall).foregroundStyle(.white)
        TrustPoint(L("ui_what_cablegram_can_do_86c731"), L("ui_like_any_telegram_app_telegram_desktop_for_example_cab_22d711"))
        TrustPoint(L("ui_what_it_does_c74ea9"), L("ui_for_your_videos_it_reads_and_manages_one_private_chann_a5212a", TelegramSession.libraryTitle))
        TrustPoint(L("ui_where_your_details_go_c282bf"), L("ui_your_phone_number_login_code_and_password_go_straight__bc3e04"))
        TrustPoint(L("ui_you_stay_in_control_faa171"), L("ui_you_ll_see_cablegram_in_telegram_settings_devices_and__e6a486"))
        TrustPoint(L("ui_you_can_check_this_ba4981"), L("ui_the_cablegram_apps_are_open_source_anyone_can_check_wh_3a0623"))
        Link(destination: telegramCodeURL) { Text(key: "ui_read_the_code_f049d5").foregroundStyle(Color.phoneAccent) }
        Text(key: "ui_cablegram_is_an_unofficial_app_it_isn_t_made_or_endors_7e0562").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
        TrustPoint(L("telegram_separate_title"), L("telegram_separate_intro"))
        Button(action: onAccountHelp) { Text(key: "telegram_separate_learn") }.buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.telegramAccountHelp)
        Button { telegram.connect() } label: { Text(key: "ui_continue_31fbef") }.buttonStyle(PrimaryButtonStyle(minHeight: 52)).accessibilityIdentifier(MaestroIds.telegramContinue)
        // Only when a Home TV is paired; it must already be signed in to the household's Telegram.
        if telegram.homeTv != nil && telegram.link?.linked == true {
            Button { telegram.connectViaTv() } label: { Text(key: "ui_use_the_qr_code_on_your_tv_instead_7c5651") }.buttonStyle(OutlinedButtonStyle())
            Text(key: "ui_your_tv_is_already_signed_in_to_your_telegram_it_appro_6e35a3").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
        }
        Button { telegram.closeSheet() } label: { Text(key: "ui_not_now_a0e63d") }.buttonStyle(OutlinedButtonStyle())
        Link(destination: privacyURL) { Text(key: "ui_how_this_works_bdbd77").foregroundStyle(Color.phoneAccent) }
    }
}

private struct TrustPoint: View {
    let title: String
    let text: String
    init(_ title: String, _ text: String) { self.title = title; self.text = text }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: title).font(PhoneFont.titleMedium).foregroundStyle(.white)
            Text(verbatim: text).font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted)
        }
    }
}

private struct SignInSteps: View {
    let viewModel: PhoneViewModel
    @State private var input = ""

    var body: some View {
        content
            // Each step starts empty (Android keys the field by the step).
            .onChange(of: PhoneTelegram.session().state) { _, _ in input = "" }
    }

    @ViewBuilder private var content: some View {
        let telegram: TelegramController = viewModel.telegram
        let session = PhoneTelegram.session()
        let state = session.state
        Text(key: telegram.botHubOnly ? "ui_bot_hub_6dcb6e" : "ui_connect_telegram_00c799").font(PhoneFont.headlineSmall).foregroundStyle(.white)
        let linkedHere = telegram.link?.linked == true && telegram.health == .ok && PhoneTelegram.wasLinked
        if linkedHere {
            if !telegram.botHubOnly, let channels = telegram.communityChannels, !channels.state.completed {
                CommunityChannelsOptions(channels: channels) { telegram.saveCommunityChannels() }
            } else if let bots = telegram.libraryBots, !bots.state.completed {
                LibraryBotOptions(bots: bots, onRetry: { telegram.loadBots() }, onAdd: { telegram.addBot() })
                    .task { telegram.loadBots() }
            } else if telegram.botHubOnly {
                Text(key: "ui_bot_hub_review_complete_check_again_any_time_for_curre_700291").foregroundStyle(Color.phoneMuted)
                Button { telegram.closeSheet() } label: { Text(key: "ui_close_bot_hub_855e5a") }.buttonStyle(PrimaryButtonStyle(minHeight: 52))
            } else {
                Done(viewModel: viewModel)
            }
        } else if let message = telegram.message {
            Text(verbatim: message).foregroundStyle(Color.phoneAccent)
            if case .ready = state { Button { telegram.connect() } label: { Text(key: "ui_try_again_d8b839") }.buttonStyle(OutlinedButtonStyle()) }
        } else if telegram.finishing || { if case .ready = state { true } else { false } }() {
            Working(L("ui_setting_up_your_channel_3eeef5", TelegramSession.libraryTitle))
        } else {
            switch state {
            case .starting, .signedOut: Working(L("ui_starting_telegram_44fd03"))
            case .needsPhoneNumber:
                Text(key: "ui_enter_the_phone_number_of_your_telegram_account_it_goe_284479").foregroundStyle(Color.phoneMuted)
                Step(label: L("ui_phone_number_306f1b"), hint: L("ui_with_country_code_e_g_31_6_1234_5678_f10939"), value: $input, keyboard: .phonePad, busy: session.busy, error: session.lastError,
                     action: L("ui_send_code_66a5b4"), enabled: input.filter(\.isNumber).count >= 7) { session.submitPhoneNumber(input) }
                    .onChange(of: input) { _, v in input = String(v.prefix(20)) }
            case .needsCode:
                Text(key: "ui_telegram_sent_a_login_code_to_your_telegram_app_or_by__0317af").foregroundStyle(Color.phoneMuted)
                Text(verbatim: L("ui_telegram_s_message_says_not_to_give_this_code_to_anyon_7277cb") + L("ui_cablegram_uses_it_to_sign_in_with_telegram_directly_li_f3fc01")).font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                Step(label: L("ui_login_code_95e717"), hint: "5 digits", value: $input, keyboard: .numberPad, busy: session.busy, error: session.lastError, action: L("ui_continue_31fbef"), enabled: input.count >= 5) { session.submitCode(input) }
                    .onChange(of: input) { _, v in input = String(v.filter(\.isNumber).prefix(8)) }
            case let .needsPassword(hint):
                Text(key: "ui_your_account_has_two_step_verification_enter_your_tele_9e706f").foregroundStyle(Color.phoneMuted)
                Text(key: "ui_cablegram_passes_your_password_to_telegram_to_sign_in__0bb35c").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                Step(label: L("ui_telegram_password_65d868"), hint: hint.isEmpty ? "" : L("ui_hint_3033f3", hint), value: $input, keyboard: .default, busy: session.busy, error: session.lastError,
                     action: L("ui_sign_in_bfd402"), enabled: !input.isEmpty, secret: true) { session.submitPassword(input) }
            case let .failed(message):
                Text(verbatim: message).foregroundStyle(Color.phoneAccent)
                Button { session.retryStartup() } label: { Text(key: "ui_try_again_d8b839") }.buttonStyle(OutlinedButtonStyle())
            case .waitingForApproval:
                Working(L(telegram.viaTv ? "ui_waiting_for_your_tv_to_approve_keep_cablegram_open_on__785c22" : "ui_starting_telegram_44fd03"))
            case .ready: EmptyView()
            }
        }
    }
}

/// Optional memberships are explicit: even the default selections do nothing until Save.
private struct CommunityChannelsOptions: View {
    let channels: CommunityChannels
    let onSave: () -> Void

    var body: some View {
        let state = channels.state
        VStack(alignment: .leading, spacing: 14) {
            Text(key: "ui_join_cablegram_channels_d35d5f").font(PhoneFont.headlineSmall).foregroundStyle(.white)
            Text(key: "ui_these_channels_are_optional_uncheck_any_you_don_t_want_b5e843").foregroundStyle(Color.phoneMuted)
            ForEach(CablegramChannel.allCases, id: \.self) { channel in
                let joined = state.joined.contains(channel)
                Button { channels.select(channel, selected: !state.selected.contains(channel)) } label: {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: state.selected.contains(channel) ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundStyle(state.selected.contains(channel) ? Color.phoneAccent : Color.phoneMuted)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: channel.title).foregroundStyle(.white)
                            Text(verbatim: channel.url).font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                            if channel == .botHubs {
                                Text(key: "ui_discover_useful_telegram_bots_that_make_managing_organ_090372").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                                Text(key: "ui_these_are_independent_third_party_bots_and_are_not_dev_296876").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                            }
                            if joined { Text(key: "ui_joined_69318b").foregroundStyle(Color.phoneAccent) }
                            if state.failed.contains(channel) { Text(key: "ui_couldn_t_join_try_save_again_or_uncheck_to_continue_02e0d9").foregroundStyle(Color.phoneAccent) }
                        }
                        Spacer(minLength: 0)
                    }
                    .frame(minHeight: 64, alignment: .leading)
                }
                .disabled(state.saving || joined)
                .accessibilityIdentifier("telegram_channel_\(channel.username)")
            }
            Text(key: "ui_joined_cablegram_channels_your_library_support_and_add_30d764").foregroundStyle(Color.phoneMuted)
            Button(action: onSave) { Text(key: state.saving ? "ui_joining_6bbb89" : "ui_save_1509f5") }
                .buttonStyle(PrimaryButtonStyle(minHeight: 52)).disabled(state.saving).accessibilityIdentifier("telegram_channels_save")
        }
        .accessibilityElement(children: .contain).accessibilityIdentifier("telegram_channels")
    }
}

/// Each Add is separately gated; Skip never requires accepting the disclaimer.
private struct LibraryBotOptions: View {
    let bots: LibraryBotRecommendations
    let onRetry: () -> Void
    let onAdd: () -> Void

    var body: some View {
        let state = bots.state
        let bot = state.current
        VStack(alignment: .leading, spacing: 14) {
            Text(key: "ui_optional_bots_for_your_library_0e5b67").font(PhoneFont.headlineSmall).foregroundStyle(.white)
            Text(key: "ui_discover_useful_telegram_bots_that_make_managing_organ_090372").foregroundStyle(Color.phoneMuted)
            Text(key: "ui_these_are_independent_third_party_bots_and_are_not_dev_296876").foregroundStyle(Color.phoneMuted)
            Text(key: "ui_recommendations_come_from_posts_in_cablegrambothubs_yo_812f39").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            if state.added > 0 { Text(verbatim: L("ui_added_to_your_library_392ec3", "\(state.added)")).foregroundStyle(Color.phoneAccent) }
            if !state.loaded {
                if state.busy { Working(L("ui_finding_bots_in_cablegram_bot_hubs_6632c3")) }
                if let error = state.error { Text(verbatim: error).foregroundStyle(Color.phoneAccent) }
                if !state.busy {
                    Button(action: onRetry) { Text(key: "ui_try_again_d8b839") }.buttonStyle(OutlinedButtonStyle())
                    Button { bots.finish() } label: { Text(key: "ui_skip_bots_9e32d8") }.buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier("telegram_bots_skip_all")
                }
            } else if let bot {
                Text(verbatim: L("ui_bot_of_c8ea8f", "\(state.index + 1)", "\(state.bots.count)")).foregroundStyle(Color.phoneMuted)
                Text(verbatim: bot.displayName).font(PhoneFont.titleLarge).foregroundStyle(.white)
                Text(verbatim: "@\(bot.username)").foregroundStyle(Color.phoneAccent)
                if let url = URL(string: bot.url) { Link(destination: url) { Text(key: "ui_review_bot_in_telegram_eec873").foregroundStyle(Color.phoneAccent) } }
                Text(key: "ui_access_to_your_library_channel_4ecfde").font(PhoneFont.titleMedium).foregroundStyle(.white)
                Text(key: "ui_the_bot_will_be_added_to_your_cablegram_library_channe_1d4a14").foregroundStyle(Color.phoneMuted)
                Text(key: "ui_this_recommendation_is_shared_for_informational_purpos_02f61b").foregroundStyle(.white).accessibilityIdentifier("telegram_bot_disclaimer")
                Button { bots.acceptDisclaimer(!state.disclaimerAccepted) } label: {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: state.disclaimerAccepted ? "checkmark.square.fill" : "square").font(.system(size: 22)).foregroundStyle(state.disclaimerAccepted ? Color.phoneAccent : Color.phoneMuted)
                        Text(verbatim: L("ui_i_have_reviewed_this_bot_s_permissions_and_agree_to_th_07cab6", bot.username)).foregroundStyle(.white).multilineTextAlignment(.leading)
                    }.frame(minHeight: 56, alignment: .leading)
                }
                .disabled(state.busy).accessibilityIdentifier("telegram_bot_accept_disclaimer")
                if let error = state.error { Text(verbatim: error).foregroundStyle(Color.phoneAccent) }
                Button(action: onAdd) { Text(key: state.busy ? "ui_adding_c6de6f" : "ui_add_to_library_channel_f72709") }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 52)).disabled(!state.disclaimerAccepted || state.busy).accessibilityIdentifier("telegram_bot_add")
                Button { bots.skipCurrent() } label: { Text(key: "ui_skip_this_bot_b287ce") }.buttonStyle(OutlinedButtonStyle()).disabled(state.busy).accessibilityIdentifier("telegram_bot_skip")
                Button { bots.finish() } label: { Text(key: "ui_skip_remaining_bots_89f0a7") }.buttonStyle(AccentTextButtonStyle()).disabled(state.busy).accessibilityIdentifier("telegram_bots_skip_all")
            } else {
                Text(key: "ui_no_bots_are_available_to_review_you_can_check_cablegra_cffc27").foregroundStyle(Color.phoneMuted)
                Button { bots.finish() } label: { Text(key: "ui_continue_31fbef") }.buttonStyle(PrimaryButtonStyle(minHeight: 52)).accessibilityIdentifier("telegram_bots_done")
            }
        }
        .accessibilityElement(children: .contain).accessibilityIdentifier("telegram_library_bots")
    }
}

private struct Step: View {
    let label: String
    let hint: String
    @Binding var value: String
    let keyboard: UIKeyboardType
    let busy: Bool
    let error: String?
    let action: String
    let enabled: Bool
    var secret = false
    let onSubmit: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if secret {
                SecretField(label: label, text: $value, supporting: error ?? hint, isError: error != nil)
            } else {
                PhoneTextField(label: label, text: $value, supporting: error ?? hint, keyboard: keyboard, isError: error != nil, leftToRight: keyboard == .phonePad || keyboard == .numberPad, capitalizes: false, id: MaestroIds.telegramInput)
            }
            Button(action: onSubmit) { Text(verbatim: busy ? L("ui_checking_ec963f") : action) }
                .buttonStyle(PrimaryButtonStyle(minHeight: 52)).disabled(!enabled || busy).accessibilityIdentifier(MaestroIds.telegramSubmit)
        }
    }
}

private struct Working: View {
    let message: String
    init(_ message: String) { self.message = message }
    var body: some View {
        HStack(spacing: 12) { ProgressView().tint(.phoneAccent); Text(verbatim: message).foregroundStyle(Color.phoneMuted) }
    }
}

private struct Done: View {
    let viewModel: PhoneViewModel

    var body: some View {
        let telegram: TelegramController = viewModel.telegram
        Text(verbatim: L("ui_connected_as_767c2d", telegram.accountName)).font(PhoneFont.titleMedium).foregroundStyle(.white)
        if let created = telegram.channelCreated {
            Text(verbatim: L(created ? "ui_cablegram_created_the_private_channel_in_your_telegram_12ed98" : "ui_cablegram_is_using_the_channel_that_already_exists_in__8a8ad9", TelegramSession.libraryTitle)).foregroundStyle(Color.phoneMuted)
        }
        if let chatId = telegram.link?.chatId {
            Button { openInTelegram(chatId) { viewModel.status = L("telegram_library_install_telegram") } } label: { Text(key: "ui_open_it_in_telegram_b012d4") }.buttonStyle(PrimaryButtonStyle(minHeight: 52))
        }
        Text(verbatim: L("ui_telegram_will_show_a_new_login_alert_possibly_someone__6a8b3b") + L("ui_on_tap_yes_it_s_me_7d75cd", PhoneTelegram.deviceModel)).foregroundStyle(Color.phoneMuted)
        Text(verbatim: L("ui_in_telegram_share_or_forward_videos_into_the_private_c_ab9acc", TelegramSession.libraryTitle) + L("ui_they_appear_in_cablegram_and_play_on_your_tv_straight__d414d1")).foregroundStyle(Color.phoneMuted)
        Button { telegram.closeSheet() } label: { Text(key: "ui_done_11a676") }.buttonStyle(OutlinedButtonStyle())
    }
}

/// Optional guidance only: opening this page never starts a Telegram session.
struct TelegramAccountHelp: View {
    let onBack: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Button(action: onBack) { Text(key: "ui_back_76900f") }.buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.telegramAccountHelpBack)
                Text(key: "telegram_separate_title").font(PhoneFont.headlineSmall).foregroundStyle(.white)
                Text(key: "telegram_separate_intro").foregroundStyle(Color.phoneMuted)
                ForEach(Array([("telegram_separate_number_title", "telegram_separate_number_body"), ("telegram_separate_super_title", "telegram_separate_super_body"),
                               ("telegram_separate_temporary_title", "telegram_separate_temporary_body"), ("telegram_separate_security_title", "telegram_separate_security_body"),
                               ("ui_you_stay_in_control_faa171", "ui_you_ll_see_cablegram_in_telegram_settings_devices_and__e6a486")].enumerated()), id: \.offset) { _, pair in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(key: pair.0).font(PhoneFont.titleMedium).foregroundStyle(.white)
                        if pair.0 == "telegram_separate_temporary_title" { Text(key: "telegram_separate_temporary_price").font(.system(size: 12)).foregroundStyle(Color.phoneMuted) }
                        Text(key: pair.1).font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted)
                        if pair.0 == "telegram_separate_temporary_title" {
                            Link("Grizzly SMS", destination: URL(string: "https://grizzlysms.com/telegram?utm_source=cablegram.app")!).foregroundStyle(Color.phoneAccent)
                            Link("5SIM", destination: URL(string: "https://5sim.net/?utm_source=cablegram.app")!).foregroundStyle(Color.phoneAccent)
                            Text(key: "telegram_separate_provider_disclaimer").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                        }
                    }
                }
                Link(destination: URL(string: "https://www.super.ee/en/esim")!) { Text(key: "telegram_separate_super_link").foregroundStyle(Color.phoneAccent) }
                Link(destination: URL(string: "https://telegram.org/faq")!) { Text(key: "telegram_separate_telegram_link").foregroundStyle(Color.phoneAccent) }
            }
            .padding(20)
        }
        .accessibilityElement(children: .contain).accessibilityIdentifier(MaestroIds.telegramAccountHelp)
    }
}

/// Import → Telegram → Hidden videos: titles removed from the library, and deletions still waiting for Telegram.
struct HiddenVideosSheet: View {
    let telegram: TelegramController

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if let titles = telegram.hiddenTitles {
                        if titles.isEmpty {
                            Text(key: "ui_nothing_is_hidden_videos_you_remove_from_the_library_a_1d6be6").foregroundStyle(Color.phoneMuted)
                        }
                        ForEach(titles, id: \.stableSourceKey) { title in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: title.title).lineLimit(2).foregroundStyle(.white)
                                    if title.state == "deleting" {
                                        Text(verbatim: title.lastError.map { L("ui_deleting_from_telegram_retrying_b48c27", $0) } ?? L("ui_deleting_from_telegram_230981")).font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                                    }
                                }
                                Spacer()
                                if title.state == "hidden" { Button { telegram.restoreHidden(title) } label: { Text(key: "ui_restore_a76e13") }.buttonStyle(AccentTextButtonStyle()) }
                            }
                        }
                    } else {
                        Text(key: "ui_loading_ba3bbb").foregroundStyle(Color.phoneMuted)
                    }
                }
                .padding(20)
            }
            .background(Color.phoneBackground)
            .navigationTitle(L("ui_hidden_videos_e92f75"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("ui_close_7d9eb7")) { telegram.showHidden = false } } }
        }
        .preferredColorScheme(.dark)
    }
}

/// "Allow <TV> to use your Telegram?" when automatic approval is off (a card: there is no push while the app is closed).
struct TelegramApprovalCard: View {
    let work: TelegramTvWork

    var body: some View {
        if let request = work.pendingLogins.first {
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: L("ui_let_use_your_telegram_814f87", request.tvName)).font(PhoneFont.titleMedium).foregroundStyle(.white)
                Text(verbatim: L("ui_wants_to_play_videos_from_your_cablegram_library_chann_84f55e", request.tvName)).font(.system(size: 13)).foregroundStyle(Color.phoneMuted)
                HStack(spacing: 12) {
                    Button { work.allow(request) } label: { Text(key: "ui_allow_e213c1") }.buttonStyle(PrimaryButtonStyle(minHeight: 44)).accessibilityIdentifier("telegram_tv_allow")
                    Button { work.deny(request) } label: { Text(key: "ui_don_t_allow_9803bd") }.buttonStyle(OutlinedButtonStyle(minHeight: 44)).accessibilityIdentifier("telegram_tv_deny")
                }
            }
            .padding(14)
            .background(Color(hex: 0x1A1A1A).opacity(0.93), in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 16).padding(.vertical, 4)
            .accessibilityElement(children: .contain).accessibilityIdentifier("telegram_tv_login_card")
        }
    }
}

/// The phone keyboard for a TV's Telegram password: a masked field, only after the phone is unlocked. The password is sealed to the TV's
/// one-time key before it leaves the phone: the control plane relays bytes it cannot read, and nothing is stored or logged here.
struct TelegramPasswordSheet: View {
    let request: PendingTvPasswordRequest
    let work: TelegramTvWork
    let catalog: @Sendable () -> CatalogClient
    let token: () -> String?
    @State private var password = ""
    @State private var sending = false
    @State private var error: String?
    @State private var code: String?
    @State private var hint = ""
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(key: "ui_telegram_password_65d868").font(.system(size: 26, weight: .bold)).foregroundStyle(.white)
            Text(verbatim: L("ui_is_signing_in_to_your_telegram_type_your_two_step_veri_999fde", request.tvName)).font(.system(size: 15)).foregroundStyle(Color.phoneMuted)
            SecureField(L("ui_password_e7cf3e"), text: $password).textContentType(.password).autocorrectionDisabled().textInputAutocapitalization(.never)
                .focused($focused).disabled(sending).padding(14).background(Color.phoneBackground, in: RoundedRectangle(cornerRadius: 4))
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color.phoneOutline)).foregroundStyle(.white).submitLabel(.send).onSubmit(send)
            if let code {
                Text(key: "ui_check_code_9a5af9").font(.system(size: 13)).foregroundStyle(Color.phoneMuted)
                Text(verbatim: code).font(.system(size: 30, weight: .bold)).tracking(3).foregroundStyle(Color.phoneAccent)
                Text(verbatim: L("ui_shows_a_code_too_send_your_password_only_if_both_codes_12e5fa", request.tvName)).font(.system(size: 13)).foregroundStyle(Color.phoneMuted)
            }
            if !hint.isEmpty { Text(verbatim: L("ui_hint_3033f3", hint)).font(.system(size: 14)).foregroundStyle(Color.phoneMuted) }
            if let error { Text(verbatim: error).font(.system(size: 14)).foregroundStyle(Color.phoneError) }
            HStack(spacing: 12) {
                Button(action: send) { Text(verbatim: sending ? L("ui_sending_b8ed52") : L("ui_send_to_0ddfbe", request.tvName)) }.buttonStyle(PrimaryButtonStyle(minHeight: 48)).disabled(password.isEmpty || sending)
                Button { work.cancelPassword(request.requestId); dismiss() } label: { Text(key: "ui_cancel_19766e") }.buttonStyle(OutlinedButtonStyle(minHeight: 48))
            }
            Spacer()
        }
        .padding(24)
        .background(Color.phoneBackground.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .privacySensitive()
        .task {
            focused = true
            // The check code of the key this password will be sealed to; the TV shows the same code for its own key.
            let key = request.tvPublicKey
            code = await Task.detached { PasswordSeal.checkCode(tvPublicKey: key) }.value
            // The hint from this phone's own Telegram session; the TV may not know it before it signs in.
            let session = PhoneTelegram.session()
            if await session.first(timeout: 5, where: { if case .ready = $0 { true } else { false } }) != nil { hint = (try? await session.api.passwordState().hint) ?? "" }
        }
    }

    private func send() {
        guard !password.isEmpty, !sending, let token = token() else { return }
        sending = true
        error = nil
        let typed = password, request = request, client = catalog()
        Task {
            // The request the user typed for, taken from the server's current list and never from what was on screen.
            guard let current = await client.pendingTvPasswordRequests(token: token)?.first(where: { $0.requestId == request.requestId }) else {
                // Answered elsewhere, withdrawn or expired.
                error = L("ui_no_longer_needs_the_password_9cb267", request.tvName)
                sending = false
                return
            }
            var sent = false
            if let sealed = try? PasswordSeal.seal(tvPublicKey: current.tvPublicKey, requestId: request.requestId, password: typed) {
                sent = await client.sealTvPassword(token: token, requestId: request.requestId, sealed: sealed)
            }
            if sent {
                if let tv = current.tvDeviceId { RecentTvPasswords.sent(tv) }
                password = ""
                work.dismissPasswordRequest(request.requestId)
                dismiss()
            } else {
                error = L("ui_couldn_t_send_it_check_your_connection_and_try_again_b8b4ee")
                sending = false
            }
        }
    }
}
