import CablegramCore
import SwiftUI

/// The full-screen cloud sheets of Android `CloudFlow`: set up, confirm, manage, connect R2, free up space.
struct CloudFlow: View {
    let viewModel: PhoneViewModel

    var body: some View {
        let cloud = viewModel.cloud!
        Group {
            switch cloud.cloudSheet {
            case .none: EmptyView()
            case .setup: SaveSetupSheet(viewModel: viewModel)
            case .confirm: SaveConfirmSheet(viewModel: viewModel)
            case .oversize: SaveOversizeSheet(viewModel: viewModel)
            case .manage: ManageCloudSheet(viewModel: viewModel)
            case .connectR2: ConnectR2Sheet(viewModel: viewModel)
            case .freeUp: FreeUpSheet(viewModel: viewModel)
            case .freeUpConfirm: FreeUpConfirmSheet(viewModel: viewModel)
            }
        }
    }
}

private struct SheetScaffold<Content: View>: View {
    let title: String
    let onClose: () -> Void
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Button(action: onClose) { Text(key: "ui_close_7d9eb7") }.buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.cloudClose)
                Text(title).font(PhoneFont.headlineSmall).foregroundStyle(.white)
                content
            }
            .padding(20)
        }
        .phoneScreenBackground()
        .transition(.move(edge: .bottom))
    }
}

private struct SaveSetupSheet: View {
    let viewModel: PhoneViewModel

    var body: some View {
        let cloud = viewModel.cloud!
        let offered = providerRows(cloud.storage, yourOwnStorage: L("ui_your_own_storage_cc541c"))
        // Saves go only to the household's own storage (CAB-38): this sheet leads to connecting it.
        SheetScaffold(title: L("ui_save_to_cloud_0fd280"), onClose: cloud.dismissCloudSheet) {
            Text(key: "ui_keep_your_videos_available_even_when_your_phone_is_off_b72efb").foregroundStyle(Color.phoneMuted)
            Text(offered.isEmpty ? L("ui_your_own_storage_isn_t_available_on_this_server_d34244") : offered.map(\.name).joined(separator: " · "))
                .font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            if !offered.isEmpty {
                Button { cloud.openOwnCloudSetup() } label: { Text(key: "ui_connect_your_storage_04561b") }
                    .buttonStyle(PrimaryButtonStyle()).accessibilityIdentifier(MaestroIds.cloudContinue)
            }
        }
    }
}

private struct SaveOversizeSheet: View {
    let viewModel: PhoneViewModel

    var body: some View {
        let cloud = viewModel.cloud!
        if let item = cloud.saveTarget {
            SheetScaffold(title: L("ui_save_to_cloud_0fd280"), onClose: cloud.dismissCloudSheet) {
                Text(verbatim: item.title).font(PhoneFont.titleLarge).foregroundStyle(.white)
                Text(item.fileSizeBytes.map(formatBytes) ?? "").foregroundStyle(Color.phoneMuted)
                Text(key: "ui_this_file_is_larger_than_your_available_storage_e6fe5f").foregroundStyle(Color(hex: 0xFFB74D))
                Text(key: "ui_cloud_storage_9644ab").foregroundStyle(.white)
                Text(L("ui_available_9ffcbc", formatBytes(cloud.cloudAvailable))).foregroundStyle(Color.phoneMuted)
                Button { cloud.chooseAnotherStorage() } label: { Text(key: "ui_choose_another_storage_10ef3b") }.buttonStyle(OutlinedButtonStyle())
            }
        }
    }
}

private struct SaveConfirmSheet: View {
    let viewModel: PhoneViewModel

    var body: some View {
        @Bindable var cloud = viewModel.cloud!
        if let item = cloud.saveTarget {
            let destination = cloud.saveDestinationName
            SheetScaffold(title: L("ui_save_to_48c64f", destination), onClose: cloud.dismissCloudSheet) {
                Text(verbatim: item.title).font(PhoneFont.titleLarge).foregroundStyle(.white)
                Text(item.fileSizeBytes.map(formatBytes) ?? "").foregroundStyle(Color.phoneMuted)
                Text(verbatim: destination).foregroundStyle(.white)
                Text(cloud.text(destinationSpaceLine(cloud.storage, cablegramAvailable: cloud.cloudAvailable))).foregroundStyle(Color.phoneMuted)
                Text(key: "ui_after_saving_this_title_stays_on_your_phone_free_up_sp_d0fcae").foregroundStyle(Color.phoneMuted)
                Text(key: "ui_keep_on_phone_cff4f5").foregroundStyle(.white)
                Text(key: "ui_remove_from_phone_b2bf2b").foregroundStyle(Color.phoneMuted)
                Toggle(isOn: $cloud.wifiOnlyTransfers) { Text(key: "ui_wi_fi_only_ca99bb").foregroundStyle(.white) }.tint(.phoneAccent)
                Button { cloud.confirmSaveToCloud() } label: { Text(L("ui_save_to_48c64f", destination)) }
                    .buttonStyle(PrimaryButtonStyle()).accessibilityIdentifier(MaestroIds.cloudConfirm)
            }
        }
    }
}

