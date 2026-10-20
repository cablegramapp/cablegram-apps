import CablegramCore
import SwiftUI

/// "Meet your TV." (Android `PairingForm`): the TV code, paste, or scan the QR.
struct PairingForm: View {
    let viewModel: PhoneViewModel
    @State private var pin = ""
    @State private var scanning = false
    @Environment(\.openURL) private var openURL

    var body: some View {
        let tv = viewModel.tv!
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Button { tv.cancelAddTv() } label: { Text(key: "ui_not_now_a0e63d") }.buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.pairingNotNow)
                ScreenHeading(title: L(tv.paired ? "ui_one_more_big_screen_3514a4" : "ui_meet_your_tv_4d8c97"), subtitle: L("pairing_setup_steps"))
                Button { if let url = URL(string: "https://cablegram.app/#downloads") { openURL(url) } } label: { Text(key: "pairing_get_tv_app") }.buttonStyle(AccentTextButtonStyle())
                VStack(alignment: .leading, spacing: 16) {
                    Image(systemName: "tv").font(.system(size: 36)).foregroundStyle(Color.phoneAccent)
                    PhoneTextField(label: L("ui_tv_code_8c33e0"), text: $pin, keyboard: .numberPad, leftToRight: true, id: MaestroIds.pairingEnterPin)
                        .onChange(of: pin) { _, value in pin = String(value.filter(\.isNumber).prefix(6)) }
                    Button { tv.applyPairUri(pin, webOrigins: pairWebOrigins(CablegramApp.configuration.webAppBase)) } label: {
                        Text(key: tv.working ? "ui_connecting_72021e" : "ui_connect_tv_c9f94c")
                    }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 52)).disabled(pin.count != 6 || tv.working).accessibilityIdentifier("pairing_connect")
                    Button { pin = String((UIPasteboard.general.string ?? "").filter(\.isNumber).prefix(6)) } label: { Text(key: "ui_paste_code_682909").frame(maxWidth: .infinity) }
                        .buttonStyle(AccentTextButtonStyle()).disabled(tv.working)
                }
                .padding(22).background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 24))
                Button { scanning.toggle() } label: { Text(key: scanning ? "ui_close_camera_bebcb8" : "ui_scan_qr_code_instead_375856") }
                    .buttonStyle(OutlinedButtonStyle()).disabled(tv.working)
                if scanning {
                    PairingScanner { code in tv.applyPairUri(code, webOrigins: pairWebOrigins(CablegramApp.configuration.webAppBase)) }
                }
                if let status = viewModel.status { StatusNote(message: status) }
                Text(key: "ui_keep_your_phone_and_tv_on_the_same_wi_fi_while_watchin_7a397d").font(PhoneFont.bodyMedium).foregroundStyle(Color.phoneMuted)
            }
            .padding(24)
        }
        .scrollDismissesKeyboard(.interactively)
        .phoneScreenBackground()
    }
}

/// "Is this your TV?" (spec 004 US8): a home TV behaves as before; a temporary TV (hotel, friend, rental) is unpaired at its end time
/// and holds no Telegram session unless the owner opts in.
struct PairTrustDialog: View {
    let viewModel: PhoneViewModel
    @State private var temporary = false
    @State private var selected = 0
    @State private var direct = false
    @Environment(\.dismiss) private var dismiss
    private let choices = TvTrustRules.checkoutChoices()

    var body: some View {
        let tv = viewModel.tv!
        // Direct Telegram needs a link older than 24 h; the Telegram link itself arrives with phase 7.
        let linked = false
        let directAllowed = false
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    choiceRow(L("ui_my_tv_home_4a4daa"), L("ui_it_signs_in_to_your_telegram_library_and_stays_paired_7153cf"), !temporary) { temporary = false }
                    choiceRow(L("ui_temporary_tv_2b77cf"), L("ui_a_hotel_a_friend_s_or_a_rental_it_is_unpaired_at_the_t_312310"), temporary) { temporary = true }
                    if temporary {
                        Text(key: "ui_unpair_it_at_e679be").font(.system(size: 12)).foregroundStyle(Color.phoneMuted).padding(.top, 8)
                        ForEach(Array(choices.enumerated()), id: \.offset) { index, choice in
                            choiceRow(choice.label, choice.date.formatted(date: .abbreviated, time: .shortened), selected == index) { selected = index }
                        }
                        if linked {
                            Toggle(isOn: $direct) { Text(key: "ui_let_this_tv_use_telegram_directly_until_then_3cd46b").foregroundStyle(directAllowed ? .white : Color.phoneMuted) }
                                .disabled(!directAllowed).tint(.phoneAccent)
                        }
                    }
                }
                .padding(20)
            }
            .phoneScreenBackground()
            .navigationTitle(L("ui_is_this_your_tv_525588")).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("ui_cancel_19766e")) { tv.cancelPendingPair() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("ui_connect_tv_c9f94c")) {
                        tv.confirmPair(temporary ? TvTrust(temporary: true, expiresAt: choices[selected].date, telegramDirect: direct && directAllowed && linked) : .home)
                    }
                    .accessibilityIdentifier("pair_trust_connect")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func choiceRow(_ title: String, _ detail: String, _ isOn: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: isOn ? "largecircle.fill.circle" : "circle").foregroundStyle(isOn ? Color.phoneAccent : Color.phoneMuted)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(.white)
                    Text(detail).font(.system(size: 12)).foregroundStyle(Color.phoneMuted)
                }
                Spacer()
            }
            .frame(minHeight: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
