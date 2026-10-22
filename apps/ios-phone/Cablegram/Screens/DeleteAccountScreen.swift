import CablegramCore
import SwiftUI

/// Deleting the account, step by step: this screen says what goes, mails a code to the account's address (accounts
/// have no password, spec 007), and a second dialog asks for one more explicit tap before anything is sent. The code
/// is kept only in memory, never on disk. Port of `DeleteAccountScreen.kt`.
struct DeleteAccountScreen: View {
    /// The public page that deletes an account without the app; also the link given to the stores.
    static let webURL = "https://api.cablegram.app/delete-account"

    let viewModel: PhoneViewModel
    @State private var code = ""
    @State private var confirming = false

    var body: some View {
        let deletion = viewModel.accountDeletion!
        let sentTo = viewModel.deletionCodeSentTo
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                ScreenHeading(title: L("delete_account_title"), subtitle: L("delete_account_intro"))
                SectionCard(title: L("delete_account_removed_heading")) {
                    Bullet(key: "delete_account_removed_account")
                    Bullet(key: "delete_account_removed_household")
                    Bullet(key: "delete_account_removed_library")
                    Bullet(key: "delete_account_removed_telegram")
                    Bullet(key: "delete_account_removed_files")
                }
                SectionCard(title: L("delete_account_kept_heading")) {
                    Bullet(key: "delete_account_kept_telegram")
                    Bullet(key: "delete_account_kept_phone")
                    // delete_account_tv_sessions joins when a Telegram link exists (phase 7).
                }
                if let sentTo {
                    PhoneTextField(label: L("delete_account_code_label"), text: $code, supporting: L("delete_account_code_hint", sentTo),
                                   keyboard: .numberPad, contentType: .oneTimeCode, isError: deletion.error == .wrongCode, leftToRight: true,
                                   id: "delete_account_code")
                        .disabled(deletion.running)
                        .onChange(of: code) { _, new in code = String(new.filter(\.isNumber).prefix(6)) }
                    Button { viewModel.requestDeletionCode() } label: { Text(key: "delete_account_send_new_code").frame(maxWidth: .infinity) }
                        .buttonStyle(AccentTextButtonStyle()).disabled(viewModel.busy || deletion.running)
                    if viewModel.deletionCodeFailed { StatusNote(message: L("delete_account_code_failed")) }
                } else {
                    Text(key: "delete_account_send_code_hint").font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted)
                    Button { viewModel.requestDeletionCode() } label: { Text(key: "delete_account_send_code") }
                        .buttonStyle(OutlinedButtonStyle(minHeight: 52))
                        .disabled(viewModel.busy || deletion.running)
                        .accessibilityIdentifier("delete_account_send_code")
                    if viewModel.deletionCodeFailed { StatusNote(message: L("delete_account_code_failed")) }
                }
                if let error = deletion.error { StatusNote(message: L(error.messageKey)) }
                Button { confirming = true } label: {
                    HStack(spacing: 10) {
                        if deletion.running { ProgressView().controlSize(.small) }
                        Text(key: deletion.running ? "delete_account_deleting" : "delete_account_continue")
                    }
                }
                .buttonStyle(PrimaryButtonStyle(destructive: true))
                .disabled(sentTo == nil || code.count != 6 || deletion.running)
                .accessibilityIdentifier("delete_account_continue")
                Button { viewModel.closeDeleteAccount() } label: { Text(key: "delete_account_cancel").frame(maxWidth: .infinity) }
                    .buttonStyle(AccentTextButtonStyle()).disabled(deletion.running)
                    .accessibilityIdentifier("delete_account_cancel")
            }
            .frame(maxWidth: 480)
            .padding(28)
            .frame(maxWidth: .infinity)
        }
        .phoneScreenBackground()
        .alert(L("delete_account_confirm_title"), isPresented: $confirming) {
            Button(L("delete_account_cancel"), role: .cancel) {}
            Button(L("delete_account_confirm_button"), role: .destructive) { viewModel.deleteAccount(code) }
                .accessibilityIdentifier("delete_account_confirm")
        } message: {
            Text(key: "delete_account_confirm_text")
        }
    }
}

private struct Bullet: View {
    let key: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text("•").foregroundStyle(Color.phoneMuted)
            Text(key: key).font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted)
        }
    }
}