private struct ProviderRowView: View {
    let row: ProviderRow
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: row.name).font(.system(size: 14, weight: .medium)).foregroundStyle(enabled ? .white : Color.phoneMuted)
                Text("\(L(row.summaryKey)) · \(L(row.durationKey))").font(.system(size: 13)).foregroundStyle(Color.phoneMuted)
                if let note = row.note {
                    Text(fillPlaceholders(L(note.key), note.args)).font(.system(size: 13)).foregroundStyle(row.state == .needsSignIn ? Color(hex: 0xFFB74D) : Color.phoneMuted)
                }
                if row.state == .needsSignIn { Text(key: "ui_sign_in_again_51fbe1").foregroundStyle(Color.phoneAccent) }
            }
            .frame(maxWidth: .infinity, alignment: .leading).padding(14)
            .background(Color(hex: 0x1A1A1A), in: RoundedRectangle(cornerRadius: 12)).contentShape(Rectangle())
        }
        .buttonStyle(.plain).disabled(!enabled)
        .accessibilityIdentifier("provider_\(row.id)")
    }
}

private struct ManageCloudSheet: View {
    let viewModel: PhoneViewModel

    var body: some View {
        @Bindable var cloud = viewModel.cloud!
        let own = L("ui_your_own_storage_cc541c")
        let rows = providerRows(cloud.storage, yourOwnStorage: own).filter { $0.state != .connected }
        let connection = cloud.storage?.connection.flatMap { $0.status == "active" ? $0 : nil }
        SheetScaffold(title: L("ui_cloud_storage_2ec3eb"), onClose: cloud.dismissCloudSheet) {
            Text(key: "ui_your_own_storage_6765b7").font(PhoneFont.titleMedium).foregroundStyle(.white).padding(.top, 8)
            if let connection {
                VStack(alignment: .leading, spacing: 6) {
                    Text(verbatim: providerName(connection.provider, status: cloud.storage, yourOwnStorage: own)).font(.system(size: 14, weight: .medium)).foregroundStyle(.white)
                    Text(connectedSummary(connection, status: cloud.storage, yourOwnStorage: own) { L("ui_free_97e1da", $0) }).foregroundStyle(Color(hex: 0x79D6B0))
                    Button { cloud.disconnectStorage() } label: { Text(L("ui_disconnect_7d073a", providerName(connection.provider, status: cloud.storage, yourOwnStorage: own))) }
                        .buttonStyle(OutlinedButtonStyle()).disabled(cloud.working)
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(14)
                .background(Color(hex: 0x1A1A1A), in: RoundedRectangle(cornerRadius: 12))
            } else {
                Text(key: "ui_keep_your_videos_in_storage_you_own_they_go_straight_t_697721").foregroundStyle(Color.phoneMuted)
            }
            if rows.isEmpty && connection == nil { Text(key: "ui_not_available_on_this_server_210147").foregroundStyle(Color.phoneMuted) }
            ForEach(rows, id: \.id) { row in
                ProviderRowView(row: row, enabled: row.state != .locked && !cloud.working) {
                    if row.id == providerGoogleDrive { cloud.googleSignInNoteOpen = true } else if row.id == providerCloudflareR2 { cloud.beginConnectR2() }
                }
            }
            if let error = cloud.googleConnectError { Text(error).foregroundStyle(Color(hex: 0xFFB74D)) }
        }
        .alert(L("ui_one_box_to_tick_0f7e37"), isPresented: $cloud.googleSignInNoteOpen) {
            Button(L("ui_cancel_19766e"), role: .cancel) {}
            Button(L("ui_open_google_f8b2a8")) { cloud.connectGoogle() }
        } message: { Text(googleSignInPreparation().map { fillPlaceholders(L($0.key), $0.args) }.joined()) }
    }
}

private let cloudflareSignUp = "https://dash.cloudflare.com/sign-up"
private let cloudflareR2 = "https://dash.cloudflare.com/?to=/:account/r2/overview"

private struct GuideStep<Extra: View>: View {
    let number: Int
    let title: String
    let lines: [String]
    var openLabel: String?
    var openUrl: String?
    @ViewBuilder var extra: Extra
    @Environment(\.openURL) private var openURL

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)").font(.system(size: 14)).foregroundStyle(.black).frame(width: 28, height: 28).background(Color.phoneAccent, in: Circle())
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.system(size: 14, weight: .medium)).foregroundStyle(.white)
                ForEach(lines, id: \.self) { Text($0).font(.system(size: 14)).foregroundStyle(Color.phoneMuted) }
                if let openLabel, let openUrl {
                    Button { if let url = URL(string: openUrl) { openURL(url) } } label: { Text(openLabel).padding(.horizontal, 12) }
                        .buttonStyle(OutlinedButtonStyle(minHeight: 40)).fixedSize()
                }
                extra
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(14)
        .background(Color(hex: 0x1A1A1A), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// Cloudflare R2: the owner makes a bucket and an Object Read & Write token once, and enters its keys here. Each step says
/// what to tap in Cloudflare, with a button that opens the page, and the form checks every field as it is typed.
private struct ConnectR2Sheet: View {
    let viewModel: PhoneViewModel
    @State private var accountId = ""
    @State private var bucket = ""
    @State private var keyId = ""
    @State private var secret = ""

    var body: some View {
        let cloud = viewModel.cloud!
        let problems = [r2AccountIdProblem(accountId), r2BucketProblem(bucket), r2KeyIdProblem(keyId), r2SecretProblem(secret)]
        let complete = [accountId, bucket, keyId, secret].allSatisfy { !$0.trimmingCharacters(in: .whitespaces).isEmpty } && problems.allSatisfy { $0 == nil }
        SheetScaffold(title: L("ui_connect_your_own_storage_10a917"), onClose: cloud.dismissCloudSheet) {
            Text(key: "ui_keep_your_videos_in_your_own_cloudflare_r2_storage_the_16689a").foregroundStyle(Color.phoneMuted)
            GuideStep(number: 1, title: L("ui_create_a_free_cloudflare_account_9d8fd1"), lines: [L("ui_already_have_one_skip_to_step_2_55c7ad")],
                      openLabel: L("ui_open_cloudflare_sign_up_53c22b"), openUrl: cloudflareSignUp) {}
            GuideStep(number: 2, title: L("ui_turn_on_r2_and_make_a_bucket_2479a0"),
                      lines: [L("ui_open_r2_cloudflare_asks_you_to_subscribe_to_r2_once_it_10d8f5"), L("ui_then_tap_create_bucket_name_it_with_lowercase_letters__10114b")],
                      openLabel: L("ui_open_r2_41969b"), openUrl: cloudflareR2) {}
            GuideStep(number: 3, title: L("ui_create_an_access_token_ed7ea7"),
                      lines: [L("ui_on_the_r2_page_find_account_details_and_tap_manage_nex_31acb8"), L("ui_permission_object_read_write_apply_it_to_a_specific_bu_ac52d4"),
                              L("ui_cloudflare_now_shows_an_access_key_id_a_secret_access__5639c8")],
                      openLabel: L("ui_open_r2_41969b"), openUrl: cloudflareR2) {}
            GuideStep(number: 4, title: L("ui_enter_the_details_0aa04d"), lines: [L("ui_copy_each_value_from_the_page_cloudflare_showed_you_sp_bb099b")]) {
                VStack(spacing: 8) {
                    PhoneTextField(label: L("ui_endpoint_or_account_id_dce8d2"), text: $accountId, supporting: problems[0] ?? L("ui_paste_the_endpoint_address_cloudflare_showed_or_just_t_6ee22f"), isError: problems[0] != nil, capitalizes: false)
                    PhoneTextField(label: L("ui_bucket_name_800f2e"), text: $bucket, supporting: problems[1] ?? L("ui_the_name_you_gave_the_bucket_2ae9c0"), isError: problems[1] != nil, capitalizes: false)
                    PhoneTextField(label: L("ui_access_key_id_8761c0"), text: $keyId, supporting: problems[2] ?? L("ui_shown_when_you_created_the_token_e2211a"), isError: problems[2] != nil, capitalizes: false)
                    SecretField(label: L("ui_secret_access_key_d2aeac"), text: $secret, supporting: problems[3] ?? L("ui_shown_once_under_the_access_key_id_27f4b9"), isError: problems[3] != nil)
                }
            }
            if let error = cloud.r2ConnectError { Text(error).foregroundStyle(Color(hex: 0xFFB74D)) }
            Button {
                cloud.connectR2Storage(accountId: accountId, bucket: bucket, accessKeyId: keyId, secret: secret)
                // The secret does not stay in the form once it has been sent.
                secret = ""
            } label: { Text(key: cloud.working ? "ui_checking_ec963f" : "ui_connect_1a2303") }
                .buttonStyle(PrimaryButtonStyle()).disabled(cloud.working || !complete)
            Text(key: "ui_what_it_costs_r2_includes_10_gb_free_after_that_cloudf_499770").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
            Text(key: "ui_cablegram_keeps_the_keys_encrypted_and_uses_them_only__5e4393").font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
        }
    }
}

private struct SecretField: View {
    let label: String
    @Binding var text: String
    var supporting: String
    var isError: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                SecureField("", text: $text).textContentType(.password).autocorrectionDisabled().textInputAutocapitalization(.never).foregroundStyle(.white)
            }
            .padding(.horizontal, 14).padding(.vertical, 8).frame(minHeight: 58, alignment: .leading)
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(isError ? Color.phoneError : Color.phoneOutline, lineWidth: 1))
            Text(supporting).font(.system(size: 12)).foregroundStyle(isError ? Color.phoneError : Color.phoneMuted).padding(.horizontal, 14)
        }
    }
}

