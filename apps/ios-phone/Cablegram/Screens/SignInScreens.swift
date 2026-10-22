import AuthenticationServices
import CablegramCore
import SwiftUI

/// Spec 007: Continue with Google, or an email code; then Create your account for a new person.
struct SignInScreen: View {
    let viewModel: PhoneViewModel

    var body: some View {
        switch viewModel.signIn.step {
        case .start:
            if viewModel.signIn.deviceMode {
                DeviceStartForm(viewModel: viewModel)
            } else {
                StartForm(viewModel: viewModel)
            }
        case .code(let email): CodeForm(viewModel: viewModel, email: email)
        case .createAccount(let ticket, let email, let name): CreateAccountForm(viewModel: viewModel, ticket: ticket, email: email, initialName: name)
        case .joinWait(_, let shortCode, _): JoinWaitForm(viewModel: viewModel, shortCode: shortCode)
        }
    }
}

/// Vertically centred, 480pt column (Android `SignInPage`).
struct SignInPage<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) { content }
                .frame(maxWidth: 480)
                .padding(28)
                .frame(maxWidth: .infinity, minHeight: UIScreen.main.bounds.height * 0.8, alignment: .center)
        }
        .scrollBounceBehavior(.basedOnSize)
        .phoneScreenBackground()
    }
}

private struct StartForm: View {
    let viewModel: PhoneViewModel
    @State private var email = ""

    var body: some View {
        let signIn: SignInController = viewModel.signIn
        let canSend = email.trimmingCharacters(in: .whitespaces).contains("@") && !signIn.busy
        SignInPage {
            BrandMark()
            Spacer().frame(height: 12)
            ScreenHeading(title: L("ui_your_library_your_big_screen_1ff86b"), subtitle: L("ui_sign_in_or_create_an_account_to_bring_your_own_videos__e9e560"))
            // App Store guideline 4.8: Sign in with Apple is offered above any other sign-in service.
            if signIn.methods?.apple != nil { AppleSignInButton(viewModel: viewModel, busy: signIn.busy) }
            if signIn.methods?.google != nil {
                Button { viewModel.signInWithGoogle() } label: {
                    HStack(spacing: 12) {
                        Image("GoogleG").resizable().frame(width: 20, height: 20)
                        Text(key: "ui_continue_with_google_cce937")
                    }
                }
                .buttonStyle(OutlinedButtonStyle(minHeight: 54))
                .disabled(signIn.busy)
                .accessibilityIdentifier("sign_in_google")
                Text(key: "ui_or_717551").foregroundStyle(Color.phoneMuted).frame(maxWidth: .infinity)
            }
            PhoneTextField(label: L("ui_email_address_f2488f"), text: $email, keyboard: .emailAddress, contentType: .emailAddress,
                           onSubmit: { if canSend { viewModel.requestSignInCode(email) } }, id: "sign_in_email")
            Button { viewModel.requestSignInCode(email) } label: { BusyLabel(busy: signIn.busy, label: "ui_email_me_a_code_88d420") }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!canSend)
                .accessibilityIdentifier("sign_in_send_code")
            if let note = signIn.message.map({ L($0.key) }) ?? viewModel.status { StatusNote(message: note) }
            JoinServerRow(viewModel: viewModel)
            PrivacyLine(documents: signIn.documents)
        }
    }
}

private struct JoinServerRow: View {
    let viewModel: PhoneViewModel
    @State private var open = false
    @State private var url = ""

