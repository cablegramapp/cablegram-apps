import CablegramCore
import SwiftUI

/// The Remote tab (Android `RemoteScreen`): direction keys, playback, volume.
struct RemoteScreen: View {
    let viewModel: PhoneViewModel
    @State private var choosingTv = false
    @State private var volume = 0.5

    var body: some View {
        let tv = viewModel.tv!
        ScrollView {
            VStack(spacing: 24) {
                ScreenHeading(title: L("ui_remote_ffa98e"), subtitle: L("ui_a_little_closer_to_your_big_screen_57b69f"))
                if !tv.paired {
                    EmptyState(symbol: "tv", title: L("ui_your_tv_within_reach_2795e6"), message: L("ui_connect_a_tv_to_browse_play_and_pause_from_your_phone_e98866"),
                               action: L("ui_connect_a_tv_0ff3f5")) { tv.startAddTv() }
                } else {
                    Menu {
                        ForEach(tv.tvs, id: \.pin) { item in Button(item.name) { tv.selectTv(item) } }
                        Button { tv.startAddTv() } label: { Text(key: "ui_connect_another_tv_c29c2b") }
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "tv").foregroundStyle(Color.phoneAccent)
                            VStack(alignment: .leading) {
                                Text(key: "ui_controlling_2084c5").font(.system(size: 11)).foregroundStyle(Color.phoneMuted)
                                Text(verbatim: tv.tvName).font(PhoneFont.titleMedium).foregroundStyle(.white)
                            }
                            Spacer()
                            Image(systemName: "chevron.down").foregroundStyle(Color.phoneMuted).accessibilityLabel(L("ui_choose_tv_07f8b4"))
                        }
                        .padding(18).background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 18))
                    }
                    pad(tv)
                    SectionCard(title: L("ui_playback_2db1db")) {
                        if let playing = tv.nowPlaying { Text(verbatim: playing.title).font(PhoneFont.titleMedium).foregroundStyle(.white) }
                        // Left-to-right in every language, like a physical remote.
                        HStack {
                            key("backward.fill", L("ui_back_seconds_fa7e12", "\(remoteSeekSeconds)")) { tv.skipSeconds(-remoteSeekSeconds) }
                            Spacer()
                            Button { tv.togglePlayPause() } label: {
                                Image(systemName: tv.paused ? "play.fill" : "pause.fill").font(.system(size: 28)).foregroundStyle(.black)
                                    .frame(width: 64, height: 64).background(Color.phoneAccent, in: Circle())
                            }
                            .accessibilityLabel(L(tv.paused ? "ui_resume_playback_b19825" : "ui_pause_playback_65a4a8"))
                            Spacer()
                            key("forward.fill", L("ui_forward_seconds_55855b", "\(remoteSeekSeconds)")) { tv.skipSeconds(remoteSeekSeconds) }
                        }
                        .environment(\.layoutDirection, .leftToRight)
                        Text(L("ui_seek_seconds_at_a_time_43ffed", "\(remoteSeekSeconds)")).font(.system(size: 12)).foregroundStyle(Color.phoneMuted).frame(maxWidth: .infinity)
                        Button { tv.remoteStop() } label: { HStack { Image(systemName: "stop.fill"); Text(key: "ui_stop_playback_19b81a") } }.buttonStyle(OutlinedButtonStyle())
                    }
                    SectionCard(title: L("ui_set_tv_volume_43ad09")) {
                        Slider(value: $volume, in: 0...1) { editing in if !editing { tv.remoteVolume(Int(volume * 100)) } }.tint(.phoneAccent)
                        Text(L("ui_drag_to_set_the_volume_to_448bd2", "\(Int(volume * 100))")).font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                    }
                    if let status = tv.remoteStatus { StatusNote(message: status) }
                    Text(key: "ui_commands_use_your_household_connection_keep_the_tv_con_b044c4").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                }
            }
            .padding(20)
        }
        .phoneScreenBackground()
    }

    private func pad(_ tv: TvController) -> some View {
        VStack(spacing: 4) {
            key("chevron.up", L("ui_navigate_up_5fa84b")) { tv.remoteNavigate("up") }
            HStack(spacing: 12) {
                key("chevron.left", L("ui_navigate_left_ceb18f")) { tv.remoteNavigate("left") }
                Button { tv.remoteSelect() } label: { Text(key: "ui_ok_565339").font(PhoneFont.titleMedium).foregroundStyle(.black).frame(width: 68, height: 68).background(Color.phoneAccent, in: Circle()) }
                key("chevron.right", L("ui_navigate_right_668ca1")) { tv.remoteNavigate("right") }
            }
            .environment(\.layoutDirection, .leftToRight)
            key("chevron.down", L("ui_navigate_down_b9d466")) { tv.remoteNavigate("down") }
        }
        .padding(16).background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 120))
    }

    private func key(_ symbol: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).font(.system(size: 24)).foregroundStyle(.white).frame(width: 56, height: 56) }.accessibilityLabel(label)
    }
}