private struct FreeUpSheet: View {
    let viewModel: PhoneViewModel

    var body: some View {
        let cloud = viewModel.cloud!
        let candidates = freeUpCandidates(viewModel.items)
        SheetScaffold(title: L("ui_free_up_space_5912ec"), onClose: cloud.dismissCloudSheet) {
            if candidates.isEmpty {
                Text(key: "ui_save_titles_to_the_cloud_first_then_you_can_remove_the_a6c5d3").foregroundStyle(Color.phoneMuted)
            } else {
                Text(L("ui_free_up_8fe74c", formatBytes(freeUpBytes(viewModel.items)))).font(PhoneFont.titleLarge).foregroundStyle(.white)
                Text(key: "ui_these_videos_are_already_safely_stored_in_the_cloud_8fce14").foregroundStyle(Color.phoneMuted)
                ForEach(candidates) { item in
                    Button { cloud.askFreeUp(item) } label: {
                        VStack(alignment: .leading) {
                            Text(verbatim: item.title).foregroundStyle(.white)
                            Text(L("ui_message_20f3b4", item.fileSizeBytes.map(formatBytes) ?? "")).foregroundStyle(Color.phoneMuted)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 8)
                    }
                    .buttonStyle(.plain)
                }
                Button { candidates.first.map(cloud.askFreeUp) } label: { Text(key: "ui_review_free_space_0a228f") }.buttonStyle(PrimaryButtonStyle())
            }
        }
    }
}