    var body: some View {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        VStack(spacing: 8) {
            Button { open = true } label: {
                Text("Join a server")
            }
            .buttonStyle(AccentTextButtonStyle())
            .accessibilityIdentifier("join_server")

            if viewModel.apiBaseUrl != AppConfiguration.defaultApiBase.absoluteString {
                Text("Server: \(viewModel.apiBaseUrl)")
                    .font(.caption)
                    .foregroundStyle(Color.phoneMuted)
                Button { viewModel.returnToCablegram() } label: {
                    Text("Return to Cablegram")
                }
                .buttonStyle(AccentTextButtonStyle())
            }
        }
        .alert("Join a server", isPresented: $open) {
            TextField("https://cablegram.example.com", text: $url)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Cancel", role: .cancel) {}
            Button("Continue") {
                if isValidServerUrl(trimmed) {
                    viewModel.updateApiBaseUrl(trimmed)
                    open = false
                }
            }
            .disabled(!isValidServerUrl(trimmed))
        } message: {
            Text(isCleartextLanServerUrl(trimmed)
                ? "This address is not encrypted. Use it only on your home network.\n\nSigning in to another server signs this phone out of the current one."
                : "Signing in to another server signs this phone out of the current one.")
        }
    }
}

private struct DeviceStartForm: View {
    let viewModel: PhoneViewModel
    @State private var code = ""

    var body: some View {
        let signIn: SignInController = viewModel.signIn
        let cleanCode = code.filter { $0.isNumber }
        let canConnect = cleanCode.count == 6 && !signIn.busy

        SignInPage {
            BrandMark()
            Spacer().frame(height: 12)
            ScreenHeading(
                title: "Connect to your server",
                subtitle: "Enter the setup code the server printed when it started, or ask a phone that is already connected."
            )
            PhoneTextField(
                label: "Setup code",
                text: Binding(get: { code }, set: { code = String($0.filter { $0.isNumber }.prefix(6)) }),
                keyboard: .numberPad,
                onSubmit: { if canConnect { viewModel.claimSetupCode(cleanCode) } },
                id: "setup_code"
            )
            Button { viewModel.claimSetupCode(cleanCode) } label: {
                BusyLabel(busy: signIn.busy, label: "Connect")
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(!canConnect)
            .accessibilityIdentifier("setup_code_submit")

            Button { viewModel.requestJoin() } label: {
                Text("Ask another phone to allow this one")
            }
            .buttonStyle(OutlinedButtonStyle(minHeight: 54))
            .disabled(signIn.busy)
            .accessibilityIdentifier("join_request")

            if let note = signIn.message.map({ L($0.key) }) ?? viewModel.status { StatusNote(message: note) }
            JoinServerRow(viewModel: viewModel)
        }
    }
}

private struct JoinWaitForm: View {
    let viewModel: PhoneViewModel
    let shortCode: String

    var body: some View {
        SignInPage {
            BrandMark()
            Spacer().frame(height: 12)
            ScreenHeading(
                title: "Waiting for approval",
                subtitle: "Open Cablegram on a phone that is already connected and tap Allow. Code: \(shortCode)"
            )
            ProgressView()
                .tint(.white)
                .padding(.vertical, 8)
            if let note = viewModel.signIn.message.map({ L($0.key) }) ?? viewModel.status { StatusNote(message: note) }
            Button { viewModel.cancelJoin() } label: {
                Text("Cancel")
            }
            .buttonStyle(AccentTextButtonStyle())
            .frame(maxWidth: .infinity)
        }
    }
}

/// The system "Sign in with Apple" button, with a fresh nonce for each sheet.
private struct AppleSignInButton: View {
    let viewModel: PhoneViewModel
    let busy: Bool
    @State private var nonce = ""

    var body: some View {
        SignInWithAppleButton(.continue) { request in
            nonce = AppleSignInRequest.randomNonce()
            AppleSignInRequest.configure(request, nonce: nonce)
        } onCompletion: { outcome in
            switch outcome {
            case .success(let authorization):
                if let result = AppleSignInRequest.result(from: authorization, nonce: nonce) { viewModel.signInWithApple(.success(result)) }
                else { viewModel.signInWithApple(.failure(URLError(.userAuthenticationRequired))) }
            case .failure(let error): viewModel.signInWithApple(.failure(error))
            }
        }
        .signInWithAppleButtonStyle(.white)
        .frame(height: 54)
        .clipShape(Capsule())
        .disabled(busy)
        .accessibilityIdentifier("sign_in_apple")
    }
}

private struct CodeForm: View {
    let viewModel: PhoneViewModel
    let email: String
    @State private var code = ""