/// The mini-player above the tab bar while something plays on the TV (Android `RemoteBar`).
struct RemoteBar: View {
    let viewModel: PhoneViewModel

    var body: some View {
        let tv = viewModel.tv!
        if viewModel.legacyRemote, let lines = miniPlayerLines(playing: tv.nowPlaying?.title, starting: tv.startingTitle, tvName: tv.tvName, status: tv.remoteStatus) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: lines.title).font(PhoneFont.titleMedium).foregroundStyle(.white).lineLimit(1)
                    Text(verbatim: lines.caption).font(.system(size: 12)).foregroundStyle(Color.phoneMuted).lineLimit(1)
                }
                Spacer()
                if lines.canToggle {
                    Button { tv.togglePlayPause() } label: { Image(systemName: tv.paused ? "play.fill" : "pause.fill").foregroundStyle(Color.phoneAccent).frame(width: 44, height: 44) }
                        .accessibilityLabel(L(tv.paused ? "ui_resume_playback_b19825" : "ui_pause_playback_65a4a8"))
                    Button { tv.remoteStop() } label: { Image(systemName: "stop.fill").foregroundStyle(Color.phoneAccent).frame(width: 44, height: 44) }
                        .accessibilityLabel(L("ui_stop_playback_19b81a"))
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
            .background(Color(hex: 0x1A1A1A).opacity(0.93), in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 16).padding(.vertical, 4)
            .accessibilityIdentifier("remote_bar")
        }
    }
}

/// A TV waits for a private title or a profile: in-app card mirroring the notification (Android `ApprovalCard`).
struct ApprovalCard: View {
    let viewModel: PhoneViewModel

    var body: some View {
        let tv = viewModel.tv!
        if let approval = tv.pendingApprovals.first {
            let profile = approval.kind == .profile
            VStack(alignment: .leading, spacing: 8) {
                Text(tv.pendingApprovals.count > 1 ? L("ui_tv_approval_requests_f2f42a", "\(tv.pendingApprovals.count)")
                     : profile ? L("ui_wants_to_open_a_profile_56a29f", (approval.tvName ?? "").isEmpty ? L("ui_a_tv_bf791d") : approval.tvName!) : L("ui_tv_playback_approval_4438c0"))
                    .font(PhoneFont.labelMedium).foregroundStyle(Color.phoneAccent)
                Text((approval.title ?? "").isEmpty ? L(profile ? "ui_a_profile_c3d18a" : "ui_a_private_title_wants_to_play_on_the_tv_840767") : approval.title!)
                    .font(PhoneFont.titleMedium).foregroundStyle(.white)
                if profile && approval.sharedTv {
                    Text(key: "ui_other_accounts_use_this_tv_too_allow_it_only_if_you_ar_7a66a8").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                }
                HStack(spacing: 10) {
                    Button { tv.respondToApproval(approval, approve: true, cancelNotification: Approvals.cancel) } label: {
                        Text(key: profile ? "ui_allow_e213c1" : "ui_allow_once_168511").padding(.horizontal, 12)
                    }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 44)).fixedSize().accessibilityIdentifier(MaestroIds.approvalAllow)
                    Button { tv.respondToApproval(approval, approve: false, cancelNotification: Approvals.cancel) } label: { Text(key: "ui_not_now_a0e63d").padding(.horizontal, 12) }
                        .buttonStyle(OutlinedButtonStyle(minHeight: 44, color: .white)).fixedSize().accessibilityIdentifier(MaestroIds.approvalDeny)
                }
            }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.phoneAccent, lineWidth: 2))
            .padding(16).accessibilityElement(children: .contain).accessibilityIdentifier(MaestroIds.approvalCard)
        }
    }
}
