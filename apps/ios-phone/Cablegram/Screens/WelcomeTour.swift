import CablegramCore
import SwiftUI

private struct TourPage {
    let step: TourStep
    let symbol: String
    let title: String
    let body: String
    let points: [String]
    /// Where the setup lives, as the path a person taps through.
    var `where`: String?
    var action: String?
    var recommended = false
}

private func tourPages() -> [TourPage] {
    [
        TourPage(step: .welcome, symbol: "film", title: L("ui_your_videos_your_big_screen_b7a07b"),
                 body: L("ui_cablegram_turns_your_phone_into_the_library_and_remote_68c907"),
                 points: [L("ui_your_phone_keeps_the_library_and_does_the_setup_e69ab1"), L("ui_your_tv_plays_your_videos_759124"),
                          L("ui_your_videos_stay_in_storage_you_own_cablegram_doesn_t__0ccf72")]),
        TourPage(step: .tv, symbol: "tv", title: L("ui_connect_your_tv_4ad8a8"),
                 body: L("ui_install_cablegram_on_your_android_tv_or_google_tv_and__d27b55"),
                 points: [L("ui_pick_a_title_on_your_phone_and_it_plays_on_the_tv_8e206a"), L("ui_add_more_tvs_whenever_you_like_46dfbc"),
                          L("ui_keep_your_phone_and_tv_on_the_same_wi_fi_to_stream_fro_e080cd")],
                 where: L("ui_settings_your_tvs_connect_a_tv_231c90"), action: L("ui_connect_a_tv_0ff3f5")),
        TourPage(step: .telegram, symbol: "paperplane", title: L("ui_watch_without_your_phone_7d2fb5"),
                 body: L("ui_connect_your_own_telegram_account_a_private_cablegram__03df9a"),
                 points: [L("ui_your_tv_plays_straight_from_telegram_so_your_phone_can_a79536"), L("ui_share_or_forward_a_video_into_the_private_channel_and__97bd0c"),
                          L("ui_cablegram_only_uses_its_own_private_channel_never_your_dd2890")],
                 where: L("ui_import_connected_storage_telegram_f2fb3f"), action: L("ui_connect_telegram_00c799"), recommended: true),
        TourPage(step: .drive, symbol: "cloud", title: L("ui_keep_a_copy_in_google_drive_8b9c00"),
                 body: L("ui_save_a_cloud_copy_of_any_title_to_your_own_google_driv_3aac39"),
                 points: [L("ui_sign_in_with_google_it_takes_about_a_minute_277553"), L("ui_cablegram_can_only_reach_the_files_it_creates_never_th_69bc5f"),
                          L("ui_have_your_own_bucket_cloudflare_r2_works_too_431345")],
                 where: L("ui_settings_storage_manage_cloud_storage_or_save_to_cloud_80ea66"), action: L("ui_connect_google_drive_4406cf")),
        TourPage(step: .subtitles, symbol: "captions.bubble", title: L("ui_subtitles_in_your_language_6e5267"),
                 body: L("ui_open_a_title_and_tap_find_subtitles_cablegram_finds_su_892bc5"),
                 points: [L("ui_uses_your_own_free_account_at_subdl_or_opensubtitles_o_35b12b"), L("ui_searches_go_straight_from_your_phone_to_that_site_f137b0"),
                          L("ui_subtitle_files_saved_next_to_your_video_are_picked_up__665d7f")],
                 where: L("ui_settings_subtitle_accounts_then_a_title_find_subtitles_60e1db"), action: L("ui_add_a_subtitle_account_ba3be8")),
        TourPage(step: .ready, symbol: "play.circle", title: L("ui_you_re_all_set_f08920"),
                 body: L("ui_start_by_importing_a_few_videos_from_your_phone_you_ca_1fb185"), points: [],
                 action: L("ui_import_videos_fec728")),
    ]
}

/// First-run introduction to the phone app: what each feature is for, why, and where to set it up later.
struct WelcomeTour: View {
    let viewModel: PhoneViewModel
    @State private var page = 0
    private let pages = tourPages()

    /// Already set up: the page shows that instead of its setup button. The TV and cloud pages are wired up; Telegram and subtitles follow with phases 7 and 8.
    private func connectedLabel(_ step: TourStep) -> String? {
        switch step {
        case .tv: viewModel.tv.paired ? L("ui_tv_connected_5710cb") : nil
        case .drive: viewModel.cloud.cloudConnected ? L("ui_cloud_storage_connected_fe282c") : nil
        default: nil   // Telegram and subtitles arrive with phases 7 and 8
        }
    }