private struct FreeUpConfirmSheet: View {
    let viewModel: PhoneViewModel

    var body: some View {
        let cloud = viewModel.cloud!
        if let item = cloud.freeUpTarget {
            SheetScaffold(title: item.title, onClose: cloud.dismissCloudSheet) {
                Text(L("ui_safely_stored_in_f9eee7", cloud.saveDestinationName)).foregroundStyle(Color(hex: 0x79D6B0))
                Text(key: "ui_remove_local_copy_67b13a").font(PhoneFont.titleMedium).foregroundStyle(.white)
                Text(L("ui_your_copy_in_will_remain_available_for_tv_playback_bb5796", cloud.saveDestinationName)).foregroundStyle(Color.phoneMuted)
                HStack(spacing: 8) {
                    Button { cloud.keepOnPhone(item) } label: { Text(key: "ui_keep_on_phone_bded0c") }.buttonStyle(OutlinedButtonStyle())
                    Button { cloud.confirmFreeUp(item) } label: { Text(key: "ui_remove_c3812f") }.buttonStyle(PrimaryButtonStyle(minHeight: 48))
                }
            }
        }
    }
}

/// "Remove the cloud copy?" (Android `RemoveCloudCopyDialog`).
struct RemoveCloudCopyDialog: ViewModifier {
    let viewModel: PhoneViewModel

    func body(content: Content) -> some View {
        let cloud = viewModel.cloud!
        let item = cloud.removeCloudCopyTarget
        content.alert(L("ui_remove_the_cloud_copy_bd936f"), isPresented: Binding(get: { item != nil }, set: { if !$0 { cloud.removeCloudCopyTarget = nil } })) {
            Button(L("ui_keep_it_fdce5d"), role: .cancel) { cloud.removeCloudCopyTarget = nil }
            Button(L("ui_remove_copy_63d1ae"), role: .destructive) { if let item { cloud.removeCloudCopy(item) } }
        } message: { Text(item.map { removeCopyQuestion(title: $0.title, destination: cloud.saveDestinationName) } ?? "") }
    }
}