    var body: some View {
        let signIn: SignInController = viewModel.signIn
        SignInPage {
            BrandMark()
            ScreenHeading(title: L("ui_check_your_email_773228"), subtitle: L("ui_enter_the_6_digit_code_we_sent_to_it_expires_in_15_min_89e634", email))
            PhoneTextField(label: L("ui_code_340f46"), text: $code, supporting: L("ui_didn_t_get_it_check_your_spam_or_junk_folder_23f145"),
                           keyboard: .numberPad, contentType: .oneTimeCode, leftToRight: true, id: "sign_in_code")
                .onChange(of: code) { _, new in code = String(new.filter(\.isNumber).prefix(6)) }
            Button { viewModel.verifySignInCode(code) } label: { BusyLabel(busy: signIn.busy, label: "ui_continue_31fbef") }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(code.count != 6 || signIn.busy)
                .accessibilityIdentifier("sign_in_code_submit")
            if let message = signIn.message { StatusNote(message: L(message.key)) }
            Button { viewModel.resendSignInCode() } label: { Text(key: "ui_send_a_new_code_9200fc").frame(maxWidth: .infinity) }
                .buttonStyle(AccentTextButtonStyle()).disabled(signIn.busy)
            Button { signIn.back() } label: { Text(key: "ui_use_a_different_email_b73370").frame(maxWidth: .infinity) }
                .buttonStyle(AccentTextButtonStyle()).disabled(signIn.busy)
                .accessibilityIdentifier("sign_in_other_email")
        }
    }
}

/// One unticked box for 18+ and the Terms; nothing is stored on the server until it is ticked and sent.
private struct CreateAccountForm: View {
    let viewModel: PhoneViewModel
    let ticket: String
    let email: String
    @State private var name: String
    @State private var agreed = false

    init(viewModel: PhoneViewModel, ticket: String, email: String, initialName: String) {
        self.viewModel = viewModel
        self.ticket = ticket
        self.email = email
        _name = State(initialValue: initialName)
    }

    var body: some View {
        let signIn: SignInController = viewModel.signIn
        let canCreate = agreed && !name.trimmingCharacters(in: .whitespaces).isEmpty && !signIn.busy
        SignInPage {
            BrandMark()
            ScreenHeading(title: L("ui_create_your_account_9e7093"), subtitle: L("ui_for_f4a63c", email))
            PhoneTextField(label: L("ui_your_name_2c6b2e"), text: $name, supporting: L("ui_shown_on_your_phones_and_tvs_b7ea60"), id: "sign_up_name")
                .onChange(of: name) { _, new in if new.count > 80 { name = String(new.prefix(80)) } }
            AgreeRow(checked: $agreed, lead: L("ui_i_m_18_or_older_and_i_accept_the_9b6ca5"), termsURL: signIn.documents.terms.url, id: "sign_up_agree")
            Button { viewModel.createAccount(name) } label: { BusyLabel(busy: signIn.busy, label: "ui_create_account_798ca2") }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!canCreate)
                .accessibilityIdentifier("sign_up_create")
            if let message = signIn.message { StatusNote(message: L(message.key)) }
            PrivacyLine(documents: signIn.documents)
            Button { signIn.back() } label: { Text(key: "ui_cancel_19766e").frame(maxWidth: .infinity) }
                .buttonStyle(AccentTextButtonStyle()).disabled(signIn.busy)
        }
    }
}

/// New Terms: nothing else until they are accepted, except deleting the account or signing out (spec 007 US4).
struct TermsScreen: View {
    let viewModel: PhoneViewModel
    @State private var agreed = false
    @Environment(\.openURL) private var openURL