    var body: some View {
        let last = page == pages.count - 1
        VStack(spacing: 0) {
            HStack {
                BrandMark(compact: true)
                Spacer()
                if !last {
                    Button { viewModel.closeWelcomeTour() } label: { Text(key: "ui_skip_28d035") }
                        .buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.tourSkip)
                }
            }
            .padding(.leading, 24).padding(.trailing, 8).padding(.top, 8)

            TabView(selection: $page) {
                ForEach(Array(pages.enumerated()), id: \.offset) { index, item in
                    TourPageContent(page: item, done: connectedLabel(item.step)).tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            VStack(spacing: 12) {
                PageDots(current: page, count: pages.count)
                let current = pages[page]
                if last {
                    Button { viewModel.startFromTour(.ready) } label: { Text(current.action ?? "") }
                        .buttonStyle(PrimaryButtonStyle()).accessibilityIdentifier(MaestroIds.tourAction)
                    Button { viewModel.closeWelcomeTour() } label: { Text(key: "ui_go_to_my_library_f23fc1") }
                        .buttonStyle(OutlinedButtonStyle(minHeight: 48))
                } else {
                    if let action = current.action, connectedLabel(current.step) == nil {
                        Button { viewModel.startFromTour(current.step) } label: { Text(L("ui_now_cecd79", action)) }
                            .buttonStyle(OutlinedButtonStyle(minHeight: 48)).accessibilityIdentifier(MaestroIds.tourAction)
                    }
                    Button { withAnimation { page += 1 } } label: {
                        Text(key: page == 0 ? "ui_show_me_around_80afe5" : "ui_next_1ff57a")
                    }
                    .buttonStyle(PrimaryButtonStyle()).accessibilityIdentifier(MaestroIds.tourNext)
                }
            }
            .frame(maxWidth: 480)
            .padding(.horizontal, 24).padding(.vertical, 16)
        }
        .phoneScreenBackground()
    }
}

private struct TourPageContent: View {
    let page: TourPage
    let done: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 14) {
                    Image(systemName: page.symbol).font(.system(size: 36)).foregroundStyle(Color.phoneAccent)
                        .padding(20).background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 24))
                    if page.recommended {
                        Text(key: "ui_recommended_d70604").font(PhoneFont.labelMedium).foregroundStyle(Color.phoneAccent)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(Color.phoneAccentContainer, in: Capsule())
                    }
                }
                ScreenHeading(title: page.title)
                Text(page.body).font(PhoneFont.bodyLarge).foregroundStyle(Color.phoneMuted)
                if !page.points.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(page.points, id: \.self) { point in
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: "checkmark.circle.fill").font(.system(size: 18)).foregroundStyle(Color.phoneSuccess).padding(.top, 2)
                                Text(point).font(PhoneFont.bodyMedium).foregroundStyle(.white)
                            }
                        }
                    }
                }
                if let place = page.where {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(key: "ui_find_it_later_bcf629").font(PhoneFont.labelMedium).foregroundStyle(Color.phoneMuted)
                        Text(place).font(PhoneFont.bodyMedium).foregroundStyle(.white)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(16)
                    .background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 16))
                }
                if let done {
                    HStack(spacing: 8) {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.phoneSuccess)
                        Text(done).font(PhoneFont.labelLarge).foregroundStyle(Color.phoneSuccess)
                    }
                }
            }
            .frame(maxWidth: 480, alignment: .leading)
            .padding(.horizontal, 24).padding(.vertical, 20)
            .frame(maxWidth: .infinity, alignment: .top)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("\(MaestroIds.tourPage)_\(page.step.rawValue)")
        }
    }
}

private struct PageDots: View {
    let current: Int
    let count: Int

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<count, id: \.self) { index in
                Capsule().fill(index == current ? Color.phoneAccent : Color.phoneOutline)
                    .frame(width: index == current ? 20 : 6, height: 6)
            }
        }
        .animation(.default, value: current)
        .accessibilityElement()
        .accessibilityLabel(L("ui_page_of_8b9ffa", "\(current + 1)", "\(count)"))
    }
}