    var body: some View {
        SignInPage {
            BrandMark()
            ScreenHeading(title: L("ui_our_terms_changed_edc5de"), subtitle: L("ui_please_read_the_new_terms_of_service_you_can_keep_usin_5c12c3"))
            AgreeRow(checked: $agreed, lead: L("ui_i_accept_the_5ce6ca"), termsURL: viewModel.signIn.documents.terms.url, id: "terms_agree")
            Button { viewModel.acceptTerms() } label: { BusyLabel(busy: viewModel.busy, label: nil, plain: "Accept") }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!agreed || viewModel.busy)
                .accessibilityIdentifier("terms_accept")
            if let status = viewModel.status { StatusNote(message: status) }
            PrivacyLine(documents: viewModel.signIn.documents)
            Button { if let url = URL(string: DeleteAccountScreen.webURL) { openURL(url) } } label: { Text(key: "ui_delete_my_account_21d529").frame(maxWidth: .infinity) }
                .buttonStyle(AccentTextButtonStyle())
            Button { viewModel.signOut() } label: { Text(key: "ui_sign_out_48f0d3").frame(maxWidth: .infinity) }
                .buttonStyle(AccentTextButtonStyle()).disabled(viewModel.busy)
        }
    }
}

/// A checkbox whose sentence ends with the Terms; tapping the sentence ticks the box, the link opens them.
private struct AgreeRow: View {
    @Binding var checked: Bool
    let lead: String
    let termsURL: String
    let id: String
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { checked.toggle() } label: {
                HStack(spacing: 12) {
                    Image(systemName: checked ? "checkmark.square.fill" : "square")
                        .font(.system(.title2)).foregroundStyle(checked ? Color.phoneAccent : Color.phoneMuted)
                    Text(lead + L("ui_terms_of_service_d9f3cc")).font(PhoneFont.bodyMedium).foregroundStyle(.white)
                        .multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 6)
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(.isToggle)
            .accessibilityValue(checked ? "1" : "0")
            .accessibilityIdentifier(id)
            Button { if let url = URL(string: termsURL) { openURL(url) } } label: { Text(key: "ui_read_the_terms_of_service_4e316c") }
                .buttonStyle(AccentTextButtonStyle()).padding(.leading, 34)
        }
    }
}

/// The privacy notice, shown before any personal data is sent (spec 007 US3).
private struct PrivacyLine: View {
    let documents: LegalDocuments
    @Environment(\.openURL) private var openURL

    var body: some View {
        Button { if let url = URL(string: documents.privacy.url) { openURL(url) } } label: {
            (Text(L("ui_how_cablegram_uses_your_data_89560e"))
                + Text(L("ui_privacy_policy_506ff3")).foregroundColor(.phoneAccent).underline()
                + Text("."))
                .font(.system(.caption)).foregroundStyle(Color.phoneMuted)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("privacy_policy")
    }
}

struct BusyLabel: View {
    let busy: Bool
    let label: String?
    var plain: String?

    init(busy: Bool, label: String?, plain: String? = nil) {
        self.busy = busy
        self.label = label
        self.plain = plain
    }

    var body: some View {
        if busy {
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text(key: "ui_please_wait_4660a9")
            }
        } else if let label {
            Text(key: label)
        } else if let plain {
            Text(verbatim: plain)
        }
    }
}

/// "Name this account": shown right after sign-in when the server has no name for it yet.
struct NameForm: View {
    let viewModel: PhoneViewModel
    @State private var name: String

    init(viewModel: PhoneViewModel) {
        self.viewModel = viewModel
        _name = State(initialValue: viewModel.householdName)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(key: "ui_name_this_household_a2930a").font(PhoneFont.headlineSmall).foregroundStyle(.white)
                Text(key: "ui_this_name_is_saved_on_the_server_every_paired_tv_and_p_0a385b").foregroundStyle(Color.phoneMuted)
                PhoneTextField(label: L("ui_household_name_e4d50a"), text: $name, id: MaestroIds.householdName)
                Button { viewModel.saveHouseholdName(name) } label: {
                    if viewModel.busy { Text(key: "ui_saving_23e392") } else { Text(key: "ui_continue_31fbef") }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || viewModel.busy)
                .accessibilityIdentifier(MaestroIds.householdContinue)
                if let status = viewModel.status { Text(status).foregroundStyle(Color.phoneMuted) }
            }
            .padding(24)
        }
        .phoneScreenBackground()
    }
}
